package com.bridger.compat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Platform-independent port of the original BridgeTracker.
 *
 * <p>The Fabric build read player state directly through mapped Minecraft classes. Here the agent
 * feeds the same data through {@link #onTick} / {@link #onJump}; everything downstream (history
 * ring, axis detection, tick offsets, label selection) is unchanged.
 *
 * <p>Diff vs. original: the sidebar scoreboard clock and the gold-plate success check need world
 * access, which is not available through the profile-driven hooks yet. Time is therefore derived
 * from the tick counter and the plate check is delegated to an optional {@link PlateProbe}.
 */
public final class Tracker {
   public enum RunStatus {
      IDLE, BRIDGING, SUCCESS, FAILED
   }

   public interface PlateProbe {
      boolean isOnGoldPlate(double x, double y, double z);
   }

   /** The map's own run clock; returns {@code -1} when the sidebar has no timer. */
   public interface TimeSource {
      double read();
   }

   /** Chat output, so the success line can be printed the way the original mod printed it. */
   public interface ChatSink {
      void send(String line);
   }

   /**
    * The game's own tick counter; returns {@code -1} when it cannot be read.
    *
    * <p>Counting hook invocations is not a substitute. The hook this runs on fires once per frame on
    * some clients and at a throttled ~22 Hz on others, and both drift against the 20 Hz the game
    * actually ticks at - which showed up as "1.05 Flat" being labelled "1.1 Flat", a one-tick error
    * in every tick-quantised verdict.
    */
   public interface TickSource {
      long read();
   }

   /**
    * Movement key state, supplied by the agent (only it can read the client's key bindings).
    *
    * <p>Bit set: 1 = forward, 2 = back, 4 = left, 8 = right. {@code 0} means "unknown", which is what
    * a client whose key bindings could not be mapped reports — the gain tag is then simply omitted
    * instead of being guessed.
    */
   public interface InputProbe {
      int read();
   }

   /**
    * Answers "is there a solid block right above the player's head".
    *
    * <p>Three-valued on purpose. Upstream proves a bonk with {@code collidedVertically &&
    * motionY > 0}; the injector only has the height curve, and a curve that flattens is not proof:
    * a telly hands the same Y over twice and stalls {@code dy} exactly like a ceiling does. So the
    * world is asked as well, and the answer is:
    * <ul>
    *   <li>{@code TRUE} — there is a block up there, the bonk is real;</li>
    *   <li>{@code FALSE} — clear sky, the stalled rise was something else, drop the entry;</li>
    *   <li>{@code null} — the world cannot be read (no world yet / block chain unresolved). The
    *       old lenient behaviour is kept so the feature does not silently vanish on such a client,
    *       and a line goes to the log the first time it happens.</li>
    * </ul>
    */
   public interface CeilingProbe {
      Boolean hasCeiling(double x, double y, double z);
   }

   /**
    * Round-trip latency to the server in milliseconds, {@code -1} when it cannot be read.
    *
    * <p>Read on demand (not cached) so the number in the success line is the latency at the moment
    * the run finished. Single-player and LAN land on {@code 0}; a client that cannot resolve the
    * player list answers {@code -1}, and the line is then printed unadjusted.
    */
   public interface PingProbe {
      int read();
   }

   private static final int POSITION_HISTORY_SIZE = 81;
   /**
    * Largest step our own tick counter may take in one frame.
    *
    * <p>World time can leap (alt-tab, a long stall, a server time sync). Advancing by the full
    * amount would blow the run's elapsed time out of proportion, so anything past this is clipped.
    */
   private static final int MAX_TICK_STEP = 20;
   /**
    * Airborne ticks a jump may last and still count as a head hit.
    *
    * <p>Ground truth from the map itself: a normal flat jump from the ground keeps the player
    * airborne for 12 ticks ({@code y} reaches 0.420 on tick 1 and is back to 0 on tick 12); with a
    * block over the head the rise is cut dead on tick 1 and the player is back down on tick 3.
    * Five leaves room for the sampling edge without ever reaching a real jump.
    */
   private static final int HEAD_HIT_MAX_AIR = 5;
   /** A bonk drops you back where you took off; landing a step higher is a normal jump. */
   private static final double HEAD_HIT_MAX_RISE = 0.2;
   /**
    * The pressure-plate trigger box is 0.25 tall itself, so the foot may still sit slightly above
    * the plate surface on the frame the plate is stepped on.
    *
    * 压力板触发盒本身有 0.25 高，踩板那一帧脚可能还在板面上方一点点。
    */
   private static final double PLATE_LEVEL_TOLERANCE = 0.35;
   /**
    * Distance in blocks below the last grounded height that makes a fall *suspicious*.
    *
    * <p>The baseline has to be {@link #groundY}, not the {@code startY} from when the run began. A
    * bridge built downwards drops one block per block placed, so three blocks already exceed 2.5,
    * and using the take-off point as the baseline marks a perfectly normal stair bridge as a void
    * fall for its whole length - all 98 failures in the field log were this case ({@code y=62.5}
    * against {@code startY=65.17}, a drop of only 2.6 blocks).
    *
    * <p>Crossing this line is not a verdict on its own; see {@link #VOID_GRACE_TICKS}.
    *
    * 离「最近一次落地的高度」多少格开始怀疑是在下坠。
    *
    * <p>基准必须是 {@link #groundY}，不是起跑时的 {@code startY}。往下搭的桥每放一块就低一格，
    * 三块就超过 2.5，拿起跑点当基准会把正常的阶梯桥整段判成掉虚空 —— 现场日志 98 次失败
    * 全部是这种情况（{@code y=62.5} 对 {@code startY=65.17}，只掉 2.6 格）。
    *
    * <p>穿过这条线本身不算判定，见 {@link #VOID_GRACE_TICKS}。
    */
   private static final double VOID_DROP = 2.5;
   /**
    * Consecutive airborne ticks before a suspicious drop is closed as a void fall.
    *
    * <p>Dropping past {@link #VOID_DROP} is normal play: the finish island can sit several blocks
    * below the bridge, and a jump down to it crosses that line many ticks before the feet land.
    * What separates the two cases is landing, not height - a successful jump down clears
    * {@code ticksSinceGround} the moment it touches the island, while a real void fall never lands
    * at all. 50 ticks is about twice the ~23 ticks a 100-block fall takes, so even a fall from the
    * top of the map reaches the ground inside the window.
    *
    * 低于 {@link #VOID_DROP} 只是「可疑」，不是判定。
    *
    * <p>往下跳本身就是正常操作：终点岛可能比桥低好几格，跳向它的途中必然会穿过那条线。
    * 区分两者的不是高度而是**落地** —— 成功跳下去落在岛上时 {@code ticksSinceGround} 立刻清零，
    * 真掉虚空则永远不落地。50 tick 约为 100 格自由落体（约 23 tick）的两倍，从地图顶部掉下来
    * 也来不及跑完这个窗口。
    */
   private static final int VOID_GRACE_TICKS = 50;
   /**
    * Grounded ticks after which a run that never reaches the plate is closed as stalled.
    *
    * <p>Replaces the old five-tick "Missed Jump" check. That window was shorter than the walk from
    * the landing spot to the plate on most finish islands, so a successful jump down followed by a
    * two-step walk was reported as a failure before the player could ever press it. The question
    * this really answers is "is this run ever going to end", and three seconds of standing still is
    * a much better proxy for that than five ticks of not jumping.
    *
    * 落地后多久还没踩到板就判定这一局卡住了。
    *
    * <p>取代原来的「Missed Jump」（落地 5 tick）。那个窗口比「落点到压力板」的步行时间还短，
    * 跳下岛走两步就踩板的情况反而会先被判死。这条真正要回答的是「这一局还会不会结束」，
    * 用「站着不动三秒」比「五 tick 没跳」靠谱得多。
    */
   private static final int STALL_TICKS = 60;
   /**
    * No new run may start once the player has been airborne for more than this many ticks.
    *
    * <p>The five-tick restart cooldown only stops "the landing block counting the previous run a
    * second time"; it does not stop "the player is still falling and places another block on the way
    * down". In that case a new run starts every 5-7 ticks, the HUD stays stuck in a 0.00 -> 0.35
    * loop, and it looks like "we fell into the void but the data keeps recording". A normal jump
    * stays airborne for 12 ticks, so anything past that cannot be a take-off.
    *
    * 超过这么多 tick 没落地，就不许开新的一局。
    *
    * <p>五 tick 的重开冷却只防「落地那一块把上一局重复计一次」，防不住「人还在往下掉、
    * 顺路又放了一块」。那种情况下每 5~7 tick 就重新开一局，HUD 一直停在 0.00→0.35 的循环里，
    * 看起来就是「掉进虚空了数据还在记」。一次正常跳跃滞空 12 tick，超过它就不该是起跳。
    */
   private static final int MAX_START_AIR_TICKS = 12;
   /**
    * Whether a run may be opened by a take-off instead of by an observed placement.
    *
    * <p>Only ever true when the block hook provably cannot be installed, because a take-off is not
    * a placement: the agent sets this from the injection table, where the absence of the controller
    * method is a fact rather than a guess. It used to be inferred from "the block hook has not fired
    * yet", and that is not the same statement — between joining a world and the first right-click
    * the hook has not fired either, so plain jumping opened a run, void falls closed it, and the
    * cycle repeated until a block was actually placed.
    *
    * 是否允许用「起跳」代替「放置方块」来开局。
    *
    * <p>只有在方块钩子**确实装不上**时才为真：起跳不是放置，这个判断由 agent 从注入表直接给出，
    * 那是事实而不是猜测。以前是从「方块钩子还没触发过」反推，这两件事并不等价——从进世界到第一次
    * 右键之间钩子同样没触发过，于是单纯起跳就开了一局，掉虚空判失败，再跳再开，循环到真的放下
    * 一块才停。
    */
   private static volatile boolean takeOffMayStart;
   /**
    * Whether this run has seen its first real placement.
    *
    * <p>The gate the whole run hangs on: no clock, no entries and no verdict until one block has
    * actually gone down. Cleared by {@link #startRun()} and by {@link #reset()}, so a new world, a
    * {@code /bt reset} or a fresh run can never inherit the previous run's evidence.
    *
    * 这一局是否已经见到第一次真正的放置。
    *
    * <p>整局都挂在它上面：在真的放下一块之前，不计时、不写条目、不下判定。
    * {@link #startRun()} 与 {@link #reset()} 都会清掉它，所以换世界、{@code /bt reset} 或新开一局
    * 都不可能继承上一局的证据。
    */
   private static boolean firstPlacementSeen;
   /**
    * How long a deferred sample may stay queued before it is abandoned.
    *
    * <p>These wait for the tick they were derived from to enter the ring buffer; if that tick never
    * arrives (world swap, server lag) the queue would block the next run's start forever.
    */
   private static final int PENDING_MAX_AGE = 60;
   /** Diagnostics are only useful for the first few events; the log must not grow per frame. */
   private static final int DIAG_LIMIT = 25;
   private static int startLogged;
   private static int jumpLogged;
   private static int fourBlockLogged;

