/**
 * Settings page (presentation layer).
 *
 * Does exactly two things: draw the fields/values given by the main process into controls, and hand user edits back as-is.
 * It knows nothing about properties files, paths or validation rules — those live in main/settings.js.
 *
 * 设置页（表现层）。
 *
 * 只做两件事：把主进程给的 fields/values 画成控件，把用户改动原样交回去。
 * 不认识 properties 文件、不认识 path、不认识校验规则——那些都在 main/settings.js。
 */

import { t, fieldText, optionText, locale, locales, requestedLocale } from '../i18n.js';

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

function row(field) {
  const wrap = el('div', 'srow');
  wrap.dataset.key = field.key;
  const text = el('div', 'srow__text');
  text.append(el('span', 'srow__label', fieldText(field, 'label')));
  const hint = fieldText(field, 'hint');
  if (hint) {
    text.append(el('span', 'srow__hint', hint));
  }
  const control = el('div', 'srow__ctl');
  wrap.append(text, control);
  return { wrap, control };
}

function switchControl(value, onChange) {
  const button = el('button', 'switch');
  button.type = 'button';
  button.setAttribute('role', 'switch');
  const on = value === true || value === 'true';
  button.setAttribute('aria-checked', on ? 'true' : 'false');
  button.append(el('span', 'switch__knob'));
  button.addEventListener('click', () => {
    const next = button.getAttribute('aria-checked') !== 'true';
    button.setAttribute('aria-checked', next ? 'true' : 'false');
    onChange(next);
  });
  return button;
}

function segmentedControl(field, value, onChange) {
  const group = el('div', 'choices');
  const current = String(value);
  for (const option of field.options) {
    const item = el('button', 'choices__item' + (String(option.value) === current ? ' is-active' : ''), optionText(option));
    item.type = 'button';
    item.dataset.value = String(option.value);
    item.addEventListener('click', () => {
      for (const sibling of group.children) {
        sibling.classList.toggle('is-active', sibling === item);
      }
      onChange(String(option.value));
    });
    group.append(item);
  }
  return group;
}

function selectControl(field, value, onChange) {
  const select = el('select', 'select');
  const current = String(value);
  for (const option of field.options) {
    const node = el('option', '', optionText(option));
    node.value = String(option.value);
    if (String(option.value) === current) {
      node.selected = true;
    }
    select.append(node);
  }
  select.addEventListener('change', () => onChange(select.value));
  return select;
}

function rangeControl(field, value, onChange) {
  const wrap = el('div', 'range');
  const input = el('input', 'range__input');
  const step = field.step || 1;
  // Show integers for integer steps, otherwise "log keep lines" would read 800.00
  // 整数步长就显示整数，不然 "日志保留行数" 会写成 800.00
  const format = (raw) => (step >= 1 ? String(Math.round(Number(raw))) : Number(raw).toFixed(2));
  input.type = 'range';
  input.min = String(field.min);
  input.max = String(field.max);
  input.step = String(step);
  input.value = String(value);
  const readout = el('span', 'range__value', format(value));
  input.addEventListener('input', () => {
    readout.textContent = format(input.value);
  });
  input.addEventListener('change', () => onChange(input.value));
  wrap.append(input, readout);
  return wrap;
}

/**
 * Action row: a button that opens a panel instead of editing a value.
 *
 * <p>Nothing is stored for these rows, so no value is ever handed back — pressing the button only
 * reports the row itself and lets app.js decide what to open.
 *
 * 动作行：只负责打开一个面板的按钮，不编辑任何值。
 *
 * <p>这类行不存值，所以永远不回报数值——按下按钮只把这一行本身交出去，由 app.js 决定打开什么。
 */
function actionControl(field, onAction) {
  const button = el('button', 'ghost ghost--tiny', t(field.actionLabelKey || field.labelKey));
  button.type = 'button';
  button.dataset.action = field.action || field.key;
  button.addEventListener('click', () => onAction(field, button));
  return button;
}

