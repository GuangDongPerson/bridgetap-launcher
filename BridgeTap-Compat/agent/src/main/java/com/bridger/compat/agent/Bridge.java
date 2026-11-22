package com.bridger.compat.agent;

import com.bridger.compat.Chat;
import com.bridger.compat.Config;
import com.bridger.compat.JumpEntry;
import com.bridger.compat.Lang;
import com.bridger.compat.Log;
import com.bridger.compat.Tracker;
import com.bridger.compat.hud.HudModel;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Runtime half of the agent.
 *
 * <p>Sites are dispatched by index, matching {@code Bootstrap.TARGETS}:
 * <pre>
 *   0  GuiIngame.renderGameOverlay(F)          draw, at the tail of the GUI pass
 *   1  Minecraft.runTick()                    read the player, once per game tick
 *   2  Minecraft.runGameLoop()                same object, used only to bind early
 *   3  EntityLivingBase.jump()                record a jump entry
 *   4  PlayerControllerMP.onPlayerRightClick  a block went down, starts a run
 *   5  Display.swapBuffers()                  fallback draw, only if site 0 never fires
 * </pre>
 *
 * <p><b>Why the overlay is drawn at site 0.</b> The previous build drew from
 * {@code Display.swapBuffers()}. That runs before the global lock is taken and before the client has
 * finished composing, and with a frame gate of a few milliseconds on top it produced exactly the
 * symptom reported: text that flickers with the frame refresh. The original mod injects at the
 * return of {@code GuiIngame.renderGameOverlay} — the GUI projection is already up, the world and
 * the GUI are done, and the buffer is presented immediately afterwards. Drawing there is also what
 * makes the client's own {@code FontRenderer} usable, which is the only way the text matches the
 * active resource pack.
 *
 * <p><b>Why a run starts at site 4.</b> The original triggers
 * {@code BridgeTracker.onBlockPlaced()} from {@code PlayerControllerMP.onPlayerRightClick} when the
 * call returned true and the held item was an {@code ItemBlock}. A jump alone never starts a timer;
 * it only contributes an entry once a run is active. Jumping around while building should not light
 * up the HUD, and the earlier build got that wrong by starting on {@code jump()}.
 */
public final class Bridge {
   /** Site order matches {@code Bootstrap.buildTargets} and {@code Hook.SITE_NAMES}. */
   public static final int SITE_GUI = 0;
   public static final int SITE_TICK = 1;
   public static final int SITE_BLOCK = 2;
   public static final int SITE_UPDATE = 3;
   public static final int SITE_SWAP = 4;
   /** Reserved for the native payload's liveness probe; never a real hook. */
   public static final int SITE_PROBE = 5;
   public static final int SITE_CHAT = 6;
   public static final int SITE_JUMP = 7;
   /**
    * Inside {@code renderGameOverlay}, immediately before the tab list.
    *
    * <p>The best draw position: same layer as the hotbar, so the inventory, the menus and the tab
    * list are all painted over the panel instead of under it.
    */
   public static final int SITE_GUI_EARLY = 8;
   /**
    * {@code Framebuffer.framebufferRender(int,int)} - the blit that copies the offscreen frame to
    * the window. Painting at its head puts the panel inside the frame that is about to be copied,
    * which is the only position that is both on screen and in a screenshot.
    */
   public static final int SITE_FBO = 9;

   /** Attempts to construct the GL renderer before giving up. */
   private static final int HUD_ATTEMPTS = 4;

   private static volatile boolean configLoaded;
   private static volatile GameBinding game;
   /** World / scoreboard / block access, resolved as soon as the game object is known. */
   private static volatile WorldProbe probe;

   private static volatile Object hudRenderer;
   /** Last time the geometry line was logged; the reload loop runs far too often to log each one. · 几何日志上次打印时刻；重载循环太频繁，不能一条一条打 */
   private static volatile long lastGeometryLog;
   private static volatile Method hudRender;
   private static volatile ClassLoader renderLoader;
   private static volatile File hudJarFile;
   private static int hudAttempts;

   private static volatile boolean overlaySeen;
   /** Set the first time a HUD-pass site fires; after that the late sites stop drawing. */
   private static volatile boolean hudSiteSeen;
   /**
    * Which site actually manages to paint, or -1 while undecided.
    *
    * <p>The overlay has to be drawn on the thread holding the GL context, and which site satisfies
    * that is a property of the client, not of the source. Badlion reaches {@code updateDisplay()}
    * from four callers, at least one of them off the render thread: there, every GL call quietly
    * returned garbage and the build reported success while drawing nothing. The first site that
    * paints wins and the rest stop, which also keeps the overlay from being drawn twice a frame.
    */
   private static volatile int drawSite = -1;
   private static final boolean[] siteLogged = new boolean[10];
   /** One log line per site the first time it actually reaches the draw branch. */
   private static final boolean[] siteTried = new boolean[10];
   private static volatile boolean blockHookSeen;
   /**
    * True when the client has no hookable block-placement method at all.
    *
    * <p>Distinct from {@link #blockHookSeen} on purpose. That one means "the hook has fired at least
    * once", which is false from world join until the first right-click — so using it to decide
    * whether the take-off fallback may run meant the fallback was live for exactly the window in
    * which it must not be.
    *
    * <p>This one is read from the injection table, where a missing controller method is a fact.
    *
    * <p>与 {@link #blockHookSeen} 刻意分开。后者是「钩子至少触发过一次」，从进世界到第一次右键
    * 之间它都是 false——用它来决定「起跳兜底能不能用」，等于兜底正好在唯一不该生效的那段时间里
    * 生效。这一个直接读注入表，那里「控制器方法不存在」是事实。
    */
   private static volatile boolean blockHookAbsent;
   /** True once the real {@code jump()} hook has fired; the sampled fallback then stands down. */
   private static volatile boolean jumpHookSeen;
   /** True once the world tick counter has been read successfully. */
   private static boolean tickClockActive;

   private static volatile Method stackGetItem;
   private static volatile Class<?> itemBlockClass;
   private static boolean itemBlockMissingLogged;
   /** {@code BlockPos} coordinate accessors, resolved on the first placement. */
   private static volatile Method[] posCoords;
   private static boolean posCoordsMissing;
   private static final int[] NO_STEP = { 0, 0, 0 };

   private static long frames;
   private static long ticks;

   /** One game tick, in nanoseconds. */
   private static final long TICK_NANOS = 50_000_000L;
   /** How far behind the wall clock the tick accumulator may fall before it is resynced. */
   private static final long MAX_TICK_LAG_NANOS = 500_000_000L;
   /** Below this average interval the hook is running at frame rate, not tick rate. */
   private static final long FRAME_RATE_THRESHOLD_NANOS = 40_000_000L;
   private static long lastTickNanos;
   private static boolean gateTicks;
   private static int calibSamples;
   private static long calibStartNanos;

   private static boolean hasSample;
   private static double prevY;
   private static boolean prevGround;
   private static boolean sampleLogged;
   private static boolean firstDrawLogged;
   /** Identity of the last World seen; 0 until the player has been in a world. */
   private static int worldIdentity;

   private Bridge() {
   }