   private static RunStatus status = RunStatus.IDLE;
   private static boolean isBridging = false;
   private static double currentBridgeTime = 0.0;
   private static String currentAxis = "Z";
   private static int jumpCount = 0;
   private static int startTick = 0;
   private static int currentTick = 0;
   private static int lastJumpTick = 0;
   private static final int[] positionHistoryTicks = new int[POSITION_HISTORY_SIZE];
   private static final double[] positionHistoryX = new double[POSITION_HISTORY_SIZE];
   /** Y joined the ring in 1.5.0: the opening-late rule needs the rise, not just the column. */
   private static final double[] positionHistoryY = new double[POSITION_HISTORY_SIZE];
   private static final double[] positionHistoryZ = new double[POSITION_HISTORY_SIZE];
   private static final boolean[] positionHistoryValid = new boolean[POSITION_HISTORY_SIZE];
   private static double lastJumpCoord = 0.0;
   private static double lastJumpDecimal = 0.0;
   /** Where the player was when the last jump was recorded; the opening-late rule starts here. */
   private static double lastLaunchX = 0.0;
   private static double lastLaunchY = 0.0;
   private static double lastLaunchZ = 0.0;
   /** Tick count of the opening jump (3 = 1.0 Flat, 4 = 1.05 Flat); 0 before the first jump. */
   private static int openingTicks = 0;
   /** Upstream 1.5.0: five ticks must pass after a finished run before the next can start. */
   private static final RunStartCooldown restartCooldown = new RunStartCooldown();
   private static double prevTickCoord = 0.0;
   private static double lastTickCoord = 0.0;
   private static double startX = 0.0;
   private static double startY = 0.0;
   private static double startZ = 0.0;
   private static double flatDuration = 1.05;
   /** Set once the sidebar clock has confirmed the first flat's real duration. */
   private static boolean flatFinalized = false;
   private static boolean turnRecorded = false;
   private static boolean platesRecorded = false;
   private static int groundTicksAfterLanding = 0;
   private static boolean wasInAir = false;
   private static final List<JumpEntry> entries = new ArrayList<JumpEntry>();
   private static final List<PendingJump> pendingJumps = new ArrayList<PendingJump>();
   /**
    * Ticks between the plate being touched and the success line reaching chat.
    *
    * <p>The verdict itself stays immediate - the HUD shows the Plates entries on the tick of
    * contact - but the chat line is held back two ticks, so it lands once the player has visibly
    * come to rest on the plate instead of the instant the feet touch it.
    */
   private static final int SUCCESS_MESSAGE_DELAY_TICKS = 2;
   private static int pendingSuccessTick = -1;
   /**
    * The run's measured time, kept raw until the line is actually built two ticks later.
    *
    * <p>The latency adjustment is added at send time, not here, so the figure reflects the link at
    * the moment the run ended rather than whatever it was when the plate was first touched.
    */
   private static double pendingSuccessTime;
   private static int pendingSuccessMilliBefore;
   private static int pendingSuccessMilliLast;
   private static boolean pendingPlateSample = false;
   private static int pendingPlateTick = 0;
   private static PlateProbe plateProbe = new PlateProbe() {
      @Override
      public boolean isOnGoldPlate(double x, double y, double z) {
         return false;
      }
   };

   /**
    * The authoritative clock. The original mod stored whatever the sidebar scoreboard printed and
    * never counted ticks; ticking a stopwatch locally is only the fallback for a map with no timer.
    */
   private static TimeSource timeSource = new TimeSource() {
      @Override
      public double read() {
         return -1.0D;
      }
   };

   private static ChatSink chatSink = new ChatSink() {
      @Override
      public void send(String line) {
         Log.info(line);
      }
   };

   private static TickSource tickSource = new TickSource() {
      @Override
      public long read() {
         return -1L;
      }
   };

   /** Default: unknown, so no gain tag is shown until the agent supplies the key states. */
   private static InputProbe inputProbe = new InputProbe() {
      @Override
      public int read() {
         return 0;
      }
   };

   /** Default "unknown": no latency figure, so the success line is printed as measured. */
   private static PingProbe pingProbe = new PingProbe() {
      @Override
      public int read() {
         return -1;
      }
   };

   /** Default "unknown": the tracker keeps working when nothing is wired up. */
   private static CeilingProbe ceilingProbe = new CeilingProbe() {
      @Override
      public Boolean hasCeiling(double x, double y, double z) {
         return null;
      }
   };

   /**
    * Last raw world tick seen, -1 before the first sample.
    *
    * <p>Kept apart from {@link #currentTick} on purpose: the world tick is what the client reports
    * (and the server may push it backwards), while {@code currentTick} is our own monotonic counter
    * that every queued sample and the run timer key off.
    */
   private static int lastWorldTick = -1;

   private Tracker() {
   }

   public static void setPlateProbe(PlateProbe probe) {
      plateProbe = probe == null ? plateProbe : probe;
   }

   public static void setTimeSource(TimeSource source) {
      timeSource = source == null ? timeSource : source;
   }

   public static void setChatSink(ChatSink sink) {
      chatSink = sink == null ? chatSink : sink;
   }

   public static void setTickSource(TickSource source) {
      tickSource = source == null ? tickSource : source;
   }