function numberControl(field, value, onChange) {
  const wrap = el('div', 'num');
  const input = el('input', 'num__input');
  input.type = 'number';
  if (field.min !== undefined) input.min = String(field.min);
  if (field.max !== undefined) input.max = String(field.max);
  input.step = String(field.step || 1);
  input.value = String(value);
  const commit = () => {
    const next = Number(input.value);
    if (!Number.isFinite(next)) {
      input.value = String(value);
      return;
    }
    const min = field.min ?? -Infinity;
    const max = field.max ?? Infinity;
    const clamped = Math.min(max, Math.max(min, Math.round(next)));
    input.value = String(clamped);
    onChange(clamped);
  };
  input.addEventListener('change', commit);
  input.addEventListener('blur', commit);
  wrap.append(input);
  if (field.unit) {
    wrap.append(el('span', 'num__unit', field.unit));
  }
  return wrap;
}

/**
 * Single-line text box: edit one word in the line text (Gain / No Gain).
 *
 * <p>The placeholder is the default value: leave the box empty and the agent naturally falls back to
 * Gain / No Gain, so the user does not need to know "what the default is" — just leave it blank.
 * The brackets are added by the display layer; this only collects the word itself.
 *
 * 单行文本框：改行文里的一个词（Gain / No Gain）。
 *
 * <p>占位符就是默认值：框里空着，agent 那边自然回落到 Gain / No Gain，所以用户不用知道
 * 「默认值是什么」，只要不填就行。方括号由显示层加，这里只收词本身。
 */
function textControl(field, value, onChange) {
  const wrap = el('div', 'num num--text');
  const input = el('input', 'num__input text__input');
  input.type = 'text';
  input.spellcheck = false;
  input.maxLength = field.maxLength || 24;
  input.placeholder = field.placeholder || '';
  const current = value === undefined || value === null ? '' : String(value);
  input.value = current;
  const commit = () => {
    const next = input.value.trim();
    if (next === current) {
      return;
    }
    input.value = next;
    onChange(next);
  };
  input.addEventListener('change', commit);
  input.addEventListener('blur', commit);
  wrap.append(input);
  return wrap;
}

/**
 * The 16 colour codes of the 1.8.9 chat bar; hex values are taken from what the game actually shows
 * (§0-§f is a fixed legacy palette, not a generic 8-bit colour set).
 *
 * 1.8.9 聊天栏的 16 个颜色码，十六进制按游戏里实际显示的值取
 * （§0-§f 是一套固定的老版调色板，不是 8bit 通用色）。
 */
const MC_PALETTE = {
  '0': { hex: '#000000' },
  '1': { hex: '#0000AA' },
  '2': { hex: '#00AA00' },
  '3': { hex: '#00AAAA' },
  '4': { hex: '#AA0000' },
  '5': { hex: '#AA00AA' },
  '6': { hex: '#FFAA00' },
  '7': { hex: '#AAAAAA' },
  '8': { hex: '#555555' },
  '9': { hex: '#5555FF' },
  a: { hex: '#55FF55' },
  b: { hex: '#55FFFF' },
  c: { hex: '#FF5555' },
  d: { hex: '#FF55FF' },
  e: { hex: '#FFFF55' },
  f: { hex: '#FFFFFF' }
};

const COLOR_ORDER = ['0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'];

/**
 * Section collapse state: the settings page is fully repainted on every change, so the state has to be kept outside.
 *
 * 分区折叠状态：设置页每次改动都会整块重画，状态得放在外面记着。
 */
let colorsExpanded = false;

/**
 * Colour names live in the language pack (`color.0` … `color.f`); this only fills in the name.
 *
 * 颜色名在语言包里（`color.0` … `color.f`），这里只补上名字。
 */
function paletteOf(code) {
  const key = String(code).toLowerCase();
  const entry = MC_PALETTE[key] || MC_PALETTE.f;
  return { hex: entry.hex, name: t('color.' + (MC_PALETTE[key] ? key : 'f')) };
}

/**
 * A small square filled with the corresponding colour.
 *
 * 一个填满对应颜色的小方块。
 */
function swatchNode(code, size, onClick) {
  const info = paletteOf(code);
  const node = size === 'dot' ? el('span', 'colorset__dot') : el('button', 'swatch');
  if (size !== 'dot') {
    node.type = 'button';
  }
  node.style.setProperty('--sw', info.hex);
  node.dataset.code = code;
  if (onClick) {
    node.addEventListener('click', onClick);
  }
  return node;
}

