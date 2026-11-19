package client;

import com.bridger.compat.JumpEntry;
import com.bridger.compat.Tracker;
import java.util.List;
import org.lwjgl.opengl.Display;

/**
 * Drives a fake obfuscated client through the agent.
 *
 * <p>What this proves end to end:
 * <ul>
 *   <li>the agent hooks {@code org.lwjgl.opengl.Display.update()} in a class it never saw at
 *       build time (the stub here is compiled with the harness, not with the agent);</li>
 *   <li>{@code Bridge.onFrame} runs, builds a 50 ms tick clock and calls the tracker;</li>
 *   <li>structural discovery finds the singleton, the player field and the x/y/z/on-ground fields
 *       purely from shape, with obfuscated names {@code a.b.c};</li>
 *   <li>jumps are detected and produce HUD entries.</li>
 * </ul>
 */
public final class TestMain {
   private static final int FRAMES = 900;
   private static final int FRAMES_PER_TICK = 5;

   private TestMain() {
   }

   public static void main(String[] args) throws Exception {
      a game = new a();
      a.a = game;
      c player = new c();
      game.b = player;

      player.a(0.0D, 64.0D, 0.0D);
      player.d = true;

      double y = 64.0D;
      boolean onGround = true;
      double airTicks = 0.0D;

      for (int frame = 0; frame < FRAMES; frame++) {
         if (frame % FRAMES_PER_TICK == 0) {
            boolean tickStart = onGround;
            if (tickStart && (frame / FRAMES_PER_TICK) % 20 == 0) {
               onGround = false;
               y += 0.42D;
               airTicks = 0.0D;
            } else if (!onGround) {
               airTicks += 1.0D;
               y += airTicks < 6 ? 0.08D : -0.12D;
               if (airTicks > 9.0D) {
                  onGround = true;
                  y = 64.0D;
               }
            }
            player.a(player.a + 0.28D, y, player.c + 0.0D);
            player.d = onGround;
         }
         Display.update();
         Thread.sleep(2L);
      }

      int ticks = Tracker.getCurrentTick();
      List<JumpEntry> entries = Tracker.getEntries();

      System.out.println("[harness] ticks=" + ticks + " entries=" + entries.size()
         + " status=" + Tracker.getStatus() + " jumps=" + Tracker.getJumpCount());
      for (JumpEntry entry : entries) {
         System.out.println("[harness]   " + entry.getFormattedLine());
      }

      boolean ok = ticks >= 20 && !entries.isEmpty();
      System.out.println(ok ? "[harness] PASS" : "[harness] FAIL");
      if (!ok) {
         System.exit(1);
      }
   }
}
