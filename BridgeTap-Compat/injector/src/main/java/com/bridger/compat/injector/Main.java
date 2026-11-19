package com.bridger.compat.injector;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** CLI entry point; falls through to the GUI when launched without arguments (double-click). */
public final class Main {
   private Main() {
   }

   public static void main(String[] args) {
      // Console codepage is 936 on most Chinese Windows installs; forcing UTF-8 on our own streams
      // keeps redirected logs and CI output readable without touching the user's console.
      try {
         java.io.PrintStream out = new java.io.PrintStream(
            new java.io.FileOutputStream(java.io.FileDescriptor.out), true, java.nio.charset.StandardCharsets.UTF_8);
         java.io.PrintStream err = new java.io.PrintStream(
            new java.io.FileOutputStream(java.io.FileDescriptor.err), true, java.nio.charset.StandardCharsets.UTF_8);
         System.setOut(out);
         System.setErr(err);
      } catch (Throwable ignored) {
         // keep the default streams
      }

      List<String> flags = new ArrayList<String>();
      String pid = null;
      String agentOverride = null;

      for (int i = 0; i < args.length; i++) {
         String arg = args[i];
         flags.add(arg);
         if ("--pid".equals(arg) && i + 1 < args.length) {
            pid = args[++i];
         } else if ("--agent".equals(arg) && i + 1 < args.length) {
            agentOverride = args[++i];
         }
      }

      if (flags.contains("--native")) {
         Injector.forceNative = true;
      }

      if (flags.contains("--list")) {
         printList();
         return;
      }

      if (flags.contains("--pid") || flags.contains("--auto")) {
         Log console = new Log(true);
         try {
            File agent = agentOverride != null ? new File(agentOverride) : AgentPayload.extract();
            Injector.Target target = pid != null ? findByPid(pid) : autoTarget(console);
            if (target == null) {
               System.exit(1);
            }
            Diagnose.Baseline baseline = Diagnose.capture();
            boolean ok = Injector.inject(target, agent, console);
            if (ok) {
               console.log("");
               console.log("Injection delivered, waiting for a client frame callback ... / 注入已送达，等待客户端帧回调 ...");
               Diagnose.Result result = Diagnose.afterInjection(baseline, 12_000L);
               for (String line : result.detail) {
                  console.log("  " + line);
               }
               console.log("== " + result.headline + " ==");
            }
            System.exit(ok ? 0 : 2);
         } catch (IOException e) {
            console.log("failed / 失败: " + e.getMessage());
            System.exit(2);
         }
      }

      InjectorFrame.showGui();
   }

   private static void printList() {
      List<Injector.Target> targets = Injector.list();
      System.out.println("Java processes / Java 进程: " + targets.size());
      for (Injector.Target target : targets) {
         System.out.println("  " + target.pid + "\t" + target.kind.label + "\t" + target.summary());
      }
   }

   private static Injector.Target findByPid(String pid) {
      for (Injector.Target target : Injector.list()) {
         if (target.pid.equals(pid)) {
            return target;
         }
      }
      System.out.println("pid " + pid + " not found, use --list to see the available processes. / 找不到 pid " + pid + "，用 --list 查看可用进程。");
      return null;
   }

   private static Injector.Target autoTarget(Log log) {
      List<Injector.Target> candidates = Injector.candidates();
      if (candidates.size() == 1) {
         return candidates.get(0);
      }
      if (candidates.isEmpty()) {
         log.log("No Lunar / Badlion / Minecraft process found. Start the client and get to the main menu first. / 没有发现 Lunar / Badlion / Minecraft 进程。先把客户端开到主界面再试。");
         log.log("Hint: --list shows every Java process. / 提示: 用 --list 可以看到全部 Java 进程。");
         return null;
      }
      log.log("Several candidate processes found, pick one with --pid: / 发现多个候选进程，请用 --pid 指定：");
      for (Injector.Target target : candidates) {
         log.log("  " + target.pid + "  " + target.kind.label + "  " + target.summary());
      }
      return null;
   }

   /** Console + file logger shared by both front ends. */
   static final class Log implements Injector.Sink {
      private final boolean console;
      private PrintWriter file;

      Log(boolean console) {
         this.console = console;
         try {
            File dir = new File(System.getenv("LOCALAPPDATA") != null
               ? System.getenv("LOCALAPPDATA") : System.getProperty("java.io.tmpdir"), "BridgeTap");
            dir.mkdirs();
            this.file = new PrintWriter(new FileWriter(new File(dir, "inject.log"), true));
         } catch (Throwable ignored) {
            this.file = null;
         }
      }

      @Override
      public void log(String line) {
         String stamped = LocalDateTime.now().withNano(0) + "  " + line;
         if (this.console) {
            System.out.println(line);
         }
         if (this.file != null) {
            this.file.println(stamped);
            this.file.flush();
         }
      }

      void close() {
         if (this.file != null) {
            this.file.close();
         }
      }
   }
}