/**
 * Eight swatches per row, two rows hold all 16 colours.
 *
 * 8 个色块一行，两行放下 16 色。
 */
function swatchGrid(field, value, onChange) {
  const grid = el('div', 'swatches');
  const current = String(value).toLowerCase();
  for (const code of COLOR_ORDER) {
    const info = paletteOf(code);
    const active = code === current;
    const node = swatchNode(code, 'block', () => {
      for (const sibling of grid.children) {
        const on = sibling === node;
        sibling.classList.toggle('is-active', on);
        sibling.setAttribute('aria-pressed', on ? 'true' : 'false');
      }
      onChange(code);
    });
    node.classList.toggle('is-active', active);
    node.setAttribute('aria-pressed', active ? 'true' : 'false');
    node.title = info.name + ' (§' + code + ')';
    node.setAttribute('aria-label', info.name + ' §' + code);
    grid.append(node);
  }
  return grid;
}

/**
 * Text colour section: collapsed by default; click the "edit" in the header to expand.
 *
 * 文本颜色分区：默认收起，点头部「编辑」展开。
 */
function colorSection(fields, values, onChange) {
  const section = el('section', 'colorset');
  const head = el('div', 'colorset__head');

  const info = el('div', 'colorset__info');
  info.append(el('span', 'colorset__title', t('colors.section.title')));

  const preview = el('div', 'colorset__preview');
  for (const field of fields) {
    const dot = swatchNode(values ? values[field.key] : 'f', 'dot');
    dot.title = field.label + '：' + paletteOf(dot.dataset.code).name;
    preview.append(dot);
  }

  const toggle = el('button', 'ghost ghost--tiny', t(colorsExpanded ? 'colors.section.collapse' : 'colors.section.expand'));
  toggle.type = 'button';
  toggle.addEventListener('click', () => {
    colorsExpanded = !colorsExpanded;
    // Toggle visibility only, do not repaint the whole page — a repaint would lose the scroll position
    // 只切显示，不重画整页——重画会丢滚动位置
    body.hidden = !colorsExpanded;
    toggle.textContent = t(colorsExpanded ? 'colors.section.collapse' : 'colors.section.expand');
    if (colorsExpanded) {
      // Bring the content into the viewport after expanding: the panel itself is the scroll container, so without scrolling the lower half is invisible
      // 展开后把内容带进视口：面板本身是滚动容器，不滚的话下面那半截看不见
      body.scrollIntoView({ block: 'nearest' });
    }
  });

  head.append(info, preview, toggle);

  const body = el('div', 'colorset__body');
  body.hidden = !colorsExpanded;
  for (const field of fields) {
    const block = el('div', 'crow');
    block.dataset.key = field.key;
    const line = el('div', 'crow__head');
    const name = field.labelArg ? t(field.labelArg) : String(field.label || field.key);
    line.append(el('span', 'crow__label', name));
    const code = String((values && values[field.key]) || 'f').toLowerCase();
    line.append(el('span', 'crow__code', '§' + code + ' · ' + paletteOf(code).name));
    block.append(line, swatchGrid(field, code, (next) => {
      const label = line.lastChild;
      label.textContent = '§' + next + ' · ' + paletteOf(next).name;
      onChange(field.key, next);
    }));
    body.append(block);
  }

  section.append(head, body);
  return section;
}

/**
 * Draw the mod settings.
 *
 * 画模组设置。
 * @param {HTMLElement} host
 * @param {Array} fields field table from the main process · 主进程给的字段表
 * @param {Record<string,string>} values
 * @param {(key:string, value:unknown)=>void} onChange
 * @param {(field:object, trigger:HTMLElement)=>void} [onAction] Called by `action` rows · `action` 行按下时回调
 */