   /**
    * Hands the injection table's verdict about the block hook to the agent and the tracker.
    *
    * <p>Called once from {@link Bootstrap} right after the site table is built, i.e. before any
    * class is transformed and long before the first tick. Both readers need the same answer:
    * {@link #blockHookAbsent} decides whether a take-off edge may open a run at all, and the tracker
    * needs to know because its "no timer before the first block" gate must be switchable off on a
    * client that will never report a block.
    *
    * <p>{@code controllerHooked} is what {@code presentSites} actually saw in the controller class,
    * not what {@code ClassScan} named — the two differ when the descriptor does not match, which is
    * exactly the case that used to leave the fallback permanently enabled.
    *
    * 把注入表对方块钩子的结论交给 agent 与 tracker。
    *
    * <p>由 {@link Bootstrap} 在站点表建好之后调用一次，也就是在任何类被转换之前、第一次 tick 之前。
    * 两个读取方要的是同一个答案：{@link #blockHookAbsent} 决定起跳边沿能不能开局，tracker 需要知道
    * 它是因为「首个方块之前不计时」这条门必须能在永远报不出方块的客户端上关掉。
    *
    * <p>{@code controllerHooked} 是 {@code presentSites} 在控制器类里**实际看到**的，而不是
    * {@code ClassScan} 报的名字——描述符不匹配时两者不同，而那恰好就是让兜底一直开着的那种情况。
    */
   public static void setControllerHookPresent(boolean controllerHooked) {
      blockHookAbsent = !controllerHooked;
      Tracker.setTakeOffMayStart(!controllerHooked);
      Log.info("controller hook " + (controllerHooked ? "present" : "absent")
         + " -> take-off as a run starter: " + (controllerHooked ? "no" : "yes"));
   }

   /** Called from every injected prologue. {@code self} is {@code null} for static methods. */
   public static void onFrame(int site, Object self) {
      try {
         if (site >= 0 && site < siteLogged.length && !siteLogged[site]) {
            siteLogged[site] = true;
            logSiteThread(site);
         }
         switch (site) {
            case SITE_GUI_EARLY:
            case SITE_GUI:
               hudSiteSeen = true;
               tryDraw(site);
               break;
            case SITE_FBO:
               // Not a draw position: the client unbinds the framebuffer *before* this call, so a
               // draw here lands on the window and the blit that follows paints straight over it.
               // Kept as a hook only - it proves whether the blit is on this client's frame path.
               noteOnce("frame blit seen (framebufferRender); painting at the late site instead");
               break;
            case SITE_UPDATE:
            case SITE_SWAP:
               // After the frame has been blitted to the window. Used only when the client has no
               // HUD method to hook (Badlion), and drawFrame compensates: the panel is painted into
               // the offscreen frame as well, which is the copy a screenshot reads back.
               if (!hudSiteSeen) {
                  tryDraw(site);
               }
               break;
            case SITE_TICK:
               tick(self);
               break;
            case SITE_JUMP:
               onJump(self);
               break;
            case SITE_BLOCK:
               // handled by onItemUse; nothing to do from a prologue
               break;
            default:
               break;
         }
      } catch (Throwable t) {
         // A broken overlay must never escape into the client's render loop.
      }
   }

   /**
    * Draw-position quality, lowest is best.
    *
    * <p>{@code renderGameOverlay}'s return wins because it is the last point where the panel is
    * still on top of the client's own HUD: Lunar and Badlion draw their keystrokes/FPS widgets from
    * tail hooks on the same method, and anything injected earlier (site 8, inside
    * {@code renderTooltip}) ends up underneath them - the panel was drawn every frame and never
    * visible. Everything that matters for occlusion still holds at site 0: {@code GuiScreen} draws
    * after {@code renderGameOverlay}, so the inventory and the menus cover the panel.
    *
    * <p>{@code -1} (nothing chosen yet) ranks worst so any site may claim the slot; after that only
    * a strictly better site displaces the current one.
    */
   /**
    * One draw attempt at {@code site}.
    *
    * <p>A better site may always take over; a worse one may only fill in while nothing has painted
    * yet. The equality case is not an optimisation detail - without it the site that won the first
    * frame was locked in and then skipped on every later frame, so the panel appeared for exactly
    * one frame and vanished.
    */
   private static void tryDraw(int site) {
      if (site != drawSite && rankOf(site) >= rankOf(drawSite)) {
         return;
      }
      // Logged once per site, not per frame: string formatting on this path would allocate twice
      // per rendered frame for no benefit.
      if (!siteTried[site]) {
         siteTried[site] = true;
         Log.info("draw: site " + site + " entered the draw branch (drawSite=" + drawSite + ")");
      }
      if (drawFrame(site)) {
         if (drawSite != site) {
            Log.info("draw site locked to " + site);
         }
         drawSite = site;
         overlaySeen = true;
      }
   }

   private static int rankOf(int site) {
      switch (site) {
         case SITE_GUI:
            return 0;
         case SITE_GUI_EARLY:
            return 1;
         case SITE_UPDATE:
            return 2;
         case SITE_SWAP:
            return 3;
         default:
            return 9;
      }
   }

   /**
    * Records which thread a site fires on, once.
    *
    * <p>Whether a site can paint depends entirely on whether its thread owns the GL context, and
    * that is not something the bytecode reveals. One line per site turns the next report into a
    * fact instead of a guess.
    */
   private static void logSiteThread(int site) {
      Thread thread = Thread.currentThread();
      StringBuilder frames = new StringBuilder();
      StackTraceElement[] stack = thread.getStackTrace();
      for (int i = 1; i < stack.length && i < 7; i++) {
         frames.append(" <- ").append(stack[i].getClassName()).append('.')
            .append(stack[i].getMethodName());
      }
      Log.info("site " + site + " on thread " + thread.getName() + " (id " + thread.getId()
         + ")" + frames);
   }

   /**
    * Called from the return site of {@code PlayerControllerMP.onPlayerRightClick}.
    *
    * <p>The click's {@code BlockPos} and {@code EnumFacing} come along since 1.12: they are the two
    * extra arguments of the controller method, and they are what turn "a block went down somewhere"
    * into "a block went down at this column". The label gates count columns, so the difference
    * decides whether a 4b Flat can be proven at all — see {@link #placedColumn}.
    *
    * <p>Both are {@code null} when the client's controller has a different signature; the player's
    * own column is then used, which is the old, weaker evidence.
    *
    * 从 {@code onPlayerRightClick} 的返回点调用。
    * 点击的 {@code BlockPos} 与 {@code EnumFacing} 是控制器方法的两个额外参数，它们把
    * 「某处放下了一块」变成「这一列放下了一块」。标签门控数的正是列，所以这决定了 4b Flat
    * 到底能不能被证明——见 {@link #placedColumn}。
    * 客户端控制器签名不同时两者都是 {@code null}，那时退回玩家自己所在列，即旧的弱证据。
    */
   public static void onItemUse(boolean success, Object stack, Object blockPos, Object facing, int site) {
      try {
         if (site != SITE_BLOCK) {
            return;
         }
         // Reaching this line at all proves the hook is installed and firing, even on a rejected click.
      // It no longer gates anything - the fallback reads blockHookAbsent, which is decided from the
      // injection table - but it is still the cheapest proof that the transform took effect, and a
      // first-click log line is what tells a field report "the hook works, look elsewhere" apart
      // from "the hook never fired".
      // 走到这一行就证明了钩子装上了并且在触发，哪怕这次点击被拒。它现在不再控制任何分支
      // （兜底看的是 blockHookAbsent，那个由注入表决定），但它仍是「转换确实生效」最省事的证据：
      // 一条首点日志能把「钩子好着，去别处找」和「钩子从来没触发过」分开。
      if (!blockHookSeen) {
         blockHookSeen = true;
         Log.info("block hook fired for the first time (site=" + site + " success=" + success + ")");
      }
         if (!success || stack == null) {
            return;
         }
         if (!isBlockItem(stack)) {
            return;
         }
         int[] column = placedColumn(blockPos, facing);
         if (column == null) {
            Tracker.onBlockPlaced();
         } else {
            Tracker.onBlockPlaced(column[0], column[1]);
         }
      } catch (Throwable t) {
         // same contract: never throw into the caller's method
      }
   }

