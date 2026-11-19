package com.bridger.compat.agent;

import com.bridger.compat.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One-shot reconnaissance, callable even after the agent has already installed itself.
 *
 * <p>Class names in a client like Lunar cannot be read off disk: ichor remaps the 1.8.9 classes at
 * load time from {@code .kin} mapping files, so the on-disk jars contain neither
 * {@code org.lwjgl.opengl.Display} nor the game's own classes. Only the live JVM knows the truth.
 *
 * <p>This class carries a name that never existed in earlier builds, so a fresh injection into an
 * already-instrumented JVM still gets to run it — {@link Bootstrap} itself is pinned to whichever jar
 * defined it first, but a new class name is loaded from the jar that was just appended.
 */
public final class ClassMapDump {
   private ClassMapDump() {
   }

   /** Writes classes.txt and returns a one-line summary (also logged). */
   public static String run() {
      try {
         Class<?>[] classes = Bootstrap.allLoadedClasses();
         if (classes == null || classes.length == 0) {
            Log.warn("class map: no class snapshot available");
            return "no classes";
         }

         List<String> lines = new ArrayList<String>(classes.length);
         int displayCopies = 0;
         int minecraftish = 0;
         for (Class<?> type : classes) {
            ClassLoader loader = type.getClassLoader();
            String owner = loader == null ? "bootstrap"
               : loader.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(loader));
            String name = type.getName();
            lines.add(name + "\t" + owner);
            if ("org.lwjgl.opengl.Display".equals(name)) {
               displayCopies++;
            }
            if (name.contains("Minecraft") || name.endsWith(".Minecraft")) {
               minecraftish++;
            }
         }
         Collections.sort(lines);

         File dir = new File(logBase(), "BridgeTap/dump");
         if (!dir.isDirectory() && !dir.mkdirs()) {
            return "cannot create " + dir;
         }
         File out = new File(dir, "classes.txt");
         try (PrintWriter writer = new PrintWriter(
               new OutputStreamWriter(new FileOutputStream(out), "UTF-8"))) {
            for (String line : lines) {
               writer.println(line);
            }
         }
         String summary = classes.length + " classes, " + displayCopies + " Display cop(ies), "
            + minecraftish + " minecraft-named -> " + out.getAbsolutePath();
         Log.info("class map: " + summary);
         return summary;
      } catch (Throwable t) {
         Log.warn("class map failed: " + t);
         return "failed: " + t;
      }
   }

   private static String logBase() {
      String base = System.getenv("LOCALAPPDATA");
      if (base == null || base.isEmpty()) {
         base = System.getProperty("java.io.tmpdir");
      }
      return base;
   }
}
