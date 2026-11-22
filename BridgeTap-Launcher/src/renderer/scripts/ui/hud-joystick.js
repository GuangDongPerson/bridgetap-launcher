/**
 * HUD quick-adjust stick.
 *
 * <p>A virtual analogue stick that edits the two percentage anchors (`hudXPercent` / `hudYPercent`)
 * without touching the number boxes those values still live in. Two things matter here:
 *
 * <ol>
 *   <li><b>It is a delta control, not a position selector.</b> The value moves with the pointer's
 *       travel, not with where the knob sits inside its base, so a short drag gives a short change
 *       and a long drag a long one — the same gesture always produces the same result. Holding the
 *       knob still never lets the value drift on its own, which a rate-style stick would do and
 *       which is horrible for a position that has to stay put.</li>
 *   <li><b>It never owns the value.</b> Every step goes straight out through `onMove`, so the panel
 *       holds no hidden state: closing it cannot roll anything back, and the number boxes stay the
 *       source of truth for what is on disk.</li>
 * </ol>
 *
 * 摇杆快捷调节面板。
 *
 * <p>一个虚拟摇杆，改的是两个百分比锚点（`hudXPercent` / `hudYPercent`），并不取代那两个数字框。两条原则：
 *
 * <ol>
 *   <li><b>它是增量控件，不是位置选择器。</b>数值跟着指针的位移走，不跟着摇杆在底座里的位置走，
 *       所以短拖小改、长拖大改，同一个手势永远得到同样的结果。按住不动时数值也不会自己漂——
 *       速率式摇杆会漂，而一个"位置"最忌讳的就是自己乱动。</li>
 *   <li><b>它不持有数值。</b>每一步都通过 `onMove` 立刻交出去，面板里没有隐藏状态：
 *       关掉它不可能回滚任何东西，磁盘上的值也始终以那两个数字框为准。</li>
 * </ol>
 */

import { t } from '../i18n.js';
import { el } from './render.js';

/**
 * Percent units covered by one full deflection (one base radius).
 *
 * <p>18% over ~90 px keeps a single pixel at a usable 0.2% step: big enough to feel responsive,
 * small enough that the HUD does not fly across the screen on a twitch. Shift cuts it to a quarter.
 *
 * 推到底（一个底座半径）对应的百分比跨度。
 *
 * <p>18% 摊到约 90px 上，1px 约 0.2%：既跟手，又不会手一抖 HUD 就飞出屏幕。按住 Shift 降到四分之一。
 */
const SPAN = 18;

/** Fine-drag multiplier (Shift held) · 精细拖动倍率（按住 Shift） */
const FINE = 0.25;

/** Arrow-key step, and the Shift variant · 方向键步长，以及按住 Shift 时的步长 */
const KEY_STEP = 1;
const KEY_STEP_FINE = 0.1;

const MIN_PERCENT = 0;
const MAX_PERCENT = 100;

const clamp = (value, lo, hi) => Math.min(hi, Math.max(lo, value));

/** The number boxes work in tenths of a percent; the stick must not produce anything finer. · 数字框精度是 0.1，摇杆不能更细 */
const round1 = (value) => Math.round(value * 10) / 10;

/**
 * Open the panel.
 *
 * @param {object} options
 * @param {number} options.x current horizontal percent · 当前横向百分比
 * @param {number} options.y current vertical percent · 当前纵向百分比
 * @param {number} [options.scale] HUD scale, only used to size the preview block · HUD 缩放，仅用于预览块大小
 * @param {() => void} [options.onStart] once per burst of interaction, before the first move · 每段连续操作开始时回调一次
 * @param {(x:number, y:number)=>void} options.onMove called on every step, already rounded/clamped · 每次变化都回调，已取整并夹紧
 * @param {(x:number, y:number)=>void} [options.onCommit] called when the pointer is released / a key is pressed · 松手或按键后回调
 * @param {(dim:boolean)=>void} [options.onDimChange] `true` when the value starts changing, `false`
 *   the moment it stops — release, lost capture, close, blur. Only edge-triggered: the panel never
 *   repeats the same state, so the receiver can treat it as "the gesture started / ended" rather
 *   than "still holding". · `true` = 数值开始变化，`false` = 停止变化（松手、捕获丢失、关闭、失焦）。
 *   只在边沿触发：同一个状态不会重复发，接收方可以把它当成"手势开始/结束"，而不是"仍按着"。
 * @param {() => void} [options.onClose]
 * @param {HTMLElement} [options.trigger] element to give focus back to · 关闭后交还焦点的元素
 * @returns {{close: () => void}} handle · 句柄
 */