   /**
    * The column the placed block occupies.
    *
    * <p>Vanilla puts the block where it was clicked when that position is empty, and against the
    * clicked face when it is not, so the answer needs exactly one bit from the world. Upstream
    * reads it by snapshotting both positions before the click and diffing afterwards; here the
    * position that changed is derivable from that one bit, which costs a single block lookup.
    *
    * @return {@code {x, z}} of the block, or {@code null} when the position could not be read
    * 放置的方块占哪一列。
    * 原版在点击位置为空时把块放在那里，不为空时放在越过点击面的相邻格，所以只需要世界给一个事实。
    * 上游靠点击前后各拍一次快照再比对；这里由这一个事实就能反推出落点，代价是一次方块查询。
    */
   private static int[] placedColumn(Object blockPos, Object facing) {
      int[] at = blockPosition(blockPos);
      if (at == null) {
         return null;
      }
      WorldProbe p = probe;
      int id = p == null ? -1 : p.blockIdAt(at[0], at[1], at[2]);
      int[] step = faceStep(facing);
      // id == 0 is air, so the click went into empty space and the block landed exactly there.
      // Anything else — including a failed lookup (-1) — is treated as a click on existing ground:
      // bridging clicks the block in front of the player, and re-reading the column it clicked
      // would count the same column twice.
      // id == 0 是空气，说明点的是空位，块就落在那里。其余情况——包括查不到（-1）——都当作点在
      // 已有的地面上：过桥点的是身前那一块，把被点击的列再记一次等于同一列数两遍。
      if (id == 0) {
         return new int[] { at[0], at[2] };
      }
      return new int[] { at[0] + step[0], at[2] + step[2] };
   }

   /** {@code BlockPos} x/y/z, or {@code null} when the accessors cannot be resolved. */
   private static int[] blockPosition(Object blockPos) {
      if (blockPos == null) {
         return null;
      }
      Method[] coords = posCoords;
      if (coords == null) {
         coords = resolvePosCoords(blockPos.getClass());
         if (coords == null) {
            if (!posCoordsMissing) {
               posCoordsMissing = true;
               Log.info("block position accessors unresolved - placement columns fall back to the player");
            }
            return null;
         }
         posCoords = coords;
      }
      try {
         return new int[] {
            ((Integer)coords[0].invoke(blockPos)).intValue(),
            ((Integer)coords[1].invoke(blockPos)).intValue(),
            ((Integer)coords[2].invoke(blockPos)).intValue()
         };
      } catch (Throwable t) {
         return null;
      }
   }

   /**
    * Resolves the three coordinate getters from {@code Vec3i}.
    *
    * <p>Every candidate is a no-argument method returning {@code int}, so the lookup never depends
    * on the argument types surviving obfuscation: only the names change, and all three spellings are
    * tried for each axis.
    */
   private static Method[] resolvePosCoords(Class<?> type) {
      String[][] names = {
         { "n", "getX", "func_177958_n" },
         { "o", "getY", "func_177956_o" },
         { "p", "getZ", "func_177952_p" }
      };
      Method[] out = new Method[3];
      for (int i = 0; i < 3; i++) {
         out[i] = findIntGetter(type, names[i]);
         if (out[i] == null) {
            return null;
         }
      }
      return out;
   }

   private static Method findIntGetter(Class<?> type, String[] names) {
      for (Class<?> current = type; current != null && current != Object.class;
            current = current.getSuperclass()) {
         for (Method candidate : current.getDeclaredMethods()) {
            if (candidate.getParameterTypes().length != 0 || candidate.getReturnType() != int.class) {
               continue;
            }
            if (!nameMatches(candidate.getName(), names)) {
               continue;
            }
            try {
               candidate.setAccessible(true);
               return candidate;
            } catch (Throwable t) {
               return null;
            }
         }
      }
      return null;
   }

   private static boolean nameMatches(String name, String[] names) {
      for (String want : names) {
         if (want.equals(name)) {
            return true;
         }
      }
      return false;
   }

   /**
    * Unit offset of a clicked face, taken from the enum's own order.
    *
    * <p>1.8.9 declares {@code EnumFacing} as DOWN, UP, NORTH, SOUTH, WEST, EAST, and that order is
    * semantic — the client sends the ordinal to the server — so it survives obfuscation. Reading the
    * offsets off the enum's fields instead would cost several more reflective lookups for no gain.
    *
    * 点击面的单位偏移，取自枚举自身的顺序。
    * 1.8.9 的 {@code EnumFacing} 声明顺序是 DOWN/UP/NORTH/SOUTH/WEST/EAST，而这个顺序是有语义的
    * ——客户端就是把它当序号发给服务器的——所以它不会被混淆。改去读枚举字段要多好几次反射，没收益。
    */
   private static int[] faceStep(Object facing) {
      if (!(facing instanceof Enum)) {
         return NO_STEP;
      }
      switch (((Enum<?>)facing).ordinal()) {
         case 0:
            return new int[] { 0, -1, 0 };
         case 1:
            return new int[] { 0, 1, 0 };
         case 2:
            return new int[] { 0, 0, -1 };
         case 3:
            return new int[] { 0, 0, 1 };
         case 4:
            return new int[] { -1, 0, 0 };
         case 5:
            return new int[] { 1, 0, 0 };
         default:
            return NO_STEP;
      }
   }

   // ---------------------------------------------------------------- draw

