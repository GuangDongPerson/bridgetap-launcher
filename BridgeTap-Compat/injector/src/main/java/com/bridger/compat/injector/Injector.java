package com.bridger.compat.injector;

import com.sun.tools.attach.AgentInitializationException;
import com.sun.tools.attach.AgentLoadException;
import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/** Discovers running JVMs and loads the agent into one of them. */
final class Injector {
   enum Kind {
      LUNAR("Lunar Client", 2),
      BADLION("Badlion Client", 2),
      MINECRAFT("Minecraft", 1),
      OTHER("其他 Java 进程", 0),
      NOISE("构建/IDE 进程", -1);

      final String label;
      final int rank;

      Kind(String label, int rank) {
         this.label = label;
         this.rank = rank;
      }

      boolean isGame() {
         return this.rank >= 1;
      }
   }

   static final class Target {
      final String pid;
      final String title;
      final String command;
      final Kind kind;

      Target(String pid, String title, String command, Kind kind) {
         this.pid = pid;
         this.title = title;
         this.command = command;
         this.kind = kind;
      }

      /** Window title when we have one, otherwise a trimmed command line. */
      String summary() {
         if (this.title != null && !this.title.isEmpty()) {
            return this.title;
         }
         String text = this.command == null ? "" : this.command;
         return text.length() <= 100 ? text : text.substring(0, 97) + "...";
      }

      @Override
      public String toString() {
         return "[" + this.kind.label + "] pid " + this.pid + "   " + summary();
      }
   }

   interface Sink {
      void log(String line);
   }

   private Injector() {
   }

   static List<Target> list() {
      // Two independent sources, each covering the other's blind spot:
      //  - ProcessHandle sees every process, but its commandLine() is empty for these clients
      //  - tasklist always has the image name (java.exe vs Lunar Client.exe) and the window title
      Map<String, ProcessScan.ProcInfo> procs = ProcessScan.snapshot();

      Map<String, String> attachNames = new HashMap<String, String>();
      try {
         for (VirtualMachineDescriptor descriptor : VirtualMachine.list()) {
            attachNames.put(descriptor.id(), descriptor.displayName());
         }
      } catch (Throwable ignored) {
         // attach enumeration is optional
      }

      long selfPid = ProcessHandle.current().pid();
      Map<String, Target> byPid = new LinkedHashMap<String, Target>();

      try {
         ProcessHandle.allProcesses().forEach(process -> {
            long id = process.pid();
            if (id == selfPid) {
               return;
            }
            String pid = String.valueOf(id);
            ProcessScan.ProcInfo info = procs.get(pid);
            String image = info == null ? "" : info.image;
            String title = info != null && info.hasRealTitle() ? info.title : "";
            String command = process.info().commandLine().orElse("");
            String exe = process.info().command().orElse("");
            String attachName = attachNames.containsKey(pid) ? attachNames.get(pid) : "";

            // The image name is the only trustworthy signal here: Lunar spawns eight
            // "Lunar Client.exe" Electron helpers whose paths contain "lunar" but which are not
            // JVMs at all, and the real game process has an empty command line.
            boolean javaImage = info != null && info.isJavaImage();
            boolean looksJava = javaImage
               || exe.toLowerCase(Locale.ROOT).contains("javaw")
               || exe.toLowerCase(Locale.ROOT).contains("java.exe")
               || !attachName.isEmpty();
            if (!looksJava) {
               return;
            }

            Kind kind = classify(image + " " + command + " " + exe + " " + title + " " + attachName);
            if (kind == Kind.NOISE) {
               return;
            }
            String display = command.isEmpty() ? exe : command;
            byPid.put(pid, new Target(pid, title, display, kind));
         });
      } catch (Throwable ignored) {
         // fall through to the attach list
      }

      for (Map.Entry<String, String> entry : attachNames.entrySet()) {
         if (!byPid.containsKey(entry.getKey())) {
            Kind kind = classify(entry.getValue().toLowerCase(Locale.ROOT));
            if (kind != Kind.NOISE && !entry.getKey().equals(String.valueOf(selfPid))) {
               ProcessScan.ProcInfo info = procs.get(entry.getKey());
               String title = info != null && info.hasRealTitle() ? info.title : "";
               byPid.put(entry.getKey(), new Target(entry.getKey(), title, entry.getValue(), kind));
            }
         }
      }

      List<Target> out = new ArrayList<Target>(byPid.values());
      // Games first; then the process that actually owns a window (i.e. the running client, not a
      // helper); then pid order so the list is stable between refreshes.
      out.sort((a, b) -> {
         int byRank = Integer.compare(b.kind.rank, a.kind.rank);
         if (byRank != 0) {
            return byRank;
         }
         boolean aTitled = a.title != null && !a.title.isEmpty();
         boolean bTitled = b.title != null && !b.title.isEmpty();
         if (aTitled != bTitled) {
            return aTitled ? -1 : 1;
         }
         return Long.compare(parsePid(a.pid), parsePid(b.pid));
      });
      return out;
   }

