from PIL import Image, ImageDraw, ImageFilter
import numpy as np, os

S = 108
SS = 4          # 超采样
N = S * SS
h = np.linspace(0, 1, N)[:, None]
top = np.array([0xB6, 0xE4, 0xFF]); bot = np.array([0xE9, 0xD8, 0xFF])
bg = (top[None, None, :] * (1 - h[..., None]) + bot[None, None, :] * h[..., None])
bg = np.repeat(bg, N, axis=1)
yy, xx = np.mgrid[0:N, 0:N]
for (cx, cy, r, a) in [(30, 24, 90, 0.55), (86, 92, 80, 0.35)]:
    d = np.sqrt((xx - cx * SS) ** 2 + (yy - cy * SS) ** 2) / (r * SS)
    m = (np.clip(1 - d, 0, 1) ** 2 * a)[..., None]
    bg = bg * (1 - m) + np.array([255, 255, 255])[None, None, :] * m
img = Image.fromarray(np.clip(bg, 0, 255).astype(np.uint8), 'RGB').convert('RGBA')
d = ImageDraw.Draw(img)

def sc(v): return v * SS

# 锁勾（圆头粗描边）
d.rounded_rectangle((sc(40), sc(27), sc(68), sc(62)), radius=sc(14), outline=(255, 156, 92, 255), width=int(sc(9)))

# 锁身
d.rounded_rectangle((sc(28), sc(48), sc(80), sc(88)), radius=sc(17), fill=(96, 176, 255, 255))
d.rounded_rectangle((sc(28), sc(48), sc(80), sc(74)), radius=sc(17), fill=(130, 199, 255, 255))
# 顶部高光
d.ellipse((sc(33), sc(52), sc(50), sc(60)), fill=(255, 255, 255, 66))
# 腮红
d.ellipse((sc(33), sc(68), sc(45), sc(76)), fill=(255, 130, 160, 90))
d.ellipse((sc(63), sc(68), sc(75), sc(76)), fill=(255, 130, 160, 90))
# 眼睛
d.rounded_rectangle((sc(44.6), sc(59.4), sc(50.4), sc(71.0)), radius=sc(2.9), fill=(26, 42, 66, 255))
d.rounded_rectangle((sc(57.6), sc(59.4), sc(63.4), sc(71.0)), radius=sc(2.9), fill=(26, 42, 66, 255))
# 眼里的高光
d.ellipse((sc(46.2), sc(61.2), sc(48.6), sc(63.6)), fill=(255, 255, 255, 235))
d.ellipse((sc(59.2), sc(61.2), sc(61.6), sc(63.6)), fill=(255, 255, 255, 235))
# 微笑
d.arc((sc(49), sc(68), sc(59), sc(78)), start=15, end=165, fill=(26, 42, 66, 255), width=int(sc(2.6)))

img = img.resize((S, S), Image.LANCZOS)
os.makedirs('docs/icon', exist_ok=True)
img.resize((432, 432), Image.LANCZOS).save('docs/icon/cute-432.png')
strip = Image.new('RGBA', (300, 100), (0, 0, 0, 0))
for i, sz in enumerate([96, 72, 48]):
    strip.paste(img.resize((sz, sz), Image.LANCZOS), (8 + i * 96, 2))
strip.save('docs/icon/cute-sizes.png')
print('ok')
