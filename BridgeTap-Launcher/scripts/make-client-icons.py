#!/usr/bin/env python3
"""Turn the clients' official logos (webp/png) into the 42px icons used by the UI (2x = 84px
transparent PNG).

Usage:
    python scripts/make-client-icons.py \
        --badlion "path/to/badlion.webp" \
        --lunar   "path/to/lunar.webp"

Why this step instead of dropping the originals into assets:
  * Badlion's original is a 1996x710 horizontal lockup: a hexagonal lion head on the left plus an
    almost pure white "BADLION" word on the right. Scaling it straight down to a square icon gives a
    single white line with nothing visible, so the hexagon has to be cropped out and the white
    background removed.
  * Lunar's original is "white glyph + transparent background"; it looks like an empty image on a
    white preview (128x117, mostly pure white pixels). It only works on a dark block, so it must not
    carry a background and only the alpha-carrying glyph itself can be output.
  * The card icon block has its own gradient underneath (.card__icon--lunar / --badlion), so the
    icon must have a transparent background, otherwise you get dirty "block on block" edges.

White-to-alpha works by inverting alpha from the largest channel distance to white, then
un-premultiplying the colour:
    d = max(255-r, 255-g, 255-b) / 255
    alpha = clamp(d / INK_MIN, 0, 1)        # INK_MIN = smallest d of the ink colour; below it counts as an edge
    color = (C - (1-alpha)*255) / alpha     # un-premultiply so edges don't smear white
This keeps the hexagon interior 100% opaque (no grey cast on dark backgrounds) while edges stay
anti-aliased.

把客户端官方 logo（webp/png）加工成界面用的 42px 图标（2x = 84px 透明 PNG）。

用法：
    python scripts/make-client-icons.py \
        --badlion "path/to/badlion.webp" \
        --lunar   "path/to/lunar.webp"

为什么要这一步，而不是直接把原图丢进 assets：
  * Badlion 的原图是 1996x710 的横版组合标：左边六边形狮头 + 右边几乎纯白的 "BADLION" 字。
    直接缩成方图标 = 一条白线，什么都看不见，所以必须裁出六边形再把白底去掉。
  * Lunar 的原图是"白色图形 + 透明底"，白底预览里看着像空图（128x117，纯白像素占多数）。
    它只能贴在深色块上，所以不能自带背景，只能输出带 alpha 的图形本身。
  * 卡片图标块有自己的渐变底（.card__icon--lunar / --badlion），所以图标必须是透明底，
    否则会出现"方块压方块"的脏边。

白底转透明用的是"按到白色的最大通道距离"反解 alpha + 反预乘颜色：
    d = max(255-r, 255-g, 255-b) / 255
    alpha = clamp(d / INK_MIN, 0, 1)        # INK_MIN = 墨色最小的 d，低于它就当边缘
    color = (C - (1-alpha)*255) / alpha     # 反预乘，边缘不会糊白
这样六边形内部保持 100% 不透明（不会在深色底上发灰），边缘又保留抗锯齿。
"""

import argparse
from pathlib import Path

from PIL import Image

# The lightest ink colour inside Badlion's hexagon is cyan (42,160,196): d = (255-42)/255 = 0.835
# Badlion 六边形里最"浅"的墨色是青色 (42,160,196)：d = (255-42)/255 = 0.835
INK_MIN = 0.80
TILE = 84          # 2x of the 42px logical size / 42px 逻辑尺寸的 2x
HEX_FILL = 0.84    # hexagon margin: filling it would let the icon block's rounded corners clip its
                   # left/right tips · 六边形留边：铺满会被图标块的圆角切掉左右尖角
GLYPH_FILL = 0.56  # the moon is a thin stroke and would touch the edge if it filled the tile
                   # 月亮是细笔画，铺满会顶到边，留出来


def ink_bbox(img):
    """Bounding box of the non-white area (excludes the near-white wordmark).
    非白区域的包围盒（排除近白的字标）。"""
    w, h = img.size
    px = img.load()
    left, top, right, bottom = w, h, 0, 0
    for y in range(h):
        for x in range(w):
            r, g, b, _ = px[x, y]
            if min(r, g, b) < 240:
                left = min(left, x)
                right = max(right, x)
                top = min(top, y)
                bottom = max(bottom, y)
    if right <= left:
        raise SystemExit("No ink area found in the source image, check the input file / 原图里没有找到墨色区域，检查输入文件")
    return left, top, right + 1, bottom + 1


