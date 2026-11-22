import com.bridger.compat.BridgeDetection;
import com.bridger.compat.JumpSignals;
import com.bridger.compat.JumpEntry;
import com.bridger.compat.PlacementEvidence;
import com.bridger.compat.Tracker;

import java.util.List;

/**
 * Offline acceptance for the upstream detection logic: feed coordinates straight into Tracker's
 * public API, without starting the game.
 *
 * <p>Run it after every "upstream sync": first ./gradlew :core:compileJava, then bash tests/run.sh.
 * It currently covers four 1.5.0 behaviours: the 1.0 Flat (3 tick), the opening Late (5b/6b), the
 * placement-evidence threshold, and the 5 tick cooldown.
 *
 * 上游检测逻辑的离线验收：不开游戏，直接用 Tracker 的公开接口喂坐标。
 *
 * <p>每次「上游更新同步」后都要跑一遍：先 ./gradlew :core:compileJava，再 bash tests/run.sh。
 * 当前覆盖 1.5.0 的四处行为：1.0 Flat(3 tick)、opening Late(5b/6b)、放置证据门槛、5 tick 冷却。
 */
public final class BtSyncTest {
   private static int failures = 0;

   private static void check(String what, Object actual, Object expected) {
      boolean ok = expected == null ? actual == null : expected.equals(actual);
      if (!ok) {
         failures++;
      }
      System.out.println((ok ? "  ✓ " : "  ✗ ") + what + " → " + actual
         + (ok ? "" : " (expected " + expected + ") / （期望 " + expected + "）"));
   }

   /**
    * Drive the tracker into a void failure: keep falling until the grace window closes.
    *
    * <p>A single frame below {@code groundY - 2.5} is no longer a failure - a jump down to a lower
    * finish island crosses that line too - so a real fall now needs 50 airborne ticks. Tests that
    * only need "a failed run" use this instead of one big drop.
    *
    * 把追踪器推进到掉虚空失败：一直下坠到宽限窗口结束。
    *
    * <p>单帧跌破 {@code groundY - 2.5} 已经不算失败 —— 跳到更低的终点岛也会穿过这条线 ——
    * 真掉下去现在要连续 50 tick 不落地。只需要「一局失败状态」的测试改用这个。
    */
   private static void fallToVoid(double fromY) {
      for (int i = 1; i <= 50; i++) {
         double y = fromY - i * 0.6D;
         Tracker.noteY(y);
         Tracker.onTick(0.5D + i * 0.01D, y, 0.0, false);
      }
   }

