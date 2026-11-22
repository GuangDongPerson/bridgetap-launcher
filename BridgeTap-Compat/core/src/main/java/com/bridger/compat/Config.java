package com.bridger.compat;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.Locale;
import java.util.Properties;

/** Same on-disk format as the original Fabric mod, so existing configs keep working. */
public final class Config {
   private static final File CONFIG_FILE = new File("config/bridgetap.properties");
   /** mtime+length fingerprint of the file as we last saw it; keeps our own writes from looping. */
   private static volatile long lastStamp;
   private static volatile boolean watching;
   /**
    * Idle poll interval, and the fast one used while the launcher's HUD stick is being dragged.
    *
    * <p>The fast cadence is what makes the panel follow the pointer: the launcher writes every ~50 ms
    * while the stick moves, and reading every 500 ms turned that into four visible steps a second.
    *
    * 空闲轮询间隔，以及启动器摇杆拖动期间使用的快速间隔。
    *
    * <p>快节奏才让面板跟手：拖动时启动器每约 50ms 写一次，而每 500ms 读一次会变成一秒四段的台阶。
    */
   private static final long POLL_IDLE_MS = 500L;
   private static final long POLL_FAST_MS = 50L;
   /** A lease further out than this is treated as a bug and cut back. · 跳得太远的租约当 bug 处理，压回来 */
   private static final long MAX_STICK_LEASE_MS = 60_000L;
   /** Key the launcher writes to say "still dragging, poll fast until this epoch ms". */
   private static final String STICK_LEASE_KEY = "hudStickUntil";
   /** Epoch ms until which the watcher polls fast; 0 = idle. Not persisted by {@link #save()}. */
   private static volatile long stickUntil;

   public static boolean enabled = true;
   /**
    * HUD anchor, in percent of the <b>free travel</b> on the GUI-scaled canvas — not in pixels.
    *
    * <p>`0` puts the panel flush against the left/top edge, `100` flush against the right/bottom,
    * `50` dead centre; the number is measured across the room left over after the panel's own width
    * or height is taken out. That way every value between 0 and 100 is reachable, the panel never
    * leaves the canvas, and moving the number by one step moves the panel by one step — no jump at
    * the midpoint. The same number is the same relative spot at 640×360 and at 1280×720, which
    * pixels could not do: the same corner of the screen needs different pixel pairs per resolution,
    * and a windowed client changes size without telling anyone.
    *
    * <p>Defaults land close to the old 10 px pair measured on a 640×360 canvas (about 6 px of travel
    * instead of 10 px of canvas, a few pixels once drawn), so a fresh install looks like it used to.
    *
    * HUD 锚点，单位是 GUI 缩放后画布上**可移动余量**的百分比，不是像素。
    *
    * <p>`0` 表示面板贴住左/上边，`100` 贴住右/下边，`50` 正中；这个数字量的是扣掉面板自身宽/高之后
    * 剩下的行程。这样 0–100 之间每个值都到得了，面板永远不出画布，数值走一步面板就走一步——中点不再跳。
    * 同一个数字在 640×360 与 1280×720 下都是同一个相对位置，这是像素做不到的：同样的屏幕角落，
    * 不同分辨率需要不同的像素对，而窗口化客户端还会在不通知任何人的情况下改尺寸。
    *
    * <p>默认值大致复现旧的 10 px（按 640×360 画布量出来的），差别只有几个像素，所以全新安装看起来和以前一样。
    */
   public static double hudXPercent = 1.6D;
   public static double hudYPercent = 2.8D;
   /**
    * The canvas the legacy pixel pair was tuned on: 1920×1080 at GUI scale 3.
    *
    * <p>Only used once, to migrate a config written by this build's predecessors or by the original
    * Fabric mod. Converting at that reference keeps the panel where the player put it instead of
    * snapping it to a corner.
    *
    * 旧像素对当初按哪块画布调的：1920×1080、GUI 缩放 3。
    *
    * <p>只在迁移时用一次——迁移这份构建的前身、或原版 Fabric 模组写下的配置。按这个基准换算能让面板
    * 停在玩家当初放的位置，而不是一下弹到角落。
    */
   private static final double LEGACY_CANVAS_WIDTH = 640.0D;
   private static final double LEGACY_CANVAS_HEIGHT = 360.0D;
   public static double hudScale = 1.0;
   public static String preferredAxis = "AUTO";
   public static boolean backgroundEnabled = true;
   public static boolean textShadow = true;
   /**
    * Draws a small "ready" line while the tracker has no bridge data.
    *
    * <p>Kept on by default: with it off, a correctly injected overlay looks exactly like a broken
    * one until the player happens to start a bridge run.
    */
   public static boolean showReadyNotice = true;
   /**
    * Hide the panel while the player-list key is held.
    *
    * <p>The tab list is drawn by the client's own HUD pass, before the only injection site this
    * build can use, so the overlay cannot draw underneath it — it either covers the list or steps
    * aside. Stepping aside is the default (that is how the port behaved before this became a
    * setting), and the switch exists for players who would rather keep the panel on screen while
    * checking the list.
    */
   public static boolean yieldToTabList = true;
   public static String displayMode = "ALL";
   /**
    * Text colour per line part, same ten slots and defaults as upstream's {@code COLOR_NAMES}.
    *
    * <p>Values are legacy {@code §} colour codes (0-9, a-f). The HUD prefixes each part with
    * {@link #color(int)}; the desktop settings page exposes them as colour pickers.
    */
   // Slot order (0-9): Labels / Coordinates / Positive delta / Negative delta / Ticks /
   // Gain / No Gain / Header / Background / Border. The user-visible names exist in exactly one
   // place, slot.<n> in the injector language pack; there is no second English constant list here,
   // so there is no way to change one side and forget the other.
   // 槽序（0-9）：Labels / Coordinates / Positive delta / Negative delta / Ticks /
   // Gain / No Gain / Header / Background / Border。用户可见的名字只有一份，在注入器语言包的
   // slot.<n>；这里不再留一份英文常量，免得两边改一处、漏一处。
   public static final String[] colors = {"b", "e", "a", "c", "7", "a", "e", "b", "0", "7"};

