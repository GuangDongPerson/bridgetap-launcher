package com.bridger.compat.hud;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rasterises HUD strings with AWT so the agent never depends on the client's own FontRenderer
 * (which is obfuscated differently in every client build).
 */
public final class TextRaster {
   public static final int FONT_SIZE = 14;
   public static final int SHADOW_OFFSET = 1;

   private final Font font = new Font(Font.MONOSPACED, Font.PLAIN, FONT_SIZE);
   private final BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
   private final Graphics2D scratchGfx = scratch.createGraphics();
   private final Map<String, BufferedImage> cache = new HashMap<String, BufferedImage>();

   public TextRaster() {
      scratchGfx.setFont(font);
      scratchGfx.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
   }

   public FontMetrics metrics() {
      return scratchGfx.getFontMetrics(font);
   }

   public int width(String raw) {
      int total = 0;
      for (TextStyle.Segment seg : TextStyle.parse(raw)) {
         total += metrics().stringWidth(seg.text);
      }
      return total;
   }

   public int lineHeight() {
      return metrics().getHeight();
   }

   /** Renders one line into a tightly cropped ARGB image; cached because lines repeat every frame. */
   public BufferedImage render(String raw, boolean shadow) {
      String key = (shadow ? "1" : "0") + raw;
      BufferedImage cached = cache.get(key);
      if (cached != null) {
         return cached;
      }

      List<TextStyle.Segment> segments = TextStyle.parse(raw);
      int textWidth = Math.max(1, width(raw));
      int height = Math.max(1, lineHeight());
      BufferedImage image = new BufferedImage(textWidth + SHADOW_OFFSET + 2, height + SHADOW_OFFSET + 2,
         BufferedImage.TYPE_INT_ARGB);
      Graphics2D g = image.createGraphics();
      g.setFont(font);
      g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

      int baseline = metrics().getAscent();
      if (shadow) {
         int x = 0;
         for (TextStyle.Segment seg : segments) {
            g.setColor(new Color(0, 0, 0, 180));
            g.drawString(seg.text, x + SHADOW_OFFSET, baseline + SHADOW_OFFSET);
            x += metrics().stringWidth(seg.text);
         }
      }

      int x = 0;
      for (TextStyle.Segment seg : segments) {
         g.setColor(new Color(seg.rgb));
         g.drawString(seg.text, x, baseline);
         x += metrics().stringWidth(seg.text);
      }
      g.dispose();

      cache.put(key, image);
      return image;
   }

   /** Drops cached textures/images; called when the config changes the shadow setting. */
   public void clearCache() {
      cache.clear();
   }
}