export function renderModSettings(host, fields, values, onChange, onAction) {
  host.replaceChildren();
  // Colour items get their own section; the main list no longer lays out 8 dropdowns
  // 颜色项单独一个分区，主列表里不再铺 8 个下拉
  const colorFields = fields.filter((field) => String(field.key).startsWith('color.'));
  const plainFields = fields.filter((field) => !String(field.key).startsWith('color.'));
  for (const field of plainFields) {
    const { wrap, control } = row(field);
    const value = values ? values[field.key] : undefined;
    if (field.type === 'action') {
      control.append(actionControl(field, (rowField, trigger) => {
        if (onAction) {
          onAction(rowField, trigger);
        }
      }));
    } else if (field.type === 'switch') {
      control.append(switchControl(value, (next) => onChange(field.key, next)));
    } else if (field.type === 'segmented') {
      control.append(segmentedControl(field, value, (next) => onChange(field.key, next)));
    } else if (field.type === 'select') {
      control.append(selectControl(field, value, (next) => onChange(field.key, next)));
    } else if (field.type === 'text') {
      control.append(textControl(field, value, (next) => onChange(field.key, next)));
    } else if (field.type === 'range') {
      control.append(rangeControl(field, value, (next) => onChange(field.key, next)));
    } else {
      control.append(numberControl(field, value, (next) => onChange(field.key, next)));
    }
    host.append(wrap);
  }
  if (colorFields.length) {
    host.append(colorSection(colorFields, values, onChange));
  }
}

/**
 * Draw the injector settings.
 *
 * 画注入器设置。
 * @param {HTMLElement} host
 * @param {{logLines:number, theme:{mode:string}, modPath:string|null, version:string,
 *          autoInject:boolean, autoInjectDelaySec:number, launchOnStartup:boolean}} state
 * @param {{onLogLines:(n:number)=>void, onTheme:(mode:string)=>void, onRelocate:()=>void,
 *          onOpenFolder:(which:string)=>void, onAutoInject:(on:boolean)=>void,
 *          onAutoInjectDelay:(seconds:number)=>void, onLaunchOnStartup:(on:boolean)=>void}} actions
 */
