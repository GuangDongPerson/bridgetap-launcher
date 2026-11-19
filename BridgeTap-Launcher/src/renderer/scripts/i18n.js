/**
 * Renderer strings.
 *
 * All user-visible text comes from the language pack; no hardcoded Chinese / English remains in code. Three-step usage:
 *
 *   1. Static DOM: add `data-i18n="key"` to the tag; `applyStatic()` fills it in once at startup;
 *      also `data-i18n-title` / `data-i18n-placeholder` / `data-i18n-aria`.
 *   2. Dynamic text: call `t('key', args…)` directly, write placeholders as `{0}` `{1}`.
 *   3. Args that themselves need translation: `t('key', t('another.key'))`.
 *
 * On a missing key the key name itself is returned, so the UI shows things like `mod.hudX.label` — easier to spot than a silent blank.
 *
 * 渲染层文案。
 *
 * 所有用户可见的文字都从语言包来，代码里不再出现硬编码的中文/英文。三步用法：
 *
 *   1. 静态 DOM：在标签上加 `data-i18n="key"`，启动时 `applyStatic()` 一次性填好；
 *      还有 `data-i18n-title` / `data-i18n-placeholder` / `data-i18n-aria`。
 *   2. 动态文案：直接 `t('key', 参数…)`，占位符写 `{0}` `{1}`。
 *   3. 带参数但参数本身也要翻译：`t('key', t('another.key'))`。
 *
 * 缺键时返回键名本身，界面上会直接显示 `mod.hudX.label` 这种东西——比静默显示空白更容易发现。
 */

const state = {
  code: 'zh-CN',
  requested: 'auto',
  auto: true,
  detected: '',
  defaultLocale: 'zh-CN',
  locales: [],
  dict: {}
};

/** @param {string} [code] when omitted, the main process decides per settings (may be auto) · 省略时由主进程按设置决定（可能是 auto） */
export async function initI18n(code) {
  const pack = await window.bridge.loadLocale(code);
  state.code = pack.code;
  state.requested = pack.requested || pack.code;
  state.auto = pack.auto === true;
  state.detected = pack.detected || '';
  state.defaultLocale = pack.defaultLocale;
  state.locales = pack.locales || [];
  state.dict = pack.dict || {};
  document.documentElement.lang = pack.code;
  return state;
}

/**
 * The locale actually in effect.
 *
 * 实际生效的语言。
 */
export function locale() {
  return state.code;
}

/**
 * The raw value from settings: may be auto.
 *
 * 设置里的原值：可能是 auto。
 */
export function requestedLocale() {
  return state.requested;
}

/**
 * The auto-detected system locale tag, e.g. zh-CN.
 *
 * 自动检测到的系统区域标签，例如 zh-CN。
 */
export function detectedLocale() {
  return state.detected;
}

export function locales() {
  return state.locales;
}

export function t(key, ...args) {
  const text = typeof state.dict[key] === 'string' ? state.dict[key] : key;
  if (!args.length) {
    return text;
  }
  let out = text;
  for (let i = 0; i < args.length; i += 1) {
    out = out.split('{' + i + '}').join(String(args[i]));
  }
  return out;
}

/**
 * For field tables: `labelKey` + optional `labelArg` (e.g. the colour slot name).
 *
 * 供字段表使用：`labelKey` + 可选 `labelArg`（比如颜色槽的名字）。
 */
export function fieldText(field, kind) {
  const key = kind === 'hint' ? field.hintKey : field.labelKey;
  if (!key) {
    return kind === 'hint' ? (field.hint || '') : (field.label || field.key);
  }
  const arg = kind === 'hint' ? field.hintArg : field.labelArg;
  return arg === undefined ? t(key) : t(key, kind === 'hint' ? arg : t(arg));
}

export function optionText(option) {
  if (!option) {
    return '';
  }
  return option.labelKey ? t(option.labelKey) : String(option.label || option.value);
}

/**
 * Spread `data-i18n*` attributes onto the DOM.
 *
 * 把 `data-i18n*` 属性铺到 DOM 上。
 * @param {ParentNode} [root] defaults to the whole document; after a partial repaint you can also refresh just one subtree · 默认整个文档；局部重画后也可以只刷一个子树
 */
export function applyStatic(root) {
  const scope = root || document;

  scope.querySelectorAll('[data-i18n]').forEach((node) => {
    node.textContent = t(node.dataset.i18n);
  });
  scope.querySelectorAll('[data-i18n-title]').forEach((node) => {
    node.title = t(node.dataset.i18nTitle);
  });
  scope.querySelectorAll('[data-i18n-placeholder]').forEach((node) => {
    node.placeholder = t(node.dataset.i18nPlaceholder);
  });
  scope.querySelectorAll('[data-i18n-aria]').forEach((node) => {
    node.setAttribute('aria-label', t(node.dataset.i18nAria));
  });
  if (scope === document) {
    document.title = t('app.title');
  }
}
