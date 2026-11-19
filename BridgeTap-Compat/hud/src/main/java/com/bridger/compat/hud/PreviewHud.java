package com.bridger.compat.hud;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

/** AWT-only renderer used to verify the HUD layout without a GPU (same geometry as GlHud). */
public final class PreviewHud {
   private static final int PADDING = 4;
   private static final int LINE_GAP = 2;

   private final TextRaster raster = new TextRaster();

   public BufferedImage render(List<String> lines, int width, int height) {
      BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
      Graphics2D g = image.createGraphics();

      int lineHeight = this.raster.lineHeight() + LINE_GAP;
      int textWidth = 0;
      for (String line : lines) {
         textWidth = Math.max(textWidth, this.raster.width(line));
      }
      int panelWidth = textWidth + PADDING * 2;
      int panelHeight = lineHeight * lines.size() + PADDING * 2 - LINE_GAP;

      g.setColor(new Color(0x10, 0x10, 0x10, 0x9F));
      g.fillRect(0, 0, panelWidth, panelHeight);
      g.setColor(new Color(0xFF, 0xFF, 0xFF, 0x41));
      g.drawRect(0, 0, panelWidth - 1, panelHeight - 1);

      int y = PADDING;
      for (String line : lines) {
         BufferedImage text = this.raster.render(line, true);
         g.drawImage(text, PADDING, y, null);
         y += lineHeight;
      }

      g.dispose();
      return image;
   }
}
