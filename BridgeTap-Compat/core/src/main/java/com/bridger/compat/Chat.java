package com.bridger.compat;

import java.util.Locale;

/**
 * {@code /bt} command parser.
 *
 * <p>The Fabric build registered these through {@code ClientPlayerEntity.sendChatMessage}; here the
 * agent cancels the same call and routes the text through this class, so the command set and its
 * replies stay identical from the player's side.
 *
 * <p>The command set is deliberately tiny: {@code toggle}, {@code reset}, {@code axis}, {@code chat}.
 * Everything else the original could do from chat (position, scale, colours, sample offsets, display
 * mode, gain wording…) lives in the desktop injector, where it is a click instead of a magic word.
 *
 * <p>Both the command name and the verb are case-insensitive: {@code /BT}, {@code /Bt} and
 * {@code /BRIDGETAP} all work. Only an exact {@code /bt} or {@code /bridgetap} token counts, so a
 * message such as {@code /btw something} is left alone.
 */
public final class Chat {
   /** Where a reply goes; the agent wires this to the player's chat, tests to a list. */
   public interface Reply {
      void line(String text);
   }

   /**
    * The brand name inside the prefix is not translated; the part outside the brackets can change.
    *
    * 前缀里的品牌名不翻译，方括号之外的部分可换。
    */
   private static String prefix() {
      return "\u00a7b" + Lang.t("chat.prefix") + " \u00a7r";
   }

   private Chat() {
   }

   /**
    * @return true when the message was a BridgeTap command and must not reach the server
    */
   public static boolean handle(String message, Reply reply) {
      if (message == null) {
         return false;
      }
      String text = message.trim();
      String[] parts = text.split("\\s+");
      String command = parts[0].toLowerCase(Locale.US);
      if (!command.equals("/bt") && !command.equals("/bridgetap")) {
         return false;
      }
      if (parts.length == 1) {
         help(reply);
         return true;
      }

      switch (parts[1].toLowerCase(Locale.US)) {
         case "toggle":
            Config.enabled = !Config.enabled;
            Config.save();
            reply.line(prefix() + Lang.t("chat.hud",
               Config.enabled ? "\u00a7a" + Lang.t("chat.enabled") : "\u00a7c" + Lang.t("chat.disabled")));
            return true;

         case "reset":
            Tracker.reset();
            reply.line(prefix() + "\u00a7a" + Lang.t("chat.reset"));
            return true;

         case "axis":
            if (parts.length < 3) {
               reply.line("\u00a7c" + Lang.t("chat.usage.axis"));
               return true;
            }
            String axis = parts[2].toUpperCase(Locale.US);
            if (!axis.equals("AUTO") && !axis.equals("Z") && !axis.equals("X")) {
               reply.line("\u00a7c" + Lang.t("chat.usage.axis"));
               return true;
            }
            Config.preferredAxis = axis;
            Config.save();
            reply.line(prefix() + "\u00a7a" + Lang.t("chat.axisSet", axis));
            return true;

         case "chat":
            // No argument means toggle: on turns it off, off turns it on. on/off is still accepted so
            // a fixed action can be scripted.
            // 不带参数就是切换：开着就关，关着就开。带 on/off 仍然认，方便把动作写死。
            if (parts.length >= 3) {
               String mode = parts[2].toLowerCase(Locale.US);
               if (mode.equals("on")) {
                  Config.chatOutput = true;
               } else if (mode.equals("off")) {
                  Config.chatOutput = false;
               } else {
                  reply.line("\u00a7c" + Lang.t("chat.usage.chat"));
                  return true;
               }
            } else {
               Config.chatOutput = !Config.chatOutput;
            }
            Config.save();
            reply.line(prefix() + "\u00a7a"
               + Lang.t(Config.chatOutput ? "chat.chatOn" : "chat.chatOff"));
            return true;

         default:
            help(reply);
            return true;
      }
   }

   /**
    * One help line: the language pack stores "/bt xxx - description", and the command and the
    * description are drawn in two different colours here.
    *
    * 一条帮助：语言包里存「/bt xxx - 说明」，这里把命令和说明分成两种颜色。
    */
   private static String helpLine(String key) {
      String text = Lang.t(key);
      int split = text.indexOf(" - ");
      if (split < 0) {
         return "\u00a7e" + text;
      }
      // Cut from the hyphen onward and replace only the space in front of it with "space + §7": the
      // vanilla grey segment is "- Enable/disable…", so the hyphen must survive (dropping it would
      // give "§7Enable…" and no longer match the old wording).
      // 从连字符开始截，只把前面那个空格换成「空格 + §7」：原版灰色段是「- Enable/disable…」，
      // 连字符不能吞（吞了会变成「§7Enable…」，与旧版文案对不上）。
      return "\u00a7e" + text.substring(0, split) + " \u00a77" + text.substring(split + 1);
   }

   private static void help(Reply reply) {
      // Header only: the trailing "(/bt or /bridgetap, case-insensitive)" that used to follow it is
      // gone.
      // 只剩标题：原先跟在后面的「(/bt 或 /bridgetap，不分大小写)」已经删掉
      reply.line(prefix() + "\u00a7e" + Lang.t("chat.help.header"));
      reply.line(helpLine("chat.help.toggle"));
      reply.line(helpLine("chat.help.reset"));
      reply.line(helpLine("chat.help.axis"));
      reply.line(helpLine("chat.help.chat"));
   }
}
