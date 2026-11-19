/**
 * Motion layer (the single place responsible for "how things move").
 *
 * Rules:
 *   1. No business terms here: no pid, injection, JVMTI, client names; only scene names.
 *   2. Only classes / CSS variables / one-shot animations; no state machine is held.
 *   3. To swap the whole feel: replace this file + styles/motion.css, nothing else.
 *
 * 动效层（唯一负责"怎么动"的地方）。
 *
 * 规则：
 *   1. 这里不出现 pid、注入、JVMTI、客户端名等任何业务词；只认场景名。
 *   2. 只操作 class / CSS 变量 / 一次性动画，不持有状态机。
 *   3. 想整体换一套观感：替换本文件 + styles/motion.css，其它文件不用动。
 */

const SCENES = new Set(['idle', 'scanning', 'injecting', 'attached', 'failed']);

/**
 * First-screen entrance: mark body; rise-in / card-in in CSS start from it.
 *
 * 首屏入场：给 body 打标记，CSS 里的 rise-in / card-in 按它启动。
 */
export function mount() {
  requestAnimationFrame(() => {
    document.body.dataset.mounted = '1';
  });
}

/**
 * Switch the overall scene. CSS takes over subsequent animations via body[data-phase="..."] selectors.
 * @param {'idle'|'scanning'|'injecting'|'attached'|'failed'} scene
 *
 * 切换整体场景。CSS 用 body[data-phase="..."] 选择器接管后续动效。
 */
export function setScene(scene) {
  const next = SCENES.has(scene) ? scene : 'idle';
  if (document.body.dataset.phase === next) {
    return;
  }
  document.body.dataset.phase = next;
}

/**
 * Stagger cards in: write --i so CSS offsets the delay.
 *
 * 卡片逐个入场：写入 --i 让 CSS 错开 delay。
 */
export function stagger(nodes) {
  nodes.forEach((node, index) => {
    node.style.setProperty('--i', String(index));
  });
}

/**
 * Click ripple. Position is the pointer offset from the element centre; size fills the diagonal.
 *
 * 点击涟漪。位置取指针相对元素中心的偏移，尺寸撑满元素对角线。
 * @param {PointerEvent|MouseEvent} event
 * @param {HTMLElement} host needs position: relative / overflow: hidden · 需要 position: relative / overflow: hidden
 * @param {'light'|'dark'} [tone]
 */
export function ripple(event, host, tone = 'light') {
  const rect = host.getBoundingClientRect();
  const x = (event.clientX || rect.left + rect.width / 2) - rect.left;
  const y = (event.clientY || rect.top + rect.height / 2) - rect.top;
  const size = Math.hypot(Math.max(x, rect.width - x), Math.max(y, rect.height - y)) * 2;

  const node = document.createElement('span');
  node.className = 'ripple' + (tone === 'dark' ? ' ripple--dark' : '');
  node.style.width = size + 'px';
  node.style.height = size + 'px';
  node.style.left = x + 'px';
  node.style.top = y + 'px';
  host.appendChild(node);
  node.addEventListener('animationend', () => node.remove(), { once: true });
  // Fallback: still clean up when the animation is disabled by reduced-motion
  // 兜底：动画被 reduced-motion 关掉时也要清理
  setTimeout(() => node.remove(), 900);
}

/**
 * Soft card glow that follows the pointer.
 *
 * 卡片柔光跟随指针。
 */
export function trackPointer(host) {
  const move = (event) => {
    const rect = host.getBoundingClientRect();
    host.style.setProperty('--mx', ((event.clientX - rect.left) / rect.width) * 100 + '%');
    host.style.setProperty('--my', ((event.clientY - rect.top) / rect.height) * 100 + '%');
  };
  host.addEventListener('pointermove', move);
  host.addEventListener('pointerleave', () => {
    host.style.removeProperty('--mx');
    host.style.removeProperty('--my');
  });
}

/**
 * Primary button highlight that follows the pointer (the MCHOSE-style glow: the spot tracks the mouse,
 * edges softened with blur). Only --lx / --ly percentage variables are written; the actual radius,
 * colour and blur radius all live in tokens.css, so retheming needs no changes here.
 *
 * 主按钮的光斑跟随指针（MCHOSE 那套光效：光斑随鼠标走，边缘用 blur 软化）。
 * 只写 --lx / --ly 两个百分比变量，具体半径、颜色、模糊半径都在 tokens.css 里，
 * 换主题时不用动这里。
 */
export function trackGlow(host) {
  if (!host) {
    return;
  }
  const move = (event) => {
    const rect = host.getBoundingClientRect();
    host.style.setProperty('--lx', ((event.clientX - rect.left) / rect.width) * 100 + '%');
    host.style.setProperty('--ly', ((event.clientY - rect.top) / rect.height) * 100 + '%');
  };
  host.addEventListener('pointermove', move);
  // Return to the centre after leaving, so the next entry does not "jump" from the previous spot
  // 离开后回到中心，下次进入时不会从上次的位置"跳"一下
  host.addEventListener('pointerleave', () => {
    host.style.setProperty('--lx', '50%');
    host.style.setProperty('--ly', '50%');
  });
}

/**
 * Segmented control: the slider follows the selected item.
 *
 * 分段控件的滑块跟随选中项。
 */
export function slideIndicator(track, activeItem) {
  if (!track || !activeItem) {
    return;
  }
  const parent = track.parentElement.getBoundingClientRect();
  const rect = activeItem.getBoundingClientRect();
  track.style.width = rect.width + 'px';
  track.style.transform = 'translateX(' + (rect.left - parent.left - 3) + 'px)';
}

/**
 * One-shot pulse: add a class to the element and remove it when the animation ends.
 *
 * 一次性脉冲：给元素加 class，动画结束后自动摘掉。
 */
export function pulse(node, className = 'is-pulsing', duration = 700) {
  node.classList.add(className);
  setTimeout(() => node.classList.remove(className), duration);
}

/**
 * New log line slides in (class only; the shape is decided by CSS).
 *
 * 新日志行滑入（只加 class，由 CSS 决定形态）。
 */
export function animateLine(node) {
  node.classList.add('is-fresh');
  node.addEventListener('animationend', () => node.classList.remove('is-fresh'), { once: true });
}

/**
 * Wait for the next layout pass, for "mount DOM first, then play the animation".
 *
 * 等到下一次布局，用于"先挂 DOM 再播动画"。
 */
export function nextFrame() {
  return new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));
}
