/**
 * Fetch the MiSans font shards.
 *
 * No font files are committed to the repo — Article 3 of the MiSans Font Intellectual Property
 * License Agreement forbids "further distribution of the font software or any copy thereof", and
 * committing 56 woff2 files to a public repo is exactly that. So the source tree keeps only
 * fonts.css and the license notice, and the files are fetched at build time; the produced exe still
 * embeds the fonts, which counts as permitted embedded use.
 *
 *   node scripts/fetch-fonts.js            Download whatever is missing
 *   node scripts/fetch-fonts.js --check    Check only; non-zero exit when files are missing (build
 *                                          precondition)
 *
 * 取回 MiSans 字体分片。
 *
 * 仓库里不放字体文件 —— 《MiSans 字体知识产权许可协议》第 3 条禁止"进一步分发字体软件
 * 或其任何副本"，把 56 个 woff2 提交进公开仓库就是这个行为。所以源码只保留 fonts.css
 * 和许可声明，文件在构建时现取；产物 exe 里仍然内置了字体，那属于允许的嵌入式使用。
 *
 *   node scripts/fetch-fonts.js            缺什么下什么
 *   node scripts/fetch-fonts.js --check    只检查，缺文件就非零退出（构建前置）
 */
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.join(__dirname, '..');
const CSS = path.join(ROOT, 'src', 'renderer', 'styles', 'fonts.css');
const DIR = path.join(ROOT, 'src', 'renderer', 'assets', 'fonts', 'misans');
const BASE = 'https://unpkg.com/misans@5.0.0/lib/Normal/';
const CONCURRENCY = 6;

/**
 * File names are read from fonts.css instead of being duplicated in the script, so adding a weight
 * never leaves the two out of sync.
 * 文件名从 fonts.css 里读，不在脚本里另存一份，加字重时不会两处不同步。
 */
function wanted() {
  const css = fs.readFileSync(CSS, 'utf8');
  const found = css.match(/\.\.\/assets\/fonts\/misans\/([^'")]+\.woff2)/g) || [];
  return [...new Set(found.map((hit) => hit.split('/').pop()))];
}

function present(name) {
  try {
    const stat = fs.statSync(path.join(DIR, name));
    if (stat.size < 1024) {
      return false;
    }
    const head = Buffer.alloc(4);
    const fd = fs.openSync(path.join(DIR, name), 'r');
    fs.readSync(fd, head, 0, 4, 0);
    fs.closeSync(fd);
    return head.toString('ascii') === 'wOF2';
  } catch {
    return false;
  }
}

async function download(name) {
  const res = await fetch(BASE + name);
  if (!res.ok) {
    throw new Error(`${name}: HTTP ${res.status}`);
  }
  const body = Buffer.from(await res.arrayBuffer());
  if (body.length < 1024 || body.subarray(0, 4).toString('ascii') !== 'wOF2') {
    throw new Error(`${name}: not a woff2 file / 不是 woff2 文件`);
  }
  fs.writeFileSync(path.join(DIR, name), body);
  return body.length;
}

async function main() {
  const checkOnly = process.argv.includes('--check');
  const names = wanted();
  if (names.length === 0) {
    console.error('No woff2 reference parsed from fonts.css, build unusable / fonts.css 里没解析到任何 woff2 引用，构建不可用');
    process.exit(1);
  }

  const missing = names.filter((name) => !present(name));
  if (missing.length === 0) {
    console.log(`All fonts present: ${names.length} shard(s) / 字体齐全：${names.length} 个分片`);
    return;
  }
  if (checkOnly) {
    console.error(`Missing ${missing.length} / ${names.length} font shard(s) / 缺 ${missing.length} / ${names.length} 个字体分片。`);
    console.error('Run `npm run fonts` once to fill them in (needs network), then package / 先跑一次 `npm run fonts` 补齐（需要联网），再打包。');
    process.exit(1);
  }

  fs.mkdirSync(DIR, { recursive: true });
  console.log(`Downloading ${missing.length} shard(s) → ${path.relative(ROOT, DIR)} / 下载 ${missing.length} 个分片`);

  let done = 0;
  let cursor = 0;
  const workers = Array.from({ length: Math.min(CONCURRENCY, missing.length) }, async () => {
    while (cursor < missing.length) {
      const name = missing[cursor++];
      for (let attempt = 0; attempt < 3; attempt += 1) {
        try {
          await download(name);
          done += 1;
          break;
        } catch (error) {
          if (attempt === 2) {
            throw error;
          }
          await new Promise((resolve) => setTimeout(resolve, 800));
        }
      }
    }
  });

  try {
    await Promise.all(workers);
  } catch (error) {
    console.error('Download failed / 下载失败：' + error.message);
    process.exit(1);
  }

  console.log(`Done: ${done} shard(s) downloaded / 完成：${done} 个分片下载完毕`);

  // Totals are computed from the actual on-disk sizes: counters accumulated across concurrent
  // workers miss entries easily, and what the user sees is how much the directory occupies
  // 汇总按磁盘上的实际大小算：并发里累加的计数容易漏算，而用户看到的是目录占了多少
  const total = names.reduce((sum, name) => {
    try {
      return sum + fs.statSync(path.join(DIR, name)).size;
    } catch {
      return sum;
    }
  }, 0);
  console.log(`Total font size: ${(total / 1024 / 1024).toFixed(1)} MB / ${names.length} shard(s) · 字体总计：${(total / 1024 / 1024).toFixed(1)} MB / ${names.length} 个分片`);
}

main();
