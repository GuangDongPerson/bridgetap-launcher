package com.bridger.compat.injector;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads back what the injected agent actually did.
 *
 * <p>A successful injection only means the DLL loaded; whether a hook ever ran is a separate fact
 * that lives in {@code hook.log} and {@code agent.log}. Showing that verdict in the injector turns
 * "it says injected but nothing happens" into a concrete diagnosis without a second round trip.
 */
final class Diagnose {
   /** One line of verdict plus the evidence it was drawn from. */
   static final class Result {
      final boolean ok;
      final String headline;
      final List<String> detail;

      Result(boolean ok, String headline, List<String> detail) {
         this.ok = ok;
         this.headline = headline;
         this.detail = detail;
      }
   }

   private Diagnose() {
   }

   static File stateDir() {
      String base = System.getenv("LOCALAPPDATA");
      if (base == null || base.isEmpty()) {
         base = System.getProperty("java.io.tmpdir");
      }
      return new File(base, "BridgeTap");
   }

   /**
    * File offsets taken before injecting.
    *
    * <p>Without this the verdict would read the previous session's lines and declare success on a
    * hook that this run never installed - which is precisely the failure that started this round.
    */
   static final class Baseline {
      final long hook;
      final long agent;
      final long hud;

      Baseline(long hook, long agent, long hud) {
         this.hook = hook;
         this.agent = agent;
         this.hud = hud;
      }
   }

   static Baseline capture() {
      return new Baseline(lengthOf(new File(stateDir(), "hook.log")),
         lengthOf(new File(stateDir(), "agent.log")),
         lengthOf(new File(stateDir(), "hud.log")));
   }

   private static long lengthOf(File file) {
      return file.isFile() ? file.length() : -1L;
   }

   /** Waits up to {@code millis} for the agent to report activity, then summarises. */
   static Result afterInjection(Baseline baseline, long millis) {
      List<String> detail = new ArrayList<String>();
      File hookLog = new File(stateDir(), "hook.log");
      File agentLog = new File(stateDir(), "agent.log");
      long startHook = baseline == null ? -1L : baseline.hook;
      long startAgent = baseline == null ? -1L : baseline.agent;

      long deadline = System.currentTimeMillis() + millis;
      while (System.currentTimeMillis() < deadline) {
         long size = lengthOf(hookLog);
         if (size > Math.max(startHook, 0L)) {
            break;
         }
         try {
            Thread.sleep(150L);
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
         }
      }

      // The first hook hit lands before the agent has finished logging the HUD and game discovery;
      // reading immediately reports "HUD not loaded" for an agent that is about to log exactly that.
      try {
         Thread.sleep(1500L);
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
      }

      List<String> hook = tail(hookLog, startHook, 24);
      List<String> agent = tail(agentLog, startAgent, 60);

      for (String line : hook) {
         if (line.contains("first hit")) {
            detail.add(line);
         }
      }
      for (String line : agent) {
         if (line.contains("frame source") || line.contains("overlay drawing")
               || line.contains("hud loaded") || line.contains("bound game object")
               || line.contains("first tick sample") || line.contains("overlay draw failed")
               || line.contains("hud load attempt") || line.contains("hud unavailable")
               || line.contains("has none of the known frame methods") || line.contains("injected into")) {
            detail.add(line);
         }
      }

      // Site 6 is the payload's own probe, not a client frame: it proves the class chain resolves,
      // which is a different claim from "the game calls us every frame".
      boolean siteHit = false;
      for (String line : hook) {
         if (line.contains("first hit") && !line.contains("native-probe")) {
            siteHit = true;
            break;
         }
      }
      boolean probeHit = false;
      for (String line : hook) {
         if (line.contains("native-probe")) {
            probeHit = true;
            break;
         }
      }
      List<String> hud = tail(new File(stateDir(), "hud.log"),
         baseline == null ? -1L : baseline.hud, 24);

      boolean hudLoaded = false;
      boolean hudDrawn = false;
      boolean playerBound = false;
      for (String line : agent) {
         if (line.contains("hud loaded")) {
            hudLoaded = true;
         }
         if (line.contains("overlay drawing")) {
            hudDrawn = true;
         }
         if (line.contains("bound game object") || line.contains("first tick sample")) {
            playerBound = true;
         }
      }
      for (String line : hud) {
         if (line.contains("hud drawn") || line.contains("hud ready notice")) {
            hudDrawn = true;
         }
         if (line.contains("GL overlay failure")) {
            detail.add(line);
         }
      }

      String headline;
      boolean ok;
      if (siteHit && hudDrawn && playerBound) {
         ok = true;
         headline = "已生效：HUD 正在绘制，玩家位置已绑定（搭桥即出数据）";
      } else if (siteHit && hudDrawn) {
         ok = true;
         headline = "已生效：HUD 正在绘制，但还没进游戏世界（面板显示 waiting for world）";
      } else if (siteHit && hudLoaded) {
         ok = true;
         headline = "已生效：帧回调命中，HUD 已加载但尚未画出第一帧（看 agent.log）";
      } else if (siteHit) {
         ok = true;
         headline = "已生效：帧回调命中，但 GL 覆盖层未加载（查 agent.log 的 hud 行）";
      } else if (probeHit) {
         ok = false;
         headline = "注入链路正常但客户端未调用任何注入点（探针命中、帧回调 0 次）";
      } else if (!hookLog.isFile() || lengthOf(hookLog) <= 0L) {
         ok = false;
         headline = "未见帧回调：hook.log 未生成（注入点未被调用或类解析失败）";
      } else {
         ok = false;
         headline = "未见帧回调：注入点未被客户端调用";
      }
      return new Result(ok, headline, detail);
   }