   public static String color(int part) {
      return "\u00a7" + colors[part];
   }

   /** RGB for the parts the GL panel paints with (8 = background, 9 = border). */
   public static int colorRgb(int part) {
      return PALETTE[Integer.parseInt(colors[part], 16)];
   }

   /** The word drawn for a successful gain, {@code Gain} when the box is empty. */
   public static String gainWord() {
      return normaliseWord(gainText, "Gain");
   }

   /** The word drawn for {@code No Gain}, likewise falling back to the default. */
   public static String noGainWord() {
      return normaliseWord(noGainText, "No Gain");
   }

   /**
    * Trims a custom word and keeps it printable.
    *
    * <p>Square brackets and line breaks are dropped because the tag is drawn as {@code [word]} and
    * a stray bracket would render as {@code [[Gain]]}; the length cap keeps a long word from
    * pushing the coordinates off the panel.
    */
   private static String normaliseWord(String raw, String fallback) {
      if (raw == null) {
         return fallback;
      }
      String text = raw.replace("[", "").replace("]", "").replace("\n", " ").replace("\r", " ").trim();
      if (text.isEmpty()) {
         return fallback;
      }
      return text.length() > MAX_GAIN_WORD ? text.substring(0, MAX_GAIN_WORD) : text;
   }

   /**
    * Wording of the two gain tags; empty means "use the default".
    *
    * <p>Upstream ships a joke switch that swaps the text for {@code No Gay}. That is now just a
    * value: the player types whatever they want, and an empty box falls back to {@code Gain} /
    * {@code No Gain}. The verdict itself is untouched - {@link JumpSignals} still answers
    * {@code Gain} / {@code No Gain} and this only renames what is drawn.
    */
   public static String gainText = "";
   public static String noGainText = "";
   /**
    * Language of the mod's text, mapped to {@code lang/<code>.properties}.
    *
    * <p>{@code en} by default (that is what the port has always displayed); switching language only
    * changes the wording, never any of the rules. The injector's "in-game language" writes this same
    * key; external language packs live in {@code config/lang/<code>.properties}.
    *
    * 模组文案的语言，对应 {@code lang/<code>.properties}。
    *
    * <p>默认 {@code en}（移植以来的显示就是这个），换语言只改文案，不动任何判定。
    * 注入器的「游戏内语言」写的就是这个键；外部语言包放 {@code config/lang/<code>.properties}。
    */
   /**
    * {@code auto} = follow the client/system; anything else is a concrete language pack code.
    *
    * {@code auto} = 跟客户端/系统走；其余是具体的语言包 code。
    */
   public static String language = Lang.AUTO;
   /**
    * Whether the {@code [Gain]}/{@code [No Gain]} tag is appended at all.
    *
    * <p>Upstream defaults this to false; here it defaults to true so that upgrading does not make
    * the gain readout silently disappear from a HUD that always showed it.
    */
   public static boolean showGainType = true;