   private static boolean drawFrame(int site) {
      ensureConfig();
      if (!Config.enabled) {
         noteOnce("config disabled");
         return false;
      }

      // The player list is drawn by the client before this hook point and there is no portable
      // injection site ahead of it, so the overlay yields instead: holding the list key hides the
      // panel for as long as the list is up. Config.yieldToTabList turns that off for players who
      // want the panel to stay put while the list is open.
      GameBinding binding = game;
      if (Config.yieldToTabList && binding != null && binding.isTabListKeyDown()) {
         noteOnce("paused: player-list key held");
         return false;
      }

      Object renderer = hud();
      Method render = hudRender;
      if (renderer == null || render == null) {
         noteOnce("no renderer (hud() returned null; see the lines above)");
         return false;
      }

      List<String> lines = HudModel.build();
      if (lines.isEmpty()) {
         // No bridge run yet. The config option "空闲时显示状态行" decides whether the panel shows
         // the live-coordinate placeholder in that window — it used to be persisted but never read,
         // so switching it off did nothing. Honour it here (and keep the old behaviour as default).
         if (!Config.showReadyNotice) {
            noteOnce("idle: ready-status line disabled (showReadyNotice=false)");
            return false;
         }
         lines = statusLines();
      }
      if ((++scaleSync) % 60 == 1) {
         syncGuiScale(renderer, binding);
      }
      boolean painted;
      try {
         Object result = render.invoke(renderer, lines);
         painted = Boolean.TRUE.equals(result);
      } catch (java.lang.reflect.InvocationTargetException e) {
         Throwable cause = e.getCause() == null ? e : e.getCause();
         noteOnce("overlay threw: " + describe(cause));
         return false;
      } catch (ReflectiveOperationException e) {
         noteOnce("overlay call failed: " + describe(e));
         return false;
      }
      if (!painted) {
         noteOnce("GlHud.render returned false (see hud.log for the reason)");
         return false;
      }

      // Screenshot copy. The window pass above is the unchanged, known-good one; this second pass
      // runs strictly after it and at the very end of the frame, so nothing downstream depends on
      // the GL state it touches. Its only job is to leave the panel inside the offscreen frame,
      // which is the image a screenshot reads back - that frame is cleared at the start of the next
      // one, so the copy lives exactly as long as a keypress can still trigger a capture.
      //
      // No blit afterwards on purpose: an extra blit here was tried and left the window blank, and
      // the window copy above is what the player actually looks at.
      if (site == SITE_UPDATE || site == SITE_SWAP) {
         Object framebuffer = binding == null ? null : binding.framebuffer();
         if (framebuffer != null && bindFramebuffer(framebuffer)) {
            try {
               render.invoke(renderer, lines);
               noteOnce("draw: screenshot copy painted into the offscreen frame");
            } catch (Throwable ignored) {
               // A failed capture copy must not affect the window copy, which already succeeded.
            }
            unbindFramebuffer(framebuffer);
         }
      }

      frames++;
      if (!firstDrawLogged) {
         firstDrawLogged = true;
         Log.info("overlay drawing (" + lines.size() + " line(s), site " + drawSite + ", thread "
            + Thread.currentThread().getName() + ")");
      }
      return true;
   }

   /** Last run status we reacted to, so the chat line is printed once per completed run. */
   private static Tracker.RunStatus lastRunStatus = Tracker.RunStatus.IDLE;

   /**
    * Prints the result to the player's chat when a run ends, gated by {@code /bt chat on}.
    *
    * <p>Kept independent of {@link HudModel#build()} on purpose: that one returns nothing when the
    * HUD is switched off, and "HUD hidden but report to chat" is a configuration people actually use.
    */
   private static void pumpChatOutput() {
      Tracker.RunStatus now = Tracker.getStatus();
      if (now == lastRunStatus) {
         return;
      }
      Tracker.RunStatus previous = lastRunStatus;
      lastRunStatus = now;
      if (!Config.chatOutput || previous != Tracker.RunStatus.BRIDGING
            || (now != Tracker.RunStatus.SUCCESS && now != Tracker.RunStatus.FAILED)) {
         return;
      }
      WorldProbe target = probe;
      if (target == null) {
         return;
      }
      // Same template as the HUD's first line: status word + elapsed time + jump count + axis, only
      // without the panel's per-line colouring here.
      // 和 HUD 首行同一套模板：状态词 + 用时 + 跳数 + 轴，只是这里不带面板的整行配色
      boolean success = now == Tracker.RunStatus.SUCCESS;
      String tone = success ? "\u00a7a" : "\u00a7c";
      String head = tone + Lang.t("hud.header",
         Lang.t(success ? "hud.status.success" : "hud.status.failed"),
         String.format(Locale.US, "%.2f", Tracker.getCurrentBridgeTime()),
         Integer.valueOf(Tracker.getJumpCount()), Tracker.getCurrentAxis());
      target.chat("\u00a7b" + Lang.t("chat.prefix") + " \u00a7r" + head);
      JumpEntry last = Tracker.getLastEntry();
      if (last != null) {
         target.chat(last.getFormattedLine());
      }
   }

   private static List<String> statusLines() {
      List<String> out = new ArrayList<String>(1);
      GameBinding binding = game;
      Object player = binding == null ? null : binding.player();
      double[] pos = player == null ? null : binding.position(player);

      // The brand name is not translated; everything else comes from the language pack (hud.idle /
      // hud.waiting).
      // 品牌名不翻译，其余走语言包（hud.idle / hud.waiting）
      if (pos == null) {
         out.add("\u00a77" + Lang.t("hud.waiting", "BridgeTap"));
         return out;
      }

      // Only the coordinate line. The old second line ("t=1334 ground (place a block to start)")
      // was debug noise — players read it as an error message. Kept out of the panel on purpose.
      out.add("\u00a7b" + Lang.t("hud.idle", "BridgeTap",
         String.format(Locale.US, "%.3f", pos[0]),
         String.format(Locale.US, "%.3f", pos[1]),
         String.format(Locale.US, "%.3f", pos[2])));
      return out;
   }

   // ---------------------------------------------------------------- data

   /** Binds the game object carried by an instance-method prologue. */
   private static void bind(Object self) {
      if (self == null) {
         return;
      }
      GameBinding current = game;
      if (current != null && current.mc == self) {
         return;
      }
      GameBinding resolved = GameBinding.of(self, Bootstrap.mapping);
      if (resolved != null) {
         game = resolved;
         Log.info("bound game object: " + resolved.describe);
         try {
            ensureProbe(resolved);
         } catch (Throwable t) {
            // A failure here must not take the tick path with it: losing the sidebar clock costs
            // timing accuracy, losing the tick hook costs every measurement.
            Log.warn("world probe setup failed: " + describe(t));
         }
      } else if (current == null) {
         Log.verbose("unusable game object: " + self.getClass().getName());
      }
   }

