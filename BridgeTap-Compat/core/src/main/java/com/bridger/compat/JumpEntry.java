package com.bridger.compat;

import java.util.Locale;

/**
 * One recorded measurement.
 *
 * <p>Text formatting follows upstream 1.5.0: colours come from {@link Config#color(int)}, the first
 * flat shows {@code +.n/a} in the delta slot (there is no previous column to compare against), and
 * the gain tag is a separate suffix driven by the strafe keys rather than by the coordinate change.
 */
public class JumpEntry {
   public enum EntryType {
      FLAT, TAP, FAIL_TAP, TURN, HEAD_HITTER, PLATES_MINUS_1, PLATES
   }

   private String label;
   private final double coordinate;
   private final double decimal;
   private final double delta;
   private final int ticksElapsed;
   private final EntryType type;
   private String gainType;

   public JumpEntry(String label, double coordinate, double decimal, double delta, int ticksElapsed, EntryType type) {
      this.label = label;
      this.coordinate = coordinate;
      this.decimal = decimal;
      this.delta = delta;
      this.ticksElapsed = ticksElapsed;
      this.type = type;
   }

   public void setLabel(String label) {
      this.label = label;
   }

   public String getLabel() {
      return this.label;
   }

   public double getCoordinate() {
      return this.coordinate;
   }

   public double getDecimal() {
      return this.decimal;
   }

   public double getDelta() {
      return this.delta;
   }

   public int getTicksElapsed() {
      return this.ticksElapsed;
   }

   public EntryType getType() {
      return this.type;
   }

   public String getGainType() {
      return this.gainType;
   }

   public void setGainType(String gainType) {
      this.gainType = gainType;
   }

   /** Coordinate part only (".437"), for the COORDS / LAST_TURN modes. */
   public String decimalText() {
      int milli = (int)Math.round(this.decimal * 1000.0) % 1000;
      if (milli < 0) {
         milli += 1000;
      }
      return String.format(Locale.US, ".%03d", milli);
   }

   public String getFormattedLine() {
      StringBuilder sb = new StringBuilder();
      String dec = Config.color(1) + decimalText();
      switch (this.type) {
         case FLAT:
            // » 1.05 Flat: .504 +.n/a (4t)
            sb.append(Config.color(0)).append("\u00bb ").append(Config.color(0)).append(this.label)
               .append(": ").append(dec).append(" ")
               .append(Config.color(2)).append("+.n/a")
               .append(" ").append(Config.color(4)).append("(").append(this.ticksElapsed).append("t)");
            break;
         case TAP:
            sb.append(Config.color(0)).append("\u00bb ").append(Config.color(0)).append(this.label)
               .append(": ").append(dec).append(" ").append(formatDelta(this.delta))
               .append(" ").append(Config.color(4)).append("(").append(this.ticksElapsed).append("t)");
            break;
         case FAIL_TAP:
            sb.append("\u00a7c\u00bb \u00a7c").append(Lang.t("entry.failTap")).append(": ").append(dec).append(" ")
               .append(formatDelta(this.delta))
               .append(" ").append(Config.color(4)).append("(").append(this.ticksElapsed).append("t)");
            break;
         case TURN:
            sb.append("\u00a7f\u00bb \u00a7d").append(Lang.t("entry.turn")).append(": ").append(dec).append(" ")
               .append(formatDelta(this.delta))
               .append(" ").append(Config.color(4)).append("(").append(this.ticksElapsed).append("t)");
            break;
         case HEAD_HITTER:
            sb.append(Config.color(0)).append("\u00bb ").append(Config.color(0))
               .append(Lang.t("entry.headHitter")).append(": ")
               .append(dec);
            break;
         case PLATES_MINUS_1:
            // Both Plates lines use the label colour (color.0) as a whole, value included — dec
            // already carries the coordinate colour (color.1), so decimalText() is coloured here
            // instead. Plates used to be hard-coded §6 gold, which clashed badly with the rest.
            // Plates 两行整体走「标签」色（color.0），连数值也是——dec 自带坐标色(color.1)，
            // 所以这里改用 decimalText() 自己上色。原来 Plates 写死 §6 金色，撞色很跳。
            sb.append(Config.color(0)).append("\u00bb ").append(Config.color(0))
               .append(Lang.t("entry.platesMinus1")).append(": ")
               .append(Config.color(0)).append(decimalText());
            break;
         case PLATES:
            sb.append(Config.color(0)).append("\u00bb ").append(Config.color(0))
               .append(Lang.t("entry.plates")).append(": ")
               .append(Config.color(0)).append(decimalText());
            break;
         default:
            break;
      }

      if (Config.showGainType && this.gainType != null) {
         // The verdict stays Gain / No Gain; only the printed word is configurable.
         boolean gain = "Gain".equals(this.gainType);
         String word = gain ? Config.gainWord()
            : "No Gain".equals(this.gainType) ? Config.noGainWord() : this.gainType;
         sb.append(" ").append(Config.color(gain ? 5 : 6))
            .append("[").append(word).append("]");
      }
      return sb.toString();
   }

   private String formatDelta(double d) {
      if (Double.isNaN(d)) {
         return Config.color(2) + Lang.t("entry.noDelta");
      }
      if (d >= 0.0) {
         int milli = (int)Math.round(d * 1000.0);
         return String.format(Locale.US, "%s+.%03d", Config.color(2), milli);
      }
      int milli = (int)Math.round(Math.abs(d) * 1000.0);
      return String.format(Locale.US, "%s-.%03d", Config.color(3), milli);
   }
}
