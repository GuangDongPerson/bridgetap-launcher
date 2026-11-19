package com.bridger.compat.agent;

import com.bridger.compat.Log;
import com.bridger.compat.boot.Hook;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Everything that runs once the agent jar is on the bootstrap classloader search.
 *
 * <p>Living on the bootstrap loader is what makes this work on clients that define their own game
 * classes through an isolated child-first loader: the injected call to {@link Hook} resolves through
 * normal parent delegation, and {@link Hook}'s sink implementation is one of our own bootstrap
 * classes, so the interface identity matches exactly. No dynamic proxy, no cross-loader casts.
 *
 * <p>Two entry points, identical afterwards:
 * <ul>
 *   <li>{@link #run} — a standard java agent ({@code premain} from launch, or {@code agentmain} via
 *       the attach API). Instrumentation drives the transform.</li>
 *   <li>{@link #runNative()} — no attach mechanism available at all ({@code -XX:+DisableAttachMechanism}).
 *       A native payload loaded through {@code CreateRemoteThread} hands us a JVMTI environment
 *       instead, and JVMTI drives the same transform through {@link #transformBytes}.</li>
 * </ul>
 */
public final class Bootstrap {
   /**
    * Injection target.
    *
    * <p>Not {@code com/bridger/compat/boot/Hook}: the client's loader claims that package for
    * itself and refuses to fall back to the parent, so the injected call would never resolve. It
    * allowlists {@code org.objectweb.asm.}, so the bridge class lives there and simply forwards.
    * See {@link org.objectweb.asm.BridgeTapHook}.
    */
   private static final String HOOK_OWNER = "org/objectweb/asm/BridgeTapHook";
   private static final String HOOK_NAME = "onFrameObj";
   private static final String HOOK_DESC = "(Ljava/lang/Object;I)V";
   private static final String ITEM_NAME = "onItemUse";
   private static final String ITEM_DESC = "(ZLjava/lang/Object;I)V";
   /** Chat entry point: argument-aware, and it returns whether the call must be aborted. */
   private static final String CHAT_NAME = "onChatObj";
   private static final String CHAT_DESC = "(Ljava/lang/Object;Ljava/lang/String;I)Z";

   /** Injected in the method prologue. */
   private static final String MODE_HEAD = "head";
   /** Injected before every {@code return}. */
   private static final String MODE_RETURN = "return";
   /** Injected before every {@code ireturn}, carrying the returned boolean and the held stack. */
   private static final String MODE_RETURN_BLOCK = "return-block";
   /** Injected in the prologue, with an early {@code return} when the hook reports "consumed". */
   private static final String MODE_CHAT = "chat";
   /** Injected immediately before a specific self-call, so the overlay lands at the right layer. */
   private static final String MODE_BEFORE_CALL = "before-call";

   /**
    * Every call site worth hooking — deliberately short.
    *
    * <p>The previous build instrumented the whole presenting chain ({@code Display.update},
    * {@code Display.swapBuffers}, {@code ContextGL}, {@code DrawableGL},
    * {@code WindowsContextImplementation}) plus {@code Sys.getTime} and
    * {@code Display.isCloseRequested}. All six swap levels hit on the same frame, so the overlay was
    * drawn six to ten times per frame, some of those in the middle of world rendering, and
    * {@code Sys.getTime} alone fires ~80 times per frame — every one of them running the hook body
    * and, every 512 calls, a synchronous file write. That is what the tearing was: repeated partial
    * overlays plus a disk-bound render thread.
    *
    * <p>Now there is exactly one draw site and one data site:
    * <ul>
    *   <li>site 0 {@code Display.swapBuffers()} — called once per presented frame, after all world
    *       and GUI rendering. Nothing draws after it, so the overlay cannot be half-overwritten.</li>
    *   <li>site 1 {@code Minecraft.runTick()} — exactly 20 times per second, one game tick apart.
    *       The {@code this} on the stack is the live {@code Minecraft} instance, which is a far
    *       harder binding than guessing at static singleton fields.</li>
    *   <li>site 2 {@code Minecraft.runGameLoop()} — instance too, used only as an early binding
    *       path when {@code runTick} has not fired yet.</li>
    *   <li>sites 3/4 {@code jump()} — the precise "a bridge run starts here" signal. Optional: a
    *       client may not have them, in which case the agent falls back to a ground-state edge.</li>
    * </ul>
    */
   /**
    * Runtime target table, filled by {@link ClassScan} during {@link #runNative()}.
    *
    * <p>Row layout: {@code {owner, name, descriptor, mode}}. Index is the site number handed to
    * {@code Hook}, so the array length and order must stay fixed across clients; a target the current
    * client does not have is filled with a placeholder that can never match, keeping the indices
    * stable instead of shifting them.
    *
    * <p>Why not a static table: Lunar keeps MCP names, Badlion runs everything through Odin and ends
    * up with {@code ave}, {@code bda}, {@code avn}. A hardcoded list is correct for exactly one of
    * them.
    */
   private static volatile String[][] targets = new String[0][];

   /** Identifier that no real class can have, used to pad missing sites. */
   private static final String[] ABSENT = {"__bridgetap_absent__", "__bridgetap_absent__", "()V", MODE_HEAD};

   /** What {@link ClassScan} found; the field-name source for {@link GameBinding}. */
   static volatile ClassScan.Result mapping;

   /** Injection targets, in site order. Exposed for the native payload's retransform list. */
   static String[][] targets() {
      return targets;
   }

   /**
    * Builds the site table for whatever client this is.
    *
    * <p>Site order is part of the contract with {@code Hook.SITE_NAMES} and {@code Bridge}:
    * <pre>
    *   0  &lt;gui&gt;.renderGameOverlay(F)V  draw (primary)
    *   1  &lt;mc&gt;.runTick()V              sample
    *   2  &lt;controller&gt;.onRightClick    block placed
    *   3  &lt;mc&gt;.updateDisplay()V        draw (fallback)
    *   4  Display.swapBuffers()V        draw (last resort)
    * </pre>
    */
   private static String[][] buildTargets(ClassScan.Result r) {
      String[][] out = new String[10][];
      out[0] = r != null && r.guiClass != null
         ? new String[] {r.guiClass, r.guiMethod, r.guiDesc, MODE_RETURN} : ABSENT;
      out[1] = r != null && r.runTick != null
         ? new String[] {r.mcClass, r.runTick, "()V", MODE_HEAD} : ABSENT;
      out[2] = r != null && r.controllerClass != null
         ? new String[] {r.controllerClass, r.controllerMethod, r.controllerDesc, MODE_RETURN_BLOCK}
         : ABSENT;
      out[3] = r != null && r.updateDisplay != null
         ? new String[] {r.mcClass, r.updateDisplay, "()V", MODE_HEAD} : ABSENT;
      out[4] = new String[] {"org/lwjgl/opengl/Display", "swapBuffers", "()V", MODE_HEAD};
      // Site 5 is the native payload's liveness probe; leaving a dead slot keeps every other index
      // stable across builds, which matters because injected bytecode hard-codes the number.
      out[5] = ABSENT;
      out[6] = r != null && r.chatClass != null
         ? new String[] {r.chatClass, r.chatMethod, r.chatDesc, MODE_CHAT} : ABSENT;
      // The take-off itself. Inferred from sampled motion it lands one tick late (a frame sees the
      // player already airborne), which shifts every tick-quantised verdict by one - the reported
      // "1.05 Flat becomes 1.1 Flat" was exactly that.
      out[7] = r != null && r.jumpClass != null
         ? new String[] {r.jumpClass, r.jumpMethod, r.jumpDesc, MODE_HEAD} : ABSENT;
      // Draw inside the HUD pass instead of after it. The tab list is drawn by renderGameOverlay
      // itself, so a hook at its return paints over it; renderTooltip runs earlier, unconditionally,
      // with the GUI projection already installed, so everything after it covers the panel.
      // Falls back to site 0 when the method is not found.
      out[8] = r != null && r.guiClass != null && r.tooltipMethod != null
         ? new String[] {r.guiClass, r.tooltipMethod, r.tooltipDesc, MODE_RETURN} : ABSENT;
      // Site 9: the frame's own blit, i.e. the only place that is both on screen and in a
      // screenshot. At the head of framebufferRender the offscreen framebuffer is still bound and
      // the finished frame is about to be copied to the window, so a panel drawn here is part of
      // that copy. Drawing after the blit (sites 3/4) is visible but never captured; binding the
      // framebuffer after the blit is captured by nothing, because the next frame clears it before
      // the blit - which is exactly why that attempt made the panel disappear entirely.
      out[9] = r != null && r.framebufferClass != null && r.framebufferRenderMethod != null
         ? new String[] {r.framebufferClass, r.framebufferRenderMethod, "(II)V", MODE_HEAD} : ABSENT;
      return out;
   }

   static boolean isHookable(String className) {
      for (String[] row : targets) {
         if (row[0].equals(className)) {
            return true;
         }
      }
      return false;
   }

   private static volatile boolean installed;
   private static volatile String mode = "none";
   private static volatile Instrumentation instrumentation;
   private static volatile Class<?>[] nativeLoadedClasses;

   private Bootstrap() {
   }

   /**
    * Every class the JVM has loaded, whichever path got us here.
    *
    * <p>{@code Instrumentation.getAllLoadedClasses()} normally provides this, but the native path has
    * no Instrumentation at all. The payload therefore calls {@link #setLoadedClasses} with the result
    * of JVMTI {@code GetLoadedClasses} before {@link #runNative()}, which is the exact same set.
    */
   public static Class<?>[] allLoadedClasses() {
      Instrumentation inst = instrumentation;
      if (inst != null) {
         try {
            return inst.getAllLoadedClasses();
         } catch (Throwable t) {
            Log.warn("getAllLoadedClasses failed: " + t);
         }
      }
      Class<?>[] cached = nativeLoadedClasses;
      return cached == null ? new Class<?>[0] : cached;
   }

   /** Called from the native payload with JVMTI {@code GetLoadedClasses}. Order matters: set this
    * before {@link #runNative()}, because the first frame may already need it. */
   public static void setLoadedClasses(Class<?>[] classes) {
      nativeLoadedClasses = classes;
      Log.verbose("native loaded-class snapshot: " + (classes == null ? 0 : classes.length));
   }

   private static synchronized boolean claim(String who) {
      if (installed) {
         Log.info("already installed via " + mode + ", ignoring second init (" + who + ")");
         return false;
      }
      installed = true;
      mode = who;
      return true;
   }

   /** Path for a real java agent: premain or agentmain, both carry an Instrumentation. */
   public static void run(Instrumentation inst) {
      if (!claim("instrumentation")) {
         return;
      }
      instrumentation = inst;
      Log.info("init via instrumentation (java " + System.getProperty("java.version") + ", pid " + pid() + ")");
      installSink();
      resolveTargets();
      if (inst == null) {
         return;
      }
      try {
         inst.addTransformer(new DisplayTransformer(), true);
      } catch (Throwable t) {
         Log.warn("addTransformer failed: " + t);
         return;
      }
      retransformDisplay(inst);
   }

   /**
    * Path for the native payload. The JVMTI environment lives on the native side, so this method
    * only wires the sink; the payload registers the class-file hook and triggers the retransform
    * through {@link #transformBytes}.
    */
   public static void runNative() {
      if (!claim("jvmti")) {
         return;
      }
      Log.info("init via jvmti (java " + System.getProperty("java.version") + ", pid " + pid() + ")");
      installSink();
      resolveTargets();
      dumpClassMapAsync();
   }

   /**
    * Writes every loaded class with its defining loader.
    *
    * <p>Static analysis of Lunar's jars is useless here: ichor remaps the 1.8.9 classes at load
    * time, so nothing on disk references {@code org.lwjgl.opengl.Display}. The live names do. This
    * snapshot answers the questions that matter offline - how many copies of Display exist, which
    * loader owns each, and what the game's own classes are called.
    */
   /**
    * Runs the snapshot off the JVMTI thread.
    *
    * <p>Writing 14k class names is a few hundred milliseconds, and doing it inline held the attach
    * thread while the client was already resumed - which the player feels as the game hitching the
    * moment injection reports success. Nothing downstream depends on this file.
    */
   private static void dumpClassMapAsync() {
      Thread worker = new Thread(new Runnable() {
         @Override
         public void run() {
            dumpClassMap();
         }
      }, "BridgeTap-classmap");
      worker.setDaemon(true);
      worker.start();
   }

   private static void dumpClassMap() {
      try {
         Class<?>[] classes = allLoadedClasses();
         if (classes == null || classes.length == 0) {
            return;
         }
         String base = System.getenv("LOCALAPPDATA");
         if (base == null || base.isEmpty()) {
            base = System.getProperty("java.io.tmpdir");
         }
         java.io.File dir = new java.io.File(base, "BridgeTap/dump");
         if (!dir.isDirectory() && !dir.mkdirs()) {
            return;
         }
         java.io.File out = new java.io.File(dir, "classes.txt");
         int displays = 0;
         try (java.io.PrintWriter writer = new java.io.PrintWriter(
               new java.io.OutputStreamWriter(new java.io.FileOutputStream(out), "UTF-8"))) {
            for (Class<?> type : classes) {
               ClassLoader loader = type.getClassLoader();
               String owner = loader == null ? "bootstrap" : loader.getClass().getName() + "@"
                  + Integer.toHexString(System.identityHashCode(loader));
               writer.println(type.getName() + "\t" + owner);
               if ("org.lwjgl.opengl.Display".equals(type.getName())) {
                  displays++;
               }
            }
         }
         Log.info("class map written: " + classes.length + " classes, " + displays
            + " Display cop(ies) -> " + out.getAbsolutePath());
      } catch (Throwable t) {
         Log.warn("class map dump failed: " + t);
      }
   }

   /**
    * Fills {@link #targets} from the live class graph.
    *
    * <p>Runs before the payload arms its class-file hook, so the first transform already sees the
    * right table. Costs a few seconds of class-file reading on the JVMTI thread; there is no way
    * around it, because the names are unknown until the graph is walked.
    */
   private static void resolveTargets() {
      try {
         ClassScan.Result found = ClassScan.discover(allLoadedClasses());
         mapping = found;
         targets = buildTargets(found);
         if (found == null) {
            Log.warn("target discovery failed - only the LWJGL fallback will be hooked");
         } else {
            Log.info("targets: " + found.describe);
         }
         for (int i = 0; i < targets.length; i++) {
            Log.info("  site " + i + " -> " + targets[i][0] + "." + targets[i][1] + targets[i][2]
               + " [" + targets[i][3] + "]");
         }
      } catch (Throwable t) {
         Log.warn("target discovery threw: " + t);
         targets = buildTargets(null);
      }
   }

   private static void installSink() {
      try {
         Hook.setSink(new Hook.FrameSink() {
            @Override
            public void onFrame(int site, Object self) {
               Bridge.onFrame(site, self);
            }

            @Override
            public void onItemUse(boolean success, Object stack, int site) {
               Bridge.onItemUse(success, stack, site);
            }

            @Override
            public boolean onChat(Object self, String message, int site) {
               return Bridge.onChat(self, message, site);
            }
         });
         Log.info("frame sink installed (hook loader: " + loaderOf(Hook.class) + ")");
      } catch (Throwable t) {
         Log.warn("could not install frame sink: " + t);
      }
   }

   private static String loaderOf(Class<?> type) {
      ClassLoader loader = type.getClassLoader();
      return loader == null ? "bootstrap" : loader.getClass().getName();
   }

   private static String pid() {
      try {
         String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
         int at = name.indexOf('@');
         return at > 0 ? name.substring(0, at) : name;
      } catch (Throwable t) {
         return "?";
      }
   }

   private static void retransformDisplay(Instrumentation inst) {
      try {
         int hits = 0;
         for (Class<?> type : inst.getAllLoadedClasses()) {
            String name = type.getName().replace('.', '/');
            if (!isHookable(name)) {
               continue;
            }
            try {
               inst.retransformClasses(type);
               hits++;
               Log.info("retransformed already-loaded " + type.getName() + " (loader: " + loaderOf(type) + ")");
            } catch (Throwable t) {
               Log.warn("retransform of " + type.getName() + " failed: " + t);
            }
         }
         if (hits == 0) {
            Log.info("no hookable LWJGL class loaded yet; the transformer will catch them on first load");
         }
      } catch (Throwable t) {
         Log.warn("retransform failed: " + t);
      }
   }

   /**
    * Called from the native JVMTI {@code ClassFileLoadHook}. Returns {@code null} to leave the class
    * untouched; the caller must copy non-null results into JVMTI-allocated memory.
    */
   public static byte[] transformBytes(String className, byte[] classBytes) {
      if (!isHookable(className) || classBytes == null) {
         return null;
      }
      if (containsHook(classBytes)) {
         return null;
      }
      try {
         byte[] patched = inject(className, classBytes);
         if (patched == null) {
            return null;
         }
         Log.info("hooked " + className + " via jvmti (" + classBytes.length + " -> " + patched.length + " bytes)");
         return patched;
      } catch (Throwable t) {
         Log.warn("failed to hook " + className + ": " + t);
         return null;
      }
   }

   private static final class DisplayTransformer implements ClassFileTransformer {
      @Override
      public byte[] transform(ClassLoader loader, String className, Class<?> redefined,
            ProtectionDomain domain, byte[] classBytes) {
         if (!isHookable(className) || classBytes == null) {
            return null;
         }
         if (containsHook(classBytes)) {
            return null;
         }
         try {
            byte[] patched = inject(className, classBytes);
            if (patched == null) {
               return null;
            }
            Log.info("hooked " + className + " [loader: "
               + (loader == null ? "bootstrap" : loader.getClass().getName()) + "]");
            return patched;
         } catch (Throwable t) {
            Log.warn("failed to hook " + className + ": " + t);
            return null;
         }
      }
   }

   /**
    * Already patched?
    *
    * <p>Keyed on the argument-aware entry point name, not on the bridge class name. A class
    * patched by an older build carries {@code BridgeTapHook.onFrame(I)V} and would otherwise be
    * treated as done, leaving the new code calling {@code onFrameObj} with nothing to dispatch it —
    * a silent "injected but dead" state that only a client restart would clear.
    */
   private static boolean containsHook(byte[] classBytes) {
      byte[] needle = "onFrameObj".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      outer:
      for (int i = 0; i <= classBytes.length - needle.length; i++) {
         for (int j = 0; j < needle.length; j++) {
            if (classBytes[i + j] != needle[j]) {
               continue outer;
            }
         }
         return true;
      }
      return false;
   }

   /** Global site index for a (class, method) pair, or -1 when it is not a hook target. */
   private static int siteOf(String className, String methodName, String descriptor) {
      String[][] table = targets;
      for (int i = 0; i < table.length; i++) {
         if (table[i][0].equals(className)
               && table[i][1].equals(methodName)
               && descMatches(table[i][2], descriptor)) {
            return i;
         }
      }
      return -1;
   }

   /**
    * A target descriptor of {@code "*"} means "boolean result, six arguments" — the shape of
    * {@code onPlayerRightClick(EntityPlayerSP, World, ItemStack, BlockPos, EnumFacing, Vec3)}.
    * Pinning the exact descriptor would silently miss a client that remaps the parameter types,
    * and the shape test is narrow enough that no other method on that class qualifies.
    */
   private static boolean descMatches(String pattern, String descriptor) {
      if (descriptor == null) {
         return false;
      }
      if (descriptor.equals(pattern)) {
         return true;
      }
      // A null pattern means "no descriptor recorded": treat it as the shape wildcard rather than
      // dereferencing it. This exact NPE skipped the block-placed hook on Badlion.
      if (pattern != null && !"*".equals(pattern)) {
         return false;
      }
      if (!descriptor.endsWith(")Z")) {
         return false;
      }
      return Type.getArgumentTypes(descriptor).length == 6;
   }

   /** Which of {@link #TARGETS} this particular class actually contains. */
   private static boolean[] presentSites(final String className, byte[] classBytes) {
      final boolean[] present = new boolean[targets.length];
      new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
         @Override
         public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
               String[] exceptions) {
            int site = siteOf(className, name, descriptor);
            if (site >= 0) {
               present[site] = true;
            }
            return null;
         }
      }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
      return present;
   }

   /** Writes the pre-transform bytes once per (class, size), so a client layout can be inspected. */
   private static void dump(String className, byte[] classBytes) {
      try {
         String base = System.getenv("LOCALAPPDATA");
         if (base == null || base.isEmpty()) {
            base = System.getProperty("java.io.tmpdir");
         }
         java.io.File dir = new java.io.File(base, "BridgeTap/dump");
         if (!dir.isDirectory() && !dir.mkdirs()) {
            return;
         }
         // The size is part of the name: a client's runtime class differs from the one on disk, and
         // a fixed filename silently kept the first (wrong) capture.
         String simple = className.replace('/', '.');
         java.io.File out = new java.io.File(dir, simple + "-" + classBytes.length + ".class");
         if (out.isFile()) {
            return;
         }
         java.nio.file.Files.write(out.toPath(), classBytes);
         Log.info("dumped pre-transform bytes to " + out.getAbsolutePath());
      } catch (Throwable ignored) {
         // diagnostics only
      }
   }

   /** Local 0 is {@code this} on an instance method; static methods have no receiver. */
   private static void pushSelf(MethodVisitor mv, int access) {
      if ((access & Opcodes.ACC_STATIC) == 0) {
         mv.visitVarInsn(Opcodes.ALOAD, 0);
      } else {
         mv.visitInsn(Opcodes.ACONST_NULL);
      }
   }

   /**
    * Prologue that can abort the whole call.
    *
    * <p>{@code sendChatMessage} returns void, so "this message was mine, do not send it" has to be
    * expressed as an early {@code RETURN}. The message argument sits in local slot 1 on the instance
    * method this targets, and the hook returns the decision off the operand stack.
    *
    * <p>Wrapped in its own try/catch for the same reason as every other injected prologue: if the
    * bridge class cannot be resolved from the client's loader, the call must still go through with
    * normal behaviour rather than throw inside chat handling.
    */
   private static void injectChatAbort(MethodVisitor mv, int access, int site) {
      Label start = new Label();
      Label end = new Label();
      Label handler = new Label();
      Label after = new Label();
      Label skip = new Label();

      mv.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
      mv.visitLabel(start);
      pushSelf(mv, access);
      mv.visitVarInsn(Opcodes.ALOAD, 1);
      mv.visitLdcInsn(Integer.valueOf(site));
      mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER, CHAT_NAME, CHAT_DESC, false);
      mv.visitJumpInsn(Opcodes.IFEQ, skip);
      mv.visitInsn(Opcodes.RETURN);
      mv.visitLabel(skip);
      mv.visitLabel(end);
      mv.visitJumpInsn(Opcodes.GOTO, after);
      mv.visitLabel(handler);
      mv.visitInsn(Opcodes.POP);
      mv.visitLabel(after);
   }

   /** Whether a call site inside a hooked method is the one this site targets. */
   private static boolean hookNameMatches(int site, String name, String descriptor) {
      String[][] table = targets;
      if (site < 0 || site >= table.length) {
         return false;
      }
      String[] row = table[site];
      if (!row[1].equals(name)) {
         return false;
      }
      return "*".equals(row[2]) || row[2].equals(descriptor);
   }

   private static boolean isReturn(int opcode) {
      return opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN;
   }

   /** The "nothing happened" value for a return opcode, used on the hook-failure path. */
   private static int defaultValue(int returnOpcode) {
      switch (returnOpcode) {
         case Opcodes.IRETURN:
            return Opcodes.ICONST_0;
         case Opcodes.LRETURN:
            return Opcodes.LCONST_0;
         case Opcodes.FRETURN:
            return Opcodes.FCONST_0;
         case Opcodes.DRETURN:
            return Opcodes.DCONST_0;
         case Opcodes.ARETURN:
            return Opcodes.ACONST_NULL;
         default:
            return Opcodes.NOP;
      }
   }

   static byte[] inject(final String className, byte[] classBytes) {
      dump(className, classBytes);

      final boolean[] present = presentSites(className, classBytes);
      int count = 0;
      for (boolean site : present) {
         if (site) {
            count++;
         }
      }
      if (count == 0) {
         Log.info(className + " has none of the known frame methods - left untouched");
         return null;
      }

      ClassReader reader = new ClassReader(classBytes);
      ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
         @Override
         protected String getCommonSuperClass(String type1, String type2) {
            // Obfuscated game classes are not loadable here; Object is a safe merge for a prologue.
            try {
               return super.getCommonSuperClass(type1, type2);
            } catch (Throwable t) {
               return "java/lang/Object";
            }
         }
      };

      final StringBuilder injected = new StringBuilder();
      reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
         @Override
         public MethodVisitor visitMethod(final int access, String name, String descriptor, String signature,
               String[] exceptions) {
            MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
            final int site = siteOf(className, name, descriptor);
            if (site < 0) {
               return delegate;
            }
            final String hookMode = targets[site][3];
            final String beforeCall = hookMode.startsWith(MODE_BEFORE_CALL + ":")
               ? hookMode.substring(MODE_BEFORE_CALL.length() + 1) : null;
            injected.append(injected.length() == 0 ? "" : ", ").append(name).append(descriptor)
               .append('@').append(hookMode);

            return new MethodVisitor(Opcodes.ASM9, delegate) {
               @Override
               public void visitCode() {
                  super.visitCode();
                  if (MODE_CHAT.equals(hookMode)) {
                     injectChatAbort(this, access, site);
                     return;
                  }
                  if (!MODE_HEAD.equals(hookMode)) {
                     return;
                  }
                  Label start = new Label();
                  Label end = new Label();
                  Label handler = new Label();
                  Label after = new Label();

                  // A client may refuse to resolve the bridge class (child-first loader with no
                  // parent fallback). Swallowing that here keeps it a silent no-op instead of a
                  // crashed render loop - the same contract the sink itself honours.
                  super.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
                  super.visitLabel(start);
                  pushSelf(this, access);
                  super.visitLdcInsn(Integer.valueOf(site));
                  super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_NAME, HOOK_DESC, false);
                  super.visitLabel(end);
                  super.visitJumpInsn(Opcodes.GOTO, after);
                  super.visitLabel(handler);
                  super.visitInsn(Opcodes.POP);
                  super.visitLabel(after);
               }

               @Override
               public void visitMethodInsn(int opcode, String owner, String name,
                     String descriptor, boolean isInterface) {
                  if (beforeCall != null && className.equals(owner) && beforeCall.equals(name)
                        && "()V".equals(descriptor)) {
                     // Same shape as every other injected call: a throwing hook must not take the
                     // caller down, so the call sits inside its own try/catch.
                     Label start = new Label();
                     Label end = new Label();
                     Label handler = new Label();
                     Label after = new Label();

                     super.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
                     super.visitLabel(start);
                     pushSelf(this, access);
                     super.visitLdcInsn(Integer.valueOf(site));
                     super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_NAME, HOOK_DESC, false);
                     super.visitLabel(end);
                     super.visitJumpInsn(Opcodes.GOTO, after);
                     super.visitLabel(handler);
                     super.visitInsn(Opcodes.POP);
                     super.visitLabel(after);
                  }
                  super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
               }

               @Override
               public void visitInsn(int opcode) {
                  if ((MODE_RETURN.equals(hookMode) || MODE_RETURN_BLOCK.equals(hookMode))
                        && isReturn(opcode)) {
                     Label start = new Label();
                     Label end = new Label();
                     Label fail = new Label();
                     Label after = new Label();

                     super.visitTryCatchBlock(start, end, fail, "java/lang/Throwable");
                     super.visitLabel(start);
                     if (MODE_RETURN_BLOCK.equals(hookMode)) {
                        // Operand stack here is exactly [int returnValue]. DUP feeds the value to
                        // the hook; local 3 is the ItemStack argument of onPlayerRightClick.
                        // Both are consumed by the call, so the original IRETURN still sees its own
                        // return value untouched.
                        super.visitInsn(Opcodes.DUP);
                        super.visitVarInsn(Opcodes.ALOAD, 3);
                        super.visitLdcInsn(Integer.valueOf(site));
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER, ITEM_NAME, ITEM_DESC, false);
                     } else {
                        pushSelf(this, access);
                        super.visitLdcInsn(Integer.valueOf(site));
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_NAME, HOOK_DESC, false);
                     }
                     super.visitLabel(end);
                     super.visitJumpInsn(Opcodes.GOTO, after);
                     // Failure path: the hook is unavailable, so hand the method a neutral result
                     // and let it return normally rather than propagate into the caller.
                     super.visitLabel(fail);
                     super.visitInsn(Opcodes.POP);
                     if (opcode != Opcodes.RETURN) {
                        super.visitInsn(defaultValue(opcode));
                     }
                     super.visitInsn(opcode);
                     super.visitLabel(after);
                  }
                  super.visitInsn(opcode);
               }
            };
         }
      }, 0);

      Log.info("injected into " + className + ": " + injected);
      return writer.toByteArray();
   }
}
