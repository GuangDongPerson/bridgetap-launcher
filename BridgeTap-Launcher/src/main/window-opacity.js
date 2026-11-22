'use strict';

/**
 * Whole-window dimming while the HUD stick is being dragged.
 *
 * <p>While the player drags the stick the launcher's own window would sit on top of the game and
 * hide the very HUD that is moving, so the window drops to 15% and comes straight back on release.
 *
 * <p>The rule that matters is that **the baseline belongs to this module, not to the renderer**.
 * The renderer only ever says "start" and "stop"; it never reads the current opacity and never
 * sends a number, because the opacity it would read back after a `start` is already the dim level.
 * One lost or duplicated message would then leave the window at 15% for good. Here the baseline is
 * captured exactly once — on the first `start` of a gesture — and `stop` restores that captured
 * value instead of any value observed later, so:
 *
 *  · repeated `start` cannot ratchet the baseline down towards the dim level,
 *  · repeated `stop` is a no-op, and
 *  · a `stop` that never arrives (renderer crash, window closed) cannot strand the window, because
 *    the baseline is only ever restored — never re-derived — and a fresh window starts at 1.
 *
 * 摇杆拖动期间的整窗变淡。
 *
 * <p>玩家拖摇杆时，启动器自己的窗口正好压在游戏上，会挡住那个正在移动的 HUD，所以拖动期间窗口
 * 压到 15%，松手立刻还原。
 *
 * <p>关键规则是**基准值归本模块所有，不归渲染层**。渲染层只会说"开始"和"结束"，既不读当前
 * 透明度、也不传数字——因为它在 `start` 之后读回来的已经是变淡值了。只要丢一条或重一条消息，
 * 窗口就会永远停在 15%。这里基准值只在一个手势的第一次 `start` 时抓取一次，`stop` 还原的是
 * 这个抓下来的值、而不是之后观察到的任何值，于是：
 *
 *  · 重复的 `start` 不会把基准一路往变淡值上拉，
 *  · 重复的 `stop` 什么也不做，
 *  · 没等到 `stop` 的情况（渲染层崩了、窗口被关）也不会把窗口困住：基准只会被还原、不会被重新
 *    推导，而新窗口的起点是 1。
 */

/**
 * Opacity used while the stick is held. Low enough to see the HUD through the window, high enough
 * that the panel is still recognisable if the drag has to be aborted.
 *
 * 按住摇杆时用的透明度。低到能透过窗口看见 HUD，又高到万一要中止拖动时面板还认得出。
 */
const DIM_OPACITY = 0.15;

/**
 * @param {() => import('electron').BrowserWindow|null} resolveWindow
 * @returns {{apply:(win:import('electron').BrowserWindow, dim:boolean)=>{ok:boolean,dimmed:boolean},
 *            reset:()=>void, isDimmed:()=>boolean}}
 */
function createOpacityController(resolveWindow) {
  /** Opacity to restore, captured on the first `start` of a gesture. `null` = not dimmed. */
  let baseline = null;

  const isDimmed = () => baseline !== null;

  /**
   * Put the window at `value`, clamping to the range Electron accepts.
   *
   * <p>Electron throws for anything outside 0–1 rather than clamping, and the values here come from
   * two sources (our own constant and whatever the window reports), so the guard belongs here.
   *
   * 把窗口设成 `value`，并夹到 Electron 接受的范围内。
   *
   * <p>超出 0–1 时 Electron 会抛异常而不是夹紧，而这里的值来自两处（我们自己的常量、窗口报的
   * 当前值），所以这道保险放在这里。
   */
  function setOpacity(win, value) {
    if (!win || win.isDestroyed()) {
      return false;
    }
    // `setOpacity` is a no-op on some Linux window managers and throws on a hidden window in older
    // Electron builds; the caller treats a failure as "could not dim", never as fatal.
    // 某些 Linux 窗口管理器会忽略 `setOpacity`，旧版 Electron 对隐藏窗口会抛异常；
    // 调用方只把它当作"没能变淡"，不是致命错误。
    try {
      win.setOpacity(Math.min(1, Math.max(0, value)));
      return true;
    } catch {
      return false;
    }
  }

  /**
   * @param {boolean} dim `true` = drop to the dim level, `false` = restore.
   */
  function apply(win, dim) {
    const target = win || resolveWindow();
    if (!target || target.isDestroyed()) {
      // Nothing to act on. Do not touch the state: a window created later starts at 1, so keeping
      // the flag set would only promise a restore we can no longer perform.
      // 没有可操作的对象。不要改状态：之后创建的窗口起点就是 1，保留标记只会许下一个再也兑不了的还原。
      return { ok: false, dimmed: isDimmed() };
    }

    if (dim) {
      if (baseline === null) {
        // First `start` of the gesture. `getOpacity()` is the truth here: it may be 1, or whatever
        // the platform reports for a window that was never touched.
        // 这个手势的第一次 `start`。这里以 `getOpacity()` 为准：可能是 1，也可能是平台对从未动过的
        // 窗口报的值。
        const current = Number(target.getOpacity());
        baseline = Number.isFinite(current) && current > 0 ? current : 1;
      }
      // Re-applied on every `start` even when already dimmed: a stray `stop` between two `start`s
      // must not leave the window bright for the rest of the drag.
      // 即使已经在变淡状态，每次 `start` 也再设一次：两次 `start` 之间来了一条多余的 `stop`，
      // 不能让它把剩下的拖动都留成亮的。
      const ok = setOpacity(target, Math.min(baseline, DIM_OPACITY));
      return { ok, dimmed: isDimmed() };
    }

    if (baseline === null) {
      return { ok: true, dimmed: false }; // never dimmed; nothing to restore · 没变淡过，无需还原
    }
    const restore = baseline;
    // Clear the flag *before* touching the window: if `setOpacity` throws we would otherwise be
    // stuck "dimmed" forever and every later `stop` would retry a call that keeps failing.
    // 先清标记再动窗口：万一 `setOpacity` 抛异常，否则就会永远卡在"变淡中"，
    // 之后每次 `stop` 都在重试一个一直失败的调用。
    baseline = null;
    setOpacity(target, restore);
    return { ok: true, dimmed: false };
  }

  return {
    apply,
    /**
     * Forget the baseline without touching any window. Used when the window goes away: the next one
     * is created at opacity 1, so keeping a stale baseline would be a lie.
     *
     * 不清任何窗口、只忘掉基准。窗口消失时用它：下一个窗口是按 1 建出来的，
     * 留着旧基准只是自欺欺人。
     */
    reset() {
      baseline = null;
    },
    isDimmed
  };
}

module.exports = { createOpacityController, DIM_OPACITY };