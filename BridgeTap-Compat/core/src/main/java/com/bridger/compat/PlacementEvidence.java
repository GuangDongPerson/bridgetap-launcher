package com.bridger.compat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Client-observed block placements, used to qualify bridging labels.
 *
 * <p>Ported from upstream 1.5.0. Upstream records the <b>placed block</b> — it can diff the world
 * state around the clicked position, see {@code PlacementHooks}. The injector reaches the game only
 * through profile-driven reflective hooks, but the right-click hook does hand over the clicked
 * {@code BlockPos} and the clicked {@code EnumFacing}, so the same answer is available one step
 * cheaper: an empty position takes the block itself, a solid one takes its neighbour across the
 * face. That is {@link #record(int, int, int, double, double)}.
 *
 * <p>The older {@link #record(int, double, double)} still exists for clients where the hook cannot
 * read those arguments. It records the player's own column instead, which is only a proxy: during a
 * bridge the player covers two blocks in twelve ticks while four blocks go down, so a distinct-column
 * count taken from the player under-reports and a real 4b Flat used to be rejected by it.
 *
 * 移植自上游 1.5.0。上游记的是<b>放下的那个方块</b>（它能比对点击位置前后的世界状态，见
 * {@code PlacementHooks}）。注入器只有反射钩子，但右键钩子本身带着被点击的 {@code BlockPos}
 * 与 {@code EnumFacing}，所以同样的答案可以更省地拿到：点击位置是空气就把块记在那里，
 * 是实心就把块记在越过那个面的相邻格——即 {@link #record(int, int, int, double, double)}。
 *
 * <p>{@link #record(int, double, double)} 留给读不到这两个参数的客户端：它记玩家自己所在的列，
 * 而那只是个代理值——过桥时玩家 12 tick 走两格却放下四块，用玩家列数去数会明显偏少，
 * 真正的 4b Flat 就是这么被否掉的。
 */
public final class PlacementEvidence {
   private static final List<Event> EVENTS = new ArrayList<Event>();

   private static final class Event {
      final int tick;
      final int x;
      final int z;
      /** False when the column is the player's own, not the block's. */
      final boolean block;

      Event(int tick, int x, int z, boolean block) {
         this.tick = tick;
         this.x = x;
         this.z = z;
         this.block = block;
      }
   }

   private PlacementEvidence() {
   }

   public static void reset() {
      EVENTS.clear();
   }

   /** Records the block that went down. Preferred over the player-column overload. */
   public static void record(int tick, int blockX, int blockZ, double playerX, double playerZ) {
      prune(tick);
      EVENTS.add(new Event(tick, blockX, blockZ, true));
   }

   /** @param tick the tick the placement was observed on; falls back to the player's own column */
   public static void record(int tick, double playerX, double playerZ) {
      prune(tick);
      EVENTS.add(new Event(tick, (int)Math.floor(playerX), (int)Math.floor(playerZ), false));
   }

   private static void prune(int tick) {
      EVENTS.removeIf(event -> tick - event.tick > 81);
   }

   /**
    * @param from first tick of the interval (inclusive)
    * @param to   last tick of the interval (inclusive)
    * @param count how many distinct block columns must have been touched
    */
   public static boolean qualifies(int from, int to, double start, double end, boolean xAxis, int count) {
      double distance = end - start;
      if (Math.abs(distance) < 0.1) {
         return false;
      }
      return columnsBetween(from, to) >= count;
   }

   /** Diagnostics only. */
   public static int size() {
      return EVENTS.size();
   }

   /** Diagnostics only: placement events recorded inside {@code [from, to]}. */
   public static int eventsBetween(int from, int to) {
      int n = 0;
      for (Event event : EVENTS) {
         if (event.tick >= from && event.tick <= to) {
            n++;
         }
      }
      return n;
   }

   /**
    * Diagnostics only: the events inside {@code [from, to]} written as {@code tick@x:z}, capped so a
    * long window cannot flood the log. A leading {@code ~} marks a column that came from the player
    * instead of the block, i.e. the client could not report where the block went.
    *
    * <p>Exists because the column count alone cannot explain a rejection: five placements inside
    * four blocks of travel scored two columns, which only the raw per-event ticks can account for.
    *
    * 仅诊断用：窗口内的事件写成 {@code tick@x:z}，有上限，免得长窗口刷爆日志。开头的 {@code ~}
    * 表示这一列取自玩家而不是方块，即客户端报不出块落在哪。
    * 光看列数解释不了一次拒绝——四格位移里五次放置只算出两列，只有逐条事件能说清为什么。
    */
   public static String describeBetween(int from, int to) {
      StringBuilder text = new StringBuilder();
      int shown = 0;
      for (Event event : EVENTS) {
         if (event.tick < from || event.tick > to) {
            continue;
         }
         if (shown == 12) {
            text.append(",…");
            break;
         }
         if (shown > 0) {
            text.append(',');
         }
         if (!event.block) {
            text.append('~');
         }
         text.append(event.tick).append('@').append(event.x).append(':').append(event.z);
         shown++;
      }
      return shown == 0 ? "-" : text.toString();
   }

   /** Diagnostics only: how many events in the interval carry a real block column. */
   public static int blockEventsBetween(int from, int to) {
      int n = 0;
      for (Event event : EVENTS) {
         if (event.tick >= from && event.tick <= to && event.block) {
            n++;
         }
      }
      return n;
   }

   /** Diagnostics only: distinct block columns recorded inside {@code [from, to]}. */
   public static int columnsBetween(int from, int to) {
      Set<String> blocks = new HashSet<String>();
      for (Event event : EVENTS) {
         if (event.tick < from || event.tick > to) {
            continue;
         }
         blocks.add(event.x + ":" + event.z);
      }
      return blocks.size();
   }
}