   public static void main(String[] args) {
      // ---- 1. flatLabel table ----
      // ---- 1. flatLabel 表 ----
      System.out.println("[1] flatLabel");
      check("3 tick → 1.0 Flat", BridgeDetection.flatLabel(3, 1.05), "1.0 Flat");
      check("4 tick → 1.05 Flat", BridgeDetection.flatLabel(4, 1.05), "1.05 Flat");
      check("5 tick → 1.1 Flat", BridgeDetection.flatLabel(5, 1.05), "1.1 Flat");
      check("11 tick opening is not a 4b Flat / 11 tick 开局不是 4b Flat", BridgeDetection.flatLabel(11, 1.15), "1.15 Flat");
      check("12 tick opening is not a 4b Flat / 12 tick 开局不是 4b Flat", BridgeDetection.flatLabel(12, 2.0), "2.00 Flat");
      check("6 tick/1.2s → 1.2 Flat", BridgeDetection.flatLabel(6, 1.2), "1.2 Flat");

      // ---- 2. lateLabel rules ----
      // ---- 2. lateLabel 规则 ----
      System.out.println("[2] lateLabel");
      check("3 tick opening + 9 tick + 1 block up + 3 columns -> 5b Late / 3 tick 开局 + 9 tick + 升 1 格 + 3 列 → 5b Late",
         BridgeDetection.lateLabel(1, 3, 9, 0.9, 64, 0.5, 3.9, 65, 0.5, true, true), "5b Late");
      check("4 tick opening -> 6b Late / 4 tick 开局 → 6b Late",
         BridgeDetection.lateLabel(1, 4, 9, 0.9, 64, 0.5, 3.9, 65, 0.5, true, true), "6b Late");
      check("no placement evidence -> null / 没有放置证据 → null",
         BridgeDetection.lateLabel(1, 3, 9, 0.9, 64, 0.5, 3.9, 65, 0.5, true, false), null);
      check("not the first jump -> null / 不是第一跳 → null",
         BridgeDetection.lateLabel(2, 3, 9, 0.9, 64, 0.5, 3.9, 65, 0.5, true, true), null);
      check("only 2 columns (ordinary tap speed) -> null / 只走 2 列（普通 tap speed）→ null",
         BridgeDetection.lateLabel(1, 3, 9, 0.9, 64, 0.5, 2.9, 65, 0.5, true, true), null);
      check("did not rise a block -> null / 没升格 → null",
         BridgeDetection.lateLabel(1, 3, 9, 0.9, 64, 0.5, 3.9, 64, 0.5, true, true), null);
      check("lateral move over 1 block -> null / 横移超 1 格 → null",
         BridgeDetection.lateLabel(1, 3, 9, 0.9, 64, 0.5, 3.9, 65, 2.0, true, true), null);

      // ---- 3. three-tick opening: the whole chain ----
      // ---- 3. 三 tick 开局：整条链路 ----
      System.out.println("[3] 3-tick opening goes through the whole chain / 3-tick 开局走完整条链路");
      Tracker.reset();
      PlacementEvidence.reset();
      Tracker.noteY(64.0); Tracker.onTick(0.0, 64.0, 0.0, true);          // t=1
      Tracker.onBlockPlaced();                        // Run start, startTick=1 / 起跑，startTick=1
      Tracker.noteY(64.0); Tracker.onTick(0.3, 64.0, 0.0, true);          // t=2
      Tracker.noteY(64.0); Tracker.onTick(0.6, 64.0, 0.0, true);          // t=3
      Tracker.noteY(64.0); Tracker.onTick(0.9, 64.0, 0.0, true);          // t=4
      Tracker.onJump();                               // Take off on tick 3 / 第 3 tick 起跳
      List<JumpEntry> entries = Tracker.getEntries();
      check("entry count at the opening / 开局条目数", entries.size(), Integer.valueOf(1));
      check("opening label / 开局标签", entries.isEmpty() ? null : entries.get(0).getLabel(), "1.0 Flat");

      // ---- 4. the opening late that follows ----
      // ---- 4. 紧跟着的 opening late ----
      System.out.println("[4] the first jump after the opening meets the late conditions / 开局后的第一跳符合 late 条件");
      for (int tick = 5; tick <= 12; tick++) {
         Tracker.noteY(64.0 + (tick - 4) * 1.0 / 9.0); Tracker.onTick(0.9 + (tick - 4) * 0.33, 64.0 + (tick - 4) * 1.0 / 9.0, 0.0, true);
         if (tick == 8 || tick == 11) {
            Tracker.onBlockPlaced();                 // Two blocks placed along the way, as placement evidence / 沿途放了两个方块，作为放置证据
         }
      }
      Tracker.noteY(65.0); Tracker.onTick(0.9 + 9 * 0.33, 65.0, 0.0, true);  // t=13, exactly the 9th tick / t=13，正好第 9 tick
      Tracker.onJump();
      entries = Tracker.getEntries();
      JumpEntry last = entries.get(entries.size() - 1);
      check("second jump label / 第二跳标签", last.getLabel(), "5b Late");

      // ---- 5. cooldown ----
      // ---- 5. 冷却 ----
      System.out.println("[6] text format: +.n/a on a flat and the gain tag / 文本格式：flat 的 +.n/a 与 gain 尾标");
      com.bridger.compat.Config.showGainType = true;
      com.bridger.compat.Config.gainText = "";
      com.bridger.compat.Config.noGainText = "";
      Tracker.reset();
      Tracker.setInputProbe(() -> 1 | 4 | 8);   // W + A + D, A/D cancel out -> Gain / W + A + D，A/D 抵消 → Gain
      Tracker.noteY(64.0); Tracker.onTick(0.0, 64.0, 0.0, true);   // t=1
      Tracker.onBlockPlaced();
      Tracker.noteY(64.0); Tracker.onTick(0.3, 64.0, 0.0, true);
      Tracker.noteY(64.0); Tracker.onTick(0.6, 64.0, 0.0, true);
      Tracker.noteY(64.0); Tracker.onTick(0.9, 64.0, 0.0, true);
      Tracker.onJump();
      String flatLine = Tracker.getEntries().get(0).getFormattedLine();
      check("flat line contains +.n/a / flat 行含 +.n/a", flatLine.contains("+.n/a"), Boolean.TRUE);
      check("flat line no longer puts gain into the delta / flat 行不再把 gain 塞进 delta", flatLine.contains("+.No Gain") || flatLine.contains("+No Gain"), Boolean.FALSE);
      check("flat line carries the [Gain] tag / flat 行有 [Gain] 尾标", flatLine.contains("[Gain]"), Boolean.TRUE);
      System.out.println("      actual output / 实际输出: " + flatLine.replace('\u00a7', '&'));

      System.out.println("[7] gain rules (A/D pressed together = Gain) / gain 规则（A/D 同时按下 = Gain）");
      check("W alone -> Gain / W 单独按 → Gain", JumpSignals.gain(true, false, false, false), "Gain");
      check("W+A → No Gain", JumpSignals.gain(true, true, false, false), "No Gain");
      check("W+A+D -> Gain (A/D cancel out) / W+A+D → Gain（A/D 抵消）", JumpSignals.gain(true, true, true, false), "Gain");
      check("W not pressed -> null / 没按 W → null", JumpSignals.gain(false, true, true, false), null);

      System.out.println("[8] head hit jump: 3 ticks airborne (a normal jump is 12) / 顶头跳：空中 3 tick（正常跳是 12 tick）");
      Tracker.reset();
      Tracker.setInputProbe(() -> 0);
      Tracker.setCeilingProbe((x, y, z) -> Boolean.TRUE);
      Tracker.noteY(64.0); Tracker.onTick(0.0, 64.0, 0.0, true);      // t=1
      Tracker.onBlockPlaced();                                       // run start / 开局
      Tracker.noteY(64.0); Tracker.onTick(0.0, 64.0, 0.0, true);      // t=2
      Tracker.onJump();                                              // first jump -> Flat / 第一跳 → Flat
      Tracker.noteY(64.0); Tracker.onTick(0.0, 64.0, 0.0, true);      // t=3 landed / t=3 落地
      Tracker.noteY(64.05); Tracker.onTick(0.05, 64.05, 0.0, true);   // t=4
      Tracker.noteY(64.15); Tracker.onTick(0.10, 64.15, 0.0, true);   // t=5
      Tracker.onJump();                                              // second jump -> Tap #1 / 第二跳 → Tap #1
      Tracker.noteY(64.57); Tracker.onTick(0.20, 64.57, 0.0, false);  // t=6 take off 0.42 / t=6 起跳 0.42
      Tracker.noteY(64.57); Tracker.onTick(0.30, 64.57, 0.0, false);  // t=7 stopped by the ceiling / t=7 被顶住
      Tracker.noteY(64.15); Tracker.onTick(0.40, 64.15, 0.0, true);   // t=8 landed -> air=2 / t=8 落地 → air=2
      java.util.List<JumpEntry> list = Tracker.getEntries();
      boolean hasHead = false;
      int headAt = -1;
      for (int i = 0; i < list.size(); i++) {
         if (list.get(i).getType() == JumpEntry.EntryType.HEAD_HITTER) {
            hasHead = true;
            headAt = i;
         }
      }
      check("Head hitter recorded / 记录了 Head hitter", Boolean.valueOf(hasHead), Boolean.TRUE);
      check("placed after the Jump entry / 排在 Jump 之后", Integer.valueOf(headAt), Integer.valueOf(2));
      check("order Flat -> Jump #1 -> Head hitter / 顺序 Flat → Jump #1 → Head hitter",
         list.get(0).getType() + "/" + list.get(1).getType() + "/" + list.get(2).getType(),
         JumpEntry.EntryType.FLAT + "/" + JumpEntry.EntryType.TAP + "/"
            + JumpEntry.EntryType.HEAD_HITTER);

      System.out.println("[8b] a normal 12 tick jump is not a head hit / 正常 12 tick 的跳不算顶头");
      Tracker.reset();
      Tracker.noteY(64.0); Tracker.onTick(0.0, 64.0, 0.0, true);
      Tracker.onBlockPlaced();
      Tracker.noteY(64.0); Tracker.onTick(0.0, 64.0, 0.0, true);
      Tracker.onJump();
      double[] arc = {0.42, 0.75, 1.00, 1.17, 1.25, 1.25, 1.17, 1.00, 0.75, 0.42, 0.0};
      for (int i = 0; i < arc.length; i++) {
         Tracker.noteY(64.0 + arc[i]);
         Tracker.onTick(0.1 * i, 64.0 + arc[i], 0.0, arc[i] == 0.0);
      }
      boolean normalHead = false;
      for (JumpEntry e : Tracker.getEntries()) {
         if (e.getType() == JumpEntry.EntryType.HEAD_HITTER) normalHead = true;
      }
      check("no Head hitter / 没有 Head hitter", Boolean.valueOf(normalHead), Boolean.FALSE);

      System.out.println("[8c] walking down a step (no rise, short air time) is not a head hit / 走下台阶（没上升、空中也短）不算顶头");
      Tracker.reset();
      Tracker.noteY(64.0); Tracker.onTick(0.0, 64.0, 0.0, true);
      Tracker.onBlockPlaced();
      Tracker.noteY(64.0); Tracker.onTick(0.0, 64.0, 0.0, true);
      Tracker.onJump();                       // one jump first, so entries is not empty / 先有一跳，entries 非空
      Tracker.noteY(64.0); Tracker.onTick(0.0, 64.0, 0.0, true);
      Tracker.noteY(63.6); Tracker.onTick(0.1, 63.6, 0.0, false);
      Tracker.noteY(63.2); Tracker.onTick(0.2, 63.2, 0.0, false);
      Tracker.noteY(63.0); Tracker.onTick(0.3, 63.0, 0.0, true);
      boolean walkHead = false;
      for (JumpEntry e : Tracker.getEntries()) {
         if (e.getType() == JumpEntry.EntryType.HEAD_HITTER) walkHead = true;
      }
      check("no Head hitter / 没有 Head hitter", Boolean.valueOf(walkHead), Boolean.FALSE);

      System.out.println("[8d] jumping up a step (landing higher) is not a head hit / 跳上一个台阶（落在高处）不算顶头");
      Tracker.reset();
      Tracker.noteY(64.0); Tracker.onTick(0.0, 64.0, 0.0, true);
      Tracker.onBlockPlaced();
      Tracker.noteY(64.0); Tracker.onTick(0.0, 64.0, 0.0, true);
      Tracker.onJump();
      Tracker.noteY(64.42); Tracker.onTick(0.1, 64.42, 0.0, false);
      Tracker.noteY(64.75); Tracker.onTick(0.2, 64.75, 0.0, false);
      Tracker.noteY(65.0); Tracker.onTick(0.3, 65.0, 0.0, true);   // landed one block higher / 落到高一格
      boolean stepHead = false;
      for (JumpEntry e : Tracker.getEntries()) {
         if (e.getType() == JumpEntry.EntryType.HEAD_HITTER) stepHead = true;
      }
      check("no Head hitter / 没有 Head hitter", Boolean.valueOf(stepHead), Boolean.FALSE);

      System.out.println("[8e] head hit landing exactly on the plate (finish dash): the head hit must survive / 顶头落地正好踩板（终点冲刺）：顶头要留下来");
      Tracker.reset();
      Tracker.setCeilingProbe((x, y, z) -> Boolean.TRUE);
      final boolean[] onPlate = {false};
      Tracker.setPlateProbe((x, y, z) -> onPlate[0]);
      Tracker.noteY(64.0); Tracker.onTick(0.0, 64.0, 0.0, true);
      Tracker.onBlockPlaced();
      Tracker.noteY(64.0); Tracker.onTick(0.0, 64.0, 0.0, true);
      Tracker.onJump();                       // Flat
      Tracker.noteY(64.0); Tracker.onTick(0.0, 64.0, 0.0, true);
      Tracker.noteY(64.0); Tracker.onTick(0.0, 64.0, 0.0, true);
      Tracker.onJump();                       // Jump #1
      Tracker.noteY(64.57); Tracker.onTick(0.2, 64.57, 0.0, false);
      Tracker.noteY(64.57); Tracker.onTick(0.3, 64.57, 0.0, false);
      onPlate[0] = true;                      // this landing tick sits exactly on the gold plate / 落地这一 tick 正好压在金踏板上
      Tracker.noteY(64.0); Tracker.onTick(0.4, 64.0, 0.0, true);
      onPlate[0] = false;
      Tracker.noteY(64.0); Tracker.onTick(0.5, 64.0, 0.0, true);   // the plate sampling point is this tick / 压力板采样点在这一 tick
      java.util.List<JumpEntry> fin = Tracker.getEntries();
      boolean finHead = false;
      for (JumpEntry e : fin) {
         if (e.getType() == JumpEntry.EntryType.HEAD_HITTER) finHead = true;
      }
      check("status is success / 状态是成功", Tracker.getStatus(), Tracker.RunStatus.SUCCESS);
      check("the head hit was not swallowed by the wrap-up / 顶头没有被收尾吞掉", Boolean.valueOf(finHead), Boolean.TRUE);
      System.out.print("      entry order / 条目顺序: ");
      for (JumpEntry e : fin) {
         System.out.print(e.getType() + " ");
      }
      System.out.println();
      Tracker.setPlateProbe((x, y, z) -> false);

      System.out.println("[8f] Plates / Plates-1t sampling points (contact tick and the tick before) / Plates / Plates-1t 的采样点（接触 tick 与前一 tick）");
      Tracker.reset();
      Tracker.setCeilingProbe((x, y, z) -> Boolean.TRUE);
      final boolean[] press = {false};
      Tracker.setPlateProbe((x, y, z) -> press[0]);
      Tracker.noteY(64.0); Tracker.onTick(0.100, 64.0, 0.0, true);
      Tracker.onBlockPlaced();
      Tracker.noteY(64.0); Tracker.onTick(0.200, 64.0, 0.0, true);
      Tracker.onJump();
      Tracker.noteY(64.0); Tracker.onTick(5.100, 64.0, 0.0, true);   // the contact tick / 接触那一 tick
      press[0] = true;
      Tracker.noteY(64.0); Tracker.onTick(5.700, 64.0, 0.0, true);   // contact tick / 接触 tick
      press[0] = false;
      Tracker.noteY(64.0); Tracker.onTick(6.300, 64.0, 0.0, true);   // next tick (sampling point) / 下一 tick（采样点）
      double plateDec = Double.NaN;
      double minusDec = Double.NaN;
      for (JumpEntry e : Tracker.getEntries()) {
         if (e.getType() == JumpEntry.EntryType.PLATES) plateDec = e.getDecimal();
         if (e.getType() == JumpEntry.EntryType.PLATES_MINUS_1) minusDec = e.getDecimal();
      }
      // Contact at 5.700: Plates-1t takes the previous tick 5.100, Plates takes the contact tick
      // 5.700.
      // 接触在 5.700：Plates-1t 取前一 tick 的 5.100，Plates 取接触这一 tick 的 5.700
      check("Plates-1t = the tick before contact (.100) / Plates-1t = 接触前一 tick(.100)", Integer.valueOf((int)Math.round(minusDec * 1000)), Integer.valueOf(100));
      check("Plates = the contact tick (.700) / Plates = 接触那一 tick(.700)", Integer.valueOf((int)Math.round(plateDec * 1000)), Integer.valueOf(700));
      Tracker.setPlateProbe((x, y, z) -> false);

      System.out.println("[8g] plate stepped on in the air (0.25 tall trigger box): the head hit still counts / 踩板发生在空中（触发盒 0.25 高）：顶头照样要记");
      Tracker.reset();
      Tracker.setCeilingProbe((x, y, z) -> Boolean.TRUE);
      final boolean[] hit = {false};
      Tracker.setPlateProbe((x, y, z) -> hit[0]);
      Tracker.noteY(64.0); Tracker.onTick(0.0, 64.0, 0.0, true);
      Tracker.onBlockPlaced();
      Tracker.noteY(64.0); Tracker.onTick(0.1, 64.0, 0.0, true);
      Tracker.onJump();                                    // Flat
      Tracker.noteY(64.0); Tracker.onTick(0.2, 64.0, 0.0, true);
      Tracker.noteY(64.0); Tracker.onTick(0.3, 64.0, 0.0, true);
      Tracker.onJump();                                    // the dash jump / 冲刺那一跳
      Tracker.noteY(64.42); Tracker.onTick(0.4, 64.42, 0.0, false);   // hit the ceiling / 顶到天花板
      Tracker.noteY(64.42); Tracker.onTick(0.5, 64.42, 0.0, false);
      hit[0] = true;                                       // foot inside the trigger box, player still airborne / 脚伸进触发盒，人还在空中
      Tracker.noteY(64.15); Tracker.onTick(0.6, 64.15, 0.0, false);
      java.util.List<JumpEntry> air = Tracker.getEntries();
      boolean airHead = false;
      for (JumpEntry e : air) {
         if (e.getType() == JumpEntry.EntryType.HEAD_HITTER) airHead = true;
      }
      check("status is success / 状态是成功", Tracker.getStatus(), Tracker.RunStatus.SUCCESS);
      check("air plate step still records the head hit / 空中踩板也记了顶头", Boolean.valueOf(airHead), Boolean.TRUE);
      hit[0] = false;
      Tracker.noteY(64.0); Tracker.onTick(0.7, 64.0, 0.0, true);      // plate sampling point / 压力板采样点
      System.out.print("      entry order / 条目顺序: ");
      for (JumpEntry e : Tracker.getEntries()) {
         System.out.print(e.getType() + " ");
      }
      System.out.println();
      Tracker.setPlateProbe((x, y, z) -> false);

      System.out.println("[5] 5 tick cooldown after the run ends / 结束后 5 tick 冷却");
      Tracker.reset();
      Tracker.noteY(64.0); Tracker.onTick(0.0, 64.0, 0.0, true);          // t=1
      Tracker.onBlockPlaced();                        // startTick=1
      Tracker.noteY(64.0); Tracker.onTick(0.1, 64.0, 0.0, true);          // t=2
      Tracker.noteY(64.0); Tracker.onTick(0.2, 64.0, 0.0, true);          // t=3
      Tracker.noteY(64.0); Tracker.onTick(0.3, 64.0, 0.0, true);          // t=4
      Tracker.onJump();
      // Trigger a failure: fall until the grace window closes. onGround has to be false - a real
      // fall has no block under the feet.
      // 触发失败：一直下坠到宽限窗口结束。onGround 必须是 false —— 真掉下去时脚下没有方块。
      fallToVoid(64.0);
      check("failure status / 失败状态", Tracker.getStatus(), Tracker.RunStatus.FAILED);
      Tracker.onBlockPlaced();                        // inside the cooldown, no new run / 冷却内，不应开新局
      check("not released during the cooldown / 冷却内不放行", Tracker.getStatus(), Tracker.RunStatus.FAILED);
      for (int i = 0; i < 5; i++) {
         Tracker.noteY(64.0); Tracker.onTick(0.5, 64.0, 0.0, true);
      }
      Tracker.onBlockPlaced();                        // cooldown over, a new run should start / 冷却结束，应开新局
      check("a new run can start after the cooldown / 冷却结束后可开新局", Tracker.getStatus(), Tracker.RunStatus.BRIDGING);

      System.out.println("[5b] a bridge built downwards is not a void fall; only a real fall is / 往下搭的桥不算掉虚空，真掉下去才判");
      Tracker.reset();
      Tracker.noteY(64.0); Tracker.onTick(0.0, 64.0, 0.0, true);
      Tracker.onBlockPlaced();
      // Stair bridge downwards: each block placed is one block lower, and every step lands. The old
      // check used the take-off height as the baseline, so it called the void at the third block
      // (64-2.5=61.5) and killed a perfectly normal downhill bridge for its whole length.
      // 阶梯形往下搭：每放一块低一格，每格都落地。旧判定拿起跑高度当基准，
      // 掉到第三格（64-2.5=61.5）就判虚空，正常的下坡桥会被整段误杀。
      for (int i = 1; i <= 6; i++) {
         double stepY = 64.0 - i;
         Tracker.noteY(stepY); Tracker.onTick(0.1 * i, stepY, 0.0, true);
      }
      check("six blocks downhill still one run / 下坡六格仍在同一局", Tracker.getStatus(), Tracker.RunStatus.BRIDGING);
      // Fall continuously after leaving the ground: no block underfoot, onGround=false. The baseline
      // is the last landed height, 58.
      // 离开地面后一直往下掉：脚下没有方块，onGround=false。基准是最后一格落地高度 58
      fallToVoid(58.0);
      check("a fall that never lands -> void fall / 一路不落地的下坠 → 判虚空",
         Tracker.getStatus(), Tracker.RunStatus.FAILED);

      // While a fall is still inside the grace window, placing a block must not restart the timer.
      // 下坠还在宽限窗口内时，放块不能重新计时。
      Tracker.reset();
      Tracker.noteY(64.0); Tracker.onTick(0.0, 64.0, 0.0, true);
      Tracker.onBlockPlaced();
      for (int i = 0; i < 20; i++) {
         double fallY = 64.0 - i * 0.6D;
         Tracker.noteY(fallY); Tracker.onTick(0.1 + i * 0.05D, fallY, 0.0, false);
      }
      double clockBefore = Tracker.getCurrentBridgeTime();
      Tracker.onBlockPlaced();
      check("no new run while falling (the clock keeps running) / 下落途中不放行新局（计时继续走）",
         Boolean.valueOf(Tracker.getCurrentBridgeTime() >= clockBefore), Boolean.TRUE);

      System.out.println("[9] colours are configurable / 颜色可配置");
      com.bridger.compat.Config.colors[0] = "d";
      com.bridger.compat.Config.colors[1] = "6";
      String recolored = new JumpEntry("1.05 Flat", 0.0, 0.5, Double.NaN, 4,
         JumpEntry.EntryType.FLAT).getFormattedLine();
      check("label colour follows color.0 / 标签色跟随 color.0", recolored.startsWith("\u00a7d"), Boolean.TRUE);
      com.bridger.compat.Config.colors[0] = "b";
      com.bridger.compat.Config.colors[1] = "e";

      System.out.println("[10] server rewinds the world time: state is kept, the timer does not run backwards / 服务器回拨世界时间：不清状态、计时器不倒带");
      java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(100000L);
      Tracker.reset();
      Tracker.setTickSource(clock::get);
      Tracker.setInputProbe(() -> 0);
      Tracker.setCeilingProbe((x, y, z) -> Boolean.TRUE);
      for (int i = 0; i < 3; i++) {
         clock.incrementAndGet(); Tracker.noteY(64.0); Tracker.onTick(0.0, 64.0, 0.0, true);
      }
      Tracker.onBlockPlaced();                        // run start / 开局
      Tracker.onJump();
      // The world clock has to move: onTick returns early while the tick source reports the same
      // value, so a static clock would freeze the whole state machine.
      // 世界时钟必须往前走：tick 源报同一个值时 onTick 会直接返回，静止的时钟会把状态机冻住。
      for (int i = 1; i <= 50; i++) {
         clock.incrementAndGet();
         double fallY = 64.0 - i * 0.6D;
         Tracker.noteY(fallY);
         Tracker.onTick(0.5 + i * 0.01D, fallY, 0.0, false);
      }
      check("Failure wrap-up / 失败收尾", Tracker.getStatus(), Tracker.RunStatus.FAILED);
      int entriesAfterFail = Tracker.getEntries().size();
      // On BLC the world time is often rewound 1~5 ticks by the server.
      // BLC 上世界时间经常被服务器往回拨 1~5 tick
      clock.addAndGet(-3);
      Tracker.noteY(64.0); Tracker.onTick(0.0, 64.0, 0.0, true);
      check("failure record survives the rewind / 回拨后失败记录还在", Integer.valueOf(Tracker.getEntries().size()),
         Integer.valueOf(entriesAfterFail));
      check("status is still FAILED after the rewind / 回拨后状态还是 FAILED", Tracker.getStatus(), Tracker.RunStatus.FAILED);

      System.out.println("[10b] frame drop (world time jumps 3) keeps state, the timer keeps running / 掉帧（世界时间跳 3）不清状态、计时器继续走");
      clock.set(200000L);
      Tracker.noteY(64.0); Tracker.onTick(0.0, 64.0, 0.0, true);
      Tracker.onBlockPlaced();                        // new run / 新局
      for (int i = 0; i < 5; i++) {
         clock.incrementAndGet(); Tracker.noteY(64.0); Tracker.onTick(0.0, 64.0, 0.0, true);
      }
      double before = Tracker.getCurrentBridgeTime();
      clock.addAndGet(3);                             // stalled for 3 ticks / 卡了 3 tick
      Tracker.noteY(64.0); Tracker.onTick(0.0, 64.0, 0.0, true);
      check("still the same run after the frame drop / 掉帧后仍在同一局", Tracker.getStatus(), Tracker.RunStatus.BRIDGING);
      double after = Tracker.getCurrentBridgeTime();
      check("the timer did not run backwards / 计时器没有倒带", Boolean.valueOf(after >= before), Boolean.TRUE);

      System.out.println("[10c] a long stall only advances MAX_TICK_STEP and does not stretch a run / 长时间卡顿只推进 MAX_TICK_STEP，不把一局拉长");
      clock.addAndGet(600);                           // equivalent to alt-tab for 30 seconds / 相当于 alt-tab 30 秒
      Tracker.noteY(64.0); Tracker.onTick(0.0, 64.0, 0.0, true);
      check("duration did not blow up / 时长没有暴涨", Boolean.valueOf(Tracker.getCurrentBridgeTime() < after + 2.0), Boolean.TRUE);
      check("still the same run / 仍在同一局", Tracker.getStatus(), Tracker.RunStatus.BRIDGING);

      System.out.println("[10d] world switch: run-time state is cleared, entries stay for replay comparison / 世界切换：清运行期状态，条目留着跟回放对照");
      Tracker.onBlockPlaced();                        // continue this run / 继续这一局
      Tracker.onJump();                               // record one entry so entries is not empty / 记一条，entries 非空
      clock.incrementAndGet(); Tracker.noteY(60.0); Tracker.onTick(0.0, 60.0, 0.0, true);
      int kept = Tracker.getEntries().size();
      check("there is data before the switch / 切换前有数据", Boolean.valueOf(kept > 0), Boolean.TRUE);
      Tracker.onWorldChange("test: world object changed");
      check("entries were kept / 条目被保留", Integer.valueOf(Tracker.getEntries().size()), Integer.valueOf(kept));
      check("status wraps up as FAILED / 状态收尾为 FAILED", Tracker.getStatus(), Tracker.RunStatus.FAILED);
      check("no longer bridging / 已不在搭路中", Boolean.valueOf(Tracker.isBridging()), Boolean.FALSE);
      clock.incrementAndGet(); Tracker.noteY(64.0); Tracker.onTick(0.0, 64.0, 0.0, true);
      Tracker.onBlockPlaced();
      check("the first block in the new world starts a new run / 新世界第一块就能开新局", Tracker.getStatus(), Tracker.RunStatus.BRIDGING);
      check("old entries replaced after the new run starts / 开新局后旧条目被替换", Integer.valueOf(Tracker.getEntries().size()), Integer.valueOf(0));

      System.out.println("[10e] cooldown lets a backwards clock straight through / 冷却遇到时钟倒退直接放行");
      com.bridger.compat.RunStartCooldown cd = new com.bridger.compat.RunStartCooldown();
      cd.finish(900000);
      check("a backwards clock does not count as cooldown / 倒退的时钟不算冷却", Boolean.valueOf(cd.ready(10)), Boolean.TRUE);
      cd.finish(100);
      check("a normal clock still has to wait 5 ticks / 正常时钟仍要等 5 tick", Boolean.valueOf(cd.ready(103)), Boolean.FALSE);
      check("released once 5 ticks have passed / 等满 5 tick 放行", Boolean.valueOf(cd.ready(105)), Boolean.TRUE);

      Tracker.setTickSource(() -> -1L);

      System.out.println("[11] HUD header follows the header colour (color.7) / HUD 首行跟随标题色（color.7）");
      Tracker.reset();
      Tracker.setInputProbe(() -> 0);
      Tracker.setCeilingProbe((x, y, z) -> Boolean.TRUE);
      Tracker.setPlateProbe((x, y, z) -> false);
      String oldTitle = com.bridger.compat.Config.colors[7];
      com.bridger.compat.Config.colors[7] = "d";
      Tracker.noteY(64.0); Tracker.onTick(0.0, 64.0, 0.0, true);
      Tracker.onBlockPlaced();                                  // BRIDGING
      Tracker.onJump();                                         // record one entry so the HUD has content / 记一条，HUD 才有内容
      java.util.List<String> hud = com.bridger.compat.hud.HudModel.build();
      String head = hud.isEmpty() ? "" : hud.get(0);
      check("Bridging line starts with §dBridging §d / Bridging 行以 §dBridging §d 开头",
         head.length() >= 13 ? head.substring(0, 13) : head, "\u00a7dBridging \u00a7d");
      check("no hard-coded status colour / 没有写死的状态色", Boolean.valueOf(head.contains("\u00a7a") || head.contains("\u00a7c")
         || head.contains("\u00a7e")), Boolean.FALSE);

      final boolean[] onPad = {true};
      Tracker.setPlateProbe((x, y, z) -> onPad[0]);
      Tracker.noteY(64.0); Tracker.onTick(0.0, 64.0, 0.0, true);   // plate stepped on -> SUCCESS / 踩板 → SUCCESS
      hud = com.bridger.compat.hud.HudModel.build();
      head = hud.isEmpty() ? "" : hud.get(0);
      check("Success line also uses §d / Success 行也用 §d", head.length() >= 12 ? head.substring(0, 12) : head, "\u00a7dSuccess \u00a7d");
      Tracker.setPlateProbe((x, y, z) -> false);
      com.bridger.compat.Config.colors[7] = oldTitle;

      System.out.println("[12] Plates / Plates-1t follow the label colour (color.0) / Plates / Plates-1t 跟随标签色（color.0）");
      String oldLabel = com.bridger.compat.Config.colors[0];
      com.bridger.compat.Config.colors[0] = "d";
      String platesLine = new JumpEntry("Plates", 1.5, 0.44, Double.NaN, 0,
         JumpEntry.EntryType.PLATES).getFormattedLine();
      String minusLine = new JumpEntry("Plates-1t", 1.4, 0.447, Double.NaN, 0,
         JumpEntry.EntryType.PLATES_MINUS_1).getFormattedLine();
      check("Plates starts with the label colour / Plates 以标签色开头", Boolean.valueOf(platesLine.startsWith("\u00a7d")), Boolean.TRUE);
      check("Plates text uses the label colour / Plates 文字用标签色", Boolean.valueOf(platesLine.contains("\u00a7dPlates: ")), Boolean.TRUE);
      check("Plates value also uses the label colour / Plates 数值也用标签色", Boolean.valueOf(platesLine.contains("\u00a7d.440")
         || platesLine.contains("\u00a7d.44")), Boolean.TRUE);
      check("Plates no longer hard-codes gold / Plates 不再写死金色", Boolean.valueOf(platesLine.contains("\u00a76")), Boolean.FALSE);
      check("Plates-1t text uses the label colour / Plates-1t 文字用标签色", Boolean.valueOf(minusLine.contains("\u00a7dPlates-1t: ")), Boolean.TRUE);
      check("Plates-1t no longer hard-codes gold / Plates-1t 不再写死金色", Boolean.valueOf(minusLine.contains("\u00a76")), Boolean.FALSE);
      System.out.println("      actual output / 实际输出: " + platesLine.replace('\u00a7', '&') + "  |  "
         + minusLine.replace('\u00a7', '&'));
      com.bridger.compat.Config.colors[0] = oldLabel;

      System.out.println("[13] /bt command: only four verbs + case-insensitive + chat toggle / /bt 命令：只留四个 + 大小写不敏感 + chat 切换");
      final java.util.List<String> heard = new java.util.ArrayList<String>();
      com.bridger.compat.Chat.Reply sink = heard::add;

      boolean enabledBefore = com.bridger.compat.Config.enabled;
      check("/BT TOGGLE is recognised / /BT TOGGLE 被识别",
         Boolean.valueOf(com.bridger.compat.Chat.handle("/BT TOGGLE", sink)), Boolean.TRUE);
      check("toggle flips it / toggle 取反",
         Boolean.valueOf(com.bridger.compat.Config.enabled), Boolean.valueOf(!enabledBefore));
      com.bridger.compat.Chat.handle("/bt toggle", sink);
      check("toggling again restores it / 再切一次还原",
         Boolean.valueOf(com.bridger.compat.Config.enabled), Boolean.valueOf(enabledBefore));

      check("/BridgeTap reset with mixed case is accepted / /BridgeTap reset 大小写混写也认",
         Boolean.valueOf(com.bridger.compat.Chat.handle("/BridgeTap reset", sink)), Boolean.TRUE);
      check("/btw is not a command / /btw 不是命令",
         Boolean.valueOf(com.bridger.compat.Chat.handle("/btw hello", sink)), Boolean.FALSE);
      check("/b is not a command / /b 不是命令",
         Boolean.valueOf(com.bridger.compat.Chat.handle("/b toggle", sink)), Boolean.FALSE);

      boolean chatBefore = com.bridger.compat.Config.chatOutput;
      heard.clear();
      com.bridger.compat.Chat.handle("/bt chat", sink);
      check("chat without an argument -> toggles / chat 无参数 → 取反",
         Boolean.valueOf(com.bridger.compat.Config.chatOutput), Boolean.valueOf(!chatBefore));
      com.bridger.compat.Chat.handle("/BT CHAT", sink);
      check("toggling again restores it / 再切一次还原",
         Boolean.valueOf(com.bridger.compat.Config.chatOutput), Boolean.valueOf(chatBefore));
      com.bridger.compat.Chat.handle("/bt chat on", sink);
      check("chat on still works / chat on 仍可用", Boolean.valueOf(com.bridger.compat.Config.chatOutput), Boolean.TRUE);
      com.bridger.compat.Chat.handle("/bt chat off", sink);
      check("chat off still works / chat off 仍可用", Boolean.valueOf(com.bridger.compat.Config.chatOutput), Boolean.FALSE);
      com.bridger.compat.Config.chatOutput = chatBefore;

      String oldAxis = com.bridger.compat.Config.preferredAxis;
      com.bridger.compat.Chat.handle("/BRIDGETAP AXIS z", sink);
      check("lowercase axis value is accepted / axis 小写值也认", com.bridger.compat.Config.preferredAxis, "Z");
      com.bridger.compat.Config.preferredAxis = oldAxis;

      heard.clear();
      int hudXBefore = (int)com.bridger.compat.Config.hudXPercent;
      com.bridger.compat.Chat.handle("/bt pos 111 222", sink);
      check("pos was removed, config untouched / pos 已砍掉，配置没被改",
         Boolean.valueOf(com.bridger.compat.Config.hudXPercent != 111.0D), Boolean.TRUE);
      boolean helpHasToggle = false;
      for (String line : heard) {
         if (line.contains("/bt toggle")) {
            helpHasToggle = true;
         }
      }
      check("unknown command falls back to help (including toggle) / 未知命令回帮助（含 toggle）", Boolean.valueOf(helpHasToggle), Boolean.TRUE);

      System.out.println("[14] after chat off no Success line is sent / chat off 之后不再发 Success 行");
      final java.util.List<String> chat = new java.util.ArrayList<String>();
      Tracker.setChatSink(chat::add);
      boolean chatWasOn = com.bridger.compat.Config.chatOutput;
      Tracker.setCeilingProbe((x, y, z) -> Boolean.TRUE);

      // Off: run one success and advance to when the success message should go out; nothing should
      // be sent.
      // 关着：跑一局成功，推进到成功消息该发的时候，应当什么都没发
      com.bridger.compat.Config.chatOutput = false;
      chat.clear();
      Tracker.reset();
      final boolean[] padDown = {false};
      Tracker.setPlateProbe((x, y, z) -> padDown[0]);
      Tracker.noteY(64.0); Tracker.onTick(0.0, 64.0, 0.0, true);
      Tracker.onBlockPlaced();
      Tracker.onJump();
      padDown[0] = true;
      Tracker.noteY(64.0); Tracker.onTick(0.1, 64.0, 0.0, true);
      for (int i = 0; i < 4; i++) {
         Tracker.noteY(64.0); Tracker.onTick(0.2 + i * 0.1, 64.0, 0.0, true);
      }
      boolean offSent = false;
      for (String line : chat) {
         if (line.contains("Success!")) {
            offSent = true;
         }
      }
      check("chat off sends no Success line / chat off 不发 Success 行", Boolean.valueOf(offSent), Boolean.FALSE);

      // On: same flow, one line should be sent.
      // 开着：同样的流程，应当发一条
      com.bridger.compat.Config.chatOutput = true;
      chat.clear();
      Tracker.reset();
      padDown[0] = false;
      Tracker.noteY(64.0); Tracker.onTick(0.0, 64.0, 0.0, true);
      Tracker.onBlockPlaced();
      Tracker.onJump();
      padDown[0] = true;
      Tracker.noteY(64.0); Tracker.onTick(0.1, 64.0, 0.0, true);
      for (int i = 0; i < 4; i++) {
         Tracker.noteY(64.0); Tracker.onTick(0.2 + i * 0.1, 64.0, 0.0, true);
      }
      boolean onSent = false;
      for (String line : chat) {
         if (line.contains("Success!")) {
            onSent = true;
         }
      }
      check("chat on does send the Success line / chat on 会发 Success 行", Boolean.valueOf(onSent), Boolean.TRUE);
      Tracker.setPlateProbe((x, y, z) -> false);
      com.bridger.compat.Config.chatOutput = chatWasOn;
      Tracker.setChatSink((line) -> { });

      System.out.println("[15] the success line adds half an RTT (rounded), the 2 tick delay stays / 成功行补上半个 RTT（四舍五入），2 tick 延迟保留");
      final java.util.List<String> successLines = new java.util.ArrayList<String>();
      Tracker.setChatSink(successLines::add);
      boolean chatWasOn2 = com.bridger.compat.Config.chatOutput;
      com.bridger.compat.Config.chatOutput = true;
      Tracker.setTickSource(() -> -1L);
      final boolean[] padDown2 = {false};
      Tracker.setPlateProbe((x, y, z) -> padDown2[0]);
      Tracker.setPingProbe(() -> 63);                       // 63/2 = 31.5 -> 32ms / 63/2 = 31.5 → 32ms

      Tracker.reset();
      Tracker.noteY(64.0); Tracker.onTick(0.0, 64.0, 0.0, true);
      Tracker.onBlockPlaced();
      for (int i = 0; i < 4; i++) {
         Tracker.noteY(64.0); Tracker.onTick(0.1 * i, 64.0, 0.0, true);
      }
      Tracker.onJump();
      padDown2[0] = true;
      Tracker.noteY(64.0); Tracker.onTick(0.5, 64.0, 0.0, true);   // plate stepped on / 踩板
      double rawTime = Tracker.getCurrentBridgeTime();
      String expectAdjusted = String.format(java.util.Locale.US, "%.2fs", rawTime + 0.032D);
      String expectRaw = String.format(java.util.Locale.US, "%.2fs", rawTime);

      check("not sent on the plate tick itself / 踩板当 tick 还没发", Integer.valueOf(successLines.size()), Integer.valueOf(0));
      Tracker.noteY(64.0); Tracker.onTick(0.6, 64.0, 0.0, true);   // 1 tick after contact / 接触后 1 tick
      check("not sent 1 tick later / +1 tick 还没发", Integer.valueOf(successLines.size()), Integer.valueOf(0));
      Tracker.noteY(64.0); Tracker.onTick(0.7, 64.0, 0.0, true);   // 2 ticks after contact / 接触后 2 tick
      check("sent 2 ticks later / +2 tick 发出", Integer.valueOf(successLines.size()), Integer.valueOf(1));
      padDown2[0] = false;
      Tracker.noteY(64.0); Tracker.onTick(0.8, 64.0, 0.0, true);
      check("sent exactly once / 只发一次", Integer.valueOf(successLines.size()), Integer.valueOf(1));
      String line = successLines.isEmpty() ? "" : successLines.get(0);
      check("time includes half an RTT (" + expectAdjusted + ") / 时间含半个 RTT（" + expectAdjusted + "）",
         Boolean.valueOf(line.contains(expectAdjusted)), Boolean.TRUE);
      check("the uncompensated raw value is not used (" + expectRaw + ") / 没有用未补偿的原值（" + expectRaw + "）",
         Boolean.valueOf(line.contains("Time: \u00a7e" + expectRaw + " ")), Boolean.FALSE);
      System.out.println("      actual output / 实际输出: " + line.replace('\u00a7', '&'));

      System.out.println("[15b] keeps the raw value when the latency cannot be read (-1) / 延迟读不到（-1）时保持原值");
      successLines.clear();
      Tracker.setPingProbe(() -> -1);
      padDown2[0] = false;
      Tracker.reset();
      Tracker.noteY(64.0); Tracker.onTick(0.0, 64.0, 0.0, true);
      Tracker.onBlockPlaced();
      for (int i = 0; i < 4; i++) {
         Tracker.noteY(64.0); Tracker.onTick(0.1 * i, 64.0, 0.0, true);
      }
      Tracker.onJump();
      padDown2[0] = true;
      Tracker.noteY(64.0); Tracker.onTick(0.5, 64.0, 0.0, true);
      double raw2 = Tracker.getCurrentBridgeTime();
      padDown2[0] = false;
      for (int i = 0; i < 3; i++) {
         Tracker.noteY(64.0); Tracker.onTick(0.6 + i * 0.1, 64.0, 0.0, true);
      }
      String plain = successLines.isEmpty() ? "" : successLines.get(0);
      check("the raw value is unchanged / 原值不变",
         Boolean.valueOf(plain.contains(String.format(java.util.Locale.US, "%.2fs", raw2))),
         Boolean.TRUE);

      System.out.println("[15c] even milliseconds do not drift / 偶数毫秒不产生漂移");
      check("60ms → 30ms", Integer.valueOf(com.bridger.compat.Tracker.pingHalf(60)), Integer.valueOf(30));
      check("61ms → 31ms", Integer.valueOf(com.bridger.compat.Tracker.pingHalf(61)), Integer.valueOf(31));
      check("1ms -> 1ms (0.5 rounds up) / 1ms → 1ms（0.5 进位）", Integer.valueOf(com.bridger.compat.Tracker.pingHalf(1)), Integer.valueOf(1));
      check("0ms → 0ms", Integer.valueOf(com.bridger.compat.Tracker.pingHalf(0)), Integer.valueOf(0));
      check("-1 → 0ms", Integer.valueOf(com.bridger.compat.Tracker.pingHalf(-1)), Integer.valueOf(0));

      Tracker.setPlateProbe((x, y, z) -> false);
      Tracker.setPingProbe(null);
      com.bridger.compat.Config.chatOutput = chatWasOn2;
      Tracker.setChatSink((l) -> { });
      Tracker.setTickSource(() -> -1L);

      System.out.println("[16] \"hide the panel while Tab is held\" goes through config read/write / 「按住 Tab 时隐藏面板」走配置读写");
      // run.sh already switched the working directory to a temp one, so this builds a throwaway
      // config.
      // run.sh 已经把工作目录切到临时目录，这里造的就是一份一次性的配置。
      java.nio.file.Path dir = java.nio.file.Paths.get("config");
      java.nio.file.Path file = dir.resolve("bridgetap.properties");
      boolean loadFalse = false;
      boolean saveTrue = false;
      String written = "";
      try {
         java.nio.file.Files.createDirectories(dir);
         java.nio.file.Files.write(file, java.util.Arrays.asList(
            "#BridgeTap Configuration", "configRevision=2", "yieldToTabList=false", ""),
            java.nio.charset.StandardCharsets.UTF_8);
         com.bridger.compat.Config.load();
         loadFalse = !com.bridger.compat.Config.yieldToTabList;
         com.bridger.compat.Config.yieldToTabList = true;
         com.bridger.compat.Config.save();
         written = new String(java.nio.file.Files.readAllBytes(file),
            java.nio.charset.StandardCharsets.UTF_8);
         saveTrue = written.contains("yieldToTabList=true");
      } catch (java.io.IOException e) {
         written = "IO error: " + e.getMessage();
      }
      check("load reads false / load 读到 false", Boolean.valueOf(loadFalse), Boolean.TRUE);
      check("save writes true back / save 写回 true", Boolean.valueOf(saveTrue), Boolean.TRUE);
      check("the key name is spelled right / 键名没写错", Boolean.valueOf(written.contains("yieldToTabList=")), Boolean.TRUE);

      System.out.println("[17] custom Gain / No Gain text / Gain / No Gain 文本自定义");
      com.bridger.compat.Config.showGainType = true;
      com.bridger.compat.Config.gainText = "涨了";
      com.bridger.compat.Config.noGainText = "没涨";
      check("custom Gain word / 自定义 Gain 词", com.bridger.compat.Config.gainWord(), "涨了");
      check("custom No Gain word / 自定义 No Gain 词", com.bridger.compat.Config.noGainWord(), "没涨");
      String customGainLine = new JumpEntry("Tap #1", 1.0, 0.137, -0.05, 9,
         JumpEntry.EntryType.TAP).getFormattedLine();
      System.out.println("      actual output / 实际输出: " + customGainLine.replace('\u00a7', '&'));

      // The verdict is not affected by the text: the Gain slot colour is only drawn for
      // setGainType("Gain").
      // 判定不受文本影响：setGainType("Gain") 才画 Gain 槽的颜色
      JumpEntry gainEntry = new JumpEntry("Tap #2", 1.0, 0.2, -0.05, 9, JumpEntry.EntryType.TAP);
      gainEntry.setGainType("Gain");
      String gainLine = gainEntry.getFormattedLine();
      check("Gain line uses the custom word / Gain 行用自定义词", Boolean.valueOf(gainLine.contains("[涨了]")), Boolean.TRUE);
      check("Gain line still uses the Gain colour slot / Gain 行仍用 Gain 颜色槽", Boolean.valueOf(gainLine.contains("\u00a7a[涨了]")), Boolean.TRUE);
      JumpEntry noGainEntry = new JumpEntry("Tap #3", 1.0, 0.3, -0.05, 9, JumpEntry.EntryType.TAP);
      noGainEntry.setGainType("No Gain");
      String noGainLine = noGainEntry.getFormattedLine();
      check("No Gain line uses the custom word / No Gain 行用自定义词", Boolean.valueOf(noGainLine.contains("[没涨]")), Boolean.TRUE);
      check("No Gain line still uses the No Gain colour slot / No Gain 行仍用 No Gain 颜色槽", Boolean.valueOf(noGainLine.contains("\u00a7e[没涨]")), Boolean.TRUE);

      System.out.println("[17b] empty falls back to the default / 留空回到默认");
      com.bridger.compat.Config.gainText = "";
      com.bridger.compat.Config.noGainText = "   ";
      check("empty string -> Gain / 空串 → Gain", com.bridger.compat.Config.gainWord(), "Gain");
      check("whitespace only -> No Gain / 全空白 → No Gain", com.bridger.compat.Config.noGainWord(), "No Gain");

      System.out.println("[17c] sanitising and truncation / 清洗与截断");
      com.bridger.compat.Config.gainText = "  [Gain]  ";
      check("brackets are removed / 方括号被去掉", com.bridger.compat.Config.gainWord(), "Gain");
      StringBuilder longWord = new StringBuilder();
      for (int i = 0; i < 40; i++) {
         longWord.append('x');
      }
      com.bridger.compat.Config.gainText = longWord.toString();
      check("truncated to 24 / 超长截到 24",
         Integer.valueOf(com.bridger.compat.Config.gainWord().length()),
         Integer.valueOf(com.bridger.compat.Config.MAX_GAIN_WORD));

      System.out.println("[17d] the old switch migrates to text / 旧开关迁移成文本");
      try {
         java.nio.file.Path dir17 = java.nio.file.Paths.get("config");
         java.nio.file.Path file17 = dir17.resolve("bridgetap.properties");
         java.nio.file.Files.createDirectories(dir17);
         java.nio.file.Files.write(file17, java.util.Arrays.asList(
            "#BridgeTap Configuration", "configRevision=2", "useNoGayLabel=true", ""),
            java.nio.charset.StandardCharsets.UTF_8);
         com.bridger.compat.Config.load();
         check("useNoGayLabel=true migrates to No Gay / useNoGayLabel=true 迁移成 No Gay",
            com.bridger.compat.Config.noGainWord(), "No Gay");
         java.nio.file.Files.write(file17, java.util.Arrays.asList(
            "#BridgeTap Configuration", "configRevision=2", "gainLabel=No Gay", ""),
            java.nio.charset.StandardCharsets.UTF_8);
         com.bridger.compat.Config.load();
         check("the older gainLabel=No Gay also migrates / 更老的 gainLabel=No Gay 也迁移",
            com.bridger.compat.Config.noGainWord(), "No Gay");
         java.nio.file.Files.write(file17, java.util.Arrays.asList(
            "#BridgeTap Configuration", "configRevision=2", "gainText=涨", "noGainText=没涨", ""),
            java.nio.charset.StandardCharsets.UTF_8);
         com.bridger.compat.Config.load();
         check("the new key wins / 新键优先", com.bridger.compat.Config.gainWord(), "涨");
      } catch (java.io.IOException e) {
         check("config read/write exception / 配置读写异常", e.getMessage(), null);
      }
      com.bridger.compat.Config.gainText = "";
      com.bridger.compat.Config.noGainText = "";

      System.out.println("[18] the mod language pack / 模组语言包");
      com.bridger.compat.Lang.load("en");
      check("default language is English / 默认语言英文", com.bridger.compat.Lang.locale(), "en");
      check("hud.status.success", com.bridger.compat.Lang.t("hud.status.success"), "Success");
      check("placeholder substitution / 占位符替换", com.bridger.compat.Lang.t("hud.time", "1.50"), "Time: 1.50s");
      check("missing key falls back to the key name / 缺键回退成键名", com.bridger.compat.Lang.t("no.such.key"), "no.such.key");

      // Up to v1.5.4 the text was hard-coded in HudModel / JumpEntry / BridgeDetection / Chat /
      // Bridge. This copies that English verbatim as a baseline: from now on, a single drifted word
      // in en.properties turns this group red.
      // v1.5.4 及之前的文案是硬编码在 HudModel / JumpEntry / BridgeDetection / Chat / Bridge 里的。
      // 这里把那一份英文原样抄下来当基线：以后改 en.properties 只要有一处措辞漂了，这组就红。
      System.out.println("[18-1] full English text baseline (v1.5.4 hard-coded -> language pack) / 英文文案全量基线（v1.5.4 硬编码 → 语言包）");
      String[][] enBaseline = {
         {"hud.status.bridging", "Bridging"},
         {"hud.status.success", "Success"},
         {"hud.status.failed", "Failed"},
         {"hud.header", "{0} {1}s {2} jumps, axis: {3}"},
         {"hud.time", "Time: {0}s"},
         {"hud.idle", "{0} \u00a78| \u00a77X \u00a7f{1}  \u00a77Y \u00a7f{2}  \u00a77Z \u00a7f{3}"},
         {"hud.waiting", "{0} \u00a78| \u00a77waiting for world"},
         {"entry.flat", "Flat"},
         {"entry.late", "Late"},
         {"entry.jump", "Jump"},
         {"entry.tapSpeed", "Tap Speed"},
         {"entry.holdSpeed", "Hold Speed"},
         {"entry.fourBlock", "4b"},
         {"entry.fiveBlock", "5b"},
         {"entry.sixBlock", "6b"},
         {"entry.turn", "Turn"},
         {"entry.failTap", "Fail Tap"},
         {"entry.headHitter", "Head hitter"},
         {"entry.plates", "Plates"},
         {"entry.platesMinus1", "Plates-1t"},
         {"entry.noDelta", "+.n/a"},
         {"chat.prefix", "[BridgeTap]"},
         {"chat.hud", "HUD: {0}"},
         {"chat.enabled", "Enabled"},
         {"chat.disabled", "Disabled"},
         {"chat.reset", "Stats reset!"},
         {"chat.axisSet", "Axis set to: {0}"},
         {"chat.chatOn", "Chat output: on"},
         {"chat.chatOff", "Chat output: off"},
         {"chat.help.header", "Available commands:"},
         {"chat.help.toggle", "/bt toggle - Enable/disable the HUD"},
         {"chat.help.reset", "/bt reset - Reset the stats"},
         {"chat.help.axis", "/bt axis <AUTO|Z|X> - Set the tracking axis"},
         {"chat.help.chat", "/bt chat - Toggle chat output (on / off)"},
         {"chat.usage.axis", "Usage: /bt axis <AUTO|Z|X>"},
         {"chat.usage.chat", "Usage: /bt chat [on|off]"},
         {"chat.success", "Success! Time: \u00a7e{0}s \u00a7a| Plates-1t: \u00a7e{1} \u00a7a| Plates: \u00a7e{2}"}
      };
      for (String[] row : enBaseline) {
         check("en " + row[0], com.bridger.compat.Lang.t(row[0]), row[1]);
      }
      // 38 entries: 7 hud + 14 entry + 17 chat. One short means a missed migration, one extra means a
      // duplicate or superfluous key.
      // 38 条：7 hud + 14 entry + 17 chat。少一条说明漏迁，多一条说明有重复/多余键。
      check("en key count = 37 (none missing, none extra) / en 键数 = 37（无缺无多）", Integer.valueOf(com.bridger.compat.Lang.size()),
         Integer.valueOf(enBaseline.length));
      check("en hud.header renders / en hud.header 渲染", com.bridger.compat.Lang.t("hud.header",
         "Success", "1.50", Integer.valueOf(3), "Z"), "Success 1.50s 3 jumps, axis: Z");
      check("en hud.idle renders / en hud.idle 渲染", com.bridger.compat.Lang.t("hud.idle",
         "BridgeTap", "1.000", "64.000", "-32.000"),
         "BridgeTap \u00a78| \u00a77X \u00a7f1.000  \u00a77Y \u00a7f64.000  \u00a77Z \u00a7f-32.000");
      check("en hud.waiting renders / en hud.waiting 渲染", com.bridger.compat.Lang.t("hud.waiting", "BridgeTap"),
         "BridgeTap \u00a78| \u00a77waiting for world");
      check("en chat.hud renders / en chat.hud 渲染", com.bridger.compat.Lang.t("chat.hud", "\u00a7aEnabled"),
         "HUD: \u00a7aEnabled");
      check("en chat.axisSet renders / en chat.axisSet 渲染", com.bridger.compat.Lang.t("chat.axisSet", "Z"),
         "Axis set to: Z");
      check("en chat.success renders / en chat.success 渲染", com.bridger.compat.Lang.t("chat.success",
         "1.50", ".123", ".456"),
         "Success! Time: \u00a7e1.50s \u00a7a| Plates-1t: \u00a7e.123 \u00a7a| Plates: \u00a7e.456");
      // The help line is "/bt xxx - description" split into yellow/grey at " - "; a wrong split turns
      // the whole line yellow.
      // 帮助行是「/bt xxx - 说明」按 " - " 拆成黄/灰两段的，拆错颜色就会整行变黄
      final java.util.List<String> help = new java.util.ArrayList<String>();
      com.bridger.compat.Chat.handle("/bt", new com.bridger.compat.Chat.Reply() {
         public void line(String text) {
            help.add(text);
         }
      });
      check("en help first line / en 帮助首行", help.get(0),
         "\u00a7b[BridgeTap] \u00a7r\u00a7eAvailable commands:");
      check("en help line splitting / en 帮助行拆分", help.get(3),
         "\u00a7e/bt axis <AUTO|Z|X> \u00a77- Set the tracking axis");
      check("en help has 5 lines / en 帮助共 5 行", Integer.valueOf(help.size()), Integer.valueOf(5));

      // When the resource cannot be read Lang falls back to BUILTIN_EN. That constant has to match
      // en.properties word for word, otherwise "cannot read from the jar" and "can read" show two
      // different sets of text.
      // 资源读不到时 Lang 会回退 BUILTIN_EN。那份常量必须与 en.properties 逐字一致，
      // 否则「jar 里读不到」和「读得到」会出现两种文案。
      try {
         java.lang.reflect.Field builtinField =
            com.bridger.compat.Lang.class.getDeclaredField("BUILTIN_EN");
         builtinField.setAccessible(true);
         String[][] builtin = (String[][]) builtinField.get(null);
         check("builtin fallback count = 37 / 内置兜底条数 = 37", Integer.valueOf(builtin.length), Integer.valueOf(37));
         int drift = 0;
         for (String[] row : builtin) {
            if (!row[1].equals(com.bridger.compat.Lang.t(row[0]))) {
               drift++;
               System.out.println("    fallback differs from the language pack / 兜底与语言包不一致: " + row[0]);
            }
         }
         check("builtin fallback has no drift from en.properties / 内置兜底与 en.properties 无漂移", Integer.valueOf(drift), Integer.valueOf(0));
      } catch (Throwable t) {
         check("reading the builtin fallback / 读取内置兜底", t.toString(), null);
      }
      check("English flatLabel unchanged / 英文 flatLabel 不变", com.bridger.compat.BridgeDetection.flatLabel(4, 1.05), "1.05 Flat");
      check("English lateLabel unchanged / 英文 lateLabel 不变", com.bridger.compat.BridgeDetection.lateLabel(
         1, 3, 9, 0.9, 64, 0.5, 3.9, 65, 0.5, true, true), "5b Late");
      // Byte-for-byte against the old hard-coded text: empty slot / brand / status / result lines must
      // not lose colour or change spacing when moved into the language pack.
      // 逐字节对齐旧硬编码：空号/品牌/状态/结算行都不能因为搬进语言包而掉色或改空格
      check("hud.idle brand colour split / hud.idle 品牌分色", com.bridger.compat.Lang.t("hud.idle", "BridgeTap",
         "1.000", "64.000", "2.000"),
         "BridgeTap \u00a78| \u00a77X \u00a7f1.000  \u00a77Y \u00a7f64.000  \u00a77Z \u00a7f2.000");
      check("hud.waiting separator colour / hud.waiting 分隔色", com.bridger.compat.Lang.t("hud.waiting", "BridgeTap"),
         "BridgeTap \u00a78| \u00a77waiting for world");
      check("hud.header chat version / hud.header 聊天版", com.bridger.compat.Lang.t("hud.header", "Success", "1.50",
         Integer.valueOf(3), "Z"), "Success 1.50s 3 jumps, axis: Z");
      check("chat.help title on its own line / chat.help 标题独立成行",
         com.bridger.compat.Lang.t("chat.help.header"), "Available commands:");
      check("chat.success result line / chat.success 结算行", com.bridger.compat.Lang.t("chat.success", "1.50", ".123", ".456"),
         "Success! Time: \u00a7e1.50s \u00a7a| Plates-1t: \u00a7e.123 \u00a7a| Plates: \u00a7e.456");

      com.bridger.compat.Lang.load("zh_CN");
      check("switched to Chinese / 切到中文", com.bridger.compat.Lang.locale(), "zh_CN");
      check("zh_CN key count = 37 (one-to-one with en) / zh_CN 键数 = 37（与 en 一一对应）", Integer.valueOf(com.bridger.compat.Lang.size()),
         Integer.valueOf(37));
      // The Chinese fallback has to match zh_CN.properties word for word too, otherwise Chinese
      // players see a different set of text when the resource goes missing.
      // 中文兜底与 zh_CN.properties 也要逐字一致，否则资源丢失时中文玩家会看到另一套措辞
      try {
         java.lang.reflect.Field zhField =
            com.bridger.compat.Lang.class.getDeclaredField("BUILTIN_ZH");
         zhField.setAccessible(true);
         String[][] zh = (String[][]) zhField.get(null);
         check("Chinese fallback count = 37 / 中文兜底条数 = 37", Integer.valueOf(zh.length), Integer.valueOf(37));
         int zhDrift = 0;
         for (String[] row : zh) {
            if (!row[1].equals(com.bridger.compat.Lang.t(row[0]))) {
               zhDrift++;
               System.out.println("    Chinese fallback differs from the language pack / 中文兜底与语言包不一致: " + row[0]);
            }
         }
         check("Chinese fallback has no drift from zh_CN.properties / 中文兜底与 zh_CN.properties 无漂移", Integer.valueOf(zhDrift), Integer.valueOf(0));
      } catch (Throwable t) {
         check("reading the Chinese fallback / 读取中文兜底", t.toString(), null);
      }
      check("Chinese hud.status.success / 中文 hud.status.success", com.bridger.compat.Lang.t("hud.status.success"), "成功");
      check("Chinese flatLabel / 中文 flatLabel", com.bridger.compat.BridgeDetection.flatLabel(4, 1.05), "1.05 Flat");
      check("Chinese lateLabel / 中文 lateLabel", com.bridger.compat.BridgeDetection.lateLabel(
         1, 3, 9, 0.9, 64, 0.5, 3.9, 65, 0.5, true, true), "5b 迟");
      // The verdict must not follow the language: the late check has to still hold in Chinese (it
      // used to rely on getLabel().endsWith("Flat")).
      // 判定不能跟着语言走：中文下 late 判定仍要成立（原来靠 getLabel().endsWith("Flat")）
      check("opening late still works in Chinese / 中文下 opening late 仍然生效", Boolean.valueOf(com.bridger.compat.BridgeDetection.lateLabel(
         1, 3, 9, 0.9, 64, 0.5, 3.9, 65, 0.5, true, true) != null), Boolean.TRUE);

      System.out.println("[18b] unknown language falls back to the default / 未知语言回退默认");
      com.bridger.compat.Lang.load("xx_YY");
      check("unknown language -> still the key value (falls back to English) / 未知语言 → 仍是键值（回退英文）", com.bridger.compat.Lang.t("hud.status.failed"), "Failed");

      System.out.println("[18c] an external language pack overrides the builtin / 外部语言包覆盖内置");
      // The test CWD is a temp directory, so the config/lang written here only affects this run.
      // 测试 CWD 是临时目录，这里写的 config/lang 只影响本次运行
      try {
         java.nio.file.Path langDir = java.nio.file.Paths.get("config", "lang");
         java.nio.file.Files.createDirectories(langDir);
         java.nio.file.Files.write(langDir.resolve("zh_CN.properties"),
            java.util.Arrays.asList("hud.status.success=\u6d4b\u8bd5\u6210\u529f", ""),
            java.nio.charset.StandardCharsets.UTF_8);
         com.bridger.compat.Lang.load("zh_CN");
         check("the external file overrode the builtin value / 外部文件覆盖了内置值", com.bridger.compat.Lang.t("hud.status.success"), "测试成功");
         check("keys that were not overridden still come from the builtin / 没覆盖的键仍来自内置", com.bridger.compat.Lang.t("hud.status.failed"), "失败");
      } catch (java.io.IOException e) {
         check("language pack read/write exception / 语言包读写异常", e.getMessage(), null);
      } finally {
         com.bridger.compat.Lang.load("en");
      }

      System.out.println("[18d] language pack integrity (key set / duplicates / empty values) / 语言包完整性（键集合 / 重复 / 空值）");
      try {
         java.util.List<String[]> enRows = readPack("/lang/en.properties");
         java.util.List<String[]> zhRows = readPack("/lang/zh_CN.properties");
         java.util.Set<String> enKeys = new java.util.LinkedHashSet<String>();
         java.util.Set<String> zhKeys = new java.util.LinkedHashSet<String>();
         int enDup = 0;
         int zhDup = 0;
         int enEmpty = 0;
         int zhEmpty = 0;
         for (String[] row : enRows) {
            if (!enKeys.add(row[0])) {
               enDup++;
            }
            if (row[1].isEmpty()) {
               enEmpty++;
            }
         }
         for (String[] row : zhRows) {
            if (!zhKeys.add(row[0])) {
               zhDup++;
            }
            if (row[1].isEmpty()) {
               zhEmpty++;
            }
         }
         check("en has no duplicate keys / en 无重复键", Integer.valueOf(enDup), Integer.valueOf(0));
         check("zh_CN has no duplicate keys / zh_CN 无重复键", Integer.valueOf(zhDup), Integer.valueOf(0));
         check("en / zh_CN key sets match / en / zh_CN 键集合一致", enKeys, zhKeys);
         check("en entry count / en 条目数", Integer.valueOf(enKeys.size()), Integer.valueOf(37));
         check("en has no empty values / en 无空值", Integer.valueOf(enEmpty), Integer.valueOf(0));
         check("zh_CN has no empty values / zh_CN 无空值", Integer.valueOf(zhEmpty), Integer.valueOf(0));
      } catch (java.io.IOException e) {
         check("language pack read exception / 语言包读取异常", e.getMessage(), null);
      }

      System.out.println("[18d] automatic language / 自动语言");
      com.bridger.compat.Lang.load("auto");
      String autoCode = com.bridger.compat.Lang.locale();
      check("auto -> resolves to an available language / auto → 落到一门可用语言", Boolean.valueOf("en".equals(autoCode) || "zh_CN".equals(autoCode)),
         Boolean.TRUE);
      check("auto key count = 37 / auto 键数 = 37", Integer.valueOf(com.bridger.compat.Lang.size()), Integer.valueOf(37));
      check("region tag zh-TW -> zh_CN / 区域标签 zh-TW → zh_CN", com.bridger.compat.Lang.autoCode("zh-TW"), "zh_CN");
      check("region tag en_GB -> en / 区域标签 en_GB → en", com.bridger.compat.Lang.autoCode("en_GB"), "en");
      check("region tag zh_CN matches exactly / 区域标签 zh_CN 精确命中", com.bridger.compat.Lang.autoCode("zh_CN"), "zh_CN");
      check("no matching language pack -> default / 没有对应语言包 → 默认", com.bridger.compat.Lang.autoCode("ja_JP"), "en");
      check("an empty value also counts as auto / 空值也算 auto", Boolean.valueOf(com.bridger.compat.Lang.isAuto("")), Boolean.TRUE);
      com.bridger.compat.Lang.load("en");

      // ---- 19. void grace: a jump down to a lower finish island is not a void fall ----
      // ---- 19. 虚空宽限：跳到更低的终点岛不算掉虚空 ----
      System.out.println("[19] void grace vs. a jump down to the finish island / 虚空宽限 vs. 跳向终点岛");

      // The island sits four blocks below the bridge and the plate is on it. Everything the old
      // rule looked at (y falling past groundY-2.5) happens on the way down, which is exactly why
      // it used to report a successful jump as a failure.
      // 终点岛比桥低四格，板在岛上。旧规则看的那个条件（y 跌破 groundY−2.5）在下坠途中必然成立，
      // 这正是成功跳下去被报成失败的原因。
      Tracker.reset();
      Tracker.setTickSource(() -> -1L);
      Tracker.setTimeSource(() -> -1.0D);
      Tracker.setPlateProbe((x, y, z) -> y <= 66.05D);
      Tracker.noteY(70.0); Tracker.onTick(0.0, 70.0, 0.0, true);      // t=1, standing on the bridge
      Tracker.onBlockPlaced();                                       // run start, groundY=70
      Tracker.onJump();                                              // take off towards the island
      double[] fall = {69.2D, 68.1D, 67.0D, 66.3D};
      for (double y : fall) {
         Tracker.noteY(y); Tracker.onTick(0.4, y, 0.0, false);
      }
      check("crossed groundY-2.5 while airborne, not a failure yet / 空中穿过 groundY−2.5，尚未判负",
         Tracker.getStatus(), Tracker.RunStatus.BRIDGING);
      Tracker.noteY(66.0); Tracker.onTick(0.9, 66.0, 0.0, true);     // landed on the plate
      check("landed on the lower island and pressed the plate -> SUCCESS / 落在低处岛上并踩板 → 成功",
         Tracker.getStatus(), Tracker.RunStatus.SUCCESS);

      // A real void fall never lands, so the same line is crossed but the airborne counter keeps
      // running past the grace window.
      // 真掉虚空永远不落地：同样穿过那条线，但空中计数会一路跑过宽限窗口。
      Tracker.reset();
      Tracker.setTimeSource(() -> -1.0D);
      Tracker.setPlateProbe((x, y, z) -> false);
      Tracker.noteY(70.0); Tracker.onTick(0.0, 70.0, 0.0, true);      // t=1
      Tracker.onBlockPlaced();                                       // startTick=1
      Tracker.noteY(70.0); Tracker.onTick(0.0, 70.0, 0.0, true);      // t=2
      Tracker.onJump();                                              // lastJumpTick=2
      for (int i = 1; i <= 49; i++) {
         double y = 70.0D - i * 0.6D;
         Tracker.noteY(y); Tracker.onTick(1.0 + i * 0.05D, y, 0.0, false);
      }
      check("49 airborne ticks -> still bridging / 离地 49 tick → 仍在搭路",
         Tracker.getStatus(), Tracker.RunStatus.BRIDGING);
      Tracker.noteY(70.0D - 50 * 0.6D);
      Tracker.onTick(4.0, 70.0D - 50 * 0.6D, 0.0, false);
      check("50 airborne ticks -> void fail / 离地 50 tick → 判掉虚空",
         Tracker.getStatus(), Tracker.RunStatus.FAILED);
      // The grace window is spent falling, so the failed run must not carry those 2.5 s.
      // 宽限期是在下坠，失败成绩里不能算进这两秒半。
      check("failed run reports up to its last jump (0.05 s) / 失败成绩只报到最后一跳（0.05 秒）",
         Boolean.valueOf(Tracker.getCurrentBridgeTime() < 0.5D), Boolean.TRUE);

      // Landing but never reaching the plate: three seconds of standing still closes the run.
      // 落到岛上却始终没踩到板：站住三秒就收尾。
      Tracker.reset();
      Tracker.setTimeSource(() -> -1.0D);
      Tracker.setPlateProbe((x, y, z) -> false);
      Tracker.noteY(70.0); Tracker.onTick(0.0, 70.0, 0.0, true);      // t=1
      Tracker.onBlockPlaced();                                       // start
      Tracker.noteY(70.0); Tracker.onTick(0.0, 70.0, 0.0, true);      // t=2
      // jumpCount only advances from the second jump on - the opening writes an entry but leaves
      // the counter at zero - so the run needs two take-offs before the stall check can see it.
      // jumpCount 从第二跳才开始递增（开局只写条目），所以要有两跳，停滞判定才看得见。
      Tracker.onJump();                                              // first jump: entry only
      Tracker.noteY(70.0); Tracker.onTick(0.1, 70.0, 0.0, false);     // airborne
      Tracker.noteY(68.0); Tracker.onTick(0.2, 68.0, 0.0, true);      // landed on the island
      Tracker.onJump();                                              // second jump: jumpCount=1
      Tracker.noteY(68.0); Tracker.onTick(0.3, 68.0, 0.0, false);     // airborne again
      // Standing still: x does not move, so every grounded tick counts.
      // 原地站着：x 不动，每个落地 tick 都计数。
      for (int i = 0; i < 60; i++) {
         Tracker.noteY(68.0); Tracker.onTick(0.2, 68.0, 0.0, true);
      }
      check("60 grounded ticks -> still bridging / 落地 60 tick → 仍在搭路",
         Tracker.getStatus(), Tracker.RunStatus.BRIDGING);
      Tracker.noteY(68.0); Tracker.onTick(0.2, 68.0, 0.0, true);
      check("61 grounded ticks -> stalled / 落地 61 tick → 判卡住",
         Tracker.getStatus(), Tracker.RunStatus.FAILED);

      // Walking to the plate takes a while: 40 grounded ticks on the island must still finish.
      // 走到板要花时间：落地后在岛上走 40 tick 仍然必须判成功。
      final boolean[] plateReached = {false};
      Tracker.reset();
      Tracker.setTimeSource(() -> -1.0D);
      Tracker.setPlateProbe((x, y, z) -> plateReached[0]);
      Tracker.noteY(70.0); Tracker.onTick(0.0, 70.0, 0.0, true);
      Tracker.onBlockPlaced();
      Tracker.noteY(70.0); Tracker.onTick(0.0, 70.0, 0.0, true);
      Tracker.onJump();
      Tracker.noteY(70.0); Tracker.onTick(0.1, 70.0, 0.0, false);
      Tracker.noteY(68.0); Tracker.onTick(0.2, 68.0, 0.0, true);
      Tracker.onJump();
      Tracker.noteY(68.0); Tracker.onTick(0.3, 68.0, 0.0, false);
      for (int i = 0; i < 40; i++) {
         Tracker.noteY(68.0); Tracker.onTick(0.2 + i * 0.05D, 68.0, 0.0, true);
      }
      plateReached[0] = true;
      Tracker.noteY(68.0); Tracker.onTick(2.3, 68.0, 0.0, true);
      check("40 grounded ticks then the plate -> SUCCESS / 落地 40 tick 后踩板 → 成功",
         Tracker.getStatus(), Tracker.RunStatus.SUCCESS);

      // Walking the last stretch of the finish island must not be closed as a stall: 80 grounded
      // ticks with movement, no jump, no plate.
      // 走终点岛最后一段不能判成卡住：80 个有位移、没跳、没踩板的落地 tick。
      Tracker.reset();
      Tracker.setTimeSource(() -> -1.0D);
      Tracker.setPlateProbe((x, y, z) -> false);
      Tracker.noteY(70.0); Tracker.onTick(0.0, 70.0, 0.0, true);
      Tracker.onBlockPlaced();
      Tracker.noteY(70.0); Tracker.onTick(0.0, 70.0, 0.0, true);
      Tracker.onJump();
      Tracker.noteY(70.0); Tracker.onTick(0.1, 70.0, 0.0, false);
      Tracker.noteY(68.0); Tracker.onTick(0.2, 68.0, 0.0, true);
      Tracker.onJump();
      Tracker.noteY(68.0); Tracker.onTick(0.3, 68.0, 0.0, false);
      for (int i = 0; i < 80; i++) {
         double walkX = 0.2 + i * 0.3D;
         Tracker.noteY(68.0); Tracker.onTick(walkX, 68.0, 0.0, true);
      }
      check("walking 80 ticks to the plate is not a stall / 走向板 80 tick 不判卡住",
         Tracker.getStatus(), Tracker.RunStatus.BRIDGING);

      // A server that yanks the player out of the void moves them up dozens of blocks in one tick.
      // Without the teleport test the run is never closed at all: the rescue happens before the
      // 50-tick grace window and landing clears the counter.
      // 服务器把人从虚空里拉回来，一 tick 向上几十格。没有传送检测这一局根本不会收尾：
      // 救援发生在 50 tick 宽限之前，落地就把计数清零了。
      Tracker.reset();
      Tracker.setTimeSource(() -> -1.0D);
      Tracker.setPlateProbe((x, y, z) -> false);
      Tracker.noteY(70.0); Tracker.onTick(0.0, 70.0, 0.0, true);
      Tracker.onBlockPlaced();
      Tracker.noteY(70.0); Tracker.onTick(0.0, 70.0, 0.0, true);
      Tracker.onJump();
      for (int i = 1; i <= 10; i++) {
         double y = 70.0 - i * 4.0D;
         Tracker.noteY(y); Tracker.onTick(0.1 * i, y, 0.0, false);
      }
      check("still bridging 10 ticks into the fall / 下坠 10 tick 仍未收尾",
         Tracker.getStatus(), Tracker.RunStatus.BRIDGING);
      Tracker.noteY(65.0); Tracker.onTick(1.6, 65.0, 0.0, true);   // rescued back onto the island
      check("rescued from the void -> failed / 被服务器救回 → 判失败",
         Tracker.getStatus(), Tracker.RunStatus.FAILED);
      check("the rescue is reported as Void, not Teleport / 该次判定的失败条目是失误行",
         Tracker.getEntries().get(Tracker.getEntries().size() - 1).getType(),
         JumpEntry.EntryType.FAIL_TAP);

      // ---- 20. the first-placement gate: nothing runs before a block goes down ----
      // ---- 20. 首个方块门：一块没放下之前什么都不跑 ----
      System.out.println("[20] no run before the first placement / 首个方块之前不开局");

      // The reported bug, replayed exactly. Joining a world with a working block hook, jumping,
      // falling into the void and jumping again used to produce timer + entries + FAILED, over and
      // over, until a block was finally placed. The take-off edge must now be inert on any client
      // whose controller is hookable, and inert by default until told otherwise.
      // 现场报的 bug，原样重放：方块钩子正常的客户端上进世界、跳、掉虚空、再跳，以前会反复产出
      // 计时+条目+失败，直到真的放下一块才停。现在只要控制器可钩，起跳边沿就必须完全无效；
      // 而默认值（没人告诉它）也必须是无效。
      Tracker.reset();
      Tracker.setTakeOffMayStart(false);
      Tracker.setTickSource(() -> -1L);
      Tracker.setTimeSource(() -> -1.0D);
      Tracker.setPlateProbe((x, y, z) -> false);
      Tracker.noteY(70.0); Tracker.onTick(0.0, 70.0, 0.0, true);   // t=1 standing
      for (int i = 0; i < 3; i++) {
         // A jump: the agent reports the take-off edge from the tick loop.
         // 一次起跳：agent 从 tick 循环里报这个起跳边沿。
         Tracker.onTakeOff();
         Tracker.noteY(70.42); Tracker.onTick(0.3 + i, 70.42, 0.0, false);
         fallToVoid(70.42);
         check("jump #" + (i + 1) + " after world join does not start a run / 进世界后第 "
            + (i + 1) + " 次跳跃不开局", Tracker.getStatus(), Tracker.RunStatus.IDLE);
      }
      check("no entries from bare jumping / 裸跳不产生任何条目",
         Integer.valueOf(Tracker.getEntries().size()), Integer.valueOf(0));
      check("clock never started / 计时没有开始",
         Double.valueOf(Tracker.getCurrentBridgeTime()), Double.valueOf(0.0));

      // The first real placement opens the run, and only then does a jump count.
      // 第一次真正的放置才开这一局，之后跳跃才算数。
      Tracker.noteY(70.0); Tracker.onTick(0.0, 70.0, 0.0, true);
      Tracker.onBlockPlaced(4, 9);
      check("the first placement opens the run / 首次放置开局",
         Tracker.getStatus(), Tracker.RunStatus.BRIDGING);
      check("the run has its first placement / 这一局已满足首个放置",
         Boolean.valueOf(Tracker.hasFirstPlacement()), Boolean.TRUE);

      // ---- 20b. a take-off client still needs a placement before anything is recorded ----
      // ---- 20b. 起跳开局客户端也必须先放下方块才记录 ----
      System.out.println("[20b] take-off as a fallback still waits for a block / 起跳兜底也要等方块");
      Tracker.reset();
      Tracker.setTakeOffMayStart(true);
      Tracker.setTimeSource(() -> -1.0D);
      Tracker.setPlateProbe((x, y, z) -> false);
      Tracker.noteY(70.0); Tracker.onTick(0.0, 70.0, 0.0, true);
      Tracker.onTakeOff();
      check("on a hookless client a take-off may open the run / 无钩子客户端起跳可以开局",
         Tracker.getStatus(), Tracker.RunStatus.BRIDGING);
      check("but it is not a placement / 但起跳不算放置",
         Boolean.valueOf(Tracker.hasFirstPlacement()), Boolean.FALSE);
      Tracker.noteY(70.42); Tracker.onTick(0.3, 70.42, 0.0, false);
      check("so the jump is not recorded yet / 所以这次跳跃还不记录",
         Integer.valueOf(Tracker.getEntries().size()), Integer.valueOf(0));
      // The run dies in the void; the next take-off must not resurrect it before the cooldown and
      // the placement gate both clear.
      // 这一局掉虚空死掉；在冷却与放置门都放行之前，下一次起跳不许把它复活。
      fallToVoid(70.42);
      check("the void fall fails the run / 掉虚空判失败",
         Tracker.getStatus(), Tracker.RunStatus.FAILED);
      Tracker.noteY(70.0); Tracker.onTick(0.0, 70.0, 0.0, true);
      Tracker.onTakeOff();
      check("the next take-off does not restart it / 下一次起跳不重开",
         Tracker.getStatus(), Tracker.RunStatus.FAILED);

      // ---- 20c. reset() must clear the placement precondition ----
      // ---- 20c. reset() 必须清掉「已放置」前置条件 ----
      System.out.println("[20c] reset clears the placement precondition / reset 清掉放置前置");
      Tracker.reset();
      Tracker.setTakeOffMayStart(true);
      check("after reset nothing is recorded as placed / reset 之后不残留已放置状态",
         Boolean.valueOf(Tracker.hasFirstPlacement()), Boolean.FALSE);
      Tracker.setTakeOffMayStart(false);

      Tracker.setPlateProbe((x, y, z) -> false);
      Tracker.setTimeSource(() -> -1.0D);
      System.out.println(failures == 0 ? "\nAll checks passed / 全部通过" : "\n" + failures + " mismatches / 有 " + failures + " 项不符");
      System.exit(failures == 0 ? 0 : 1);
   }

