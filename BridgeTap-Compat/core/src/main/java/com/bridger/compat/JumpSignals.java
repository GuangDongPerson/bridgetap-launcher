package com.bridger.compat;

/** Small, independently testable rules; ambiguous input is left unlabelled. Ported from upstream. */
public final class JumpSignals {
   private JumpSignals() {
   }

   /**
    * The {@code [Gain]}/{@code [No Gain]} tag.
    *
    * <p>1.5.0 redefined this: it is no longer about how much the coordinate moved, it is about the
    * strafe keys. Holding A and D together cancels out — the player leaves the block straight, so
    * the coordinate climbs noticeably — that is {@code Gain}. Holding only one of them is
    * {@code No Gain}. No forward input, or back-pedalling, has no verdict at all.
    */
   public static String gain(boolean forward, boolean left, boolean right, boolean back) {
      if (!forward || back) {
         return null;
      }
      return left == right ? "Gain" : "No Gain";
   }

   /** A height jump this large in one tick is a teleport, not movement. */
   public static boolean isUpwardTeleport(double previousY, double currentY) {
      return currentY - previousY > 1.5;
   }
}
