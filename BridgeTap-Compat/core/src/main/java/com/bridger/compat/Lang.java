package com.bridger.compat;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 模组文案的语言包。
 *
 * <p>两个来源，后者覆盖前者：
 * <ol>
 *   <li>jar 里自带的 {@code lang/<code>.properties}（默认 {@code en}）；</li>
 *   <li>{@code config/lang/<code>.properties}——玩家/译者可以直接编辑的那个文件。</li>
 * </ol>
 *
 * <p>键缺失时回退到 {@code en}，再缺就显示键名本身，所以漏翻译一眼就能看出来，不会静默变空白。
 * 占位符是 {@code {0}} {@code {1}}，按顺序替换——刻意不用 {@link java.util.Formatter}，
 * 因为 {@code %.2f} 会因为区域设置把小数点变成逗号，HUD 上的数字会跟着变。
 *
 * <p>加一门语言：复制 {@code config/lang/en.properties}，改名成 {@code <code>.properties}，
 * 把值翻掉；注入器的「游戏内语言」里会多一个选项（列表见 {@code i18n/index.json} 与
 * {@code Launcher} 的 {@code MOD_LANGUAGES}）。
 */
public final class Lang {
   /** 内置的兜底语言，也是没有配置时的默认值。 */
   public static final String DEFAULT_LOCALE = "en";

   private static final String RESOURCE_DIR = "lang/";
   private static final Map<String, String> DICT = new HashMap<String, String>();
   private static String locale = DEFAULT_LOCALE;

   /**
    * 资源彻底读不到时的最后一道防线。
    *
    * <p>真机上出现过 {@code config loaded (… 0 strings)}：jar 明明在 bootstrap 搜索路径里、类也加载成功，
    * 但 {@code getResourceAsStream} 返回 null，结果 HUD 与 /bt 全变成 {@code entry.flat} 这种键名。
    * 这份表保证最坏情况下也是英文，不是键名。内容必须与 {@code lang/en.properties} 逐字一致
    * （{@code tests/run.sh} 的 [18-1] 会同时校验两边）。
    */
   private static final String[][] BUILTIN_EN = {
      {"hud.status.bridging", "Bridging"},
      {"hud.status.success", "Success"},
      {"hud.status.failed", "Failed"},
      {"hud.header", "{0} {1}s {2} jumps, axis: {3}"},
      {"hud.time", "Time: {0}s"},
      {"hud.idle", "{0} \u00a78| \u00a77X \u00a7f{1}  \u00a77Y \u00a7f{2}  \u00a77Z \u00a7f{3}"},
      {"hud.waiting", "{0} \u00a78| \u00a77waiting for world"},
      {"entry.flat", "Flat"},
      {"entry.late", "Late"},
      {"entry.jump", "Jump"},
      {"entry.tapSpeed", "Tap Speed"},
      {"entry.holdSpeed", "Hold Speed"},
      {"entry.fourBlock", "4b"},
      {"entry.fiveBlock", "5b"},
      {"entry.sixBlock", "6b"},
      {"entry.turn", "Turn"},
      {"entry.failTap", "Fail Tap"},
      {"entry.headHitter", "Head hitter"},
      {"entry.plates", "Plates"},
      {"entry.platesMinus1", "Plates-1t"},
      {"entry.noDelta", "+.n/a"},
      {"chat.prefix", "[BridgeTap]"},
      {"chat.hud", "HUD: {0}"},
      {"chat.enabled", "Enabled"},
      {"chat.disabled", "Disabled"},
      {"chat.reset", "Stats reset!"},
      {"chat.axisSet", "Axis set to: {0}"},
      {"chat.chatOn", "Chat output: on"},
      {"chat.chatOff", "Chat output: off"},
      {"chat.help.header", "Available commands:"},
      {"chat.help.toggle", "/bt toggle - Enable/disable the HUD"},
      {"chat.help.reset", "/bt reset - Reset the stats"},
      {"chat.help.axis", "/bt axis <AUTO|Z|X> - Set the tracking axis"},
      {"chat.help.chat", "/bt chat - Toggle chat output (on / off)"},
      {"chat.usage.axis", "Usage: /bt axis <AUTO|Z|X>"},
      {"chat.usage.chat", "Usage: /bt chat [on|off]"},
      {"chat.success", "Success! Time: \u00a7e{0}s \u00a7a| Plates-1t: \u00a7e{1} \u00a7a| Plates: \u00a7e{2}"}
   };