   /** Longest custom gain word kept; anything longer would push the coordinate off the panel. */
   public static final int MAX_GAIN_WORD = 24;
   /** 16-colour palette, index = {@code §} code. */
   private static final int[] PALETTE = {
      0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
      0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
   };
   /** Prints the coordinate entries to chat when an attempt ends. */
   public static boolean chatOutput = false;
   /**
    * Which tick the coordinate decimal is read from, relative to the take-off tick.
    *
    * <p>{@code 0} - the position at the start of the tick the player jumped in, i.e. exactly where
    * the bridge was built from. {@code -1} reads one tick earlier, before the take-off.
    */
   public static int tickOffset = 0;
   public static int plateTickOffset = 0;
   public static int turnTicks = 15;

   private Config() {
   }

   /**
    * One HUD anchor, migrating the legacy pixel pair on first sight.
    *
    * <p>A config written by an older build — or by the original Fabric mod, whose file format this
    * one matches — carries `hudX=10`. Read as a percentage that would be 10% of the screen (64 px on
    * a 640-wide canvas), so the very first read converts it against the canvas it was tuned on. From
    * then on only `hudXPercent` exists and the old key disappears on the next save.
    *
    * 一个 HUD 锚点，第一次读到时顺便迁移旧的像素对。
    *
    * <p>旧构建——或原版 Fabric 模组（本文件格式与它一致）——写下的配置带 `hudX=10`。把它当百分比读
    * 就成了屏宽的 10%（640 宽画布上 64 px），所以第一次读取时按当初那块画布换算。此后只有
    * `hudXPercent`，旧键在下一次保存时消失。
    *
    * @param key          new property, {@code hudXPercent}
    * @param legacyKey    old pixel property, {@code hudX}
    * @param legacyCanvas the canvas width/height those pixels were measured on
    * @param fallback     value for a file that has neither
    */
   private static double anchorPercent(Properties props, String key, String legacyKey,
         double legacyCanvas, double fallback) {
      String raw = props.getProperty(key);
      if (raw != null) {
         return clampPercent(parseDouble(raw, fallback));
      }
      String legacy = props.getProperty(legacyKey);
      if (legacy != null) {
         return clampPercent(parseDouble(legacy, 0.0D) / legacyCanvas * 100.0D);
      }
      return fallback;
   }

   /** Never throws: a malformed property must not stop the config from loading. */
   private static double parseDouble(String raw, double fallback) {
      try {
         return Double.parseDouble(raw.trim());
      } catch (Throwable t) {
         return fallback;
      }
   }

   /** Keeps an anchor on the canvas; a percentage outside 0–100 has no meaning. */
   private static double clampPercent(double value) {
      if (Double.isNaN(value) || Double.isInfinite(value)) {
         return 0.0D;
      }
      return Math.max(0.0D, Math.min(100.0D, value));
   }