   public static void setInputProbe(InputProbe probe) {
      inputProbe = probe == null ? inputProbe : probe;
   }

   public static void setCeilingProbe(CeilingProbe probe) {
      ceilingProbe = probe == null ? ceilingProbe : probe;
   }

   public static void setPingProbe(PingProbe probe) {
      pingProbe = probe == null ? pingProbe : probe;
   }

   /**
    * Half of the round trip, rounded to the nearest millisecond.
    *
    * <p>The timer stops when the <em>client</em> sees the plate; the server only agrees half a round
    * trip later. Adding that half is what makes the printed figure line up with the server's replay,
    * and it is applied to the display only — the run's own measurement is left untouched.
    *
    * @param pingMillis full round-trip latency, or {@code -1} when it could not be read
    * @return {@code 0} for an unknown latency (the line is then printed as measured)
    */
   public static int pingHalf(int pingMillis) {
      return pingMillis > 0 ? (int)Math.round(pingMillis / 2.0D) : 0;
   }

   /** {@code Gain} / {@code No Gain} / null, from the strafe keys at this instant. */
   private static String currentGain() {
      int bits = inputProbe.read();
      if (bits == 0) {
         return null;
      }
      return JumpSignals.gain((bits & 1) != 0, (bits & 4) != 0, (bits & 8) != 0, (bits & 2) != 0);
   }

   /** The newest recorded entry, or null while nothing has been measured. */
   public static synchronized JumpEntry getLastEntry() {
      return entries.isEmpty() ? null : entries.get(entries.size() - 1);
   }

   /** The turn entry once it has been recorded, otherwise null. */
   public static synchronized JumpEntry getTurnEntry() {
      for (int i = entries.size() - 1; i >= 0; i--) {
         if (entries.get(i).getType() == JumpEntry.EntryType.TURN) {
            return entries.get(i);
         }
      }
      return null;
   }

   public static synchronized List<JumpEntry> getEntries() {
      return Collections.unmodifiableList(new ArrayList<JumpEntry>(entries));
   }

   public static boolean isBridging() {
      return isBridging;
   }

   public static RunStatus getStatus() {
      return status;
   }

   /**
    * Declares whether a take-off may stand in for a placement.
    *
    * <p>Called once from the agent, which knows from the injection table whether the client has a
    * hookable {@code onPlayerRightClick}. Never call it with {@code true} on a client where the hook
    * is present: that re-opens the "jumping around lights up the HUD" hole this gate exists to
    * close.
    *
    * <p>Sets the flag and forgets any run in flight, because the value is decided at injection time
    * and a stale run opened under the previous answer must not continue.
    *
    * 声明「起跳是否可代替放置」。
    *
    * <p>由 agent 调用一次，它从注入表就能知道客户端有没有可钩的 {@code onPlayerRightClick}。
    * 钩子存在的客户端**绝不能**传 {@code true}：那会把「到处乱跳也点亮 HUD」这个洞重新打开，
    * 而这个开关的存在就是为了堵住它。
    *
    * <p>置位的同时丢弃正在跑的那一局：这个值在注入期就定了，按旧答案开出来的局不该继续。
    */
   public static synchronized void setTakeOffMayStart(boolean allowed) {
      if (takeOffMayStart == allowed) {
         return;
      }
      takeOffMayStart = allowed;
      firstPlacementSeen = false;
      Log.info("take-off may start a run: " + allowed + " (block hook "
         + (allowed ? "absent on this client" : "present; a placement is required)"));
   }

   /** Diagnostics only: whether the current run has seen its first placement yet. */
   public static boolean hasFirstPlacement() {
      return firstPlacementSeen;
   }

   public static double getCurrentBridgeTime() {
      return currentBridgeTime;
   }

   public static int getJumpCount() {
      return entries.isEmpty() ? 0 : jumpCount + 1;
   }

   public static String getCurrentAxis() {
      return currentAxis;
   }

   public static int getCurrentTick() {
      return currentTick;
   }

   public static synchronized void reset() {
      restartCooldown.reset();
      PlacementEvidence.reset();
      firstPlacementSeen = false;
      openingTicks = 0;
      status = RunStatus.IDLE;
      isBridging = false;
      currentBridgeTime = 0.0;
      jumpCount = 0;
      startTick = 0;
      lastJumpTick = 0;
      lastJumpCoord = 0.0;
      lastJumpDecimal = 0.0;
      flatDuration = 1.05;
      flatFinalized = false;
      turnRecorded = false;
      platesRecorded = false;
      groundTicksAfterLanding = 0;
      wasInAir = false;
      entries.clear();
      pendingJumps.clear();
      pendingPlateSample = false;
      pendingSuccessTick = -1;
      pendingSuccessTime = 0.0D;
      lastWorldTick = -1;
      airStartTick = 0;
      airStartY = 0.0;
      airPeakY = 0.0;
      groundY = 0.0;
      ticksSinceGround = 0;
      previousY = Double.NaN;
      lastJumpEntryIndex = -1;
   }

   /**
    * A different world just became active: dimension change, a multi-world plugin, or a server
    * transfer.
    *
    * <p>The clock, the sampled positions and the 5-tick restart cooldown all belong to the world
    * that was unloaded, and the cooldown is the dangerous one: it compares absolute tick numbers,
    * a fresh world counts from zero, so {@code tick - finishedTick} stays negative for hours and
    * every placement is ignored until the player types {@code /bt reset}.
    *
    * <p>The recorded entries are kept on purpose: they are what a player lines up against the
    * server-side replay, and throwing them away on a /spawn defeats that comparison.
    *
    * @param reason what the caller saw, written to the log once
    */
   public static synchronized void onWorldChange(String reason) {
      boolean midRun = isBridging;
      boolean hadRun = midRun || !entries.isEmpty();
      // Entries are kept: the player lines the HUD's time and coordinates up against the server
      // replay, and clearing them makes that comparison impossible. Only the run-time state that
      // "belongs to the old world" is replaced - that is what actually got stuck.
      // 条目留着：玩家要拿 HUD 上的时间和坐标去跟服务器回放对轴，清掉就没法比了。
      // 只把那批「属于旧世界」的运行期状态换掉——它们才是卡死的来源。
      if (midRun) {
         // The world changed before the run finished, so this run really is dead: wrap it up as a
         // failure and keep the data.
         // 一局没跑完就换了世界，这一局确实废了：按失败收尾，数据保留
         status = RunStatus.FAILED;
      }
      isBridging = false;
      // A placement in the old world says nothing about the new one: the player has not built
      // anything yet, so the gate must start closed again.
      // 旧世界的一次放置在新区没有意义：玩家还没建任何东西，这道门必须重新关上。
      firstPlacementSeen = false;
      // The clock, the cooldown and pending samples are all recorded in old-world ticks; without a
      // reset the first block in the new world is treated as "still cooling down".
      // 时钟、冷却、待采样全都按旧世界的 tick 记的，不清的话新世界第一块会被当成「还在冷却」
      restartCooldown.reset();
      pendingJumps.clear();
      pendingPlateSample = false;
      PlacementEvidence.reset();
      clearPositionHistory();
      lastWorldTick = -1;
      startTick = currentTick;
      lastJumpTick = currentTick;
      wasInAir = false;
      groundTicksAfterLanding = 0;
      airStartTick = 0;
      airStartY = 0.0D;
      airPeakY = 0.0D;
      groundY = 0.0D;
      previousY = Double.NaN;
      if (hadRun) {
         Log.info("world changed: " + reason + " (run state reset, " + entries.size()
            + " entries kept for replay comparison)");
      } else {
         Log.verbose("world changed: " + reason + " (nothing to keep)");
      }
   }

   /**
    * Old-world coordinates mean nothing in the new world, so all of them are dropped.
    *
    * 旧世界的坐标对新世界没有意义，全部作废。
    */
   private static void clearPositionHistory() {
      for (int i = 0; i < POSITION_HISTORY_SIZE; i++) {
         positionHistoryValid[i] = false;
      }
   }

