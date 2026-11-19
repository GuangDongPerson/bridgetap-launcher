package com.bridger.compat.tools;

import com.bridger.compat.Tracker;
import com.bridger.compat.hud.HudModel;
import com.bridger.compat.hud.PreviewHud;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Renders the HUD layout to a PNG without a GPU.
 *
 * <p>Feeds the real {@link Tracker} with synthetic ticks so the produced image exercises the exact
 * formatting and line-assembly code the in-game overlay uses.
 */
public final class PreviewMain {
   private PreviewMain() {
   }

   public static void main(String[] args) throws Exception {
      File out = new File(args.length > 0 ? args[0] : "bridgetap-hud.png");

      double y = 64.0D;
      boolean onGround = true;
      int air = 0;
      double x = 0.0D;

      for (int tick = 0; tick < 60; tick++) {
         boolean jumpNow = onGround && tick % 18 == 0 && tick > 0;
         if (jumpNow) {
            Tracker.onJump();
            onGround = false;
            air = 0;
            y += 0.42D;
         } else if (!onGround) {
            air++;
            y += air < 6 ? 0.08D : -0.12D;
            if (air > 9) {
               onGround = true;
               y = 64.0D;
            }
         }
         Tracker.noteY(y);
         Tracker.onTick(x, y, 0.0D, onGround);
         x += onGround ? 0.0D : 0.28D;
      }

      List<String> lines = HudModel.build();
      System.out.println("lines=" + lines.size());
      for (String line : lines) {
         System.out.println("  " + line.replace('\u00a7', '&'));
      }

      PreviewHud preview = new PreviewHud();
      BufferedImage image = preview.render(lines, 520, 220);
      ImageIO.write(image, "png", out);
      System.out.println("wrote " + out.getAbsolutePath() + " (" + image.getWidth() + "x" + image.getHeight() + ")");
   }
}
