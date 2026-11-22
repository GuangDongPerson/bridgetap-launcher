package org.objectweb.asm;

/**
 * Injection target that the client's classloader will actually resolve.
 *
 * <p>Lunar's game loader is child-first with a hard rule: for any class the ichor filter claims, it
 * calls {@code findClass} on its own jars and, on failure, throws — it never falls back to the
 * parent. The filter claims everything that is not on a fixed prefix allowlist, and
 * {@code com.bridger.*} is not on it. So an injected {@code invokestatic} naming
 * {@code com.bridger.compat.boot.Hook} from inside {@code Display} or {@code Minecraft} dies with
 * {@code NoClassDefFoundError} — silently, inside the frame loop's own try/catch.
 *
 * <p>{@code org.objectweb.asm.} <em>is</em> on that allowlist, which is why this class lives in that
 * package: the loader hands the lookup to {@code super.loadClass}, normal parent delegation applies,
 * and the bootstrap search path (where the agent jar sits) is finally consulted. The name is
 * distinctive enough not to collide with real ASM classes, and being first on the parent chain means
 * the bootstrap copy wins even if the client ships its own ASM.
 *
 * <p>Everything else stays where it belongs; only this one hop needs the disguise.
 */
public final class BridgeTapHook {
   private BridgeTapHook() {
   }

   /** Static-method prologue: site only. Never throws. */
   public static void onFrame(int site) {
      com.bridger.compat.boot.Hook.onFrame(site);
   }

   /** Instance-method prologue: {@code this} plus site. Never throws. */
   public static void onFrameObj(Object self, int site) {
      com.bridger.compat.boot.Hook.onFrameObj(self, site);
   }

   /**
    * Return-site prologue for the right-click controller: return value, held stack, clicked position
    * and clicked face. The last two are {@code null} on a controller signature that has no such
    * arguments.
    */
   public static void onItemUse(boolean success, Object stack, Object blockPos, Object facing, int site) {
      com.bridger.compat.boot.Hook.onItemUse(success, stack, blockPos, facing, site);
   }

   /**
    * Chat prologue: {@code this}, the message, plus site.
    *
    * @return true when the message was a BridgeTap command; the caller must then return immediately
    */
   public static boolean onChatObj(Object self, String message, int site) {
      return com.bridger.compat.boot.Hook.onChat(self, message, site);
   }
}