   /** Called once per game tick with the post-tick player position. */
   public static synchronized void onTick(double x, double y, double z, boolean onGround) {
      long external = tickSource.read();
      if (external >= 0L) {
         int worldTick = (int)(external & 0x3FFFFFFF);
         if (worldTick == lastWorldTick) {
            // The hook fires several times per game tick (once per frame). Sampling again would
            // overwrite the same history slot and, worse, run the whole state machine twice.
            return;
         }
         int delta = lastWorldTick < 0 ? 1 : worldTick - lastWorldTick;
         lastWorldTick = worldTick;
         // Our own counter only ever moves forward. The world's tick is NOT a safe clock: servers
         // re-send world time and the client jumps back a tick or two quite often (1596 times in
         // one session on Badlion), and a big backward jump used to be read as "another world",
         // throwing the run away. A world change is detected by the World object's identity
         // instead (see Bridge), so here a rewind just means "this frame is one tick later".
         if (delta <= 0) {
            currentTick++;
         } else if (delta > MAX_TICK_STEP) {
            // A long stall (alt-tab, chunk rebuild) must not stretch the run's timer.
            currentTick += MAX_TICK_STEP;
         } else {
            currentTick += delta;
         }
      } else {
         currentTick++;
      }
      savePositionSnapshot(x, y, z, currentTick);
      lastX = x;
      lastZ = z;
      processPendingSamples();
      flushSuccessMessage();

      if (onGround) {
         groundY = y;
         ticksSinceGround = 0;
      } else {
         ticksSinceGround++;
      }

      double coord = currentAxis.equals("X") ? x : z;
      if (!isBridging) {
         prevTickCoord = coord;
         lastTickCoord = coord;
         previousY = y;
         return;
      }

      currentBridgeTime = (currentTick - startTick) / 20.0;
      // The map's timer wins whenever it exists. 1.0-1.25 s is the window in which the first
      // straight is being timed, and that reading is what picks 1.05/1.1/1.15/1.2 for the label.
      double clock = timeSource.read();
      if (clock >= 0.0D) {
         currentBridgeTime = clock;
         if (!flatFinalized && clock >= 1.0D && clock <= 1.25D) {
            finalizeFlat(clock);
         }
      }


      // A server that pulls a falling player out of the void teleports them back up. That is a
      // failed run, not a teleport to somewhere else, so it is reported as Void when the previous
      // sample was already below the fall line. This is the only verdict a void fall ever gets on a
      // server that rescues early: the grace window never closes because the player lands first.
      // 服务器把掉下去的人拉回来，就是一次向上传送。这不是「传送到别处」而是掉虚空被救回，
      // 上一帧已经低于判定线就按 Void 收尾。在被提前救起的服务器上，这是掉虚空唯一能拿到的判定：
      // 人先落地，宽限窗口就永远走不完。
      if (!Double.isNaN(previousY) && JumpSignals.isUpwardTeleport(previousY, y)) {
         String reason = (groundY - previousY) >= VOID_DROP ? "Void" : "Teleport";
         // before the overwrite: the log needs the height we fell from
         // 覆盖之前先判：日志要记录是从多高掉下来的
         failRun(coord, reason);
         previousY = y;
         return;
      }
      previousY = y;

      if (jumpCount > 0 && !turnRecorded && !platesRecorded) {
         int sinceJump = currentTick - lastJumpTick;
         if (sinceJump == Config.turnTicks) {
            turnRecorded = true;
            double turnCoord = Config.turnTicks == 12 ? coord - 100.0 : coord;
            double abs = Math.abs(turnCoord);
            double dec = abs - Math.floor(abs);
            double delta = wrapDelta(dec - lastJumpDecimal);
            entries.add(new JumpEntry(Lang.t("entry.turn"), turnCoord, dec, delta, sinceJump, JumpEntry.EntryType.TURN));
         }
      }

      if (plateProbe.isOnGoldPlate(x, y, z)) {
         if (!platesRecorded) {
            // The finish dash is usually a head hit: bonk the ceiling, drop onto the plate on the
            // tick you land. Finish the jump's bookkeeping before the run is closed, otherwise the
            // line is silently lost — the plate branch returns without ever looking at the landing.
            // The plate trigger box is (plateY, plateY + 0.25), so stepping on the plate usually
            // happens on the **last frame before landing**: the player is still in the air while the
            // foot has already entered the trigger area. Settling only when onGround meant a finish
            // dash head hit was swallowed by the wrap-up. Air settling is allowed here, with the
            // height tolerance relaxed to the trigger box's 0.25.
            // 压力板的触发盒是 (plateY, plateY + 0.25)，所以踩板通常发生在**落地前的最后一帧**：
            // 人还在空中，脚已经伸进触发区。原来只在 onGround 时才结算，终点冲刺的顶头就这样被
            // 收尾吞掉了。这里允许空中结算，高度容差放宽到触发盒的 0.25。
            if (wasInAir) {
               considerHeadHit(currentTick - airStartTick, y, coord, PLATE_LEVEL_TOLERANCE);
            }
            platesRecorded = true;
            isBridging = false;
            status = RunStatus.SUCCESS;
            restartCooldown.finish(currentTick);
            pendingPlateTick = currentTick + Config.plateTickOffset;
            if (hasPositionSnapshot(pendingPlateTick)) {
               recordPlateEntries(pendingPlateTick);
            } else if (pendingPlateTick > currentTick) {
               pendingPlateSample = true;
            } else {
               recordPlateEntries(pendingPlateTick);
            }
         }
      } else if (!onGround) {
         if (!wasInAir) {
            airStartTick = currentTick;
            // The 0.42 rise happens between the last grounded sample and this one, so the take-off
            // height has to come from that grounded sample — using the first airborne Y would eat
            // the whole rise and make every bonk look like a step off a ledge.
            airStartY = groundY;
            airPeakY = Math.max(groundY, y);
         }
         if (y > airPeakY) {
            airPeakY = y;
         }
         wasInAir = true;
         groundTicksAfterLanding = 0;

         // Airborne bookkeeping first, verdict second. Dropping below the line is how a jump down to
         // a lower finish island starts, so it may only close the run once it is clear the player is
         // not going to land at all. Landing on the island clears ticksSinceGround and this branch
         // is never reached again.
         // 先记空中状态，再下判定。跳向更低的终点岛本来就会穿过这条线，所以必须先确认「根本不
         // 会落地」才收尾；落在岛上会把 ticksSinceGround 清零，这条分支就再也不会命中。
         if (y < groundY - VOID_DROP && ticksSinceGround >= VOID_GRACE_TICKS) {
            failRun(coord, "Void");
            return;
         }
      } else if (wasInAir) {
         if (groundTicksAfterLanding == 0) {
            considerHeadHit(currentTick - airStartTick, y, coord, HEAD_HIT_MAX_RISE);
         }
         // turnRecorded is deliberately not part of this test. It flips 15 ticks after the jump,
         // which is long before the 60-tick stall window could ever close, so keeping it would
         // disable the check outright.
         // 这里刻意不看 turnRecorded：它在跳后 15 tick 就置真，远早于 60 tick 的窗口能走完，
         // 留着等于把这条判定整个关掉。
         if (jumpCount > 0 && !platesRecorded) {
            // Walking to the plate is not a stall. Only ticks with no horizontal movement count;
            // otherwise a successful run is closed while the player is still walking the last
            // stretch of the finish island, which is exactly what the field log shows.
            // 走向压力板不算卡住。只有完全没有水平位移的 tick 才计数；否则玩家还在走终点岛最后
            // 一段就被判死 —— 现场日志就是这么误判的。
            if (Math.abs(coord - lastTickCoord) > 0.1D) {
               groundTicksAfterLanding = 0;
            } else {
               groundTicksAfterLanding++;
            }
            if (groundTicksAfterLanding > STALL_TICKS) {
               failRun(coord, "Stalled");
               return;
            }
         }
      } else {
         prevTickCoord = lastTickCoord;
         lastTickCoord = coord;
      }

      if (isBridging) {
         prevTickCoord = lastTickCoord;
         lastTickCoord = coord;
      }
   }

