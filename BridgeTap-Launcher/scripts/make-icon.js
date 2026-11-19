'use strict';

/**
 * Generate the app icons (build/icon.png + build/icon.ico) with zero dependencies:
 *   - compute pixels directly and hand-write the PNG (zlib is built into Node)
 *   - then pack several PNGs into an ICO container (ICO allows embedded PNGs since Vista; shipping
 *     16/32/48 avoids the system's crude upscaling)
 *
 * The shape copies the brand-mark in the top-left of the UI: **a white rounded square with a black
 * bridge-shaped curve** (the curve uses the same SVG path: M3 16.5 c3.2 0 3.2 -9 6.4 -9 s3.2 9 6.4
 * 9 s3.2 -9 5.2 -9).
 *
 * Usage: node scripts/make-icon.js
 *
 * 生成应用图标（build/icon.png + build/icon.ico），零依赖：
 *   - 直接算像素，手写 PNG（zlib 由 Node 内置）
 *   - 再把多张 PNG 塞进 ICO 容器（Vista 起 ICO 允许内嵌 PNG；带 16/32/48 就不会被系统粗糙缩放）
 *
 * 造型照抄界面左上角 brand-mark 那个标：**白色圆角方块 + 一条黑色桥形曲线**
 * （曲线用的是同一段 SVG path：M3 16.5 c3.2 0 3.2 -9 6.4 -9 s3.2 9 6.4 9 s3.2 -9 5.2 -9）
 *
 * 用法：node scripts/make-icon.js
 */

const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');

const CRC_TABLE = (() => {
  const table = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) {
      c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    }
    table[n] = c;
  }
  return table;
})();

function crc32(buffer) {
  let c = 0xffffffff;
  for (let i = 0; i < buffer.length; i++) {
    c = CRC_TABLE[(c ^ buffer[i]) & 0xff] ^ (c >>> 8);
  }
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length, 0);
  const typeBuf = Buffer.from(type, 'ascii');
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(Buffer.concat([typeBuf, data])), 0);
  return Buffer.concat([length, typeBuf, data, crc]);
}

function distToSegment(px, py, ax, ay, bx, by) {
  const dx = bx - ax;
  const dy = by - ay;
  const len2 = dx * dx + dy * dy;
  let t = len2 === 0 ? 0 : ((px - ax) * dx + (py - ay) * dy) / len2;
  t = Math.max(0, Math.min(1, t));
  return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
}

/**
 * Rounded-rectangle coverage: 1px anti-aliasing at the edge, returns 0..1.
 * 圆角矩形覆盖率：边缘 1px 抗锯齿，返回 0..1。
 */
function roundedCoverage(x, y, size, r) {
  const cx = Math.min(Math.max(x, r), size - r);
  const cy = Math.min(Math.max(y, r), size - r);
  const d = Math.hypot(x - cx, y - cy);
  return Math.max(0, Math.min(1, r - d + 0.5));
}

/**
 * Flatten the SVG path segment into a polyline (cubic Bezier sampling).
 * Coordinates keep their original values inside the 24×24 viewport; scaling is left to the caller.
 *
 * 把 SVG 那段 path 展开成折线（三次贝塞尔采样）。
 * 坐标保持 24×24 视口里的原值，缩放交给调用方。
 */
function buildWave(samples = 64) {
  const cubics = [
    // M3 16.5 c3.2 0 3.2 -9 6.4 -9
    [[3, 16.5], [6.2, 16.5], [6.2, 7.5], [9.4, 7.5]],
    // s3.2 9 3.2 9 (first control point = reflection of the previous control point about the start)
    // s3.2 9 3.2 9（第一控制点 = 上一控制点关于起点的反射）
    [[9.4, 7.5], [12.6, 7.5], [12.6, 16.5], [15.8, 16.5]],
    // s3.2 -9 5.2 -9
    [[15.8, 16.5], [19.0, 16.5], [19.0, 7.5], [21.0, 7.5]]
  ];
  const points = [];
  for (const [p0, p1, p2, p3] of cubics) {
    for (let i = 0; i <= samples; i += 1) {
      const t = i / samples;
      const u = 1 - t;
      const x = u * u * u * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t * t * t * p3[0];
      const y = u * u * u * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t * t * t * p3[1];
      points.push([x, y]);
    }
  }
  return points;
}

const WAVE = buildWave();

/**
 * Render one size×size icon and return a PNG Buffer.
 * 渲染一张 size×size 的图标，返回 PNG Buffer。
 */
