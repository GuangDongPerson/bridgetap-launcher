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

   public static boolean enabled = true;
   public static int hudX = 10;
   public static int hudY = 10;
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

   public static synchronized void load() {
      if (!CONFIG_FILE.exists()) {
         save();
         return;
      }
      try (FileReader reader = new FileReader(CONFIG_FILE)) {
         Properties props = new Properties();
         props.load(reader);
         enabled = Boolean.parseBoolean(props.getProperty("enabled", "true"));
         hudX = Integer.parseInt(props.getProperty("hudX", "10"));
         hudY = Integer.parseInt(props.getProperty("hudY", "10"));
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
            props.setProperty("hudX", String.valueOf(hudX));
            props.setProperty("hudY", String.valueOf(hudY));
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
    * @param onReload run after a successful reload (the agent uses it to push new geometry to the HUD)
    */
   public static void startWatcher(Runnable onReload) {
      if (watching) {
         return;
      }
      watching = true;
      lastStamp = stamp();
      Thread thread = new Thread(() -> {
         while (true) {
            try {
               Thread.sleep(500L);
            } catch (InterruptedException e) {
               return;
            }
            long now = stamp();
            if (now == 0L || now == lastStamp) {
               continue;
            }
            load();
            Log.info("config reloaded from disk: " + path());
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

   private static int parseOffset(String raw, int fallback) {
      try {
         int v = Integer.parseInt(raw);
         return v >= -40 && v <= 40 ? v : fallback;
      } catch (NumberFormatException e) {
         return fallback;
      }
   }
}
