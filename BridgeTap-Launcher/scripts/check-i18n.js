// Check that the language packs and the keys used in code line up:
//   · A key used in code but missing from a pack → the UI shows the key name itself (the t() fallback),
//     so it must be fixed
//   · A key present in a pack but unused → only redundant, print a hint, not an error
//   · The key sets of both packs must be exactly identical
//
// Usage: node scripts/check-i18n.js
// 校验语言包和代码里的键是否对得上：
//   · 代码里用到但包里没有的键 → 界面上会直接显示键名（t() 的回退），必须修
//   · 包里多出来的键 → 只是冗余，提示一下，不算错
//   · 两套语言包的键集合必须完全一致
//
// 用法：node scripts/check-i18n.js
const fs = require('node:fs');
const path = require('node:path');

const root = path.join(__dirname, '..');
const dir = path.join(root, 'i18n');

const read = (file) => JSON.parse(fs.readFileSync(file, 'utf8'));
const index = read(path.join(dir, 'index.json'));
const packs = index.locales.map((entry) => ({
  code: entry.code,
  keys: Object.keys(read(path.join(dir, entry.file))).filter((k) => !k.startsWith('_meta.'))
}));

let failures = 0;
const fail = (msg) => {
  failures += 1;
  console.log('  ✗ ' + msg);
};
const ok = (msg) => console.log('  ✓ ' + msg);

// 1) Key sets of both packs
// 1) 两套包的键集合
const base = packs[0];
for (const pack of packs.slice(1)) {
  const missing = base.keys.filter((k) => !pack.keys.includes(k));
  const extra = pack.keys.filter((k) => !base.keys.includes(k));
  if (missing.length || extra.length) {
    fail(pack.code + ' and ' + base.code + ' key sets differ / 与 ' + base.code + ' 键不一致：缺 ' + missing + ' / 多 ' + extra);
  } else {
    ok(pack.code + ' and ' + base.code + ' key sets match / 与 ' + base.code + ' 键一致（' + base.keys.length + ' 条）');
  }
}

// 2) Keys referenced in code
// 2) 代码里引用的键
const files = [];
const walk = (dir2) => {
  for (const name of fs.readdirSync(dir2)) {
    const full = path.join(dir2, name);
    const stat = fs.statSync(full);
    if (stat.isDirectory()) {
      walk(full);
    } else if (/\.(js|html)$/.test(name)) {
      files.push(full);
    }
  }
};
walk(path.join(root, 'src'));

const used = new Set();
for (const file of files) {
  // i18n.js's own doc comments contain examples like t('key') / t('another.key'); scanning them
  // would produce false positives
  // i18n.js 自己的文档注释里有 t('key') / t('another.key') 这类示例，扫进来会误报
  if (path.basename(file) === 'i18n.js') {
    continue;
  }
  const text = fs.readFileSync(file, 'utf8');
  // t('key') / t("key", …) / data-i18n="key" / labelKey: 'key' / hintKey: 'key' / placeholder, etc.
  // t('key') / t("key", …) / data-i18n="key" / labelKey: 'key' / hintKey: 'key' / placeholder 等
  const patterns = [
    /\bt\(\s*'([a-zA-Z0-9_.]+)'/g,
    /\bt\(\s*"([a-zA-Z0-9_.]+)"/g,
    /data-i18n(?:-title|-placeholder|-aria)?="([a-zA-Z0-9_.]+)"/g,
    /(?:labelKey|hintKey|defaultHintKey)\s*:\s*'([a-zA-Z0-9_.]+)'/g,
    /\bt\(\s*'([a-zA-Z0-9_.]+)'\s*\+/g
  ];
  for (const pattern of patterns) {
    let match;
    while ((match = pattern.exec(text)) !== null) {
      used.add(match[1]);
    }
  }
}

// Keys built dynamically: the option names of the language dropdown, "injector.theme." + mode,
// "color." + code, etc.
// 动态拼出来的键：语言下拉的选项名、"injector.theme." + mode、"color." + code 等
const dynamicPrefixes = ['color.', 'injector.theme.', 'mod.colorN.', 'lang.'];
const missing = [...used].filter((key) => !base.keys.includes(key)
  && !dynamicPrefixes.some((prefix) => key.startsWith(prefix)));

if (missing.length) {
  fail('Keys used in code but missing from the pack / 代码里用到但语言包没有的键：' + missing.join(', '));
} else {
  ok('All ' + used.size + ' referenced keys exist in the pack / 代码引用的 ' + used.size + ' 个键都能在包里找到');
}

// Every dynamic prefix must actually resolve to existing keys
// 动态前缀要真的能拼出存在的键
for (const prefix of dynamicPrefixes) {
  const known = base.keys.filter((k) => k.startsWith(prefix));
  if (!known.length) {
    fail('Dynamic prefix ' + prefix + ' has no entry in the pack / 动态前缀 ' + prefix + ' 在包里一条都没有');
  }
}

// 3) Keys in the pack that nothing references (hint only)
// 3) 包里没被引用的键（只提示）
const unused = base.keys.filter((key) => !used.has(key)
  && !dynamicPrefixes.some((prefix) => key.startsWith(prefix)));
if (unused.length) {
  console.log('  · Not directly referenced (maybe built dynamically or unused for now) / 未被直接引用（可能是动态拼接或暂时没用到）：' + unused.length + ' 条');
  console.log('    ' + unused.join(', '));
}

console.log(failures === 0 ? '\nAll checks passed / 全部通过' : '\n' + failures + ' mismatch(es) / 有 ' + failures + ' 项不符');
process.exit(failures === 0 ? 0 : 1);