def white_to_alpha(img):
    """White background -> transparent (un-premultiplied, so edges don't smear white).
    白底 -> 透明底（反预乘，边缘不糊白）。"""
    img = img.convert("RGBA")
    w, h = img.size
    out = Image.new("RGBA", (w, h))
    src = img.load()
    dst = out.load()
    for y in range(h):
        for x in range(w):
            r, g, b, a0 = src[x, y]
            if a0 == 0:
                dst[x, y] = (0, 0, 0, 0)
                continue
            d = max(255 - r, 255 - g, 255 - b) / 255.0
            alpha = min(1.0, d / INK_MIN)
            if alpha <= 0.004:
                dst[x, y] = (0, 0, 0, 0)
                continue
            # Un-premultiply: C = alpha*F + (1-alpha)*255  =>  F = (C - (1-alpha)*255) / alpha
            # 反预乘：C = alpha*F + (1-alpha)*255  =>  F = (C - (1-alpha)*255) / alpha
            f = [min(255, max(0, int(round((c - (1 - alpha) * 255) / alpha)))) for c in (r, g, b)]
            dst[x, y] = (f[0], f[1], f[2], int(round(alpha * 255)))
    return out


def fit_square(img, fill):
    """Crop to the content bounds and fit into a square canvas at the given fill ratio (centered,
    proportional scaling).
    裁到内容边界，按 fill 比例塞进正方形画布（居中等比缩放）。"""
    box = img.getbbox()
    if box is None:
        raise SystemExit("Empty image after cropping / 裁剪后是空图")
    art = img.crop(box)
    side = int(TILE * fill)
    scale = min(side / art.width, side / art.height)
    art = art.resize((max(1, round(art.width * scale)), max(1, round(art.height * scale))), Image.LANCZOS)
    canvas = Image.new("RGBA", (TILE, TILE), (0, 0, 0, 0))
    canvas.paste(art, ((TILE - art.width) // 2, (TILE - art.height) // 2), art)
    return canvas


def make_badlion(path):
    raw = Image.open(path).convert("RGBA")
    hexagon = raw.crop(ink_bbox(raw))
    return fit_square(white_to_alpha(hexagon), HEX_FILL)


def make_lunar(path):
    raw = Image.open(path).convert("RGBA")
    # White glyph + transparent background: alpha itself is the mask, so just crop the visible part
    # 白图形 + 透明底：alpha 本身就是蒙版，裁出可见部分即可
    mask = raw.getchannel("A").point(lambda v: 255 if v > 8 else 0)
    box = mask.getbbox()
    if box is None:
        raise SystemExit("lunar source image is fully transparent, no glyph / lunar 源图整张透明，没有图形")
    art = raw.crop(box)
    # Normalize to pure white: the source is already white, this just clears any faint grey noise
    # 统一成纯白：源图本来就是白的，这里顺手清掉可能的淡灰噪点
    art.putalpha(art.getchannel("A"))
    return fit_square(art, GLYPH_FILL)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--badlion", required=True)
    ap.add_argument("--lunar", required=True)
    ap.add_argument("--out", default="src/renderer/assets")
    args = ap.parse_args()

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    badlion = make_badlion(args.badlion)
    lunar = make_lunar(args.lunar)

    badlion.save(out / "badlion.png")
    lunar.save(out / "lunar.png")
    print("wrote", (out / "badlion.png").as_posix(), badlion.size)
    print("wrote", (out / "lunar.png").as_posix(), lunar.size)

    # Preview: paste the icons onto the card's real background colour to confirm they look clean on
    # both dark and light
    # 预览图：把图标贴到卡片里真实的底色上，确认在深色/浅色都不脏
    preview = Image.new("RGBA", (TILE * 4, TILE * 2), (255, 255, 255, 255))
    tiles = [
        ((43, 48, 56), (21, 23, 28), badlion),
        ((111, 123, 255), (61, 59, 214), lunar),
    ]
    for index, (top, bottom, icon) in enumerate(tiles):
        block = Image.new("RGBA", (TILE, TILE), (0, 0, 0, 0))
        bpx = block.load()
        for y in range(TILE):
            mix = y / (TILE - 1)
            color = tuple(int(top[i] + (bottom[i] - top[i]) * mix) for i in range(3))
            for x in range(TILE):
                bpx[x, y] = color + (255,)
        block.alpha_composite(icon)
        preview.alpha_composite(block, (index * TILE, 0))
        preview.alpha_composite(block, (index * TILE, TILE))
    preview.convert("RGB").save(Path(__file__).resolve().parent.parent / "docs" / "icon-preview.png")
    print("wrote docs/icon-preview.png")


if __name__ == "__main__":
    main()
