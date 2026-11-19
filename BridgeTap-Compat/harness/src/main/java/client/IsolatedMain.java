package client;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * Simulates a client that loads its own game classes through an isolated, child-first loader — the
 * situation that breaks naive hot-inject designs. The agent must still reach us, which only works
 * because the injected call targets a bootstrap-loaded shim.
 *
 * <p>Run: {@code java -cp harness.jar client.IsolatedMain harness.jar}
 */
public final class IsolatedMain {
   private IsolatedMain() {
   }

   public static void main(String[] args) throws Exception {
      File jar = new File(args.length > 0 ? args[0] : "bridgetap-harness.jar");
      URL[] urls = {jar.toURI().toURL()};
      // Parent is the platform loader: game classes and the LWJGL stub are defined by the child.
      URLClassLoader isolated = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());

      Class<?> gameClass = isolated.loadClass("client.a");
      Class<?> entityClass = isolated.loadClass("client.b");
      Class<?> playerClass = isolated.loadClass("client.c");
      Class<?> displayClass = isolated.loadClass("org.lwjgl.opengl.Display");

      Object game = gameClass.getConstructor().newInstance();
      gameClass.getField("a").set(null, game);
      Object player = playerClass.getConstructor().newInstance();
      gameClass.getField("b").set(game, player);

      Field fx = entityClass.getField("a");
      Field fy = entityClass.getField("b");
      Field fz = entityClass.getField("c");
      Field fGround = entityClass.getField("d");
      Method update = displayClass.getMethod("update");

      System.out.println("[isolated] game loader = " + gameClass.getClassLoader());
      System.out.println("[isolated] display loader = " + displayClass.getClassLoader());
      System.out.println("[isolated] pid=" + ProcessHandle.current().pid());

      double x = 0.0D;
      double y = 64.0D;
      boolean ground = true;
      int air = 0;
      long frames = 0L;
      long deadline = System.currentTimeMillis() + 90_000L;

      while (System.currentTimeMillis() < deadline) {
         int tick = (int) (frames / 5);
         if (ground && tick % 20 == 0 && frames % 5 == 0 && frames > 20) {
            ground = false;
            air = 0;
            y += 0.42D;
         } else if (!ground && frames % 5 == 0) {
            air++;
            y += air < 6 ? 0.08D : -0.12D;
            if (air > 9) {
               ground = true;
               y = 64.0D;
            }
         }

         fx.setDouble(player, x);
         fy.setDouble(player, y);
         fz.setDouble(player, 0.0D);
         fGround.setBoolean(player, ground);
         x += ground ? 0.0D : 0.28D;

         update.invoke(null);
         frames++;

         if (frames % 200 == 0) {
            System.out.println("[isolated] alive frames=" + frames + " y=" + String.format("%.2f", y));
         }
         Thread.sleep(2L);
      }

      System.out.println("[isolated] done frames=" + frames);
   }
}