   private static long parsePid(String pid) {
      try {
         return Long.parseLong(pid);
      } catch (Throwable t) {
         return Long.MAX_VALUE;
      }
   }

   static List<Target> candidates() {
      List<Target> out = new ArrayList<Target>();
      for (Target target : list()) {
         if (target.kind.isGame()) {
            out.add(target);
         }
      }
      return out;
   }

   private static Kind classify(String haystack) {
      if (haystack == null) {
         return Kind.OTHER;
      }
      String lower = haystack.toLowerCase(Locale.ROOT);

      // Ourselves, and the build tooling around us. Checked first: this process' own jar path
      // contains "bridgetap-injector" and would otherwise be offered as a target.
      if (lower.contains("bridgetapinjector") || lower.contains("bridgetap-injector")
         || lower.contains("jpackage") || lower.contains("gradle") || lower.contains("kotlin")
         || lower.contains("jetbrains") || lower.contains("intellij") || lower.contains("eclipse")) {
         return Kind.NOISE;
      }
      if (lower.contains("lunar")) {
         return Kind.LUNAR;
      }
      if (lower.contains("badlion")) {
         return Kind.BADLION;
      }
      if (lower.contains("minecraft") || lower.contains("weave")) {
         return Kind.MINECRAFT;
      }
      return Kind.OTHER;
   }

   /**
    * Loads the agent into {@code target}, choosing the mechanism from what the target allows.
    *
    * <p>Lunar starts its JVM with {@code -XX:+DisableAttachMechanism}. That flag stops HotSpot from
    * creating the attach listener at all, so {@code com.sun.tools.attach} fails with "The VM does not
    * support the attach mechanism" no matter what we do. When that happens the only way in is a
    * remote thread running LoadLibraryW on our payload DLL, which then bootstraps JVMTI from inside
    * the process.
    */
   /** Set by {@code --native}: skip the attach attempt entirely. */
   static volatile boolean forceNative;

   static boolean inject(Target target, File agentJar, Sink sink) {
      sink.log("Preparing injection / 准备注入 -> pid " + target.pid + " (" + target.kind.label + ")");
      if (target.title != null && !target.title.isEmpty()) {
         sink.log("Window title / 窗口标题: " + target.title);
      }
      sink.log("agent: " + agentJar.getAbsolutePath() + "  (" + agentJar.length() / 1024 + " KB)");

      boolean disabled = target.command != null
         && (target.command.contains("DisableAttachMechanism")
            || target.command.contains("DisableAttach"));
      if (forceNative || disabled) {
         sink.log("Using native injection (remote thread + JVMTI), bypassing the attach protocol. / 走 native 注入（远程线程 + JVMTI），不经过 attach 协议。");
         return injectNative(target, agentJar, sink);
      }

      try {
         return injectAttach(target, agentJar, sink);
      } catch (AttachNotSupportedException e) {
         sink.log("attach unavailable, falling back to native injection / attach 不可用（" + e.getMessage() + "），改走 native 注入。");
         return injectNative(target, agentJar, sink);
      } catch (Throwable t) {
         // Badlion's JVM answers the attach handshake and then fails inside it with an
         // java.lang.InternalError ("Remote thread failed for unknown reason"), which is an Error
         // and therefore escapes every checked-exception branch. Any failure here means the same
         // thing: this channel is closed, use the remote-thread one.
         sink.log("attach channel failed, falling back to native injection / attach 通道失败（" + t.getClass().getName() + ": " + t.getMessage()
            + "），改走 native 注入。");
         return injectNative(target, agentJar, sink);
      }
   }