export function openHudJoystick(options) {
  const opts = options || {};
  const state = {
    x: clamp(round1(Number(opts.x) || 0), MIN_PERCENT, MAX_PERCENT),
    y: clamp(round1(Number(opts.y) || 0), MIN_PERCENT, MAX_PERCENT),
    scale: Number(opts.scale) > 0 ? Number(opts.scale) : 1
  };

  /* ── DOM ───────────────────────────────────────────────── */

  const overlay = el('div', 'hudstick');
  overlay.setAttribute('role', 'presentation');

  const scrim = el('div', 'hudstick__scrim');
  scrim.addEventListener('click', () => close());

  const card = el('div', 'hudstick__card');
  card.setAttribute('role', 'dialog');
  card.setAttribute('aria-modal', 'true');
  card.setAttribute('aria-labelledby', 'hudStickTitle');

  const title = el('h2', 'hudstick__title', t('hudStick.title'));
  title.id = 'hudStickTitle';
  // Title only, no subtitle: a sentence there restated what the moving block on the right already
  // shows. The one line worth keeping — the Shift modifier, which nothing else in the panel
  // reveals — lives in the hint under the body.
  // 只留标题，不要副标题：在那里写一句，说的就是右边那块跟着动的预览已经在展示的事。
  // 真正值得留的一句是 Shift——整个面板没有别的地方能看出它存在——放在正文下面的提示行。
  const head = el('header', 'hudstick__head');
  head.append(title);

  /* Preview: a 16:9 stand-in for the game window. The block travels across the room left over
     after its own width/height is taken out — 0% is flush left/top, 100% flush right/bottom, 50%
     dead centre — exactly what the agent computes now. There is deliberately no edge flip: flipping
     at the midpoint threw the panel its own width sideways the moment the number crossed 50. */
  /* 预览：一块 16:9 的"游戏窗口"。小块在"扣掉自身宽高后剩下的余量"上移动——0% 贴左/上、
     100% 贴右/下、50% 正中——与 agent 现在算的完全一致。这里刻意不做换边：
     在 50% 处翻转会让面板在数字跨过中点的一瞬间横移自身一个宽度。 */
  const previewLabel = el('span', 'hudstick__caption', t('hudStick.preview'));
  const screen = el('div', 'hudstick__screen');
  const crossV = el('span', 'hudstick__cross hudstick__cross--v');
  const crossH = el('span', 'hudstick__cross hudstick__cross--h');
  const ghost = el('div', 'hudstick__ghost');
  ghost.append(el('span', 'hudstick__ghost-line'), el('span', 'hudstick__ghost-line hudstick__ghost-line--short'));
  screen.append(crossV, crossH, ghost);

  /* The pad itself. `--kx` / `--ky` carry the knob offset; CSS owns the visuals. */
  /* 摇杆本体。偏移量通过 `--kx` / `--ky` 交给 CSS 画。 */
  const base = el('div', 'hudstick__base');
  base.tabIndex = 0;
  base.setAttribute('role', 'application');
  base.setAttribute('aria-label', t('hudStick.padAria'));
  const ring = el('span', 'hudstick__ring');
  const marks = el('span', 'hudstick__marks');
  marks.setAttribute('aria-hidden', 'true');
  const knob = el('span', 'hudstick__knob');
  knob.append(el('span', 'hudstick__knob-grip'));
  // No rim labels: the knob covers them the moment it swings that way, which reads as text stuck on
  // the knob. The two readouts under the preview name the axes instead, and the hint says which
  // direction does what. · 底座上不写方向字：摇杆一荡过去就把它盖住，看着像字贴在摇杆上。
  // 轴名交给右栏两个读数，方向交给下面的提示行。
  base.append(ring, marks, knob);

  const readX = el('span', 'hudstick__num');
  const readY = el('span', 'hudstick__num');
  const rowX = el('div', 'hudstick__read');
  rowX.append(el('span', 'hudstick__read-label', t('hudStick.axisX')), readX);
  const rowY = el('div', 'hudstick__read');
  rowY.append(el('span', 'hudstick__read-label', t('hudStick.axisY')), readY);
  const reads = el('div', 'hudstick__reads');
  reads.append(rowX, rowY);

  const side = el('div', 'hudstick__side');
  side.append(previewLabel, screen, reads);

  const body = el('div', 'hudstick__body');
  body.append(base, side);

  const hint = el('p', 'hudstick__hint', t('hudStick.hint'));

  const done = el('button', 'modal__btn modal__btn--ghost', t('hudStick.done'));
  done.type = 'button';
  done.addEventListener('click', () => close());
  const actions = el('div', 'hudstick__actions');
  actions.append(done);

  card.append(head, body, hint, actions);
  overlay.append(scrim, card);

  /* ── Paint ─────────────────────────────────────────────── */

  function paint() {
    readX.textContent = state.x.toFixed(1) + '%';
    readY.textContent = state.y.toFixed(1) + '%';

    // Scale first: the block's own size decides the travel, so measuring before the scale lands
    // would position it with the previous geometry.
    // 先落缩放：余量由小块自身尺寸决定，缩放还没上就量，会拿上一版几何去摆位。
    ghost.style.setProperty('--hs', String(state.scale));

    // Same formula as GlHud.resolveAnchor: percent of the free travel, no edge flip, so crossing
    // 50% moves the block by a fraction of a pixel instead of a whole panel width.
    // 与 GlHud.resolveAnchor 同一套公式：按可移动余量的百分比摆，不换边，
    // 所以跨过 50% 时小块只挪一点点，而不是整整一个面板宽。
    const travelX = Math.max(0, screen.clientWidth - ghost.offsetWidth);
    const travelY = Math.max(0, screen.clientHeight - ghost.offsetHeight);
    ghost.style.left = (travelX * state.x / 100).toFixed(2) + 'px';
    ghost.style.top = (travelY * state.y / 100).toFixed(2) + 'px';
  }

  function setKnob(dx, dy) {
    const radius = base.clientWidth / 2;
    const limit = Math.max(0, radius - knob.offsetWidth / 2 - 4);
    const distance = Math.hypot(dx, dy);
    const k = distance > limit && distance > 0 ? limit / distance : 1;
    base.style.setProperty('--kx', (dx * k).toFixed(1) + 'px');
    base.style.setProperty('--ky', (dy * k).toFixed(1) + 'px');
  }

  /* ── Window dimming ────────────────────────────────────── */

  /**
   * Whether the value is currently changing, and therefore whether the window should be dimmed.
   *
   * <p>Edge-triggered on purpose. The receiver (main process) restores a remembered baseline, so it
   * only needs the two edges — sending the same state on every move would be pure noise on the IPC
   * channel, and worse, would give a "still dimmed" message the power to overwrite a restore that
   * happened because the gesture was interrupted.
   *
   * 数值当前是否正在变化，也就是窗口该不该变淡。
   *
   * <p>刻意做成边沿触发。接收方（主进程）还原的是它记下来的基准，所以只需要两个边沿——
   * 每次移动都发同一个状态纯粹是 IPC 噪音，更糟的是会让一条"仍在变淡"的消息有能力覆盖掉
   * 因为手势被打断而已经执行的还原。
   */
  let dimmed = false;

  function setDimmed(next) {
    if (next === dimmed) {
      return;
    }
    dimmed = next;
    if (opts.onDimChange) {
      opts.onDimChange(next);
    }
  }

  /** Re-assert dimming (idempotent in the receiver) · 重申变淡状态（接收方幂等） */
  function dimNow() {
    setDimmed(true);
  }

  /** Give the window back its opacity · 把透明度还给窗口 */
  function undimNow() {
    setDimmed(false);
  }

  function setValues(nextX, nextY) {
    const x = clamp(round1(nextX), MIN_PERCENT, MAX_PERCENT);
    const y = clamp(round1(nextY), MIN_PERCENT, MAX_PERCENT);
    if (x === state.x && y === state.y) {
      return;
    }
    state.x = x;
    state.y = y;
    // Dim on the first *actual* change, not on pointer-down: pressing and holding a motionless
    // stick must leave the window alone, exactly like merely opening the panel does.
    // 在第一次"真的变了"时才变淡，而不是按下的瞬间：按住不动必须跟只打开面板一样，
    // 完全不碰窗口透明度。
    dimNow();
    paint();
    if (opts.onMove) {
      opts.onMove(x, y);
    }
  }

  function commit() {
    if (opts.onCommit) {
      opts.onCommit(state.x, state.y);
    }
  }

  /* ── Pointer drag ──────────────────────────────────────── */

  /**
   * Announce a gesture once per burst of interaction.
   *
   * <p>app.js uses it to tell the agent to poll fast *before* the first pixel moves: the agent needs
   * up to one idle tick (500 ms) to notice any message, and that tick must happen while the hand is
   * still travelling to the stick, not while the panel is supposed to be following it. The 800 ms
   * quiet window keeps a held arrow key from firing the announcement on every repeat.
   *
   * 每段连续操作只宣布一次。
   *
   * <p>app.js 借此在"第一个像素移动之前"就让 agent 转成快轮询：agent 发现任何消息都要等一个空闲周期
   * （最多 500ms），这个周期得花在手还没摸到摇杆的时候，而不是花在面板该跟着动的时候。
   * 800ms 的静默窗口让按住方向键连发时不会每次都宣布。
   */
  let announceTimer = 0;
  function announce() {
    if (!announceTimer && opts.onStart) {
      opts.onStart();
    }
    if (announceTimer) {
      window.clearTimeout(announceTimer);
    }
    announceTimer = window.setTimeout(() => {
      announceTimer = 0;
    }, 800);
  }

  /* The grab point is remembered instead of assuming the knob centre: grabbing the edge of the
     base then moving 10 px must move the value by 10 px worth, not jump by half a radius. */
  /* 记下"抓住的那一点"，而不是假定从摇杆中心开始：抓住底座边缘再移 10px，值就该变 10px 的量，不该跳半个半径。 */
  let dragging = false;
  let pointerId = null;
  let originX = 0;
  let originY = 0;
  let startX = 0;
  let startY = 0;
  let fine = false;

  base.addEventListener('pointerdown', (event) => {
    if (event.button !== 0 || dragging) {
      return;
    }
    announce();
    dragging = true;
    pointerId = event.pointerId;
    fine = event.shiftKey;
    originX = event.clientX;
    originY = event.clientY;
    startX = state.x;
    startY = state.y;
    base.classList.add('is-dragging');
    setKnob(0, 0);
    try {
      base.setPointerCapture(event.pointerId);
    } catch {
      // A capture failure only costs us moves outside the window; the drag itself still works
      // 捕获失败最多是拖出窗口后收不到消息，拖动本身照常
    }
    base.focus({ preventScroll: true });
    event.preventDefault();
  });

  base.addEventListener('pointermove', (event) => {
    if (!dragging || event.pointerId !== pointerId) {
      return;
    }
    // Switching Shift mid-drag rebases instead of rescaling: without the rebase the value would
    // jump the moment the multiplier changed, because the whole travel is multiplied at once.
    // 拖动途中切换 Shift 要重设基准，而不是只换倍率：整段位移是乘出来的，直接换倍率会让数值瞬跳。
    if (event.shiftKey !== fine) {
      fine = event.shiftKey;
      originX = event.clientX;
      originY = event.clientY;
      startX = state.x;
      startY = state.y;
      setKnob(0, 0);
      return;
    }
    const dx = event.clientX - originX;
    const dy = event.clientY - originY;
    const radius = Math.max(1, base.clientWidth / 2);
    const perPx = (SPAN / radius) * (fine ? FINE : 1);
    setKnob(dx, dy);
    setValues(startX + dx * perPx, startY + dy * perPx);
  });

  function endDrag(event) {
    if (!dragging || (event && event.pointerId !== pointerId)) {
      return;
    }
    dragging = false;
    pointerId = null;
    base.classList.remove('is-dragging');
    base.classList.add('is-settling');
    base.style.setProperty('--kx', '0px');
    base.style.setProperty('--ky', '0px');
    window.setTimeout(() => base.classList.remove('is-settling'), 220);
    // Restore first, then commit: the window must be readable again the instant the hand leaves the
    // stick, not after a disk write round-trip. `undimNow` is unconditional — a press/release with
    // no movement never dimmed, but sending the stop edge anyway is how "pressed by accident, let
    // go immediately" stays clean instead of relying on the dim having happened.
    // 先还原再提交：手一离开摇杆窗口就该恢复可读，而不是等一次写盘往返。
    // `undimNow` 无条件调用——按下就松、数值没变的情况其实没变淡过，但照样发一次结束边沿，
    // 正是这样"误按马上松手"才能保证干净，而不是指望"变淡一定发生过"。
    undimNow();
    commit();
  }

  base.addEventListener('pointerup', endDrag);
  base.addEventListener('pointercancel', endDrag);
  base.addEventListener('lostpointercapture', endDrag);

  /* ── Keyboard ──────────────────────────────────────────── */

  /* Arrow keys are a gesture too: a held key repeats `keydown`, so dimming there would flicker on
     every repeat. Instead the key is a press-and-hold like the pointer — dim on the first keydown,
     restore on keyup — and `blur` covers the case where the keyup never arrives (focus moved away
     while the key was down). */
  /* 方向键也是一种手势：按住会连发 `keydown`，在那里变淡会每一下闪一次。所以按键和指针一样按
     "按下—松开"处理——第一个 keydown 变淡、keyup 还原——`blur` 兜住 keyup 永远不来的情况
     （按着键的时候焦点被移走）。 */
  const ARROW_KEYS = new Set(['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown']);
  /** True between an arrow keydown and its keyup · 方向键按下与松开之间为真 */
  let keyHeld = false;

  base.addEventListener('keydown', (event) => {
    const step = event.shiftKey ? KEY_STEP_FINE : KEY_STEP;
    const moves = {
      ArrowLeft: [-step, 0],
      ArrowRight: [step, 0],
      ArrowUp: [0, -step],
      ArrowDown: [0, step]
    };
    const delta = moves[event.key];
    if (!delta) {
      return;
    }
    event.preventDefault();
    announce();
    keyHeld = true;
    // Dimmed only when the value actually moves — at 0% or 100% the step is clamped away, and a
    // window that dims while the number refuses to change would look like a glitch.
    // 只有数值真的动了才变淡——在 0% 或 100% 时步进会被夹掉，而"数字不动窗口却暗了"看着像故障。
    const beforeX = state.x;
    const beforeY = state.y;
    setValues(state.x + delta[0], state.y + delta[1]);
    if (beforeX === state.x && beforeY === state.y && keyHeld) {
      // Nothing moved (already at the limit): drop the pending dim straight away
      // 一点没动（已经到头）：立刻撤掉这次还没生效的变淡
      keyHeld = false;
      undimNow();
    }
    commit();
  });

  base.addEventListener('keyup', (event) => {
    if (!ARROW_KEYS.has(event.key) || !keyHeld) {
      return;
    }
    keyHeld = false;
    undimNow();
  });

  /* ── Open / close ──────────────────────────────────────── */

  function onKeydown(event) {
    if (event.key === 'Escape') {
      event.preventDefault();
      close();
    }
  }

  /* The travel depends on the preview's pixel size, so a window resize has to repaint or the block
     would keep the old offset until the next drag. */
  /* 余量取决于预览的像素尺寸，窗口一变就得重画，否则小块会停在按旧尺寸算的位置上。 */
  function onResize() {
    paint();
  }

  /**
   * Abort any gesture in flight and put the window back.
   *
   * <p>The safety net for "the gesture never ends normally": the window loses focus mid-drag, the
   * panel is closed from a keyboard shortcut while a key is held, or the pointer leaves without a
   * `pointerup` ever firing. Without this the window could stay at 15% with no way back, because the
   * only code that restores is the release handler.
   *
   * 中止进行中的手势并把窗口还原。
   *
   * <p>这是"手势没有正常结束"的兜底：拖到一半窗口失焦、按着键时用快捷键关掉面板、或者指针离开却
   * 始终没收到 `pointerup`。没有它，窗口可能一直停在 15% 且无路可回，因为唯一负责还原的只有松手
   * 那个处理函数。
   */
  function abortGesture() {
    dragging = false;
    pointerId = null;
    keyHeld = false;
    base.classList.remove('is-dragging');
    base.style.setProperty('--kx', '0px');
    base.style.setProperty('--ky', '0px');
    undimNow();
  }

  /**
   * The main process put the opacity back on its own — clear our own edge flag to match.
   *
   * <p>This exists because `dimmed` is edge-triggered: if the window is minimized mid-drag, the
   * page may never see `blur`, so `dimmed` would stay `true` while the window is already opaque.
   * The panel would then swallow the next start edge (state "unchanged") and the window would stop
   * dimming for the rest of the session. Forgetting the flag — without sending anything, since the
   * window is already restored — lets the next real move send a fresh `true`.
   *
   * 主进程自己把透明度还回去了——把本地的边沿标记也清掉。
   *
   * <p>之所以需要它：`dimmed` 是边沿触发的。拖到一半窗口被最小化时页面可能根本收不到 `blur`，
   * 于是窗口已经恢复不透明、`dimmed` 却还停在 `true`。接下来面板会把开始边沿当成"状态没变"吞掉，
   * 这次运行剩下的时间里窗口就不再变淡。忘掉这个标记——不发任何消息，因为窗口已经还原了——
   * 下一次真实移动才会重新发出 `true`。
   */
  function syncUndimmed() {
    dragging = false;
    pointerId = null;
    keyHeld = false;
    base.classList.remove('is-dragging');
    base.style.setProperty('--kx', '0px');
    base.style.setProperty('--ky', '0px');
    // Assign directly instead of `undimNow()`: the receiver already restored the window, so a
    // `false` here would be a redundant round-trip, and — worse — it would be indistinguishable
    // from a real stop edge if the user had meanwhile started a new drag.
    // 直接改标记而不用 `undimNow()`：接收方已经还原了窗口，再发一个 `false` 是多余的往返，
    // 更糟的是，如果用户这时已经开始新一次拖动，它会被当成一次真正的结束边沿。
    dimmed = false;
  }

  /**
   * Losing focus can mean the button is still held but this window will no longer see the release
   * (a native menu, an alt-tab, an OS dialog). Restore opacity now rather than risk a stuck window.
   *
   * 失焦可能意味着按键还按着、但本窗口再也收不到松手（原生菜单、alt-tab、系统弹窗）。
   * 现在就还原，别冒卡住的风险。
   */
  function onBlur() {
    abortGesture();
  }

  let closed = false;
  function close() {
    if (closed) {
      return;
    }
    closed = true;
    if (announceTimer) {
      window.clearTimeout(announceTimer);
      announceTimer = 0;
    }
    // Closing must never leave the window dimmed, whether it was a click on "done", the scrim, Esc,
    // or a programmatic close while the stick was mid-drag.
    // 关面板绝不能留下变淡的窗口：不管是点"完成"、点遮罩、按 Esc，还是拖到一半被程序关掉。
    abortGesture();
    document.removeEventListener('keydown', onKeydown);
    window.removeEventListener('resize', onResize);
    window.removeEventListener('blur', onBlur);
    card.classList.remove('is-open');
    overlay.classList.remove('is-open');
    window.setTimeout(() => overlay.remove(), 220);
    if (opts.onClose) {
      opts.onClose(state.x, state.y);
    }
    if (opts.trigger && typeof opts.trigger.focus === 'function') {
      opts.trigger.focus({ preventScroll: true });
    }
  }

  // Mount before the first paint: the block's offsetWidth is zero until it is in the document, and
  // a zero-size block would report the whole preview as travel.
  // 先挂载再首绘：不在文档里 offsetWidth 是 0，零尺寸的小块会把整块预览都当成余量。
  document.body.append(overlay);
  paint();
  document.addEventListener('keydown', onKeydown);
  window.addEventListener('resize', onResize);
  window.addEventListener('blur', onBlur);
  void overlay.offsetWidth; // force a reflow so the opening transition actually plays · 强制回流，开场过渡才会跑
  overlay.classList.add('is-open');
  card.classList.add('is-open');
  done.focus({ preventScroll: true });

  return { close, syncUndimmed };
}