   /**
    * A block went down, but the client could not say which column.
    *
    * <p>Reached from the placement hook when the controller's {@code BlockPos} could not be read.
    * It is a real placement: the player's own column becomes the weaker evidence, exactly as it did
    * before the block column was available.
    *
    * <p>Never reached by a take-off. That goes through {@link #onTakeOff()} instead, which is a
    * different statement and is gated separately — conflating the two is what made the fallback
    * silently disable itself for clients whose controller does not expose the block position.
    */
   public static synchronized void onBlockPlaced() {
      onPlacement(-1, -1, false, true);
   }

   /**
    * A take-off that stands in for a placement, on a client with no block hook at all.
    *
    * <p>Only honoured when {@link #setTakeOffMayStart} said so; otherwise it is dropped and logged.
    * A jump is not a placement, and treating it as one is exactly the bug this gate closes: joining
    * a world, jumping, falling into the void, jumping again used to run the whole cycle — timer,
    * entries, FAILED — before a single block had been placed.
    *
    * <p>When it <em>is</em> honoured the run still starts with no block on record, so
    * {@link #firstPlacementSeen} stays false and {@link #onJump()} keeps refusing to record until a
    * real placement arrives.
    */
   public static synchronized void onTakeOff() {
      if (!takeOffMayStart) {
         if (startLogged < DIAG_LIMIT) {
            startLogged++;
            Log.info("take-off t=" + currentTick + " ignored (this client has a block hook; only a"
               + " placement may open a run) status=" + status + " bridging=" + isBridging);
         }
         return;
      }
      onPlacement(-1, -1, false, false);
   }

   /**
    * A placement whose block position the client could report.
    *
    * <p>Preferred over {@link #onBlockPlaced()}: the gates ask "how many distinct columns did you
    * build in", and only the block itself answers that. The player's own column does not — during a
    * bridge the player covers about two blocks in twelve ticks while four blocks go down.
    *
    * @param blockX placed block's X · 放下的方块的 X
    * @param blockZ placed block's Z · 放下的方块的 Z
    */
   public static synchronized void onBlockPlaced(int blockX, int blockZ) {
      onPlacement(blockX, blockZ, true, true);
   }

   /**
    * The one implementation behind every placement entry point.
    *
    * <p>Keeping it in one place is what stops the overloads drifting apart; the two boolean
    * parameters are deliberately separate, because "which column" and "was this even a placement"
    * are different questions and answering both with one flag broke the column-less clients.
    *
    * @param blockX  block column, or -1 when unknown
    * @param blockZ  block column, or -1 when unknown
    * @param hasBlockColumn true when the client could report where the block went
    * @param isPlacement    false for a take-off standing in for a placement
    */
   private static void onPlacement(int blockX, int blockZ, boolean hasBlockColumn,
         boolean isPlacement) {
      // A real placement is the precondition every run needs, and this is the only place it is
      // satisfied. A take-off must not satisfy it: the whole point of the gate below is that the
      // take-off client has to wait for an actual block like everyone else.
      // 真正的放置才是每一局都需要的前置条件，而只有这里能满足它。起跳不能满足——下面那道门存在
      // 的全部意义，就是让「起跳开局」的客户端也跟别人一样，等到真的放下方块才算数。
      if (isPlacement) {
         firstPlacementSeen = true;
      }
      boolean restart = !isBridging || status == RunStatus.SUCCESS || status == RunStatus.FAILED;
      if (restart) {
         // Upstream 1.5.0: a finished run blocks the next one for five ticks, and no run may start
         // while a plate/turn sample is still pending or while standing on the finish plate.
         boolean blockedPlate = plateProbe.isOnGoldPlate(lastX, lastKnownY, lastZ);
         boolean airborne = ticksSinceGround > MAX_START_AIR_TICKS;
         boolean ready = restartCooldown.ready(currentTick)
            && !pendingPlateSample && pendingJumps.isEmpty() && !blockedPlate
            && !airborne;
         if (startLogged < DIAG_LIMIT) {
            startLogged++;
            Log.info((isPlacement ? "placement t=" : "take-off t=") + currentTick
               + " status=" + status + " entries=" + entries.size()
               + (hasBlockColumn ? " block=" + blockX + ":" + blockZ : " block=player")
               + " -> " + (ready ? "START NEW RUN"
                  : "ignored (cooldown=" + restartCooldown.ready(currentTick)
                     + " pendingPlate=" + pendingPlateSample + " pendingJumps=" + pendingJumps.size()
                     + " onPlate=" + blockedPlate + " air=" + ticksSinceGround + ")"));
         }
         if (!ready) {
            return;
         }
         startRun();
         // startRun() clears the flag on purpose (see there), so a run that actually opened has to
         // set it again after the reset - the placement that opened it is its own precondition.
         // startRun() 会刻意清掉这个标志（原因见那里），所以真正开起来的局必须在复位之后再置一次。
         if (isPlacement) {
            firstPlacementSeen = true;
         }
      } else if (startLogged < DIAG_LIMIT) {
         startLogged++;
         Log.info((isPlacement ? "placement t=" : "take-off t=") + currentTick
            + " bridging=true status=" + status + " entries=" + entries.size()
            + (hasBlockColumn ? " block=" + blockX + ":" + blockZ : " block=player")
            + " -> continue run");
      }
      // Evidence for the label gates. Recorded after startRun() on purpose: that clears the ring,
      // and the placement which opens a run is itself the opening's evidence.
      // A take-off is not evidence: recording it would credit the label gates with a block that was
      // never placed, which is how a bare jump used to earn a 1.0 Flat.
      // 起跳不是证据：记下来等于给标签门控补上一块从没放过的方块，裸跳拿到 1.0 Flat 就是这么来的。
      if (isPlacement) {
         if (hasBlockColumn) {
            PlacementEvidence.record(currentTick, blockX, blockZ, lastX, lastZ);
         } else {
            PlacementEvidence.record(currentTick, lastX, lastZ);
         }
      }
   }

   /**
    * Called from the jump() hook. Records one jump entry; never starts a run.
    *
    * <p>The second condition is the "failed run must not restart on the next jump" rule. It reads as
    * redundant next to the first — {@code failRun} clears {@code isBridging} — but the two are set
    * from different threads and {@code isBridging} is not volatile, so the explicit status test is
    * what actually holds the line.
    */
   public static synchronized void onJump() {
      if (!isBridging || status == RunStatus.SUCCESS || status == RunStatus.FAILED) {
         if (jumpLogged < DIAG_LIMIT) {
            jumpLogged++;
            Log.info("jump t=" + currentTick + " ignored (bridging=" + isBridging
               + " status=" + status + ")");
         }
         return;
      }
      if (!firstPlacementSeen) {
         // A run exists but no block has gone down in it, so there is nothing to time yet. This only
         // happens on a client where a take-off opened the run; on a normal client the placement
         // hook sets the flag before any jump can arrive.
         // 这一局开着，但还没放下任何方块，没有可计时的东西。只会在「起跳开局」的客户端上出现；
         // 正常客户端里放置钩子会在任何一次跳跃到达之前就把它置真。
         if (jumpLogged < DIAG_LIMIT) {
            jumpLogged++;
            Log.info("jump t=" + currentTick + " ignored (no placement yet) status=" + status);
         }
         return;
      }

      int sampleTick = currentTick + Config.tickOffset;
      if (jumpLogged < DIAG_LIMIT) {
         jumpLogged++;
         Log.info("jump t=" + currentTick + " sinceStart=" + (currentTick - startTick)
            + " sinceLastJump=" + (currentTick - lastJumpTick) + " entries=" + entries.size()
            + " sample=" + sampleTick + " haveSample=" + hasPositionSnapshot(sampleTick));
      }
      if (hasPositionSnapshot(sampleTick)) {
         int slot = Math.floorMod(sampleTick, POSITION_HISTORY_SIZE);
         recordJump(positionHistoryX[slot], positionHistoryY[slot], positionHistoryZ[slot], currentTick);
      } else if (sampleTick > currentTick && Config.tickOffset <= 40) {
         pendingJumps.add(new PendingJump(sampleTick, currentTick));
      }
   }

