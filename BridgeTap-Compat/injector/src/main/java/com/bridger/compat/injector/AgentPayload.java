package com.bridger.compat.injector;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Materialises the embedded agent jar and native binaries, and returns their paths. */
final class AgentPayload {
   private static final String AGENT_RESOURCE = "com/bridger/compat/injector/payload/agent.jar";
   private static final String NATIVE_PREFIX = "com/bridger/compat/injector/native/";
   private static final String HUD_ENTRY = "com/bridger/compat/agent/hud.jar";

   private AgentPayload() {
   }

   /** %LOCALAPPDATA%\BridgeTap\inject — shared by the agent's own log and the payload's. */
   static File deployDir() throws IOException {
      File dir = new File(System.getenv("LOCALAPPDATA") != null
         ? System.getenv("LOCALAPPDATA")
         : System.getProperty("java.io.tmpdir"), "BridgeTap/inject");
      if (!dir.isDirectory() && !dir.mkdirs()) {
         throw new IOException("cannot create " + dir);
      }
      return dir;
   }

   static File extract() throws IOException {
      File out = new File(deployDir(), "bridgetap-agent-" + uniqueStamp() + ".jar");
      try (InputStream in = AgentPayload.class.getClassLoader().getResourceAsStream(AGENT_RESOURCE)) {
         if (in == null) {
            throw new IOException("embedded agent missing from injector jar");
         }
         Files.copy(in, out.toPath(), StandardCopyOption.REPLACE_EXISTING);
      }
      return out;
   }

   /**
    * Copies the agent jar under a name that is unique per injection.
    *
    * <p>A running target keeps the jar open: {@code AddToBootstrapClassLoaderSearch} holds a handle
    * on it for the lifetime of the JVM. Re-using the same filename therefore fails with "the file is
    * being used by another process" on every injection after the first. A per-call name avoids the
    * conflict entirely; the payload picks the exact path up from bootstrap.ini.
    */
   static File deployAgent(File source, File dir) throws IOException {
      // extract() already produced a uniquely named copy in this directory.
      if (source.getParentFile() != null && source.getParentFile().equals(dir)
         && source.getName().startsWith("bridgetap-agent-")) {
         return source;
      }
      File out = new File(dir, "bridgetap-agent-" + uniqueStamp() + ".jar");
      Files.copy(source.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
      return out;
   }

   /** Unpacks {@code btinject.exe} or {@code bridgetap-payload.dll} next to the agent jar. */
   static File extractNative(String name, File dir) throws IOException {
      File out = new File(dir, name);
      try {
         try (InputStream in = AgentPayload.class.getClassLoader().getResourceAsStream(NATIVE_PREFIX + name)) {
            if (in == null) {
               throw new IOException("embedded " + name + " missing from injector jar"
                  + " (run native/build.sh before packaging)");
            }
            Files.copy(in, out.toPath(), StandardCopyOption.REPLACE_EXISTING);
         }
         return out;
      } catch (IOException e) {
         // Loaded payload DLLs and running helpers stay locked; fall back to a fresh name.
         File alt = new File(dir, name.replaceFirst("(\\.[^.]+)$", "-" + uniqueStamp() + "$1"));
         try (InputStream in = AgentPayload.class.getClassLoader().getResourceAsStream(NATIVE_PREFIX + name)) {
            if (in == null) {
               throw e;
            }
            Files.copy(in, alt.toPath(), StandardCopyOption.REPLACE_EXISTING);
         }
         return alt;
      }
   }

   /**
    * Pulls {@code hud.jar} out of the agent jar and drops it beside it.
    *
    * <p>The agent cannot read it as a classpath resource — everything in the agent jar is loaded by
    * the bootstrap loader, and resource lookups from there go through the system loader instead. So
    * the injector materialises it as a plain file, named uniquely for the same locking reason as the
    * agent jar, and the payload passes the path on to the agent.
    */
   static File deployHud(File agentJar, File dir) throws IOException {
      File out = new File(dir, "bridgetap-hud-" + uniqueStamp() + ".jar");
      try (JarFile jar = new JarFile(agentJar)) {
         JarEntry entry = jar.getJarEntry(HUD_ENTRY);
         if (entry == null) {
            throw new IOException("hud.jar missing from agent jar");
         }
         try (InputStream in = jar.getInputStream(entry)) {
            Files.copy(in, out.toPath(), StandardCopyOption.REPLACE_EXISTING);
         }
      }
      return out;
   }

   /**
    * The payload reads this from its own directory. It cannot receive parameters through
    * CreateRemoteThread, since the only thing that can be passed to LoadLibraryW is the DLL path.
    */
   static void writeBootstrapIni(File dir, File agentJar, File hudJar) throws IOException {
      File ini = new File(dir, "bootstrap.ini");
      StringBuilder content = new StringBuilder();
      content.append("agent=").append(agentJar.getAbsolutePath()).append("\r\n");
      if (hudJar != null) {
         content.append("hud=").append(hudJar.getAbsolutePath()).append("\r\n");
      }
      Files.write(ini.toPath(), content.toString().getBytes(StandardCharsets.UTF_8));
   }

   private static String uniqueStamp() {
      return Long.toHexString(System.currentTimeMillis());
   }

   static String version() {
      Package pkg = AgentPayload.class.getPackage();
      String v = pkg == null ? null : pkg.getImplementationVersion();
      return v == null ? "dev" : v;
   }
}
