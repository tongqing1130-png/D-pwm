from PIL import Image, ImageDraw
import numpy as np, os

S = 108
h = np.linspace(0, 1, S)[:, None]
top = np.array([0x14, 0x1E, 0x46]); bot = np.array([0x07, 0x0A, 0x18])
bg = (top[None, None, :] * (1 - h[..., None]) + bot[None, None, :] * h[..., None])
bg = np.repeat(bg, S, axis=1)

yy, xx = np.mgrid[0:S, 0:S]
for (cx, cy, r, rgb, a) in [(20, 16, 78, (0x2B, 0xB7, 0xFF), 0.62), (94, 98, 86, (0x6C, 0x5C, 0xE7), 0.5)]:
    d = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2) / r
    m = (np.clip(1 - d, 0, 1) ** 2 * a)[..., None]
    bg = bg * (1 - m) + np.array(rgb)[None, None, :] * m
img = Image.fromarray(np.clip(bg, 0, 255).astype(np.uint8), 'RGB').convert('RGBA')

mask = Image.new('L', (S, S), 0)
d = ImageDraw.Draw(mask)
d.ellipse((47 - 28, 54 - 28, 47 + 28, 54 + 28), fill=255)
d.rectangle((0, 0, 34, S), fill=0)
d.rectangle((34, 26, 47, 82), fill=255)
d.ellipse((56 - 9, 46 - 9, 56 + 9, 46 + 9), fill=0)
d.polygon([(52.5, 55), (59.5, 55), (57.4, 68.5), (54.6, 68.5)], fill=0)

white = Image.new('RGBA', (S, S), (255, 255, 255, 255))
img = Image.alpha_composite(img, Image.composite(white, Image.new('RGBA', (S, S), (0, 0, 0, 0)), mask))

os.makedirs('docs/icon', exist_ok=True)
img.resize((432, 432), Image.LANCZOS).save('docs/icon/preview-432.png')
strip = Image.new('RGBA', (300, 100), (0, 0, 0, 0))
for i, sz in enumerate([96, 72, 48]):
    strip.paste(img.resize((sz, sz), Image.LANCZOS), (8 + i * 96, 2))
strip.save('docs/icon/preview-sizes.png')
circ = Image.new('L', (432, 432), 0); ImageDraw.Draw(circ).ellipse((0, 0, 431, 431), fill=255)
big = img.resize((432, 432), Image.LANCZOS); big.putalpha(circ); big.save('docs/icon/preview-round.png')
# 导出 512 商店图
img.resize((512, 512), Image.LANCZOS).save('docs/icon/icon-512.png')
print('ok')