   private static void startRun() {
      entries.clear();
      jumpCount = 0;
      openingTicks = 0;
      PlacementEvidence.reset();
      pendingJumps.clear();
      pendingPlateSample = false;
      pendingSuccessTick = -1;
      pendingSuccessTime = 0.0D;
      // Cleared here and set again by onPlacement's caller path: a run opened by a take-off starts
      // with no placement either, so the flag may never simply carry over from the previous run.
      // 在这里清掉，再由 onPlacement 的后续路径重新置位：起跳开出来的局同样一块都没有，
      // 所以这个标志绝不能从上一局顺延过来。
      firstPlacementSeen = false;
      startTick = currentTick;
      lastJumpTick = currentTick;
      airStartTick = currentTick;
      airStartY = lastKnownY;
      airPeakY = lastKnownY;
      groundY = lastKnownY;
      previousY = lastKnownY;
      // Treat this instant as just landed: otherwise the airborne counter left over from the
      // previous fall would block this new run as well.
      // 开跑这一刻视为刚落地：否则上一次掉落留下的空中计数会把这个新局也一起挡掉
      ticksSinceGround = 0;
      lastJumpEntryIndex = -1;
      startX = positionHistoryValid[Math.floorMod(currentTick, POSITION_HISTORY_SIZE)]
         ? positionHistoryX[Math.floorMod(currentTick, POSITION_HISTORY_SIZE)] : 0.0;
      startY = lastKnownY;
      startZ = positionHistoryValid[Math.floorMod(currentTick, POSITION_HISTORY_SIZE)]
         ? positionHistoryZ[Math.floorMod(currentTick, POSITION_HISTORY_SIZE)] : 0.0;
      lastTickCoord = currentAxis.equals("X") ? startX : startZ;
      prevTickCoord = lastTickCoord;
      turnRecorded = false;
      platesRecorded = false;
      groundTicksAfterLanding = 0;
      wasInAir = false;
      currentBridgeTime = 0.0;
      status = RunStatus.BRIDGING;
      isBridging = true;
   }

   /**
    * Locks in the first flat's real duration and retitles the entry that was already added.
    *
    * <p>The first entry is created the instant the first jump is sampled, which is before the
    * sidebar clock has printed a value in the 1.0-1.25 s band. The original re-labelled it here for
    * the same reason: the label is "1.15 Flat", not "1.05 Flat", and only the clock can say which.
    */
   private static void finalizeFlat(double duration) {
      flatDuration = duration;
      flatFinalized = true;
      // A first jump that failed placement evidence is already labelled Jump; retitling it here
      // would launder an unverified opening into a valid 1.1x Flat. Upstream guards on the label,
      // so this does too.
      // 首跳没过放置证据时已经被标成 Jump，这里再改名就等于把一次没验证的开桥洗成 1.1x Flat。
      // 上游用标签挡这一层，这里照做。
      if (!entries.isEmpty() && entries.get(0).getType() == JumpEntry.EntryType.FLAT
            && !Lang.t("entry.jump").equals(entries.get(0).getLabel())) {
         entries.get(0).setLabel(flatLabel(entries.get(0).getTicksElapsed(), flatDuration));
      }
   }

   private static double lastKnownY = 0.0;
   /**
    * Feet height of the previous tick, for the teleport test only.
    *
    * <p>A server that pulls a falling player out of the void moves them up dozens of blocks in one
    * tick. Without this signal the fall never gets a verdict at all: the rescue happens before the
    * void grace window closes, and landing on the island afterwards clears every counter.
    *
    * {@code NaN} means "no previous sample" - freshly reset, or a world just changed.
    *
    * 上一 tick 的脚高，只用于传送检测。
    *
    * <p>服务器把掉下去的人从虚空里拉回来时，一 tick 会向上跳几十格。没有这个信号，掉虚空永远
    * 等不到判定：救援发生在宽限窗口走完之前，之后落到岛上又把所有计数清零了。
    *
    * {@code NaN} 表示「还没有上一帧」——刚重置，或者刚换了世界。
    */
   private static double previousY = Double.NaN;
   /** Last sampled X / Z, so a placement arriving between ticks can still be located. */
   private static double lastX = 0.0;
   private static double lastZ = 0.0;
   /** First airborne tick of the current jump and the height it started from. */
   private static int airStartTick = 0;
   private static double airStartY = 0.0;
   private static double airPeakY = 0.0;
   /** Feet height of the last grounded sample. */
   private static double groundY = 0.0;
   /**
    * Ticks since the player last stood on something.
    *
    * <p>Drives both the void baseline ({@link #VOID_DROP} is measured from {@link #groundY}) and
    * the gate that stops a run from restarting mid-fall ({@link #MAX_START_AIR_TICKS}).
    */
   private static int ticksSinceGround = 0;
   /** Index of the entry the current jump produced, so the bonk line can sit above it. */
   private static int lastJumpEntryIndex = -1;

   private static void savePositionSnapshot(double x, double y, double z, int tick) {
      int slot = Math.floorMod(tick, POSITION_HISTORY_SIZE);
      positionHistoryTicks[slot] = tick;
      positionHistoryX[slot] = x;
      positionHistoryY[slot] = y;
      positionHistoryZ[slot] = z;
      positionHistoryValid[slot] = true;
   }

   private static boolean hasPositionSnapshot(int tick) {
      int slot = Math.floorMod(tick, POSITION_HISTORY_SIZE);
      return positionHistoryValid[slot] && positionHistoryTicks[slot] == tick;
   }

   private static double coordinateAtTick(int tick) {
      if (!hasPositionSnapshot(tick)) {
         return Double.NaN;
      }
      int slot = Math.floorMod(tick, POSITION_HISTORY_SIZE);
      return currentAxis.equals("X") ? positionHistoryX[slot] : positionHistoryZ[slot];
   }

   /**
    * Sends the success line once its two-tick delay has elapsed.
    *
    * <p>The delay itself is unchanged ({@link #SUCCESS_MESSAGE_DELAY_TICKS}), but the line is now
    * assembled here rather than when the plate was recorded: the latency has to be the one on the
    * link at the moment the line goes out, and the run's time is only known for certain by then.
    */
   private static void flushSuccessMessage() {
      if (pendingSuccessTick < 0 || currentTick < pendingSuccessTick) {
         return;
      }
      double measured = pendingSuccessTime;
      int milliBefore = pendingSuccessMilliBefore;
      int milliLast = pendingSuccessMilliLast;
      pendingSuccessTick = -1;
      pendingSuccessTime = 0.0D;
      // This is the "Success! Time … Plates" line, part of the chat output: if it is off, nothing is
      // sent. Checking here rather than only when recording also covers "the switch was turned off
      // during the 2 ticks between finishing and sending".
      // 这一行是「Success! Time … Plates」那条，属于聊天输出的一部分：关掉就不能发。
      // 判在这里而不是只判记录时，是为了让「跑完到发出之间那 2 tick 里关掉开关」也生效。
      if (!Config.chatOutput) {
         return;
      }
      // One-way latency = half the round trip (rounded to the millisecond when it does not divide
      // evenly). The bridge timer is measured client-side, but the server only counts it after one
      // one-way leg, so adding this half RTT is what lines up with the server's replay timeline.
      // 单程延迟 = 往返的一半（除不尽四舍五入到毫秒）。搭路计时是客户端量的，服务器要过一个
      // 单程才认账，补上这半个 RTT 才对得上服务器的回放时间轴。
      int ping = pingProbe.read();
      int halfMs = pingHalf(ping);
      double shown = measured + halfMs / 1000.0D;
      chatSink.send("\u00a7a" + Lang.t("chat.prefix") + " " + Lang.t("chat.success",
         String.format(Locale.US, "%.2f", shown),
         String.format(Locale.US, ".%03d", milliBefore),
         String.format(Locale.US, ".%03d", milliLast)));
      if (halfMs > 0) {
         Log.verbose(String.format(Locale.US,
            "success line: %.3fs + %dms = %.3fs (ping %dms, halved and rounded)",
            measured, halfMs, shown, ping));
      }
   }