   /** 与 {@code lang/zh_CN.properties} 逐字一致，理由同上；中文玩家不该因为资源读不到就看到英文。 */
   private static final String[][] BUILTIN_ZH = {
      {"hud.status.bridging", "搭路中"},
      {"hud.status.success", "成功"},
      {"hud.status.failed", "失败"},
      {"hud.header", "{0} {1}s 跳跃数 {2}，{3} 轴"},
      {"hud.time", "用时 {0}s"},
      {"hud.idle", "{0} \u00a78| \u00a77X \u00a7f{1}  \u00a77Y \u00a7f{2}  \u00a77Z \u00a7f{3}"},
      {"hud.waiting", "{0} \u00a78| \u00a77等待进入世界"},
      {"entry.flat", "Flat"},
      {"entry.late", "迟"},
      {"entry.jump", "跳跃"},
      {"entry.tapSpeed", "Tap Speed"},
      {"entry.holdSpeed", "Hold Speed"},
      {"entry.fourBlock", "4b"},
      {"entry.fiveBlock", "5b"},
      {"entry.sixBlock", "6b"},
      {"entry.turn", "转头"},
      {"entry.failTap", "失误"},
      {"entry.headHitter", "顶头"},
      {"entry.plates", "压力板触发"},
      {"entry.platesMinus1", "压力板触发-1t"},
      {"entry.noDelta", "+.n/a"},
      {"chat.prefix", "[BridgeTap]"},
      {"chat.hud", "HUD: {0}"},
      {"chat.enabled", "已开启"},
      {"chat.disabled", "已关闭"},
      {"chat.reset", "数据已清空"},
      {"chat.axisSet", "跟踪轴：{0}"},
      {"chat.chatOn", "聊天输出：开"},
      {"chat.chatOff", "聊天输出：关"},
      {"chat.help.header", "可用命令"},
      {"chat.help.toggle", "/bt toggle - 开关 HUD"},
      {"chat.help.reset", "/bt reset - 清空当前数据"},
      {"chat.help.axis", "/bt axis <AUTO|Z|X> - 设置跟踪轴"},
      {"chat.help.chat", "/bt chat - 切换聊天输出（开 / 关）"},
      {"chat.usage.axis", "用法：/bt axis <AUTO|Z|X>"},
      {"chat.usage.chat", "用法：/bt chat [on|off]"},
      {"chat.success", "成功！用时 \u00a7e{0}s \u00a7a| 压力板触发-1t: \u00a7e{1} \u00a7a| 压力板触发: \u00a7e{2}"}
   };

   private static void fillBuiltin(String code) {
      String[][] table = code != null && code.toLowerCase(java.util.Locale.ROOT).startsWith("zh")
         ? BUILTIN_ZH : BUILTIN_EN;
      for (int i = 0; i < table.length; i++) {
         DICT.put(table[i][0], table[i][1]);
      }
   }

   static {
      // 类一加载就装好默认语言：某些入口（单元测试、还没读配置就画 HUD 的情况）不会调 load()，
      // 那时 t() 也不该返回裸键名。
      readResource(DICT, DEFAULT_LOCALE);
      if (DICT.isEmpty()) {
         fillBuiltin(DEFAULT_LOCALE);
      }
   }

   private Lang() {
   }

   /**
    * 载入一门语言。
    *
    * @param code 例如 {@code en} / {@code zh_CN}；空或未知就用 {@link #DEFAULT_LOCALE}
    */
   public static synchronized void load(String code) {
      String wanted = isAuto(code) ? systemLanguage() : normalise(code);
      DICT.clear();
      int loaded = readResource(DICT, DEFAULT_LOCALE);
      loaded += readExternal(DICT, DEFAULT_LOCALE);
      if (!wanted.equals(DEFAULT_LOCALE)) {
         int target = readResource(DICT, wanted);
         target += readExternal(DICT, wanted);
         if (target == 0) {
            // 目标语言一条都没读到：用编译内置的那份盖上去，不能悄悄退回英文
            fillBuiltin(wanted);
            Log.warn("lang: " + wanted + " 的语言包读不到，已用编译内置文案兜底");
         }
      }
      locale = wanted;
      if (DICT.isEmpty()) {
         fillBuiltin(wanted);
         Log.warn("lang: 一条文案都没读到（" + wanted + "），已用编译内置文案兜底");
      } else if (loaded == 0) {
         Log.info("lang: 内置资源不可用，" + wanted + " 使用编译内置文案（" + DICT.size() + " 条）");
      }
   }

   public static String locale() {
      return locale;
   }

   /** 已经载入的键数，自检/日志用。 */
   public static synchronized int size() {
      return DICT.size();
   }

   /**
    * 取一条文案。
    *
    * @param key  语言包里的键
    * @param args 依次替换 {@code {0}}、{@code {1}}…
    * @return 永远不为 null；缺键时返回键名
    */
   public static synchronized String t(String key, Object... args) {
      String text = DICT.get(key);
      if (text == null) {
         text = key;
      }
      for (int i = 0; i < args.length; i++) {
         text = text.replace("{" + i + "}", String.valueOf(args[i]));
      }
      return text;
   }

   /** 设置里选「自动」时存的值；和空值等价。 */
   public static final String AUTO = "auto";

   /** @return true 表示交给运行环境去挑语言，而不是用户指定了某一门 */
   public static boolean isAuto(String code) {
      return code == null || code.trim().isEmpty() || AUTO.equalsIgnoreCase(code.trim());
   }

   /** 按系统区域挑一门可用语言（游戏内语言读不到时的兜底）。 */
   public static String systemLanguage() {
      return autoCode(java.util.Locale.getDefault().toString());
   }