   /**
    * Wires the world-side subsystems into the tracker.
    *
    * <p>The tracker itself is platform-independent and originally read these three things through
    * mapped Minecraft classes. Here they arrive as callbacks so the pure logic stays testable:
    * the run clock from the sidebar, the finish line from the block under the player, and chat for
    * the success line and the {@code /bt} replies.
    */
   private static void ensureProbe(GameBinding binding) {
      if (probe != null) {
         return;
      }
      final WorldProbe created = WorldProbe.attach(binding.mc, binding.mcClass, Bootstrap.mapping);
      if (created == null) {
         Log.warn("no world probe: sidebar time and gold-plate detection unavailable");
         return;
      }
      probe = created;
      Tracker.setTimeSource(new Tracker.TimeSource() {
         @Override
         public double read() {
            WorldProbe p = probe;
            return p == null ? -1.0D : p.readTime();
         }
      });
      Tracker.setPlateProbe(new Tracker.PlateProbe() {
         @Override
         public boolean isOnGoldPlate(double x, double y, double z) {
            WorldProbe p = probe;
            return p != null && p.isOnGoldPlate(x, y, z);
         }
      });
      Tracker.setChatSink(new Tracker.ChatSink() {
         @Override
         public void send(String line) {
            WorldProbe p = probe;
            if (p != null) {
               p.chat(line);
            }
         }
      });
      Tracker.setTickSource(new Tracker.TickSource() {
         @Override
         public long read() {
            WorldProbe p = probe;
            return p == null ? -1L : p.worldTime();
         }
      });
      Tracker.setInputProbe(new Tracker.InputProbe() {
         @Override
         public int read() {
            WorldProbe p = probe;
            return p == null ? 0 : p.movementBits();
         }
      });
      Tracker.setCeilingProbe(new Tracker.CeilingProbe() {
         @Override
         public Boolean hasCeiling(double x, double y, double z) {
            WorldProbe p = probe;
            return p == null ? null : p.ceilingAbove(x, y, z);
         }
      });
      Tracker.setPingProbe(new Tracker.PingProbe() {
         @Override
         public int read() {
            WorldProbe p = probe;
            return p == null ? -1 : p.pingMillis();
         }
      });
      Log.info("tick clock: " + (created.hasTick()
         ? "world.getTotalWorldTime (exact 20 Hz)"
         : "synthesised from the frame hook (approximate)"));
      Log.info("movement keys: " + (created.hasMovementKeys()
         ? "W/A/S/D bound (gain tag available)"
         : "not mapped (gain tag hidden)"));
   }

   /**
    * {@code /bt} handling, called from the injected prologue of the player's chat send.
    *
    * @return true when the message was a command and must not be sent to the server
    */
   public static boolean onChat(Object self, String message, int site) {
      try {
         if (site != SITE_CHAT) {
            return false;
         }
         WorldProbe p = probe;
         final WorldProbe target = p;
         return Chat.handle(message, new Chat.Reply() {
            @Override
            public void line(String text) {
               if (target != null) {
                  target.chat(text);
               } else {
                  Log.info(text);
               }
            }
         });
      } catch (Throwable t) {
         // A broken command must never break chat.
         return false;
      }
   }

   /**
    * Game-tick sampling.
    *
    * <p>The beat is synthesised here, not taken from the client. The hook this runs on fires once
    * per frame - proven by the hit counters, where the tick site and the present site log identical
    * numbers - so treating every call as a tick made a 60 fps client run its tick clock three times
    * too fast. Every tick-derived quantity was then wrong in a way that looked like bad judgement:
    * the "landed on the ground for more than 4 ticks" fail fired 66 ms after touchdown, so each
    * block placement started a fresh run, and the first jump's tick count never landed on the
    * 4/5/6 values that pick the Flat label.
    *
    * <p>Every client ticks at 20 Hz, so a 50 ms gate on the frame hook reproduces the original
    * {@code runTick} cadence without needing to find that method in an obfuscated jar.
    */
   /**
    * Decides once, from the first 20 call intervals, whether this hook needs a 20 Hz gate.
    *
    * <p>Both cases exist in the wild: a client may call this once per frame (60-200 Hz) or once per
    * game tick (~20 Hz). Measuring beats assuming - gating an already-tick-rate hook would silently
    * drop ticks and make the very timings this mod reports drift.
    */
   private static void calibrate(long now) {
      if (calibSamples >= 20) {
         return;
      }
      if (calibSamples == 0) {
         calibStartNanos = now;
      }
      calibSamples++;
      if (calibSamples == 20) {
         long average = (now - calibStartNanos) / 19L;
         gateTicks = average < FRAME_RATE_THRESHOLD_NANOS;
         Log.info("tick calibration: average interval " + (average / 1_000_000L)
            + "ms over 20 calls, 20Hz gate " + (gateTicks ? "enabled" : "not needed"));
      }
   }

   private static void tick(Object self) {
      ensureConfig();
      bind(self);
      maybeFollowLanguage();

      long now = System.nanoTime();
      calibrate(now);
      // With a real tick clock the gate is redundant and harmful: it would drop frames that the
      // tracker already de-duplicates, and its 50 ms grid does not line up with the client's own.
      boolean exactTick = probe != null && probe.hasTick();
      if (gateTicks && !exactTick) {
         if (now - lastTickNanos < TICK_NANOS) {
            return;
         }
         lastTickNanos += TICK_NANOS;
         // A stalled frame (loading screen, alt-tab) should not fire a burst of catch-up ticks.
         if (now - lastTickNanos > MAX_TICK_LAG_NANOS) {
            lastTickNanos = now;
         }
      } else {
         lastTickNanos = now;
      }

      ticks++;

      GameBinding binding = game;
      if (binding == null) {
         return;
      }
      Object player = binding.player();
      if (player == null) {
         hasSample = false;
         return;
      }
      // The world only exists in-game, so the probe and the chat sink are (re)resolved from here.
      WorldProbe p = probe;
      if (p == null) {
         try {
            ensureProbe(binding);
         } catch (Throwable t) {
            Log.warn("world probe setup failed: " + describe(t));
         }
         p = probe;
      }
      if (p != null) {
         p.setPlayer(player);
         p.retryIfNeeded((int)ticks);
         if (p.hasTick() && !tickClockActive) {
            tickClockActive = true;
            Log.info("tick clock active: world.getTotalWorldTime (exact 20 Hz)");
         }
      }
      double[] pos = binding.position(player);
      if (pos == null) {
         return;
      }
      boolean ground = binding.onGround(player, pos[1]);

      if (!sampleLogged) {
         sampleLogged = true;
         Log.info(String.format(Locale.US,
            "first tick sample: x=%.3f y=%.3f z=%.3f ground=%s", Double.valueOf(pos[0]),
            Double.valueOf(pos[1]), Double.valueOf(pos[2]), Boolean.valueOf(ground)));
      }

      // Take-off edge as a run starter, only for a client where onPlayerRightClick could not be
      // hooked at all. Asking blockHookSeen here was the bug: that flag only turns true once a
      // right-click has been observed, so it is false on every client between world join and the
      // first click - the fallback then started a run on nothing but a jump, the fall into the void
      // closed it, and the next jump opened another.
      // 起跳边沿只在客户端**确实钩不上** onPlayerRightClick 时才用来开局。原来这里问的是
      // blockHookSeen，那正是 bug：它要等观察到一次右键才变真，所以进世界到第一次点击之间
      // 每个客户端上它都是 false——兜底于是仅凭一次起跳就开局，掉虚空判失败，下一跳又开一局。
      if (blockHookAbsent && hasSample && prevGround && !ground) {
         Tracker.onTakeOff();
      }

      // A different world invalidates everything the tracker is holding: the tick baseline, the
      // sampled positions and the restart cooldown all belong to the world that was unloaded.
      // 1.8.9 hands out a fresh World object per dimension, and a multi-world plugin or a server
      // transfer replaces it, so the identity is an exact signal — unlike the tick counter, which
      // also moves when the frame rate dips.
      if (p != null) {
         int identity = p.worldIdentity();
         if (identity != 0) {
            if (worldIdentity != 0 && identity != worldIdentity) {
               Tracker.onWorldChange("world object changed");
            }
            worldIdentity = identity;
         }
      }

      Tracker.noteY(pos[1]);
      Tracker.onTick(pos[0], pos[1], pos[2], ground);
      pumpChatOutput();

      // After onTick on purpose: the tracker samples the take-off position at
      // currentTick + tickOffset, so the counter has to have advanced past this tick first.
      detectJump(pos, ground);

      prevY = pos[1];
      prevGround = ground;
      hasSample = true;
   }