   /**
    * Reads properties in order of appearance: returns {key, value}, used to check duplicate keys and
    * empty values at once.
    *
    * 按出现顺序读 properties：返回 {键, 值}，用于同时查重复键与空值。
    */
   private static java.util.List<String[]> readPack(String resource) throws java.io.IOException {
      java.io.InputStream in = BtSyncTest.class.getResourceAsStream(resource);
      if (in == null) {
         throw new java.io.IOException("missing " + resource);
      }
      java.util.List<String[]> rows = new java.util.ArrayList<String[]>();
      java.io.BufferedReader reader = new java.io.BufferedReader(
         new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
      try {
         String line;
         while ((line = reader.readLine()) != null) {
            String text = line.trim();
            if (text.isEmpty() || text.startsWith("#") || text.startsWith("!")) {
               continue;
            }
            int cut = separatorIndex(text);
            if (cut < 0) {
               continue;
            }
            rows.add(new String[] { text.substring(0, cut).trim(), text.substring(cut + 1).trim() });
         }
      } finally {
         reader.close();
      }
      return rows;
   }

   /**
    * The key/value separator in properties is the first unescaped {@code =} or {@code :}; here only
    * our own packs are handled.
    *
    * properties 的键值分隔符是第一个未转义的 {@code =} 或 {@code :}，这里只处理我们自己的包。
    */
   private static int separatorIndex(String text) {
      int eq = text.indexOf('=');
      int colon = text.indexOf(':');
      if (eq < 0) {
         return colon;
      }
      if (colon < 0) {
         return eq;
      }
      return Math.min(eq, colon);
   }
}
