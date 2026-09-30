"""Fits the bundled font to the reference's live HUD text (size and baseline position).

Renders each string with Fredoka Bold, slides/scales it over the reference's text
pixels and prints the best (IoU, size, x, baseline_y, angle). The winning values are
the TIMER_/SCORE_/COINS_/COMBO_ constants in Renderer.kt.

    python3 tools/assets/fit_text.py
"""
import os

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
REF = os.path.join(REPO, "design", "level5_reference.png")
FONT = os.path.join(REPO, "app", "src", "main", "assets", "fonts", "Fredoka-Bold.ttf")

ref = np.array(Image.open(REF).convert("RGB"))
hsv = cv2.cvtColor(ref, cv2.COLOR_RGB2HSV)
L = 0.299 * ref[..., 0] + 0.587 * ref[..., 1] + 0.114 * ref[..., 2]


def white(box):
    l, t, r, b = box
    m = np.zeros(L.shape, bool)
    m[t:b, l:r] = (L[t:b, l:r] > 190) & (hsv[t:b, l:r, 1] < 70)
    return m


def gold(box):
    l, t, r, b = box
    h = hsv[t:b, l:r]
    m = np.zeros(L.shape, bool)
    m[t:b, l:r] = (h[..., 0] >= 8) & (h[..., 0] <= 38) & (h[..., 1] > 90) & (h[..., 2] > 185)
    return m


def render(text, size, x, y, angle=0.0):
    f = ImageFont.truetype(FONT, size)
    im = Image.new("L", (1024, 1536), 0)
    ImageDraw.Draw(im).text((x, y), text, font=f, fill=255, anchor="ls")
    if angle:
        im = im.rotate(-angle, resample=Image.BICUBIC, center=(x, y))
    return np.array(im) > 127


def fit(text, mask, sizes, angles=(0,)):
    ys, xs = np.nonzero(mask)
    best = None
    for ang in angles:
        for s in sizes:
            ry, rx = np.nonzero(render(text, s, 200, 400, ang))
            dx = (xs.min() + xs.max()) / 2 - (rx.min() + rx.max()) / 2
            dy = (ys.min() + ys.max()) / 2 - (ry.min() + ry.max()) / 2
            for ox in range(-4, 5, 2):
                for oy in range(-4, 5, 2):
                    r = render(text, s, 200 + dx + ox, 400 + dy + oy, ang)
                    iou = (r & mask).sum() / max(1, (r | mask).sum())
                    if best is None or iou > best[0]:
                        best = (round(float(iou), 3), s, round(200 + dx + ox, 1), round(400 + dy + oy, 1), ang)
    return best


if __name__ == "__main__":
    print("timer ", fit("0:36", white((860, 40, 995, 115)), range(50, 62, 2)))
    print("score ", fit("1,760", white((60, 1378, 255, 1458)), range(72, 84, 2)))
    print("coins ", fit("+30", gold((725, 1345, 878, 1435)), range(79, 91, 3)))
    print("comboX", fit("x9", gold((790, 305, 975, 445)), range(140, 160, 4), (-6, -5, -4)))
