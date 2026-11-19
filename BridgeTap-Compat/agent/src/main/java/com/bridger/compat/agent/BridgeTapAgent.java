package com.bridger.compat.agent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.jar.JarFile;

/**
 * Thin java-agent entry point. All it does is put the agent jar on the bootstrap classloader search
 * and hand control to {@link Bootstrap}, which is then loaded <em>by the bootstrap loader</em>.
 *
 * <p>Why the indirection: the class that gets injected into {@code Display.update()} is
 * {@code com.bridger.compat.boot.Hook}, and the client's game classes live in a child-first,
 * isolated loader. For the injected call to resolve, Hook must come from the bootstrap loader. For
 * {@code Hook.setSink(...)} to type-check, the sink implementation must come from the same loader.
 * Therefore absolutely everything moves to the bootstrap loader in one step.
 *
 * <p>This class itself may only reference {@code java.base} and {@code java.instrument}.
 */
public final class BridgeTapAgent {
   private BridgeTapAgent() {
   }

   public static void premain(String args, Instrumentation inst) {
      boot(args, inst, "premain");
   }

   public static void agentmain(String args, Instrumentation inst) {
      boot(args, inst, "attach");
   }

   private static void boot(String args, Instrumentation inst, String mode) {
      try {
         if (args != null && args.contains("verbose=true")) {
            com.bridger.compat.Log.setVerbose(true);
         }
         com.bridger.compat.Log.info("agent " + mode + " entry (java " + System.getProperty("java.version") + ")");

         File jar = selfJar();
         if (jar == null) {
            com.bridger.compat.Log.warn("cannot locate own jar; bootstrap append skipped");
            return;
         }
         inst.appendToBootstrapClassLoaderSearch(new JarFile(jar));
         com.bridger.compat.Log.info("appended to bootstrap search: " + jar.getName());

         // Loaded with the bootstrap loader explicitly (null), not the loader that loaded us.
         Class<?> entry = Class.forName("com.bridger.compat.agent.Bootstrap", true, null);
         entry.getMethod("run", Instrumentation.class).invoke(null, inst);
      } catch (Throwable t) {
         com.bridger.compat.Log.warn("agent " + mode + " failed: " + t);
      }
   }

   /**
    * The agent jar itself. Both the attach API and {@code -javaagent} load the agent from a jar, so
    * the code source is a real file. If it is not (an exotic launcher), there is nothing sensible to
    * append and the caller degrades to instrumenting only the caller's own loader.
    */
   private static File selfJar() {
      try {
         java.security.CodeSource source = BridgeTapAgent.class.getProtectionDomain().getCodeSource();
         if (source != null && source.getLocation() != null) {
            File file = new File(source.getLocation().toURI());
            if (file.isFile()) {
               return file;
            }
         }
      } catch (Throwable ignored) {
         // reported by the caller
      }
      return null;
   }
}