   /**
    * The player's own take-off, hooked directly.
    *
    * <p>This is the same signal the original mod used ({@code EntityLivingBase.jump()} → a jump
    * entry). Inferring it from sampled motion works but always lands one tick late: the first frame
    * that shows the player airborne is the frame after the one the jump happened on, so every
    * tick-quantised verdict shifts by one - a 4-tick flat reports as 5 and gets labelled 1.1 instead
    * of 1.05. When this hook is live, {@link #detectJump} stands down.
    *
    * <p>Mobs call the same method, so the entity has to be the local player before it counts.
    */
   private static void onJump(Object self) {
      GameBinding binding = game;
      if (binding == null || self == null || self != binding.player()) {
         return;
      }
      jumpHookSeen = true;
      Tracker.onJump();
   }

   /**
    * A jump happened, detected from the sampled motion instead of from a client method.
    *
    * <p>Kept as a fallback for the case where the {@code jump()} hook did not attach. On both
    * clients the real hook is preferred: taking off is visible in sampled data only one tick late
    * (a frame sees the player already airborne), and that single tick is what turned "1.05 Flat"
    * into "1.1 Flat".
    *
    * <p>Walking off a ledge does not qualify (the height drops rather than rises) and stepping onto
    * a slab does not either (the game still reports ground contact), so the edge only fires for a
    * real jump.
    */
   private static void detectJump(double[] pos, boolean ground) {
      if (jumpHookSeen) {
         // The real hook is live; sampling would only add a second, one-tick-late entry.
         return;
      }
      if (!hasSample || !prevGround || ground) {
         return;
      }
      if (pos[1] <= prevY + 0.01D) {
         return;
      }
      // Records one entry if a run is active. Starting one is the block-placement hook's job.
      Tracker.onJump();
   }

   /**
    * @return true when the held stack is a block item, i.e. this right-click was a placement
    *
    * <p>Falls back to {@code true} when the item type cannot be resolved: for this mod's purpose a
    * false positive costs one early timer start, while a false negative means the HUD never comes up
    * at all. The original checked {@code getItem() instanceof ItemBlock} with no fallback because it
    * was compiled against the game.
    */
   /**
    * True when the held stack is a block, i.e. this click actually placed something.
    *
    * <p>{@code onPlayerRightClick} returns true both for a placed block and for a used item (food,
    * boat, bucket). The original mod narrowed that with {@code getItem() instanceof ItemBlock}, and
    * the same test is kept - but both names now come from {@link ClassScan}, because on Badlion the
    * stack class is {@code zx}, the getter is {@code b()Lzw;} and {@code ItemBlock} is {@code yo}.
    *
    * <p>When neither name resolves, the click result alone decides. Refusing to ever start would be
    * worse than starting on the odd loaf of bread: the block hook is the only thing that starts a
    * timer once the jump hook is gone.
    */
   private static boolean isBlockItem(Object stack) {
      try {
         Method getter = stackGetItem;
         if (getter == null) {
            getter = itemGetter(stack.getClass());
            if (getter == null) {
               return true;
            }
            stackGetItem = getter;
         }
         Object item = getter.invoke(stack);
         if (item == null) {
            return false;
         }
         Class<?> base = itemBlockClass;
         if (base == null) {
            base = resolveItemBlock(item.getClass().getClassLoader());
            if (base == null) {
               if (!itemBlockMissingLogged) {
                  itemBlockMissingLogged = true;
                  Log.warn("ItemBlock class not resolvable; block placement inferred from the click"
                     + " result alone (any successful item use will also start a run)");
               }
               return true;
            }
            itemBlockClass = base;
         }
         return base.isInstance(item);
      } catch (Throwable t) {
         return true;
      }
   }

   /** {@code ItemStack.getItem()}, by the client's own name when the scan produced one. */
   private static Method itemGetter(Class<?> stackClass) {
      ClassScan.Result map = Bootstrap.mapping;
      if (map != null && map.itemGetterName != null) {
         try {
            Method m = stackClass.getMethod(map.itemGetterName);
            m.setAccessible(true);
            return m;
         } catch (Throwable ignored) {
            // fall through to the MCP name
         }
      }
      try {
         Method m = stackClass.getMethod("getItem");
         m.setAccessible(true);
         return m;
      } catch (Throwable t) {
         return null;
      }
   }

   private static Class<?> resolveItemBlock(ClassLoader loader) {
      ClassScan.Result map = Bootstrap.mapping;
      String name = map != null && map.itemBlockClass != null
         ? map.itemBlockClass : "net.minecraft.item.ItemBlock";
      try {
         return Class.forName(name, false, loader);
      } catch (Throwable t) {
         return null;
      }
   }

   /**
    * Language is checked once every 100 ticks: if the player changes the in-game language, the HUD
    * follows within 5 seconds.
    *
    * 语言检查每 100 tick 一次：玩家在游戏里换语言，HUD 5 秒内跟上。
    */
   private static int langCheckTicks = 99;

   /**
    * On "auto", prefer the client's own language ({@code GameSettings.language}) and fall back to the
    * system locale when that cannot be read. Which language a region tag resolves to is decided by
    * {@link Lang#autoCode}: exact match -> primary language -> built-in default.
    *
    * 「自动」时优先跟客户端自己的语言（GameSettings.language），读不到再看系统区域。
    * 区域标签落到哪门语言由 {@link Lang#autoCode} 决定：精确 → 主语言 → 内置默认。
    */
   private static String resolveLanguage() {
      if (!Lang.isAuto(Config.language)) {
         return Lang.normalise(Config.language);
      }
      GameBinding binding = game;
      String fromGame = binding == null ? null : binding.gameLanguage();
      if (fromGame != null) {
         return Lang.autoCode(fromGame);
      }
      return Lang.systemLanguage();
   }

   private static void maybeFollowLanguage() {
      if (!Lang.isAuto(Config.language)) {
         return;
      }
      if (++langCheckTicks < 100) {
         return;
      }
      langCheckTicks = 0;
      String want = resolveLanguage();
      if (!Lang.normalise(want).equalsIgnoreCase(Lang.locale())) {
         Lang.load(want);
         Log.info("language followed the client: " + Config.language + " -> " + Lang.locale());
      }
   }

