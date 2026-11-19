package com.bridger.compat.hud;

import java.util.ArrayList;
import java.util.List;

/** Parses Minecraft section-sign colour codes into drawable segments. */
public final class TextStyle {
   public static final class Segment {
      public final String text;
      public final int rgb;

      Segment(String text, int rgb) {
         this.text = text;
         this.rgb = rgb;
      }
   }

   private static final int[] COLORS = {
      0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
      0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
   };

   private TextStyle() {
   }

   public static List<Segment> parse(String raw) {
      List<Segment> out = new ArrayList<Segment>();
      if (raw == null || raw.isEmpty()) {
         return out;
      }

      int rgb = 0xFFFFFF;
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < raw.length(); i++) {
         char c = raw.charAt(i);
         if (c == '\u00a7' && i + 1 < raw.length()) {
            char code = Character.toLowerCase(raw.charAt(i + 1));
            if (code >= '0' && code <= '9') {
               if (sb.length() > 0) {
                  out.add(new Segment(sb.toString(), rgb));
                  sb.setLength(0);
               }
               rgb = COLORS[code - '0'];
            } else if (code >= 'a' && code <= 'f') {
               if (sb.length() > 0) {
                  out.add(new Segment(sb.toString(), rgb));
                  sb.setLength(0);
               }
               rgb = COLORS[10 + (code - 'a')];
            } else if (code == 'r') {
               if (sb.length() > 0) {
                  out.add(new Segment(sb.toString(), rgb));
                  sb.setLength(0);
               }
               rgb = 0xFFFFFF;
            }
            i++;
         } else {
            sb.append(c);
         }
      }
      if (sb.length() > 0) {
         out.add(new Segment(sb.toString(), rgb));
      }
      return out;
   }

   public static String strip(String raw) {
      return raw == null ? "" : raw.replaceAll("\u00a7[0-9a-fk-orA-FK-OR]", "");
   }
}
