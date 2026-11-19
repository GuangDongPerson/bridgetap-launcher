package com.bridger.compat;

import java.util.Locale;

/**
 * Timing and launch-geometry rules, ported verbatim from upstream {@code BridgeDetection} (1.5.0).
 *
 * <p>Kept as its own class here for the same reason it exists upstream: the desktop injector and the
 * Fabric build must agree on every label, and one file makes the next upstream sync a straight copy
 * instead of an archaeology exercise inside {@link Tracker}.
 */
public final class BridgeDetection {
   private BridgeDetection() {
   }

   /** Flat label from the opening's tick count, falling back to the map clock. */
   public static String flatLabel(int ticks, double duration) {
      // Only the word "Flat" goes into the language pack; the number/block count in front is fixed
      // terminology, and translating it makes it harder to read.
      // 只有「平」这个词进语言包，前面的数字/格数是固定术语，翻译了反而看不懂
      String flat = Lang.t("entry.flat");
      if (ticks >= 11 && ticks <= 12) {
         return Lang.t("entry.fourBlock") + " " + flat;
      }
      // Upstream 1.5.0: a three-tick opening is its own technique, not a rounding of 1.05.
      if (ticks == 3) {
         return "1.0 " + flat;
      }
      if (ticks == 4) {
         return "1.05 " + flat;
      }
      if (ticks == 5) {
         return "1.1 " + flat;
      }
      if (ticks == 6) {
         return (duration >= 1.175 ? "1.2 " : "1.15 ") + flat;
      }
      if (duration <= 1.075) {
         return "1.05 " + flat;
      }
      if (duration <= 1.125) {
         return "1.1 " + flat;
      }
      if (duration <= 1.175) {
         return "1.15 " + flat;
      }
      if (duration <= 1.225) {
         return "1.2 " + flat;
      }
      return String.format(Locale.US, "%.2f ", duration) + flat;
   }

   /**
    * Opening-only "Late" labels: after a 1.0 Flat the first follow-up jump may be a 5b Late, after a
    * 1.05 Flat a 6b Late.
    *
    * <p>Conditions copied from upstream: it is the first follow-up jump, exactly 9 ticks later, it
    * rises one block, it advances three block columns along the bridge axis without drifting
    * sideways, and placements were observed. Ordinary two-column tap speeds and any later jump do
    * not qualify.
    *
    * @return the label, or {@code null} when this jump is not an opening-late
    */
   public static String lateLabel(int jumpIndex, int openingTicks, int elapsedTicks,
         double fromX, double fromY, double fromZ, double toX, double toY, double toZ,
         boolean xAxis, boolean placements) {
      if (jumpIndex != 1 || elapsedTicks != 9 || !placements) {
         return null;
      }
      if (openingTicks != 3 && openingTicks != 4) {
         return null;
      }
      if (Math.abs((toY - fromY) - 1.0) > 0.12) {
         return null;
      }
      double from = xAxis ? fromX : fromZ;
      double to = xAxis ? toX : toZ;
      double lateral = xAxis ? toZ - fromZ : toX - fromX;
      // Three axial block columns and one block up distinguish a late opening from a two-column
      // tap speed. Floor is deliberate: negative axes must not round towards zero.
      if (Math.abs(Math.floor(to) - Math.floor(from)) != 3 || Math.abs(lateral) > 1.0) {
         return null;
      }
      String late = Lang.t("entry.late");
      return (openingTicks == 3 ? Lang.t("entry.fiveBlock") : Lang.t("entry.sixBlock")) + " " + late;
   }
}
