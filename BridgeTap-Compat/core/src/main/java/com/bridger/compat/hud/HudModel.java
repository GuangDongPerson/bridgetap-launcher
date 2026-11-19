package com.bridger.compat.hud;

import com.bridger.compat.Config;
import com.bridger.compat.JumpEntry;
import com.bridger.compat.Lang;
import com.bridger.compat.Tracker;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Turns tracker state into the exact line strings the original HUD drew. */
public final class HudModel {
   private HudModel() {
   }

   /**
    * The first line ({@code Success/Failed/Bridging x.xxs N jumps, axis: X}) uses the header colour
    * for the whole line.
    *
    * <p>These three status words used to be hard-coded green/red/yellow, which looked bad next to a
    * custom header colour; they now all take {@code color.7}, the "text colour - header" slot.
    *
    * 第一行（{@code Success/Failed/Bridging x.xxs N jumps, axis: X}）整行使用标题色。
    *
    * <p>这三个状态词原来是写死的绿/红/黄，跟自定义的标题色一撞就很难看；现在统一取
    * {@code color.7}，也就是「文本颜色 · 标题」那一项。
    */
   private static String headerLine(String statusWord, double time, int jumps, String axis) {
      String tone = Config.color(7);
      // The template lives in the language pack (hud.header); placeholder order: status word /
      // elapsed time / jump count / axis.
      // 模板在语言包里（hud.header），占位符顺序：状态词 / 用时 / 跳数 / 轴
      return Lang.t("hud.header", tone + statusWord,
         tone + String.format(Locale.US, "%.2f", time), Integer.valueOf(jumps), axis);
   }

   public static List<String> build() {
      List<String> lines = new ArrayList<String>();
      if (!Config.enabled) {
         return lines;
      }

      List<JumpEntry> entries = Tracker.getEntries();
      if (entries.isEmpty() && !Tracker.isBridging()) {
         return lines;
      }

      double time = Tracker.getCurrentBridgeTime();
      int jumps = Tracker.getJumpCount();
      String axis = Tracker.getCurrentAxis();
      Tracker.RunStatus status = Tracker.getStatus();
      String mode = Config.displayMode;

      String header;
      if (mode.equals("TIME")) {
         String color = status == Tracker.RunStatus.SUCCESS ? "\u00a7a"
            : status == Tracker.RunStatus.FAILED ? "\u00a7c" : "\u00a7e";
         header = color + Lang.t("hud.time", String.format(Locale.US, "%.2f", time));
      } else if (mode.equals("COORDS") || mode.equals("COORDS_ONLY") || mode.equals("TICKS")
            || mode.equals("LAST_TURN")) {
         header = "";
      } else if (status == Tracker.RunStatus.SUCCESS) {
         header = headerLine(Lang.t("hud.status.success"), time, jumps, axis);
      } else if (status == Tracker.RunStatus.FAILED) {
         header = headerLine(Lang.t("hud.status.failed"), time, jumps, axis);
      } else {
         header = headerLine(Lang.t("hud.status.bridging"), time, jumps, axis);
      }
      if (!header.isEmpty()) {
         lines.add(header);
      }

      for (JumpEntry entry : entries) {
         switch (mode) {
            case "ALL":
               lines.add(entry.getFormattedLine());
               break;
            case "COORDS":
               lines.add(formatCoordinateLine(entry));
               break;
            case "COORDS_ONLY":
               lines.add(formatCoordinateOnlyLine(entry));
               break;
            case "LAST_TURN":
               // Two lines at most, no header: the latest entry plus the turn once it exists.
               if (entry == Tracker.getLastEntry()) {
                  lines.add(formatCoordinateLine(entry));
               } else if (entry.getType() == JumpEntry.EntryType.TURN) {
                  lines.add(formatCoordinateLine(entry));
               }
               break;
            case "TICKS":
               if (entry.getType() != JumpEntry.EntryType.PLATES
                  && entry.getType() != JumpEntry.EntryType.PLATES_MINUS_1) {
                  lines.add(Config.color(0) + "\u00bb " + Config.color(0) + entry.getLabel() + " "
                     + Config.color(4) + "(" + entry.getTicksElapsed() + "t)");
               }
               break;
            default:
               break;
         }
      }

      return lines;
   }

   private static String formatCoordinateLine(JumpEntry entry) {
      int milli = (int)Math.round(entry.getDecimal() * 1000.0) % 1000;
      if (milli < 0) {
         milli += 1000;
      }
      return String.format(Locale.US, "%s\u00bb %s%s: %s.%03d", Config.color(0), Config.color(0),
         entry.getLabel(), Config.color(1), milli);
   }

   private static String formatCoordinateOnlyLine(JumpEntry entry) {
      int milli = (int)Math.round(entry.getDecimal() * 1000.0) % 1000;
      if (milli < 0) {
         milli += 1000;
      }
      return Config.color(1) + String.format(Locale.US, ".%03d", milli);
   }
}