   /**
    * 把 {@code zh-TW} / {@code en_GB} 这样的区域标签落到一门真实存在的语言上。
    *
    * <p>可用集合 = 内置的 {@code en} / {@code zh_CN} + {@code config/lang/*.properties}。
    * 顺序是精确匹配 → 主语言匹配（{@code zh_TW} → {@code zh_CN}）→ 内置默认。
    */
   public static String autoCode(String tag) {
      if (tag == null || tag.trim().isEmpty()) {
         return DEFAULT_LOCALE;
      }
      String norm = tag.trim().replace('-', '_');
      java.util.List<String> codes = knownCodes();
      for (int i = 0; i < codes.size(); i++) {
         if (codes.get(i).equalsIgnoreCase(norm)) {
            return codes.get(i);
         }
      }
      String lang = norm.toLowerCase(java.util.Locale.ROOT);
      int cut = lang.indexOf('_');
      if (cut > 0) {
         lang = lang.substring(0, cut);
      }
      for (int i = 0; i < codes.size(); i++) {
         String lower = codes.get(i).toLowerCase(java.util.Locale.ROOT);
         if (lower.equals(lang) || lower.startsWith(lang + "_")) {
            return codes.get(i);
         }
      }
      return DEFAULT_LOCALE;
   }

   private static java.util.List<String> knownCodes() {
      java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<String>();
      set.add(DEFAULT_LOCALE);
      set.add("zh_CN");
      try {
         java.io.File dir = new java.io.File(Config.path()).getParentFile();
         java.io.File[] files = new java.io.File(dir, "lang").listFiles();
         if (files != null) {
            for (int i = 0; i < files.length; i++) {
               String name = files[i].getName();
               if (name.endsWith(".properties")) {
                  set.add(name.substring(0, name.length() - ".properties".length()));
               }
            }
         }
      } catch (Throwable ignored) {
         // 配置还没定位时只用内置集合
      }
      return new java.util.ArrayList<String>(set);
   }

   /** 把 {@code code} 规整成文件名能用的形式：{@code zh-CN} 与 {@code zh_CN} 都认。 */
   public static String normalise(String code) {
      if (code == null) {
         return DEFAULT_LOCALE;
      }
      String text = code.trim().replace('-', '_');
      if (text.isEmpty()) {
         return DEFAULT_LOCALE;
      }
      // 大小写不敏感地匹配内置的那个（zh_CN / ZH_cn 都当同一门）
      if (text.equalsIgnoreCase(DEFAULT_LOCALE)) {
         return DEFAULT_LOCALE;
      }
      return text;
   }

   /**
    * jar 里的 {@code lang/<code>.properties}。
    *
    * <p>agent 挂在 bootstrap classloader 上，所以用自己这个类的 ClassLoader 取资源，
    * 而不是线程上下文那个（那一个是游戏自己的）。
    */
   private static int readResource(Map<String, String> target, String code) {
      String path = RESOURCE_DIR + code + ".properties";
      InputStream stream = Lang.class.getResourceAsStream("/" + path);
      if (stream == null) {
         // 真机上出过这一条：jar 在 bootstrap 搜索路径里、类能加载，但这个调用返回 null，
         // 于是字典是空的、HUD 全是键名。必须打 warn，不然只能靠肉眼看出 0 strings。
         Log.warn("lang: 取不到内置资源 " + path);
         return 0;
      }
      try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
         Properties props = new Properties();
         props.load(reader);
         return copy(props, target);
      } catch (Throwable t) {
         Log.verbose("lang: 读不到内置 " + path + "：" + t);
         return 0;
      }
   }

   /**
    * {@code config/lang/<code>.properties}——与 {@code config/bridgetap.properties} 同目录。
    *
    * <p>用配置文件的绝对路径推目录，而不是相对 CWD：游戏的工作目录不一定是 {@code .minecraft}。
    */
   private static int readExternal(Map<String, String> target, String code) {
      String configPath;
      try {
         configPath = Config.path();
      } catch (Throwable t) {
         return 0;
      }
      if (configPath == null) {
         return 0;
      }
      File dir = new File(configPath).getParentFile();
      if (dir == null) {
         return 0;
      }
      File file = new File(new File(dir, "lang"), code + ".properties");
      if (!file.isFile()) {
         return 0;
      }
      try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
         Properties props = new Properties();
         props.load(reader);
         return copy(props, target);
      } catch (Throwable t) {
         Log.verbose("lang: 读不到 " + file.getName() + "：" + t);
         return 0;
      }
   }

   private static int copy(Properties props, Map<String, String> target) {
      int count = 0;
      for (String name : props.stringPropertyNames()) {
         String value = props.getProperty(name);
         if (value != null && !value.trim().isEmpty()) {
            target.put(name, value.trim());
            count++;
         }
      }
      return count;
   }

   /** 已载入的键快照，`/bt lang` 之类想列出来时用得上。 */
   public static synchronized Map<String, String> snapshot() {
      return new LinkedHashMap<String, String>(DICT);
   }
}
