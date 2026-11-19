/**
 * Render layer: only responsible for drawing state into the DOM.
 *
 * No animations (those go to ui/motion.js), no business flow (that goes to app.js).
 * In other words: swapping this whole thing for React / Vue would not affect the injection module.
 *
 * 渲染层：只负责把状态画成 DOM。
 *
 * 不写动画（交给 ui/motion.js），不写业务流程（交给 app.js）。
 * 换句话说：把这里整个换成 React / Vue 也不会影响注入模块。
 */

import { t } from '../i18n.js';

/**
 * Client icons.
 *   Lunar / Badlion use official logo bitmaps (assets/*.png, 84px transparent background, see scripts/make-client-icons.py);
 *   Minecraft / Java have no official mark, so they keep the letter block.
 * The image paths are relative to index.html; they stay relative after packaging into asar, no change needed.
 *
 * 客户端图标。
 *   Lunar / Badlion 用官方 logo 位图（assets/*.png，84px 透明底，见 scripts/make-client-icons.py）；
 *   Minecraft / Java 没有官方标识，继续用字母块。
 * 图片路径相对 index.html，打进 asar 后同样是相对路径，不用改。
 */
const CLIENT_ICON = {
  Lunar: { glyph: 'L', tone: 'lunar', img: 'assets/lunar.png' },
  Badlion: { glyph: 'B', tone: 'badlion', img: 'assets/badlion.png' },
  Minecraft: { glyph: 'M', tone: null },
  Java: { glyph: 'J', tone: 'java' }
};

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) {
    node.className = className;
  }
  if (text !== undefined) {
    node.textContent = text;
  }
  return node;
}

/** @param {HTMLElement} grid */
function buildCard(target, selected) {
  const meta = CLIENT_ICON[target.client] || CLIENT_ICON.Java;

  const card = el('button', 'card');
  card.type = 'button';
  card.dataset.pid = String(target.pid);
  card.classList.toggle('is-selected', selected);
  card.classList.toggle('is-disabled', !target.injectable);
  card.setAttribute('aria-pressed', selected ? 'true' : 'false');

  const glow = el('span', 'card__glow');
  glow.setAttribute('aria-hidden', 'true');

  const head = el('span', 'card__head');
  const icon = el('span', 'card__icon' + (meta.tone ? ' card__icon--' + meta.tone : '') + (meta.img ? ' card__icon--image' : ''));
  if (meta.img) {
    // Official logo: leave alt empty; the card title already announces the client name, so screen readers should not repeat it
    // 官方 logo：alt 留空，卡片标题已经念出客户端名，读屏不该重复
    const logo = document.createElement('img');
    logo.src = meta.img;
    logo.alt = '';
    logo.draggable = false;
    icon.append(logo);
  } else {
    icon.textContent = meta.glyph;
  }
  const titles = el('span', 'card__titles');
  titles.append(
    el('span', 'card__name', target.client),
    el('span', 'card__sub', 'PID ' + target.pid + ' · 1.8.9')
  );
  const check = el('span', 'card__check', '✓');
  head.append(icon, titles, check);

  const row = el('span', 'card__row');
  row.append(el('span', 'card__label', t('device.window')), el('span', 'card__value', target.title));

  card.append(glow, head, row);
  return card;
}

/**
 * Redraw the device list.
 *
 * 重画设备列表。
 * @param {{grid:HTMLElement, empty:HTMLElement, count:HTMLElement}} refs
 * @param {Array} targets
 * @param {number|null} selectedPid
 * @returns {HTMLElement[]} new cards, for the motion layer's entrance stagger · 新卡片，供动效层做入场错峰
 */
export function renderTargets(refs, targets, selectedPid) {
  refs.grid.replaceChildren();
  if (!targets.length) {
    refs.empty.hidden = false;
    refs.count.textContent = t('count.targets', 0);
    return [];
  }
  refs.empty.hidden = true;
  const cards = targets.map((target) => buildCard(target, target.pid === selectedPid));
  refs.grid.append(...cards);
  refs.count.textContent = t('count.targets', targets.length);
  return cards;
}


/**
 * Primary button label and availability.
 *
 * 主按钮文案与可用性。
 */
export function renderPrimary(button, labelNode, { scene, hasTarget }) {
  const busy = scene === 'injecting';
  const done = scene === 'attached';
  button.disabled = busy || done || !hasTarget || scene === 'scanning';
  labelNode.textContent = busy ? t('btn.injecting') : done ? t('btn.done') : t('btn.inject');
}

/**
 * The whole log block (used when switching views).
 *
 * 整块日志（切视图时用）。
 */
export function renderLog(body, lines) {
  body.replaceChildren();
  if (!lines.length) {
    body.append(el('span', 'console__line', t('log.empty')));
    return;
  }
  const fragment = document.createDocumentFragment();
  for (const line of lines) {
    fragment.append(el('span', 'console__line', line));
  }
  body.append(fragment);
  body.scrollTop = body.scrollHeight;
}

/**
 * Append one line and return the node (the motion layer may optionally animate it).
 *
 * 追加一行，返回该节点（动效层可选地加动画）。
 */
export function appendLog(body, entry) {
  const node = el('span', 'console__line console__line--' + (entry.stream || 'info'), entry.line);
  body.append(node);
  body.scrollTop = body.scrollHeight;
  // Keep only the latest 800 lines to avoid unbounded DOM growth after long runs
  // 只保留最近 800 行，避免长跑之后 DOM 无限膨胀
  while (body.childElementCount > 800) {
    body.firstElementChild?.remove();
  }
  return node;
}

export { el };