   private static boolean injectAttach(Target target, File agentJar, Sink sink) throws AttachNotSupportedException {
      VirtualMachine vm;
      try {
         vm = VirtualMachine.attach(target.pid);
      } catch (AttachNotSupportedException e) {
         throw e;
      } catch (IOException e) {
         String message = e.getMessage() == null ? "" : e.getMessage();
         if (message.contains("Access is denied") || message.contains("拒绝访问")) {
            sink.log("failed: permission denied. The client may be running as administrator, so the injector must be started as administrator too. / 失败: 权限不足。客户端可能以管理员身份运行，注入器也要用管理员身份启动。");
         } else {
            sink.log("failed: attach IO error / 失败: attach IO 异常 - " + message);
         }
         return false;
      }

      try {
         // Deploy under a fresh name every time: a loaded agent jar stays locked by the target JVM,
         // so re-using "bridgetap-agent.jar" fails on every injection after the first.
         File deployed;
         try {
            File dir = AgentPayload.deployDir();
            deployed = AgentPayload.deployAgent(agentJar, dir);
            File hud = AgentPayload.deployHud(deployed, dir);
            AgentPayload.writeBootstrapIni(dir, deployed, hud);
         } catch (IOException e) {
            sink.log("failed: extracting the agent file failed / 失败: 释放 agent 文件出错 - " + e.getMessage());
            return false;
         }

         String version = "?";
         try {
            Properties props = vm.getSystemProperties();
            version = props.getProperty("java.version", "?");
            sink.log("Target JVM / 目标 JVM: " + version + " / " + props.getProperty("java.vendor", "?"));
         } catch (Throwable t) {
            sink.log("could not read the target JVM version (injection is unaffected) / 目标 JVM 版本读取失败（不影响注入）: " + t.getMessage());
         }

         vm.loadAgent(deployed.getAbsolutePath());
         sink.log("attach injection complete. Verify with / attach 注入完成。确认方式: %LOCALAPPDATA%\\BridgeTap\\agent.log");
         return true;
      } catch (AgentLoadException e) {
         sink.log("failed: the JVM refused to load the agent. / 失败: agent 被 JVM 拒绝加载。 " + e.getMessage());
         return false;
      } catch (AgentInitializationException e) {
         sink.log("failed: the agent loaded but threw internally, returnValue= / 失败: agent 已载入但内部抛异常，returnValue=" + e.returnValue());
         return false;
      } catch (IOException | RuntimeException e) {
         sink.log("failed / 失败: " + e.getClass().getSimpleName() + " - " + e.getMessage());
         return false;
      } finally {
         try {
            vm.detach();
         } catch (Throwable ignored) {
            // detach failures are irrelevant to the injection result
         }
      }
   }

   /**
    * Remote thread injection. Works when the attach mechanism is disabled, because it never speaks
    * the attach protocol: OpenProcess -> VirtualAllocEx -> WriteProcessMemory ->
    * CreateRemoteThread(LoadLibraryW on our payload DLL).
    */
   private static boolean injectNative(Target target, File agentJar, Sink sink) {
      try {
         File dir = AgentPayload.deployDir();
         File payload = AgentPayload.extractNative("bridgetap-payload.dll", dir);
         File launcher = AgentPayload.extractNative("btinject.exe", dir);
         File agent = AgentPayload.deployAgent(agentJar, dir);
         File hud = AgentPayload.deployHud(agent, dir);
         AgentPayload.writeBootstrapIni(dir, agent, hud);

         sink.log("native: " + launcher.getAbsolutePath());
         sink.log("payload: " + payload.getAbsolutePath());

         Process process = new ProcessBuilder(launcher.getAbsolutePath(), target.pid,
            payload.getAbsolutePath())
            .redirectErrorStream(true)
            .start();
         String output = readAll(process.getInputStream()).trim();
         int code = process.waitFor();

         if (code == 0 && output.contains("OK")) {
            sink.log("native injection succeeded / native 注入成功: " + output);
            sink.log("Verify with %LOCALAPPDATA%\\BridgeTap\\inject-native.log and agent.log / 确认方式: %LOCALAPPDATA%\\BridgeTap\\inject-native.log 与 agent.log");
            return true;
         }
         sink.log("native injection failed / native 注入失败 (exit=" + code + "): " + (output.isEmpty() ? "(no output) / (无输出)" : output));
         explainNativeFailure(output, sink);
         return false;
      } catch (Throwable t) {
         sink.log("native injection threw / native 注入异常: " + t.getClass().getSimpleName() + " - " + t.getMessage());
         return false;
      }
   }

   private static void explainNativeFailure(String output, Sink sink) {
      if (output.contains("access-denied")) {
         sink.log("  reason: OpenProcess was denied. The client may run as administrator; reopen the injector as administrator. / 原因: OpenProcess 被拒绝。客户端可能以管理员运行，用管理员身份重开注入器。");
      } else if (output.contains("bitness")) {
         sink.log("  reason: injector and target bitness differ / 原因: 注入器与目标位数不同。");
      } else if (output.contains("payload-dll")) {
         sink.log("  reason: payload.dll or one of its dependencies is missing. Check that dist/native is complete. / 原因: payload.dll 或其依赖缺失。检查 dist/native 是否完整。");
      } else if (output.contains("create-remote-thread")) {
         sink.log("  reason: the target process refused to create a remote thread (anti-injection protection) / 原因: 目标进程拒绝创建远程线程（反注入保护）。");
      }
   }

   private static String readAll(InputStream in) throws IOException {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      byte[] chunk = new byte[4096];
      int n;
      while ((n = in.read(chunk)) > 0) {
         buffer.write(chunk, 0, n);
      }
      return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
   }
}