function renderPng(size) {
  const radius = size * 0.295; // the corner ratio from the screenshot / 照截图那个圆角比例
  const scale = (size * 0.56) / 18; // curve spans 18 units horizontally → 56% of the width / 曲线横向 18 个单位 → 占宽 56%
  const stroke = size * 0.078; // same ratio as brand-mark's 1.9/24 / 与 brand-mark 的 1.9/24 同比例
  const offset = size / 2 - 12 * scale; // center the 24 viewport / 24 视口居中
  const border = size >= 48 ? Math.max(1, size * 0.004) : 0;

  const pixels = Buffer.alloc(size * size * 4);

  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const i = (y * size + x) * 4;
      const px = x + 0.5;
      const py = y + 0.5;
      const cover = roundedCoverage(px, py, size, radius);
      if (cover <= 0) {
        continue; // transparent outside the rounded corners / 圆角外透明
      }

      let r = 255;
      let g = 255;
      let b = 255;

      // Distance field of the curve → 1px anti-aliasing
      // 曲线的距离场 → 1px 抗锯齿
      let dist = Infinity;
      for (let s = 0; s < WAVE.length - 1; s += 1) {
        const ax = WAVE[s][0] * scale + offset;
        const ay = WAVE[s][1] * scale + offset;
        const bx = WAVE[s + 1][0] * scale + offset;
        const by = WAVE[s + 1][1] * scale + offset;
        dist = Math.min(dist, distToSegment(px, py, ax, ay, bx, by));
      }
      const edge = stroke / 2;
      if (dist < edge + 1) {
        const alpha = dist <= edge ? 1 : 1 - (dist - edge);
        r = Math.round(255 + (10 - 255) * alpha);
        g = Math.round(255 + (10 - 255) * alpha);
        b = Math.round(255 + (10 - 255) * alpha);
      }

      // Very thin stroke: keeps the white block from blending into the background on light desktops,
      // and stays nearly invisible on dark ones
      // 极细描边：浅色桌面上白块不糊在背景里，深色桌面上基本看不见
      if (border > 0) {
        const inner = roundedCoverage(px, py, size, Math.max(1, radius - border));
        const rim = Math.max(0, cover - inner);
        if (rim > 0) {
          const t = Math.min(1, rim) * 0.12;
          r = Math.round(r * (1 - t) + 20 * t);
          g = Math.round(g * (1 - t) + 20 * t);
          b = Math.round(b * (1 - t) + 20 * t);
        }
      }

      pixels[i] = r;
      pixels[i + 1] = g;
      pixels[i + 2] = b;
      pixels[i + 3] = Math.round(255 * cover);
    }
  }

  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 6; // RGBA

  const stride = size * 4 + 1;
  const raw = Buffer.alloc(stride * size);
  for (let y = 0; y < size; y++) {
    raw[y * stride] = 0; // filter: none
    pixels.copy(raw, y * stride + 1, y * size * 4, (y + 1) * size * 4);
  }

  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0))
  ]);
}

/**
 * Multi-size ICO: one PNG per size, so each context picks the most fitting one.
 * 多尺寸 ICO：每个尺寸一张 PNG，各场景直接用最合适那档。
 */
function wrapIco(entries) {
  const header = Buffer.alloc(6);
  header.writeUInt16LE(0, 0);
  header.writeUInt16LE(1, 2);
  header.writeUInt16LE(entries.length, 4);

  const directory = Buffer.alloc(16 * entries.length);
  let offset = 6 + 16 * entries.length;
  entries.forEach(({ size, png }, index) => {
    const base = index * 16;
    directory[base] = size >= 256 ? 0 : size;
    directory[base + 1] = size >= 256 ? 0 : size;
    directory.writeUInt16LE(1, base + 4); // planes
    directory.writeUInt16LE(32, base + 6); // bit count
    directory.writeUInt32LE(png.length, base + 8);
    directory.writeUInt32LE(offset, base + 12);
    offset += png.length;
  });

  return Buffer.concat([header, directory, ...entries.map((entry) => entry.png)]);
}

const outDir = path.join(__dirname, '..', 'build');
fs.mkdirSync(outDir, { recursive: true });

const master = renderPng(1024);
const icoSizes = [16, 24, 32, 48, 64, 128, 256];
const icoEntries = icoSizes.map((size) => ({ size, png: renderPng(size) }));
const ico = wrapIco(icoEntries);

fs.writeFileSync(path.join(outDir, 'icon.png'), master);
fs.writeFileSync(path.join(outDir, 'icon.ico'), ico);

console.log('[icon] icon.png=%d bytes (1024×1024)  icon.ico=%d bytes (%s)',
  master.length, ico.length, icoSizes.join('/'));