   /** Last {@code lines} lines that start at or after {@code fromOffset}; whole file when negative. */
   /**
    * One-line live status, or null when nothing has been recorded yet.
    *
    * <p>Used while the player is still in a menu: the frame loop only starts once a world loads, so
    * a one-shot check right after injection will legitimately see nothing.
    */
   static String snapshot(Baseline baseline) {
      List<String> hook = tail(new File(stateDir(), "hook.log"), baseline == null ? -1L : baseline.hook, 400);
      int[] hits = new int[SITE_LABELS.length];
      int best = -1;
      for (String line : hook) {
         int site = siteOf(line);
         if (site < 0) {
            continue;
         }
         if (line.contains("first hit") || line.contains("hits=")) {
            hits[site]++;
            if (site != PROBE_SITE) {
               best = site;
            }
         }
      }
      if (best < 0) {
         return null;
      }
      int total = 0;
      for (int i = 0; i < hits.length; i++) {
         if (i != PROBE_SITE) {
            total += hits[i];
         }
      }
      return "帧回调已命中 site " + best + "（" + SITE_LABELS[best] + "），记录 " + total + " 条";
   }

   /** Index reserved by the payload for its own out-of-band probe. */
   static final int PROBE_SITE = 5;

   /** Must match {@code Bootstrap.buildTargets} order, plus the payload's probe. */
   static final String[] SITE_LABELS = {
      "gui.renderGameOverlay", "client.runTick", "PlayerController.onPlayerRightClick",
      "client.updateDisplay", "Display.swapBuffers", "probe"
   };

   /** Site index from a "[hook] site N (...)" line, or -1. */
   private static int siteOf(String line) {
      int marker = line.indexOf("site ");
      if (marker < 0) {
         return -1;
      }
      int i = marker + 5;
      int start = i;
      while (i < line.length() && Character.isDigit(line.charAt(i))) {
         i++;
      }
      if (i == start) {
         return -1;
      }
      try {
         int site = Integer.parseInt(line.substring(start, i));
         return site >= 0 && site < SITE_LABELS.length ? site : -1;
      } catch (NumberFormatException e) {
         return -1;
      }
   }

   private static List<String> tail(File file, long fromOffset, int lines) {
      List<String> out = new ArrayList<String>();
      if (file == null || !file.isFile() || file.length() == 0L) {
         return out;
      }
      try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
         long length = raf.length();
         long start = fromOffset < 0L ? 0L : Math.min(fromOffset, length);
         // Cap the read: a long-running session appends heartbeats forever.
         long floor = Math.max(start, length - 32768L);
         raf.seek(floor);
         byte[] buffer = new byte[(int)(length - floor)];
         raf.readFully(buffer);
         String text = new String(buffer, StandardCharsets.UTF_8);
         // A truncated first line would be a partial record; drop it unless we started at 0.
         String[] all = text.split("\\r?\\n");
         int from = Math.max(0, all.length - lines);
         for (int i = from; i < all.length; i++) {
            if (!all[i].isEmpty()) {
               out.add(all[i]);
            }
         }
      } catch (IOException ignored) {
         // a diagnostic must not fail the injection
      }
      return out;
   }
}