   private static void processPendingSamples() {
      for (int i = 0; i < pendingJumps.size(); ) {
         PendingJump pending = pendingJumps.get(i);
         if (hasPositionSnapshot(pending.sampleTick)) {
            int slot = Math.floorMod(pending.sampleTick, POSITION_HISTORY_SIZE);
            recordJump(positionHistoryX[slot], positionHistoryY[slot], positionHistoryZ[slot], pending.jumpTick);
            pendingJumps.remove(i);
         } else if (currentTick - pending.jumpTick > PENDING_MAX_AGE) {
            // The tick this sample was derived from will never show up (world swap, long lag).
            // Dropping it is the only way out: while it sits here the next run cannot start.
            Log.info("dropped a deferred jump sample (t=" + pending.jumpTick
               + ", waited " + (currentTick - pending.jumpTick) + " ticks)");
            pendingJumps.remove(i);
         } else {
            i++;
         }
      }

      if (pendingPlateSample) {
         if (hasPositionSnapshot(pendingPlateTick)) {
            pendingPlateSample = false;
            recordPlateEntries(pendingPlateTick);
         } else if (currentTick - pendingPlateTick > PENDING_MAX_AGE) {
            Log.info("dropped the deferred plate sample (t=" + pendingPlateTick + ")");
            pendingPlateSample = false;
         }
      }
   }

   /**
    * A jump that is back on the ground within {@code HEAD_HIT_MAX_AIR} ticks was cut short by the
    * ceiling.
    *
    * <p>This is the map's own ground truth instead of a guess: a normal jump keeps the player
    * airborne for 12 ticks ({@code y} reaches 0.420 on the first tick, back to 0 on the twelfth),
    * while a bonk kills the rise on tick 1 and puts the player back down on tick 3. It also
    * replaces the earlier "the rise stopped" test, which a telly could fake by handing over a
    * duplicated position sample — airborne ticks are counted from real samples, so nothing short
    * of actually leaving the ground produces them.
    *
    * @param airTicks ticks spent off the ground, ending with this landing tick
    */
   private static void considerHeadHit(int airTicks, double y, double coord,
         double levelTolerance) {
      if (airTicks < 1 || airTicks > HEAD_HIT_MAX_AIR) {
         return;
      }
      if (!isBridging || platesRecorded || status != RunStatus.BRIDGING || entries.isEmpty()) {
         return;
      }
      // A bonk kills the rise but the player does leave the ground, and lands back on the same
      // level. Walking off a ledge also ends quickly, and it never rises at all.
      if (airPeakY - airStartY < 0.15D || Math.abs(y - airStartY) > levelTolerance) {
         return;
      }
      double abs = Math.abs(coord);
      JumpEntry entry = new JumpEntry(Lang.t("entry.headHitter"), coord, abs - Math.floor(abs), Double.NaN,
         airTicks, JumpEntry.EntryType.HEAD_HITTER);
      // The jump happens first, the bonk is noticed on landing, so the line belongs right after
      // the jump it describes (and therefore after the flat as well).
      int at = lastJumpEntryIndex;
      if (at >= 0 && at < entries.size()) {
         entries.add(at + 1, entry);
         lastJumpEntryIndex = at + 1;
      } else {
         entries.add(entry);
      }
      Boolean ceiling = ceilingProbe.hasCeiling(lastX, airStartY, lastZ);
      Log.info("head hitter: air=" + airTicks
         + " rise=" + String.format(Locale.US, "%.3f", airPeakY - airStartY)
         + " landing=" + String.format(Locale.US, "%.3f", y - airStartY)
         + " ceiling=" + (ceiling == null ? "unknown" : (ceiling.booleanValue() ? "yes" : "no")));
   }

   /**
    * @param reason written to agent.log only, for troubleshooting, and **not into the language
    * pack**: a log keyword cannot be searched once its language changes
    * · 只写进 agent.log 用于排障，**不进语言包**：日志关键词换了语言就搜不到了
    */
   private static void failRun(double coord, String reason) {
      if (!isBridging) {
         return;
      }
      // The grace window is spent falling, not bridging. Keeping it would add two and a half
      // seconds the player never ran, so the failed run reports up to its last jump.
      // 宽限期是在下坠，不是在搭路。留着会凭空多出两秒半，所以失败成绩只报到最后一跳。
      currentBridgeTime = Math.max(0.0D, (lastJumpTick - startTick) / 20.0D);
      Log.info("FAIL (" + reason + ") y=" + lastKnownY
         + " drop=" + String.format(Locale.US, "%.2f", groundY - lastKnownY)
         + " prevY=" + String.format(Locale.US, "%.2f", previousY)
         + " startY=" + startY
         + " groundY=" + groundY + " air=" + ticksSinceGround
         + " t=" + currentTick + " start=" + startTick
         + " sinceStart=" + (currentTick - startTick) + " sinceLastJump="
         + (currentTick - lastJumpTick) + " groundTicks=" + groundTicksAfterLanding
         + " jumpCount=" + jumpCount + " entries=" + entries.size() + " coord=" + coord);
      isBridging = false;
      status = RunStatus.FAILED;
      restartCooldown.finish(currentTick);
      // Upstream samples the tap that was supposed to happen nine ticks after the last jump, and
      // refuses to print anything when there is nothing to sample: no entries yet, that tick has
      // not arrived, or no position snapshot was kept for it. Emitting a line anyway prints a
      // coordinate that was never measured.
      // 上游取"最后一跳之后第 9 tick"本该落地的那一下，并且在三种情况下不出这一行：还没有条目、
      // 那一 tick 还没到、那一 tick 没有位置快照。硬出一行，打的就是一个根本没采样过的坐标。
      int expectedTapTick = lastJumpTick + 9;
      int sampleTick = expectedTapTick + Config.tickOffset;
      if (entries.isEmpty() || currentTick < expectedTapTick || !hasPositionSnapshot(sampleTick)) {
         return;
      }
      double sampled = coordinateAtTick(sampleTick);
      double abs = Math.abs(sampled);
      double dec = abs - Math.floor(abs);
      double delta = wrapDelta(dec - lastJumpDecimal);
      entries.add(new JumpEntry(Lang.t("entry.failTap"), sampled, dec, delta, 9, JumpEntry.EntryType.FAIL_TAP));
   }

   private static void recordPlateEntries(int tick) {
      double last = coordinateAtTick(tick);
      double before = coordinateAtTick(tick - 1);
      if (Double.isNaN(last)) {
         last = lastTickCoord;
      }
      if (Double.isNaN(before)) {
         before = prevTickCoord;
      }
      double absBefore = Math.abs(before);
      double decBefore = absBefore - Math.floor(absBefore);
      entries.add(new JumpEntry(Lang.t("entry.platesMinus1"), before, decBefore, Double.NaN, 0, JumpEntry.EntryType.PLATES_MINUS_1));
      double absLast = Math.abs(last);
      double decLast = absLast - Math.floor(absLast);
      entries.add(new JumpEntry(Lang.t("entry.plates"), last, decLast, Double.NaN, 0, JumpEntry.EntryType.PLATES));
      int milliBefore = (int)Math.round(decBefore * 1000.0D) % 1000;
      int milliLast = (int)Math.round(decLast * 1000.0D) % 1000;
      if (milliBefore < 0) {
         milliBefore += 1000;
      }
      if (milliLast < 0) {
         milliLast += 1000;
      }
      if (!Config.chatOutput) {
         pendingSuccessTick = -1;
         pendingSuccessTime = 0.0D;
         return;
      }
      // Record raw data only; the line is assembled two ticks later by flushSuccessMessage, because
      // that is the only place the current latency can be read.
      // 只记原始数据，行等两 tick 后 flushSuccessMessage 再拼——那里才读得到当下的延迟。
      pendingSuccessTime = currentBridgeTime;
      pendingSuccessMilliBefore = milliBefore;
      pendingSuccessMilliLast = milliLast;
      pendingSuccessTick = currentTick + SUCCESS_MESSAGE_DELAY_TICKS;
      Log.verbose(String.format(Locale.US, "run success in %.2fs", currentBridgeTime));
   }

