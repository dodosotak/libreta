"""Splits the square Libreta app icon into adaptive-icon layers and a transparent start-screen logo.

usage: python branding/make_icon.py branding/libreta-icon.png app/src/main/res [preview.png]
"""
import sys
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

src, res = sys.argv[1], sys.argv[2]
img = Image.open(src).convert("RGB")
W, H = img.size
a = np.asarray(img).astype(np.float64)
lum = a @ [0.2126, 0.7152, 0.0722]

# Inside of the rounded square: flood the white corners from outside, everything else is the icon.
mask_img = Image.fromarray(((lum > 235) * 255).astype(np.uint8)).copy()
for corner in [(0, 0), (W - 1, 0), (0, H - 1), (W - 1, H - 1)]:
    ImageDraw.floodfill(mask_img, corner, 128)
inside_img = Image.fromarray(((np.asarray(mask_img) != 128) * 255).astype(np.uint8))
inside = np.asarray(inside_img) > 0
# Shrink past the anti-aliased rim of the rounded square so it doesn't count as logo.
core = np.asarray(inside_img.filter(ImageFilter.MinFilter(31))) > 0

# Fit the blue gradient (color = c0 + cx*x + cy*y) on plain background pixels.
ys, xs = np.mgrid[0:H, 0:W]
blue = core & (lum < 130) & (a[..., 2] > a[..., 0] + 60)
m = np.column_stack([np.ones(blue.sum()), xs[blue] / W, ys[blue] / H])
coef, *_ = np.linalg.lstsq(m, a[blue], rcond=None)
model = coef[0] + coef[1] * (xs / W)[..., None] + coef[2] * (ys / H)[..., None]

# Logo = pixels clearly lighter than the gradient behind them.
diff = (a - model) @ [0.2126, 0.7152, 0.0722]
alpha = np.clip((diff - 25) / 60, 0, 1) * core
ys_l, xs_l = np.nonzero(alpha > 0.5)
x0, x1, y0, y1 = xs_l.min(), xs_l.max(), ys_l.min(), ys_l.max()
print("logo bbox", x0, y0, x1, y1)

# Foreground: 432px (108dp @ xxxhdpi); logo fits the 66dp safe circle.
SIZE = 432
logo = Image.fromarray(np.dstack([a, alpha * 255]).clip(0, 255).astype(np.uint8), "RGBA").crop((x0, y0, x1 + 1, y1 + 1))
lw, lh = logo.size
# Start screen: just the logo (white + light blue accent) on the screen's own gradient.
logo.resize((900, round(lh * 900 / lw)), Image.LANCZOS).save(f"{res}/drawable-nodpi/libreta_logo_white.png", optimize=True)
target_w = 58 / 108 * SIZE
scale = target_w / lw
logo = logo.resize((round(lw * scale), round(lh * scale)), Image.LANCZOS)
fg = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
# Keep the logo where it sits in the original icon vertically (slightly above center incl. wheels).
cy = ((y0 + y1) / 2) / H
fg.paste(logo, ((SIZE - logo.width) // 2, round(cy * SIZE - logo.height / 2)), logo)
fg.save(f"{res}/drawable-nodpi/ic_launcher_libreta_fg.png", optimize=True)

# Monochrome (themed icons): white logo shape.
mono = Image.new("RGBA", fg.size, (255, 255, 255, 0))
mono.putalpha(fg.getchannel("A"))
mono.save(f"{res}/drawable-nodpi/ic_launcher_libreta_mono.png", optimize=True)

# Background: the fitted gradient, full bleed.
gy, gx = np.mgrid[0:SIZE, 0:SIZE] / SIZE
bg = coef[0] + coef[1] * gx[..., None] + coef[2] * gy[..., None]
Image.fromarray(bg.clip(0, 255).astype(np.uint8), "RGB").filter(ImageFilter.GaussianBlur(1)).save(
    f"{res}/drawable-nodpi/ic_launcher_libreta_bg.png", optimize=True)

# Preview: how launchers mask it (circle and rounded square).
prev = Image.new("RGBA", (SIZE * 2 + 40, SIZE), (240, 240, 240, 255))
icon = Image.open(f"{res}/drawable-nodpi/ic_launcher_libreta_bg.png").convert("RGBA")
icon.alpha_composite(fg)
for i, shape in enumerate(["circle", "squircle"]):
    mk = Image.new("L", (SIZE, SIZE), 0)
    d = ImageDraw.Draw(mk)
    inset = SIZE * 21 / 108  # launchers show the middle 72dp of the 108dp layers
    box = (inset, inset, SIZE - inset, SIZE - inset)
    d.ellipse(box, fill=255) if shape == "circle" else d.rounded_rectangle(box, radius=SIZE * 0.12, fill=255)
    prev.paste(icon, (i * (SIZE + 40), 0), mk)
prev.save(sys.argv[3] if len(sys.argv) > 3 else "preview.png")
