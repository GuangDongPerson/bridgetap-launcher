package com.bridger.compat.boot;

/**
 * Bootstrap-loaded hook shim.
 *
 * <p>The agent appends this jar to the bootstrap classloader search, so {@code Hook} is defined by
 * the bootstrap loader. Any class in the client — even one defined by a child-first, isolated
 * classloader such as a launcher's game loader — can resolve it through normal parent delegation.
 *
 * <p>Everything stateful lives behind {@link FrameSink}, whose implementation is supplied by the
 * agent after it attaches. That keeps this class dependency-free (java.base only) and makes a
 * missing/unloaded agent a silent no-op instead of a linkage error inside the render loop.
 *
 * <p><b>Two entry points on purpose.</b> A static method can only pass the site index, an instance
 * method can also pass {@code this} — and for {@code Minecraft.runTick()} that {@code this} is the
 * only hard way to get the live game object: Lunar's client keeps {@code theMinecraft} in a static
 * field, but structural discovery across 26k classes is slow and can silently return nothing. The
 * injected prologue therefore pushes {@code ALOAD 0} for instance methods and {@code null} for
 * static ones, so both land in one place.
 *
 * <p>{@link #diag} exists because a silent no-op is indistinguishable from a broken injection from
 * the outside: an unresolved {@code invokestatic} throws before this method's body runs, and a
 * throwing sink leaves nothing in the game's log. It writes the first hit per site and then a very
 * sparse heartbeat — {@code Sys.getTime()} alone is called ~80 times per frame, so anything denser
 * turns the diagnostic into a disk-bound frame killer that shows up as visible tearing.
 */
public final class Hook {
   public interface FrameSink {
      /**
       * @param site index into {@link #SITE_NAMES}
       * @param self {@code this} for instance methods, {@code null} for static ones
       */
      void onFrame(int site, Object self);

      /** Called from a return-site hook; see {@link Hook#onItemUse}. */
      void onItemUse(boolean success, Object stack, int site);

      /**
       * Called from the prologue of the player's chat send.
       *
       * @return true when the message was consumed and the original method must not run
       */
      boolean onChat(Object self, String message, int site);
   }

   /** Injected call sites, indexed in {@code Bootstrap.TARGETS} order plus the native probe. */
   private static final String[] SITE_NAMES = {
      "gui.renderGameOverlay(F)V",
      "client.runTick()V",
      "PlayerController.onPlayerRightClick",
      "client.updateDisplay()V",
      "Display.swapBuffers()V",
      "native-probe",
      "player.sendChatMessage(String)",
      "player.jump()V",
      "gui.beforePlayerList",
      "framebuffer.render(II)V"
   };

   private static final int SITES = SITE_NAMES.length;

   private static volatile FrameSink sink;
   private static volatile boolean failed;

   private static final int[] hits = new int[SITES];

   private Hook() {
   }

   public static void setSink(FrameSink newSink) {
      sink = newSink;
   }

   public static boolean ready() {
      return sink != null;
   }

   /** Static-method entry point. Kept for the native side's probe and for old bytecode. */
   public static void onFrame(int site) {
      dispatch(site, null);
   }

   /** Instance-method entry point: carries {@code this} so the agent can bind the game object. */
   public static void onFrameObj(Object self, int site) {
      dispatch(site, self);
   }

   /**
    * Return-site entry point for {@code PlayerControllerMP.onPlayerRightClick}.
    *
    * <p>The method returns a boolean and that boolean is the whole signal: true means the click was
    * accepted (a block went down, or an item was used). It is read off the operand stack with a
    * {@code DUP} before the {@code IRETURN}, and the stack argument is the {@code ItemStack} that was
    * in hand, so the agent can tell a placed block from a swung sword.
    */
   public static void onItemUse(boolean success, Object stack, int site) {
      dispatchItem(success, stack, site);
   }

   /**
    * Chat interception entry point.
    *
    * <p>Returns the value the injected prologue turns into "return early": a {@code /bt} command
    * must never reach the server, and it must not appear in the client's own chat history either.
    * Everything that is not a command returns false and the original method runs untouched.
    */
   public static boolean onChat(Object self, String message, int site) {
      note(site);
      FrameSink current = sink;
      if (current == null) {
         return false;
      }
      try {
         return current.onChat(self, message, site);
      } catch (Throwable t) {
         if (!failed) {
            failed = true;
            trace("chat sink threw, disabled: " + describe(t));
         }
         sink = null;
         return false;
      }
   }

   private static void dispatchItem(boolean success, Object stack, int site) {
      note(site);

      FrameSink current = sink;
      if (current == null) {
         return;
      }
      try {
         current.onItemUse(success, stack, site);
      } catch (Throwable t) {
         if (!failed) {
            failed = true;
            trace("item sink threw, disabled: " + describe(t));
         }
         sink = null;
      }
   }

   private static void dispatch(int site, Object self) {
      note(site);

      FrameSink current = sink;
      if (current == null) {
         return;
      }
      try {
         current.onFrame(site, self);
      } catch (Throwable t) {
         // One noisy failure must not spam the game's render loop.
         if (!failed) {
            failed = true;
            trace("sink threw, hook disabled: " + describe(t));
         }
         sink = null;
      }
   }

   /**
    * Writes the first hit per site, then a heartbeat every ~16384 hits.
    *
    * <p>Deliberately allocation-light and file-based: the target runs with javaw and has no console,
    * and anything thrown here would land in an empty catch block inside the render loop. The
    * heartbeat is sparse because the hot sites run per frame (and {@code Sys.getTime} far more than
    * that); a synchronous {@code FileWriter} on that path stalls the render thread.
    */
   private static void note(int site) {
      if (site < 0 || site >= SITES) {
         return;
      }
      int count = ++hits[site];
      if (count == 1) {
         trace("site " + site + " (" + SITE_NAMES[site] + ") first hit, sink="
            + (sink == null ? "null" : sink.getClass().getName()));
      } else if ((count & 0x3FFF) == 0) {
         trace("site " + site + " hits=" + count + " sink=" + (sink == null ? "null" : "live"));
      }
   }

   private static void trace(String line) {
      try {
         String base = System.getenv("LOCALAPPDATA");
         if (base == null || base.isEmpty()) {
            base = System.getProperty("java.io.tmpdir");
         }
         java.io.File dir = new java.io.File(base, "BridgeTap");
         if (!dir.isDirectory() && !dir.mkdirs()) {
            return;
         }
         java.io.File out = new java.io.File(dir, "hook.log");
         try (java.io.FileWriter writer = new java.io.FileWriter(out, true)) {
            writer.write("[hook] " + line + "\r\n");
         }
      } catch (Throwable ignored) {
         // a diagnostic must never break the render loop
      }
   }

   private static String describe(Throwable t) {
      StringBuilder sb = new StringBuilder(String.valueOf(t));
      StackTraceElement[] frames = t.getStackTrace();
      for (int i = 0; i < frames.length && i < 6; i++) {
         sb.append(" | ").append(frames[i]);
      }
      return sb.toString();
   }
}
