package com.bridger.compat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Client-observed block placements, used to qualify bridging labels.
 *
 * <p>Ported from upstream 1.5.0, with one deliberate adaptation: upstream records the <b>placed
 * block</b> (it can diff the world state around the clicked position, see {@code PlacementHooks}).
 * The injector reaches the game only through profile-driven reflective hooks and cannot read a
 * raytrace or a chunk, so it records the <b>player's block column at the placement tick</b> instead.
 *
 * <p>What survives that substitution: the question "did the player actually place blocks across
 * this interval, at N distinct columns?" — which is what every label gate asks. What is lost: the
 * "block is ahead of the player" direction test. That test only rejected placements inside the
 * player's own column, and a placement while bridging is always ahead anyway; the cost of losing it
 * is a slightly more permissive gate, never a false negative on a real run.
 */
public final class PlacementEvidence {
   private static final List<Event> EVENTS = new ArrayList<Event>();

   private static final class Event {
      final int tick;
      final int x;
      final int z;

      Event(int tick, int x, int z) {
         this.tick = tick;
         this.x = x;
         this.z = z;
      }
   }

   private PlacementEvidence() {
   }

   public static void reset() {
      EVENTS.clear();
   }

   /** @param tick the tick the placement was observed on */
   public static void record(int tick, double playerX, double playerZ) {
      EVENTS.removeIf(event -> tick - event.tick > 81);
      EVENTS.add(new Event(tick, (int)Math.floor(playerX), (int)Math.floor(playerZ)));
   }

   /**
    * @param from first tick of the interval (inclusive)
    * @param to   last tick of the interval (inclusive)
    * @param count how many distinct block columns must have been touched
    */
   public static boolean qualifies(int from, int to, double start, double end, boolean xAxis, int count) {
      double distance = end - start;
      if (Math.abs(distance) < 0.1) {
         return false;
      }
      Set<String> blocks = new HashSet<String>();
      for (Event event : EVENTS) {
         if (event.tick < from || event.tick > to) {
            continue;
         }
         blocks.add(event.x + ":" + event.z);
      }
      return blocks.size() >= count;
   }

   /** Diagnostics only. */
   public static int size() {
      return EVENTS.size();
   }
}
