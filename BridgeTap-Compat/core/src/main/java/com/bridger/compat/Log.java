package com.bridger.compat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Minimal logger so the agent never depends on the client's logging stack.
 *
 * <p>Clients launch with {@code javaw.exe}, which has no console: anything written only to
 * {@code System.out} is lost. Every line is therefore mirrored to
 * {@code %LOCALAPPDATA%\BridgeTap\agent.log}, which is the only reliable way to debug an injection
 * on a real client.
 *
 * <p>Only {@code java.base} APIs may be used here — this class is loaded by the bootstrap loader.
 */
public final class Log {
   private static final String PREFIX = "[BridgeTap] ";
   private static final PrintStream FILE = openLog();
   /** Mirror next to the client's own logs, because a javaw launch has no console to read. */
   private static final PrintStream GAME = openGameLog();
   private static volatile boolean verbose = Boolean.getBoolean("bridgetap.verbose");

   private Log() {
   }

   private static PrintStream openLog() {
      try {
         String base = System.getenv("LOCALAPPDATA");
         if (base == null || base.isEmpty()) {
            base = System.getProperty("java.io.tmpdir");
         }
         File dir = new File(base, "BridgeTap");
         if (!dir.isDirectory() && !dir.mkdirs()) {
            return null;
         }
         OutputStream out = new FileOutputStream(new File(dir, "agent.log"), true);
         return new PrintStream(out, true, StandardCharsets.UTF_8.name());
      } catch (Throwable t) {
         return null;
      }
   }

   /**
    * {@code <appdata>\.minecraft\logs\bridgetap.log}.
    *
    * <p>Clients launched with {@code javaw.exe} discard stdout, so the only place a user thinks to
    * look - the folder holding the client's own logs - otherwise never mentions this mod at all.
    * Truncated per launch: it is meant to answer "what happened during this launch".
    */
   private static PrintStream openGameLog() {
      try {
         String appData = System.getenv("APPDATA");
         if (appData == null || appData.isEmpty()) {
            return null;
         }
         File logs = new File(new File(appData, ".minecraft"), "logs");
         if (!logs.isDirectory()) {
            return null;
         }
         OutputStream out = new FileOutputStream(new File(logs, "bridgetap.log"), false);
         return new PrintStream(out, true, StandardCharsets.UTF_8.name());
      } catch (Throwable t) {
         return null;
      }
   }

   public static void setVerbose(boolean value) {
      verbose = value;
   }

   private static void emit(String line) {
      System.out.println(line);
      PrintStream file = FILE;
      if (file != null) {
         file.println(line);
      }
      PrintStream game = GAME;
      if (game != null) {
         game.println(line);
      }
   }

   public static void verbose(String message) {
      if (verbose) {
         emit(PREFIX + message);
      }
   }

   public static void info(String message) {
      emit(PREFIX + message);
   }

   public static void warn(String message) {
      emit(PREFIX + "WARN " + message);
   }
}
