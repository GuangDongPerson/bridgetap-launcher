'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { app } = require('electron');

/**
 * Language pack loader (main-process side).
 *
 * A language pack is just `i18n/<code>.json`: flat keys plus `{0}` placeholders, editable by anyone:
 *   - Adding a language: copy `zh-CN.json`, change the values, and add one entry to `locales` in
 *     `i18n/index.json`. No code change; the dropdown in the UI grows an item automatically.
 *   - Missing key: falls back to `default` in `index.json`, then to the key name itself (so a gap is
 *     obvious at a glance).
 *
 * The renderer receives the **whole merged dictionary**, because it has to translate a large static
 * DOM synchronously; the main process itself only uses `t()` for the window title and a few error
 * messages.
 *
 * 语言包加载器（主进程侧）。
 *
 * 语言包就是 `i18n/<code>.json`，扁平键 + `{0}` 占位符，谁都能改：
 *   - 加一门语言：复制 `zh-CN.json` 改值，在 `i18n/index.json` 的 `locales` 里加一行即可，
 *     代码不用动，界面上的下拉会自动多一项。
 *   - 缺某个键：回退到 `index.json` 的 `default`，再缺就用键名本身（一眼能看出漏了哪条）。
 *
 * 渲染层拿到的是**合并后的整份字典**，因为它要同步翻译一大片静态 DOM；主进程自己只用
 * `t()` 拼窗口标题和几条错误消息。
 */

const DIR = path.join(__dirname, '..', '..', 'i18n');

/**
 * Value stored when "auto" is selected in settings.
 * 设置里选「自动」时存的值。
 */
const AUTO = 'auto';

let index = { default: 'zh-CN', locales: [] };
let locale = null;
const cache = new Map();

function readJson(file) {
  try {
    return JSON.parse(fs.readFileSync(path.join(DIR, file), 'utf8'));
  } catch (error) {
    console.warn('[i18n] cannot read / 读不到 ' + file + '：' + error.message);
    return null;
  }
}

/** @returns {{default:string, locales:{code:string,name:string}[]}} */
function listLocales() {
  const data = readJson('index.json');
  if (data && Array.isArray(data.locales) && data.locales.length) {
    index = {
      default: typeof data.default === 'string' ? data.default : data.locales[0].code,
      locales: data.locales.map((item) => ({ code: String(item.code), name: String(item.name || item.code), file: String(item.file || item.code + '.json') }))
    };
  }
  return { default: index.default, locales: index.locales.map((item) => ({ code: item.code, name: item.name })) };
}

/**
 * Normalize region tags to the `zh-cn` shape for easy comparison.
 * 区域标签统一成 `zh-cn` 这种形状，方便比较。
 */
function tagOf(code) {
  return String(code || '').trim().replace(/_/g, '-').toLowerCase();
}

/**
 * Map a system locale onto a language that actually exists: exact → primary language → default.
 * Example: zh-TW → zh-CN, en-GB → en, ja-JP → default.
 *
 * 把系统区域落到一门真实存在的语言上：精确 → 主语言 → 默认。
 * 例：zh-TW → zh-CN，en-GB → en，ja-JP → 默认。
 */
function matchLocale(tag) {
  const meta = listLocales();
  const norm = tagOf(tag);
  if (!norm) {
    return meta.default;
  }
  const exact = meta.locales.find((item) => tagOf(item.code) === norm);
  if (exact) {
    return exact.code;
  }
  const lang = norm.split('-')[0];
  const same = meta.locales.find((item) => {
    const code = tagOf(item.code);
    return code === lang || code.startsWith(lang + '-');
  });
  return same ? same.code : meta.default;
}

/**
 * The system locale (Electron hands out BCP 47 tags such as zh-CN).
 * 系统区域（Electron 会给 zh-CN 这种 BCP 47 标签）。
 */
function systemTag() {
  try {
    return app.getLocale() || '';
  } catch {
    return '';
  }
}

/**
 * Resolve a settings value into the effective language.
 *
 * 把设置值解析成实际语言。
 *
 * @param {string} [code] Raw value from settings, possibly auto · 设置里的原值，可能是 auto
 * @returns {{requested:string, code:string, auto:boolean, detected:string}}
 */
function resolve(code) {
  const meta = listLocales();
  const requested = typeof code === 'string' && code ? code : AUTO;
  if (requested === AUTO) {
    const tag = systemTag();
    return { requested: AUTO, code: matchLocale(tag), auto: true, detected: tag };
  }
  const hit = meta.locales.find((item) => item.code === requested);
  return { requested, code: hit ? hit.code : meta.default, auto: false, detected: '' };
}

function dictOf(code) {
  if (cache.has(code)) {
    return cache.get(code);
  }
  const meta = listLocales();
  const entry = index.locales.find((item) => item.code === code) || index.locales.find((item) => item.code === meta.default);
  const data = entry ? readJson(entry.file) : null;
  const dict = {};
  if (data) {
    for (const [key, value] of Object.entries(data)) {
      if (!key.startsWith('_meta.') && typeof value === 'string') {
        dict[key] = value;
      }
    }
    if (data['_meta.locale']) {
      dict['_meta.locale'] = data['_meta.locale'];
    }
  }
  cache.set(code, dict);
  return dict;
}

/**
 * Load one language: keys from the target language win, missing ones come from the default.
 *
 * 载入一门语言：目标语言的键优先，缺的用默认语言补。
 *
 * @param {string} [code]
 */
function load(code) {
  const meta = listLocales();
  const picked = resolve(code);
  const wanted = picked.code;
  const base = dictOf(meta.default);
  const target = wanted === meta.default ? {} : dictOf(wanted);
  return {
    code: wanted,
    requested: picked.requested,
    auto: picked.auto,
    detected: picked.detected,
    defaultLocale: meta.default,
    locales: meta.locales,
    dict: { ...base, ...target }
  };
}

/**
 * Language used by the main process itself (window title, error messages); may be auto.
 * 主进程自己用的语言（窗口标题、错误消息）；可以是 auto。
 */
function setLocale(code) {
  locale = typeof code === 'string' && code ? code : AUTO;
}

/**
 * The code actually in effect (auto already resolved).
 * 实际生效的 code（auto 已经解析过）。
 */
function current() {
  return resolve(locale).code;
}

/**
 * The raw value from settings, possibly auto.
 * 设置里的原值，可能是 auto。
 */
function requested() {
  return locale || AUTO;
}

/**
 * Synchronous translate, for the main process only; the renderer has its own implementation.
 * 同步翻译，只给主进程用；渲染层有自己的实现。
 */
function t(key, ...args) {
  const meta = listLocales();
  const wanted = resolve(locale).code;
  const dict = wanted === meta.default ? dictOf(meta.default) : { ...dictOf(meta.default), ...dictOf(wanted) };
  let text = dict[key];
  if (typeof text !== 'string') {
    text = key;
  }
  for (let i = 0; i < args.length; i += 1) {
    text = text.split('{' + i + '}').join(String(args[i]));
  }
  return text;
}

module.exports = { AUTO, listLocales, load, setLocale, current, requested, t };