export function renderAppSettings(host, state, actions) {
  host.replaceChildren();

  // UI language: put it on top — changing it reloads the whole page, so at the bottom it is easy to miss
  // 界面语言：放在最上面——换语言要整页重载，放下面容易找不到
  const langRow = row({ key: 'appLanguage', labelKey: 'injector.language.label' });
  const known = state.locales && state.locales.length ? state.locales : locales();
  const activeName = (known.find((item) => item.code === locale()) || {}).name || '';
  // Append the effective language after Auto, otherwise "Auto" does not show which language is in use
  // Auto 后面带上实际生效的语言，不然选「自动」看不出现在是哪门
  const languageOptions = [
    { value: 'auto', label: activeName ? t('lang.auto') + ' · ' + activeName : t('lang.auto') },
    ...known.map((item) => ({ value: item.code, label: item.name }))
  ];
  langRow.control.append(selectControl(
    { options: languageOptions },
    state.appLanguage || requestedLocale(),
    (next) => actions.onLanguage(next)
  ));
  host.append(langRow.wrap);

  // Theme
  // 主题
  const themeRow = row({ key: 'theme', labelKey: 'injector.theme.label' });
  themeRow.control.append(segmentedControl(
    { options: [
      { value: 'auto', labelKey: 'injector.theme.auto' },
      { value: 'light', labelKey: 'injector.theme.light' },
      { value: 'dark', labelKey: 'injector.theme.dark' }
    ] },
    state.theme.mode,
    actions.onTheme
  ));
  host.append(themeRow.wrap);

  // Log line count
  // 日志行数
  const logRow = row({ key: 'logLines', labelKey: 'injector.logLines.label' });
  logRow.control.append(rangeControl(
    { min: 200, max: 3000, step: 100 },
    state.logLines,
    actions.onLogLines
  ));
  host.append(logRow.wrap);

  // Launch at startup
  // 开机自启
  const startupRow = row({ key: 'launchOnStartup', labelKey: 'injector.launchOnStartup.label' });
  startupRow.control.append(switchControl(
    state.launchOnStartup ? 'true' : 'false',
    (next) => actions.onLaunchOnStartup(next)
  ));
  host.append(startupRow.wrap);

  // Auto update check (a manual entry sits on the right)
  // 自动检查更新（右边留一个手动入口）
  const updateRow = row({ key: 'autoUpdateCheck', labelKey: 'injector.autoUpdate.label' });
  const checkBtn = el('button', 'ghost ghost--tiny', t('injector.autoUpdate.check'));
  checkBtn.type = 'button';
  checkBtn.addEventListener('click', () => actions.onCheckUpdate(checkBtn));
  updateRow.control.append(checkBtn, switchControl(
    state.autoUpdateCheck ? 'true' : 'false',
    (next) => actions.onAutoUpdate(next)
  ));
  host.append(updateRow.wrap);

  // Check updates via mirror (only meaningful when direct access fails, hence no extra explanatory text)
  // 走镜像查更新（只在直连不通时才有意义，所以没有额外说明文字）
  const mirrorRow = row({ key: 'useMirrorCheck', labelKey: 'injector.mirrorCheck.label' });
  mirrorRow.control.append(switchControl(
    state.useMirrorCheck ? 'true' : 'false',
    (next) => actions.onMirrorCheck(next)
  ));
  host.append(mirrorRow.wrap);

  // Auto inject
  // 自动注入
  const injectRow = row({ key: 'autoInject', labelKey: 'injector.autoInject.label' });
  injectRow.control.append(switchControl(
    state.autoInject ? 'true' : 'false',
    (next) => actions.onAutoInject(next)
  ));
  host.append(injectRow.wrap);

  // Wait duration
  // 等待时长
  const delayRow = row({
    key: 'autoInjectDelaySec',
    labelKey: 'injector.autoInjectDelay.label'
  });
  delayRow.control.append(numberControl(
    { min: 1, max: 120, step: 1, unit: t('injector.autoInjectDelay.unit') },
    state.autoInjectDelaySec,
    (next) => actions.onAutoInjectDelay(Number(next))
  ));
  host.append(delayRow.wrap);

  // Mod config file location
  // 模组配置文件位置
  const pathRow = row({ key: 'modPath', labelKey: 'injector.modPath.label' });
  const pathWrap = el('div', 'pathbox');
  const pathText = el('code', 'pathbox__text', state.modPath || t('injector.modPath.notFound'));
  if (state.modPath) {
    pathText.title = state.modPath;
  }
  const relocate = el('button', 'ghost ghost--tiny', t('injector.modPath.relocate'));
  relocate.type = 'button';
  relocate.addEventListener('click', actions.onRelocate);
  pathWrap.append(pathText, relocate);
  pathRow.control.append(pathWrap);
  host.append(pathRow.wrap);

  // Directory shortcuts
  // 目录快捷入口
  const openRow = row({ key: 'folders', labelKey: 'injector.folders.label', hintKey: 'injector.folders.hint' });
  const openWrap = el('div', 'btnrow');
  const logs = el('button', 'ghost ghost--tiny', t('injector.folders.logs'));
  logs.type = 'button';
  logs.addEventListener('click', () => actions.onOpenFolder('logs'));
  const conf = el('button', 'ghost ghost--tiny', t('injector.folders.config'));
  conf.type = 'button';
  conf.disabled = !state.modPath;
  conf.addEventListener('click', () => actions.onOpenFolder('mod'));
  openWrap.append(logs, conf);
  openRow.control.append(openWrap);
  host.append(openRow.wrap);

  // Version
  // 版本
  const versionRow = row({ key: 'version', labelKey: 'injector.version.label' });
  versionRow.control.append(el('span', 'srow__value', 'v' + (state.version || '—')));
  host.append(versionRow.wrap);

  // Uninstall: only clears this program's data under the user directory; game config and the launcher exe are untouched
  // 卸载：只清本程序在用户目录下的数据，游戏配置与入口 exe 不动
  const uninstallRow = row({ key: 'uninstall', labelKey: 'injector.uninstall.label' });
  const uninstallBtn = el('button', 'ghost ghost--tiny', t('injector.uninstall.action'));
  uninstallBtn.type = 'button';
  uninstallBtn.id = 'btnUninstall';
  uninstallBtn.addEventListener('click', () => actions.onUninstall());
  uninstallRow.control.append(uninstallBtn);
  host.append(uninstallRow.wrap);
}