   private static void recordJump(double x, double y, double z, int tick) {
      if ("X".equalsIgnoreCase(Config.preferredAxis)) {
         currentAxis = "X";
      } else if ("Z".equalsIgnoreCase(Config.preferredAxis)) {
         currentAxis = "Z";
      } else {
         double dx = Math.abs(x - startX);
         double dz = Math.abs(z - startZ);
         // Upstream 1.5.0 lowered this from 1.0 to 0.05: a three-tick opening can travel less than
         // one block, and waiting for dx > 1 mislabelled its axis and dropped its placement data.
         currentAxis = dx > dz && dx > 0.05 ? "X" : "Z";
      }

      double coord = currentAxis.equals("X") ? x : z;
      double abs = Math.abs(coord);
      double dec = abs - Math.floor(abs);

      if (entries.isEmpty()) {
         int ticks = Math.max(1, tick - startTick);
         openingTicks = ticks;
         String label = flatLabel(ticks, flatDuration);
         double startCoord = currentAxis.equals("X") ? startX : startZ;
         // An opening can no longer be a 4b Flat, so it no longer needs the four columns that label
         // used to demand; one placement is the evidence every opening label needs.
         // 开桥不再有 4b Flat 这一档，也就不再要求那个标签用的四列；开桥标签只需一次放置。
         if (!PlacementEvidence.qualifies(startTick, tick, startCoord, coord,
               currentAxis.equals("X"), 1)) {
            label = Lang.t("entry.jump");
         }
         entries.add(new JumpEntry(label, coord, dec, Double.NaN, ticks, JumpEntry.EntryType.FLAT));
         lastJumpEntryIndex = entries.size() - 1;
         entries.get(entries.size() - 1).setGainType(currentGain());
         lastJumpTick = tick;
         lastJumpCoord = coord;
         lastJumpDecimal = dec;
      } else {
         jumpCount++;
         int sinceJump = tick - lastJumpTick;
         double delta = wrapDelta(dec - lastJumpDecimal);
         int blocks = (int)Math.round(Math.abs(coord - lastJumpCoord));
         // Upstream 1.5.0/1.9.0: twelve ticks is the whole definition of a 4b Flat — the label does
         // not ask how far the player travelled, only the interval. A previous revision required
         // four blocks of travel as well, which demoted every real 4b Flat to a plain Jump: field
         // logs show such a jump covering two blocks, not four, because the player is sneaking.
         // 上游 1.5.0/1.9.0：12 tick 就是 4b Flat 的全部定义——标签只看间隔，不看走了多远。
         // 之前这里额外要求四格位移，把每一次真正的 4b Flat 都降级成了普通 Jump：
         // 现场日志里这种跳跃只走两格，因为玩家是蹲着过的。
         boolean fourBlockFlat = sinceJump == 12;
         String label;
         if (turnRecorded) {
            label = Lang.t("entry.jump") + " #" + jumpCount;
         } else if (fourBlockFlat) {
            label = Lang.t("entry.fourBlock") + " " + Lang.t("entry.flat") + " #" + jumpCount;
         } else if (sinceJump == 9) {
            label = Lang.t("entry.tapSpeed") + " #" + jumpCount;
         } else if (sinceJump == 10) {
            label = Lang.t("entry.holdSpeed") + " #" + jumpCount;
         } else {
            label = (blocks >= 4 ? Lang.t("entry.fourBlock") + " " + Lang.t("entry.jump") : Lang.t("entry.jump")) + " #" + jumpCount;
         }
         // Upstream asks for four distinct columns, because a 4b Flat puts four blocks down. The
         // field rhythm here is one block every four ticks, so twelve ticks hold three placements
         // and the fourth lands on the next jump's own tick — after this verdict is taken. The gate
         // therefore asks for the three the window can show; a window with no placement at all is
         // still rejected, which is the part that matters.
         // 上游要求四列，因为一次 4b Flat 会放下四块。这里的节奏是每 4 tick 一块，12 tick 里只有
         // 三次放置，第四块落在下一次跳自己的那一 tick——判完之后。所以门槛按窗口能给出的三次设，
         // 一列都没有的窗口仍然被判掉，而这才是关键的那部分。
         int need = fourBlockFlat ? 3 : 1;
         // Columns are the placed block's now, not the player's: the hook hands over the clicked
         // BlockPos, so "which columns did you build" is answered directly instead of by proxy.
         // 列现在是方块自己的列，不再是玩家所在列：钩子带回了被点击的 BlockPos，
         // 「你建了哪几列」直接得到答案，而不是靠代理值。
         boolean evidence = PlacementEvidence.qualifies(lastJumpTick, tick, lastJumpCoord, coord,
            currentAxis.equals("X"), need);
         // Own counter: the shared jump log stops at DIAG_LIMIT and would hide exactly the runs
         // this line exists to explain.
         // 独立计数器：共用的跳跃日志到 DIAG_LIMIT 就停了，恰好会把要解释的那几局藏掉。
         if (sinceJump == 12 && fourBlockLogged < 8) {
            fourBlockLogged++;
            Log.info("4b check: travel=" + blocks + " need=" + need
               + " window=" + lastJumpTick + ".." + tick
               + " events=" + PlacementEvidence.eventsBetween(lastJumpTick, tick)
               + " located=" + PlacementEvidence.blockEventsBetween(lastJumpTick, tick)
               + " cols=" + PlacementEvidence.columnsBetween(lastJumpTick, tick)
               + " at=" + PlacementEvidence.describeBetween(lastJumpTick, tick)
               + " qualifies=" + evidence);
         }
         if (!turnRecorded && !evidence) {
            label = Lang.t("entry.jump") + " #" + jumpCount;
         }
         // Opening-only late detection: the first follow-up jump after a 1.0/1.05 Flat.
         String late = BridgeDetection.lateLabel(jumpCount, openingTicks, sinceJump,
            lastLaunchX, lastLaunchY, lastLaunchZ, x, y, z, currentAxis.equals("X"),
            // This used to test getLabel().endsWith("Flat"), which breaks in another language
            // ("1.05 平"), so it now looks at the entry type instead: language affects display only
            // and must not affect the verdict. The label is still checked by identity against the
            // Jump text, because upstream only accepts an opening that actually earned a Flat —
            // a first jump demoted to Jump is not a Late either.
            // 原来用 getLabel().endsWith("Flat") 判断，换个语言（"1.05 平"）就失效了，
            // 改成看条目类型：语言只影响显示，不该影响判定。标签仍要与 Jump 文案比对一次，
            // 因为上游只认真正拿到 Flat 的开桥——首跳被降级成 Jump 的，也不算 Late。
            !turnRecorded && !entries.isEmpty()
               && entries.get(0).getType() == JumpEntry.EntryType.FLAT
               && !Lang.t("entry.jump").equals(entries.get(0).getLabel())
               && PlacementEvidence.qualifies(lastJumpTick + 1, tick, lastJumpCoord, coord,
                  currentAxis.equals("X"), 2));
         if (late != null) {
            label = late;
            if (jumpLogged < DIAG_LIMIT) {
               jumpLogged++;
               Log.info("opening late: " + late + " openingTicks=" + openingTicks
                  + " elapsed=" + sinceJump);
            }
         }
         entries.add(new JumpEntry(label, coord, dec, delta, sinceJump, JumpEntry.EntryType.TAP));
         lastJumpEntryIndex = entries.size() - 1;
         entries.get(entries.size() - 1).setGainType(currentGain());
         lastJumpTick = tick;
         lastJumpCoord = coord;
         lastJumpDecimal = dec;
      }

      wasInAir = false;
      lastLaunchX = x;
      lastLaunchY = y;
      lastLaunchZ = z;
      groundTicksAfterLanding = 0;
   }

   private static double wrapDelta(double delta) {
      if (delta < -0.5) {
         return delta + 1.0;
      }
      if (delta > 0.5) {
         return delta - 1.0;
      }
      return delta;
   }

   private static String flatLabel(int ticks, double duration) {
      return BridgeDetection.flatLabel(ticks, duration);
   }

   /** Keeps the void check meaningful when a run starts mid-air. */
   public static void noteY(double y) {
      lastKnownY = y;
   }

   private static final class PendingJump {
      private final int sampleTick;
      private final int jumpTick;

      private PendingJump(int sampleTick, int jumpTick) {
         this.sampleTick = sampleTick;
         this.jumpTick = jumpTick;
      }
   }
}