   private static void ensureConfig() {
      if (configLoaded) {
         return;
      }
      configLoaded = true;
      Config.load();
      // The language pack follows the config: Config only stores the code (which may be auto); the
      // actual strings live in Lang.
      // 语言包要跟着配置走：Config 只存 code（可能是 auto），真正的文案在 Lang 里
      Lang.load(resolveLanguage());
      Log.info("config loaded (enabled=" + Config.enabled + ", displayMode=" + Config.displayMode
         + ", language=" + Config.language + " -> " + Lang.locale() + ", " + Lang.size() + " strings)");
      // Absolute path + this JVM's pid: the desktop injector parses this line to know which file to
      // write for which client. Without the pid, two clients (Lunar 和 Badlion 的 CWD 不同)
      // share one "last seen" path and edits land in the wrong game's config.
      // ProcessHandle is Java 9+, while the agent has to load in Java 8 clients (see build.gradle).
      // The "| pid N" on this line is parsed by the desktop injector, so the format cannot change.
      // ProcessHandle 是 Java 9+，而 agent 必须能在 Java 8 的客户端里加载（见 build.gradle）。
      // 这一行的 "| pid N" 会被桌面注入器解析，格式不能变。
      String runtime = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
      int at = runtime.indexOf('@');
      Log.info("config file: " + Config.path() + " | pid " + (at > 0 ? runtime.substring(0, at) : runtime));
      Config.startWatcher(Bridge::onConfigReload);
   }

   /**
    * Runs on the watcher thread after the config file changed under us.
    *
    * <p>Everything except the HUD geometry is read live at draw/tick time, so only the renderer
    * needs to be told. It lives in its own classloader, hence the reflective call.
    */
   private static void onConfigReload() {
      // Only when the language actually changed: while the launcher's HUD stick is being dragged the
      // file reloads 20 times a second, and re-reading the whole dictionary each time would be pure
      // waste — the language cannot change mid-drag.
      // 只在语言真的变了时才重载：摇杆拖动时这个文件一秒要重载 20 次，
      // 每次都把整本字典重读一遍纯属浪费——拖动过程中语言不可能变。
      maybeFollowLanguage();
      Object renderer = hudRenderer;
      if (renderer == null) {
         return;
      }
      try {
         renderer.getClass().getMethod("applyConfig", double.class, double.class, double.class,
            boolean.class, boolean.class).invoke(renderer, Double.valueOf(Config.hudXPercent),
            Double.valueOf(Config.hudYPercent), Double.valueOf(Config.hudScale),
            Boolean.valueOf(Config.backgroundEnabled && !"1".equals(System.getenv("BRIDGETAP_NO_BG"))),
            Boolean.valueOf(Config.textShadow));
         // One line a second is enough to see that a drag happened; one line per frame would bury
         // everything else in the log.
         // 一秒一条足以看出"拖过"，每帧一条会把别的日志全埋掉。
         long now = System.currentTimeMillis();
         if (now - lastGeometryLog >= 1000L) {
            lastGeometryLog = now;
            Log.info("hud geometry applied: anchor=" + Config.hudXPercent + "%," + Config.hudYPercent
               + "% scale=" + Config.hudScale);
         }
      } catch (Throwable t) {
         Log.warn("hud geometry apply failed: " + t);
      }
   }

   // ---------------------------------------------------------------- renderer

   /**
    * Loads the GL renderer from its own jar, parented by the classloader that defined LWJGL.
    *
    * <p>{@code GlHud} references {@code org.lwjgl.opengl.*} directly, and those classes exist only in
    * the client's own loader — the bootstrap loader (which owns everything in the agent jar) cannot
    * see them. Hence a child loader whose parent is the game loader.
    *
    * <p>Everything crossing that boundary is reflective. The renderer implements no interface from
    * this jar on purpose: a client loader is free to refuse to resolve our class names, and Lunar's
    * does exactly that. Reflection turns that hard failure into a plain "no HUD" and keeps the frame
    * loop alive.
    *
    * <p>The client's own {@code FontRenderer} is handed across, which is what makes the overlay use
    * the active resource pack's font.
    */
   /** A flag file in the deploy directory; see {@link #hud()}. */
   private static boolean flagFile(String name) {
      try {
         return new File(logDir(), name).isFile();
      } catch (Throwable t) {
         return false;
      }
   }

   private static int scaleSync;

   /**
    * {@code Framebuffer.bindFramebuffer(true)} / {@code unbindFramebuffer()}.
    *
    * <p>Resolved once per class and cached: this runs every frame, and the names differ between the
    * two clients ({@code bindFramebuffer(boolean)} vs {@code a(Z)V}).
    */
   private static boolean bindFramebuffer(Object framebuffer) {
      try {
         Method bind = framebufferMethod(framebuffer.getClass(), true);
         if (bind == null) {
            return false;
         }
         bind.invoke(framebuffer, Boolean.TRUE);
         return true;
      } catch (Throwable t) {
         return false;
      }
   }

   private static void unbindFramebuffer(Object framebuffer) {
      try {
         Method unbind = framebufferMethod(framebuffer.getClass(), false);
         if (unbind != null) {
            unbind.invoke(framebuffer);
         }
      } catch (Throwable ignored) {
         // the frame is already on its way out; nothing to do
      }
   }

   private static Method framebufferMethod(Class<?> type, boolean bind) {
      if (type == framebufferBindType) {
         return bind ? framebufferBind : framebufferUnbind;
      }
      Method foundBind = null;
      Method foundUnbind = null;
      try {
         for (Method candidate : type.getMethods()) {
            Class<?>[] params = candidate.getParameterTypes();
            if (bind && params.length == 1 && params[0] == boolean.class
                  && ("bindFramebuffer".equals(candidate.getName())
                     || "a".equals(candidate.getName()))) {
               foundBind = candidate;
            } else if (!bind && params.length == 0 && candidate.getReturnType() == void.class
                  && ("unbindFramebuffer".equals(candidate.getName())
                     || "e".equals(candidate.getName()))) {
               foundUnbind = candidate;
            }
         }
      } catch (Throwable ignored) {
         return null;
      }
      if (foundBind != null) {
         try {
            foundBind.setAccessible(true);
         } catch (Throwable ignored) {
            // public is enough
         }
      }
      if (foundUnbind != null) {
         try {
            foundUnbind.setAccessible(true);
         } catch (Throwable ignored) {
            // public is enough
         }
      }
      framebufferBindType = type;
      framebufferBind = foundBind;
      framebufferUnbind = foundUnbind;
      return bind ? foundBind : foundUnbind;
   }

   private static Class<?> framebufferBindType;
   private static Method framebufferBind;
   private static Method framebufferUnbind;

   /**
    * Hands {@code GameSettings.guiScale} to the renderer.
    *
    * <p>The overlay works in GUI-scaled pixels, and on Badlion it is drawn after every renderer,
    * where no such projection exists - so the renderer builds one and needs the factor.
    */
   private static void syncGuiScale(Object renderer, GameBinding binding) {
      try {
         Method setter = renderer.getClass().getMethod("setGuiScale", int.class);
         setter.invoke(renderer, Integer.valueOf(binding == null ? 0 : binding.guiScale()));
      } catch (Throwable ignored) {
         // a renderer without the setter simply uses its own fallback
      }
   }