   public static synchronized void load() {
      if (!CONFIG_FILE.exists()) {
         save();
         return;
      }
      try (FileReader reader = new FileReader(CONFIG_FILE)) {
         Properties props = new Properties();
         props.load(reader);
         enabled = Boolean.parseBoolean(props.getProperty("enabled", "true"));
         hudXPercent = anchorPercent(props, "hudXPercent", "hudX", LEGACY_CANVAS_WIDTH, 1.6D);
         hudYPercent = anchorPercent(props, "hudYPercent", "hudY", LEGACY_CANVAS_HEIGHT, 2.8D);
         hudScale = Double.parseDouble(props.getProperty("hudScale", "1.0"));
         if (hudScale < 0.5 || hudScale > 2.0) {
            hudScale = 1.0;
         }
         preferredAxis = props.getProperty("preferredAxis", "AUTO");
         backgroundEnabled = Boolean.parseBoolean(props.getProperty("backgroundEnabled", "true"));
         textShadow = Boolean.parseBoolean(props.getProperty("textShadow", "true"));
         showReadyNotice = Boolean.parseBoolean(props.getProperty("showReadyNotice", "true"));
         yieldToTabList = Boolean.parseBoolean(props.getProperty("yieldToTabList", "true"));
         displayMode = props.getProperty("displayMode", "ALL").toUpperCase(Locale.US);
         if (!displayMode.equals("ALL") && !displayMode.equals("COORDS")
            && !displayMode.equals("COORDS_ONLY") && !displayMode.equals("TICKS")
            && !displayMode.equals("TIME") && !displayMode.equals("LAST_TURN")) {
            displayMode = "ALL";
         }
         language = Lang.normalise(props.getProperty("language", Lang.AUTO));
         gainText = props.getProperty("gainText", "");
         // Older builds spelled this as a switch (useNoGayLabel) or, before that, as a gainLabel
         // value. Either one only ever meant "draw No Gay", so it becomes the custom word once.
         String storedNoGain = props.getProperty("noGainText", null);
         if (storedNoGain == null) {
            boolean noGay = "No Gay".equalsIgnoreCase(props.getProperty("gainLabel", ""))
               || Boolean.parseBoolean(props.getProperty("useNoGayLabel", "false"));
            noGainText = noGay ? "No Gay" : "";
         } else {
            noGainText = storedNoGain;
         }
         showGainType = Boolean.parseBoolean(props.getProperty("showGainType", "true"));
         for (int i = 0; i < colors.length; i++) {
            String value = props.getProperty("color." + i, colors[i]).toLowerCase(Locale.US);
            if (value.matches("[0-9a-f]")) {
               colors[i] = value;
            }
         }
         chatOutput = Boolean.parseBoolean(props.getProperty("chatOutput", "false"));
         // Files written before this change carry tickOffset=-1, which sampled one tick *before*
         // take-off. The read now happens on the take-off tick itself; an old file has to be
         // realigned rather than silently keeping the previous timing.
         boolean legacy = props.getProperty("configRevision") == null;
         tickOffset = legacy ? 0 : parseOffset(props.getProperty("tickOffset", "0"), 0);
         plateTickOffset = parseOffset(props.getProperty("plateTickOffset", String.valueOf(tickOffset)), tickOffset);
         turnTicks = Integer.parseInt(props.getProperty("turnTicks", "15"));
         if (turnTicks != 12 && turnTicks != 15) {
            turnTicks = 15;
         }
         // The launcher's fast-poll lease: only meaningful while it keeps being refreshed.
         // 启动器给的快轮询租约：只有不断被刷新时才有意义。
         stickUntil = parseStickUntil(props.getProperty(STICK_LEASE_KEY));
         if (legacy) {
            Log.info("config migrated: tickOffset realigned to 0 (coordinate read moved to the take-off tick)");
            save();
         }
      } catch (Exception e) {
         Log.warn("config load failed: " + e);
      } finally {
         lastStamp = stamp();
      }
   }

