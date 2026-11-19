package com.bridger.compat;

/**
 * A completed run must age five client ticks before another can start.
 *
 * <p>Ported from upstream 1.5.0. Without it, the placement that finishes a run (the one you land
 * with) can immediately open the next run, so a single botched attempt is counted twice: once as
 * the failure and once as the opening of the next.
 */
public final class RunStartCooldown {
   public static final int TICKS = 5;

   private boolean finished;
   private int finishedTick;

   public void finish(int tick) {
      finished = true;
      finishedTick = tick;
   }

   public boolean ready(int tick) {
      if (!finished) {
         return true;
      }
      if (tick < finishedTick) {
         // The clock went backwards, i.e. this is another world counting from zero. The gate cannot
         // be measured against a different clock, and holding it would freeze the tracker until the
         // new world's counter catches up with the old one — which is hours, not five ticks.
         finished = false;
         return true;
      }
      return tick - finishedTick >= TICKS;
   }

   public void reset() {
      finished = false;
   }
}