   private static Object hud() {
      Object current = hudRenderer;
      if (current != null) {
         return current;
      }
      // Escape hatches. The data path is independent of the overlay, so a client whose driver
      // dislikes our GL calls can still be measured with the panel switched off. File flags rather
      // than environment variables: the JVM is started by the client's launcher, and its
      // environment is not something the user can set per session.
      //   <deploy>\hud.off    - no overlay at all (data, chat and /bt keep working)
      //   <deploy>\hud.nobg   - text only, skip the four background quads
      if ("0".equals(System.getenv("BRIDGETAP_HUD")) || flagFile("hud.off")) {
         noteOnce("hud disabled by flag (hud.off / BRIDGETAP_HUD=0)");
         return null;
      }
      GameBinding binding = game;
      if (binding == null) {
         // No game object yet; not a failure, just too early.
         noteOnce("hud deferred: game object not bound yet");
         return null;
      }
      Object font = binding.fontRenderer();
      if (font == null) {
         noteOnce("hud deferred: font renderer field is null on " + binding.mcClass.getName());
         return null;
      }
      if (hudAttempts >= HUD_ATTEMPTS) {
         noteOnce("hud given up after " + hudAttempts + " attempts");
         return null;
      }
      hudAttempts++;
      try {
         ClassLoader loader = gameLoader();
         if (loader == null) {
            Log.info("overlay deferred: client's LWJGL (Display/GL11) not resolvable yet");
            return null;
         }
         File jar = hudJar();
         if (jar == null) {
            Log.warn("hud unavailable: no bridgetap-hud-*.jar in the deploy directory");
            return null;
         }
         java.net.URLClassLoader hudLoader = new java.net.URLClassLoader(
            new java.net.URL[] {jar.toURI().toURL()}, loader);
         Class<?> type = hudLoader.loadClass("com.bridger.compat.hud.GlHud");
         ClassScan.Result map = Bootstrap.mapping;
         Class<?> glState = null;
         if (map != null && map.glStateClass != null) {
            try {
               glState = Class.forName(map.glStateClass, false, loader);
            } catch (Throwable ignored) {
               glState = null;
            }
         }
         // GlStateManager method names, in the order GlHud expects them. Lunar keeps the MCP names;
         // Badlion's are the 1.8.9 obfuscation, read from 1.8.9-mcp.tsrg2.
         String[] glNames = map != null && map.obf
            ? new String[] {"c", "f", "w", "l", "a", "d", "i"}
            : new String[] {"color", "disableLighting", "enableTexture2D", "enableBlend",
               "tryBlendFuncSeparate", "enableAlpha", "disableDepth"};
         Object renderer = type.getConstructor(Object.class, double.class, double.class, double.class,
            boolean.class, boolean.class, String.class, Class.class, String[].class).newInstance(
               font, Double.valueOf(Config.hudXPercent), Double.valueOf(Config.hudYPercent),
               Double.valueOf(Config.hudScale),
               Boolean.valueOf(Config.backgroundEnabled && !flagFile("hud.nobg")
                  && !"1".equals(System.getenv("BRIDGETAP_NO_BG"))),
               Boolean.valueOf(Config.textShadow), logDir() + File.separator + "hud.log",
               glState, glNames);
         Method render = type.getMethod("render", List.class);
         hudRenderer = renderer;
         hudRender = render;
         Log.info("hud loaded (font " + font.getClass().getName() + ", parent "
            + loader.getClass().getName() + ")");
         return renderer;
      } catch (Throwable t) {
         Log.warn("hud load attempt " + hudAttempts + "/" + HUD_ATTEMPTS + " failed: " + describe(t));
         return null;
      }
   }

   /**
    * The loader that owns the client's LWJGL, verified to be able to resolve the GL classes the
    * renderer imports. Cached: the fallback scan walks every loaded class, far too expensive per
    * frame.
    */
   private static ClassLoader gameLoader() {
      ClassLoader cached = renderLoader;
      if (cached != null) {
         return cached;
      }
      for (Class<?> type : Bootstrap.allLoadedClasses()) {
         if (!"org.lwjgl.opengl.Display".equals(type.getName())) {
            continue;
         }
         ClassLoader loader = type.getClassLoader();
         if (loader == null) {
            return null;
         }
         try {
            loader.loadClass("org.lwjgl.opengl.GL11");
            loader.loadClass("org.lwjgl.opengl.GL12");
         } catch (Throwable t) {
            return null;
         }
         renderLoader = loader;
         return loader;
      }
      return null;
   }

   private static String logDir() {
      String base = System.getenv("LOCALAPPDATA");
      if (base == null || base.isEmpty()) {
         base = System.getProperty("java.io.tmpdir");
      }
      return new File(base, "BridgeTap").getAbsolutePath();
   }

   /**
    * hud.jar sits in the injector's deploy directory.
    *
    * <p>It cannot be read as a classpath resource: this class is loaded by the bootstrap loader, and
    * {@code Class.getResourceAsStream("/x")} resolves through the <em>system</em> loader. The name
    * carries a timestamp because a loaded jar stays locked, so the newest one wins. Resolved once.
    */
   private static File hudJar() {
      File cached = hudJarFile;
      if (cached != null) {
         return cached;
      }
      try {
         String base = System.getenv("LOCALAPPDATA");
         if (base == null || base.isEmpty()) {
            base = System.getProperty("java.io.tmpdir");
         }
         File dir = new File(new File(base, "BridgeTap/inject"), ".");
         File[] files = dir.listFiles();
         if (files == null) {
            return null;
         }
         File newest = null;
         for (File file : files) {
            String name = file.getName();
            if (!name.startsWith("bridgetap-hud") || !name.endsWith(".jar")) {
               continue;
            }
            if (newest == null || file.lastModified() > newest.lastModified()) {
               newest = file;
            }
         }
         hudJarFile = newest;
         return newest;
      } catch (Throwable t) {
         return null;
      }
   }

   /**
    * Logs the first time each distinct reason prevents a draw.
    *
    * <p>The overlay fails silently by design - a broken HUD must never throw into the client's frame
    * loop - which makes "nothing appears" indistinguishable from "never reached" without this. Each
    * reason is printed once so a normal session stays quiet.
    */
   private static void noteOnce(String reason) {
      if (DRAW_NOTES.add(reason)) {
         Log.info("draw: " + reason);
      }
   }

   private static final java.util.Set<String> DRAW_NOTES =
      java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

   private static String describe(Throwable t) {
      StringBuilder sb = new StringBuilder(String.valueOf(t));
      Throwable cause = t.getCause();
      if (cause != null) {
         sb.append(" <- ").append(cause);
      }
      StackTraceElement[] frames = t.getStackTrace();
      for (int i = 0; i < frames.length && i < 4; i++) {
         sb.append(" | ").append(frames[i]);
      }
      return sb.toString();
   }

   // ---------------------------------------------------------------- diagnostics

   public static GameBinding currentGame() {
      return game;
   }

   public static double[] currentPosition() {
      GameBinding binding = game;
      if (binding == null) {
         return null;
      }
      Object player = binding.player();
      return player == null ? null : binding.position(player);
   }

   public static long frameCount() {
      return frames;
   }

   public static long tickCount() {
      return ticks;
   }

   static void reset() {
      hasSample = false;
      prevGround = false;
      prevY = 0.0D;
   }
}
