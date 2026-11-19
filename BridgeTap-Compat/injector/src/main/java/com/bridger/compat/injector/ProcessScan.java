package com.bridger.compat.injector;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads process window titles from {@code tasklist}.
 *
 * <p>Needed because {@code ProcessHandle.Info.commandLine()} comes back empty for exactly the
 * processes we care about: Lunar's JVM runs with its own hand-picked flags and the platform refuses
 * to hand us the PEB strings, so pid 21664 shows up with no command line at all. The window title is
 * still readable, and for a Minecraft client it is the single most useful identifier:
 *
 * <pre>"javaw.exe","21664","Console","1","2,742,300 K","Running","PC\\user","0:05:21","Lunar Client 1.8.9 (v2.23.15-2645)"</pre>
 *
 * <p>{@code tasklist /v /fo csv} always emits the same column order, with the title last. Column
 * <em>names</em> are localised, so they are not used for lookup.
 */
final class ProcessScan {
   private ProcessScan() {
   }

   /** One row of {@code tasklist /v}: the image name decides if it is a JVM, the title identifies it. */
   static final class ProcInfo {
      final String image;
      final String title;

      ProcInfo(String image, String title) {
         this.image = image;
         this.title = title;
      }

      boolean isJavaImage() {
         String lower = this.image.toLowerCase(java.util.Locale.ROOT);
         return lower.equals("java.exe") || lower.equals("javaw.exe");
      }

      /** tasklist writes a placeholder instead of a title for windows-less processes. */
      boolean hasRealTitle() {
         return this.title != null && !this.title.isEmpty()
            && !"暂缺".equals(this.title) && !"N/A".equalsIgnoreCase(this.title)
            && !this.title.startsWith("OleMainThread")
            && !this.title.startsWith("DDE Server Window")
            && !this.title.startsWith("GDI+ Window");
      }
   }

   /** pid -> image name and window title, for every process the current user can see. */
   static Map<String, ProcInfo> snapshot() {
      Map<String, ProcInfo> out = new HashMap<String, ProcInfo>();
      Process process = null;
      try {
         process = new ProcessBuilder("tasklist", "/v", "/fo", "csv", "/nh")
            .redirectErrorStream(false)
            .start();
         byte[] raw = readAll(process.getInputStream());
         String text = new String(raw, consoleCharset());
         for (String line : text.split("\\r?\\n")) {
            if (line.isEmpty()) {
               continue;
            }
            List<String> columns = splitCsv(line);
            if (columns.size() < 9) {
               continue;
            }
            String image = columns.get(0).trim();
            String pid = columns.get(1).trim();
            String title = columns.get(columns.size() - 1).trim();
            if (!pid.isEmpty()) {
               out.put(pid, new ProcInfo(image, title));
            }
         }
      } catch (Throwable ignored) {
         // titles are a nicety; enumeration still works without them
      } finally {
         if (process != null) {
            try {
               process.waitFor();
            } catch (InterruptedException ignored) {
               Thread.currentThread().interrupt();
            }
         }
      }
      return out;
   }

   /** The console codepage, not UTF-8: tasklist writes in the system ANSI codepage. */
   private static Charset consoleCharset() {
      String[] candidates = {
         System.getProperty("native.encoding"),
         System.getProperty("sun.jnu.encoding"),
         System.getProperty("file.encoding")
      };
      for (String name : candidates) {
         if (name == null || name.isEmpty()) {
            continue;
         }
         try {
            Charset charset = Charset.forName(name);
            // A UTF-8 console would mangle the GBK bytes tasklist emits on zh-CN systems.
            if (charset.name().startsWith("UTF")) {
               continue;
            }
            return charset;
         } catch (Throwable ignored) {
            // try the next one
         }
      }
      try {
         return Charset.forName("GBK");
      } catch (Throwable ignored) {
         return Charset.defaultCharset();
      }
   }

   private static byte[] readAll(InputStream in) throws java.io.IOException {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      byte[] chunk = new byte[8192];
      int n;
      while ((n = in.read(chunk)) > 0) {
         buffer.write(chunk, 0, n);
      }
      return buffer.toByteArray();
   }

   /** RFC 4180-ish split: commas inside double quotes do not separate columns. */
   private static List<String> splitCsv(String line) {
      List<String> out = new ArrayList<String>();
      StringBuilder field = new StringBuilder();
      boolean quoted = false;
      for (int i = 0; i < line.length(); i++) {
         char c = line.charAt(i);
         if (c == '"') {
            if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
               field.append('"');
               i++;
            } else {
               quoted = !quoted;
            }
         } else if (c == ',' && !quoted) {
            out.add(field.toString());
            field.setLength(0);
         } else {
            field.append(c);
         }
      }
      out.add(field.toString());
      return out;
   }
}
