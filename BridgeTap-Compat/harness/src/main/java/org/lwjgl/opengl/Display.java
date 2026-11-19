package org.lwjgl.opengl;

/**
 * Stub of the real LWJGL2 class so the agent's Display hook can be exercised headlessly.
 * In a real client this class ships with the game's libraries and is never obfuscated.
 */
public final class Display {
   private static int width = 1280;
   private static int height = 720;

   private Display() {
   }

   public static void update() {
      // no-op: the real one swaps buffers
   }

   public static int getWidth() {
      return width;
   }

   public static int getHeight() {
      return height;
   }

   public static void setSize(int w, int h) {
      width = w;
      height = h;
   }
}