   public static synchronized void save() {
      try {
         File parent = CONFIG_FILE.getParentFile();
         if (parent != null && !parent.exists()) {
            parent.mkdirs();
         }
         try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            Properties props = new Properties();
            props.setProperty("enabled", String.valueOf(enabled));
            props.setProperty("hudXPercent", String.valueOf(hudXPercent));
            props.setProperty("hudYPercent", String.valueOf(hudYPercent));
            props.setProperty("hudScale", String.valueOf(hudScale));
            props.setProperty("preferredAxis", preferredAxis);
            props.setProperty("backgroundEnabled", String.valueOf(backgroundEnabled));
            props.setProperty("textShadow", String.valueOf(textShadow));
            props.setProperty("showReadyNotice", String.valueOf(showReadyNotice));
            props.setProperty("yieldToTabList", String.valueOf(yieldToTabList));
            props.setProperty("displayMode", displayMode);
            props.setProperty("showGainType", String.valueOf(showGainType));
            props.setProperty("language", language);
            props.setProperty("gainText", gainText);
            props.setProperty("noGainText", noGainText);
            for (int i = 0; i < colors.length; i++) {
               props.setProperty("color." + i, colors[i]);
            }
            props.setProperty("chatOutput", String.valueOf(chatOutput));
            props.setProperty("tickOffset", String.valueOf(tickOffset));
            props.setProperty("configRevision", "2");
            props.setProperty("plateTickOffset", String.valueOf(plateTickOffset));
            props.setProperty("turnTicks", String.valueOf(turnTicks));
            props.store(writer, "BridgeTap Configuration");
         }
      } catch (Exception e) {
         Log.warn("config save failed: " + e);
      } finally {
         // Refresh the fingerprint after our own write too, otherwise the watcher thread treats this
         // very write as an "external change" and loads it again.
         // 自己写完也要刷新指纹，否则监听线程会把自己这次写入当成"外部改动"再 load 一遍
         lastStamp = stamp();
      }
   }

   /** Absolute path. The agent logs it so the injector knows which file to edit. */
   public static String path() {
      try {
         return CONFIG_FILE.getAbsoluteFile().getCanonicalPath();
      } catch (Exception e) {
         return CONFIG_FILE.getAbsolutePath();
      }
   }

   private static long stamp() {
      return CONFIG_FILE.exists() ? CONFIG_FILE.lastModified() * 31L + CONFIG_FILE.length() : 0L;
   }

   /**
    * Watches the config file for changes made by another process (the desktop injector) and reloads.
    *
    * <p>Polling instead of {@code WatchService}: the injector replaces the whole file, 500 ms is
    * indistinguishable from instant to a human, and file-system events behave differently across
    * the two clients' JVMs and Wine-less Windows setups we support. Called once per process.
    *
    * <p>While the player drags the HUD stick in the launcher the cadence drops to 50 ms: those
    * writes land every ~50 ms, and at 500 ms the panel visibly stepped across the screen instead of
    * following the pointer. The launcher writes {@code hudStickUntil=<epoch ms>} into the same file
    * for exactly as long as the gesture lasts, so the switch needs no side channel; when the lease
    * runs out (released, panel closed, or the launcher died mid-drag) the loop drops back to 500 ms
    * on its next tick without waiting for another file change.
    *
    * @param onReload run after a successful reload (the agent uses it to push new geometry to the HUD)
    */
   public static void startWatcher(Runnable onReload) {
      if (watching) {
         return;
      }
      watching = true;
      lastStamp = stamp();
      Thread thread = new Thread(() -> {
         long lastLogged = 0L;
         while (true) {
            long now = System.currentTimeMillis();
            boolean fast = now < stickUntil;
            try {
               Thread.sleep(fast ? POLL_FAST_MS : POLL_IDLE_MS);
            } catch (InterruptedException e) {
               return;
            }
            long current = stamp();
            if (current == 0L || current == lastStamp) {
               continue;
            }
            load();
            // At 20 reloads a second this line alone would fill the log; one line a second keeps the
            // trail readable and still shows that a drag happened.
            // 一秒 20 次重载时，这一行本身就能把日志刷满；限到一秒一条，既看得懂又留得下"拖过"的痕迹。
            long after = System.currentTimeMillis();
            if (after - lastLogged >= 1000L) {
               lastLogged = after;
               Log.info("config reloaded from disk" + (fast ? " (stick: " + POLL_FAST_MS + " ms)" : "")
                  + ": " + path());
            }
            if (onReload != null) {
               try {
                  onReload.run();
               } catch (Throwable t) {
                  Log.warn("config reload hook failed: " + t);
               }
            }
         }
      }, "BridgeTap-config-watch");
      thread.setDaemon(true);
      thread.start();
      Log.info("config watch: " + path());
   }

   /**
    * True while the launcher's HUD stick is being dragged (its lease has not run out).
    *
    * 启动器摇杆正在拖动期间为 true（租约未到期）。
    */
   public static boolean stickActive() {
      return System.currentTimeMillis() < stickUntil;
   }

   /**
    * Reads the launcher's fast-poll lease.
    *
    * <p>Anything absurd is cut down: a far-future value (clock skew, a hand-edited file) would keep
    * the watcher at 20 Hz for the rest of the session, so the lease is capped at a minute from now.
    *
    * 读启动器给的快轮询租约。
    *
    * <p>离谱值一律压住：一个远在未来的值（时钟偏差、手改过的文件）会让监听线程整个会话都跑在 20Hz，
    * 所以租约最多只认到当前时间之后一分钟。
    */
   private static long parseStickUntil(String raw) {
      if (raw == null) {
         return 0L;
      }
      try {
         long value = Long.parseLong(raw.trim());
         if (value <= 0L) {
            return 0L;
         }
         return Math.min(value, System.currentTimeMillis() + MAX_STICK_LEASE_MS);
      } catch (NumberFormatException e) {
         return 0L;
      }
   }

   private static int parseOffset(String raw, int fallback) {
      try {
         int v = Integer.parseInt(raw);
         return v >= -40 && v <= 40 ? v : fallback;
      } catch (NumberFormatException e) {
         return fallback;
      }
   }
}
