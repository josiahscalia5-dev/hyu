"""Fits the bundled font to the reference's live HUD text.

For each string (timer, score, coins, "Combo", "x9") the painted letters are
segmented, then Fredoka Bold is slid, scaled, squeezed and rotated over them to
maximise overlap (IoU). build_sprites.py writes the winners into level5.json as
left-baseline anchors, so the app draws its live numbers exactly where the
reference paints them.

    python3 tools/assets/fit_text.py     # prints the fits
"""
import os

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

import layout

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
REF = os.path.join(REPO, "design", layout.REFERENCE)
FONT = os.path.join(REPO, "app", "src", "main", "assets", "fonts", "Fredoka-Bold.ttf")


def masks(ref):
    hsv = cv2.cvtColor(ref, cv2.COLOR_RGB2HSV)
    L = 0.299 * ref[..., 0] + 0.587 * ref[..., 1] + 0.114 * ref[..., 2]

    def boxed(m, box):
        out = np.zeros(m.shape, bool)
        l, t, r, b = box
        out[t:b, l:r] = m[t:b, l:r]
        return out

    white = (L > 185) & (hsv[..., 1] < 70)
    cream = (L > 170) & (hsv[..., 1] < 150) & (hsv[..., 0] >= 15) & (hsv[..., 0] <= 45) | white
    gold = (hsv[..., 0] >= 8) & (hsv[..., 0] <= 38) & (hsv[..., 1] > 90) & (hsv[..., 2] > 185)
    return white, cream, gold, boxed


class Fitter:
    def __init__(self, ref):
        self.ref = ref
        self.white, self.cream, self.gold, self.boxed = masks(ref)

    @staticmethod
    def render(text, size, x, y, angle, scale_x, box, pad=0):
        """Letter mask of `text` at left-baseline (x, y) in stage coords.

        Covers `box` grown by `pad` on every side; returns (mask, origin).
        """
        l, t, r, b = box
        keep = pad
        pad = max(pad, 60)
        w, h = r - l + 2 * pad, b - t + 2 * pad
        f = ImageFont.truetype(FONT, size)
        im = Image.new("L", (w, h), 0)
        ox, oy = x - l + pad, y - t + pad
        ImageDraw.Draw(im).text((ox, oy), text, font=f, fill=255, anchor="ls")
        if scale_x != 1.0:
            # Squeeze horizontally about the anchor.
            a = (1 / scale_x, 0, ox - ox / scale_x, 0, 1, 0)
            im = im.transform(im.size, Image.AFFINE, a, resample=Image.BILINEAR)
        if angle:
            im = im.rotate(-angle, resample=Image.BICUBIC, center=(ox, oy))
        m = np.array(im) > 127
        k0 = pad - keep
        return m[k0:k0 + b - t + 2 * keep, k0:k0 + r - l + 2 * keep], (l - keep, t - keep)

    def fit(self, text, mask, box, sizes, angles=(0,), scales=(1.0,)):
        l, t, r, b = box
        target = mask[t:b, l:r]
        ys, xs = np.nonzero(target)
        best = None
        for ang in angles:
            for sx in scales:
                for s in sizes:
                    x0, y0 = l + (r - l) // 3, (t + b) // 2
                    probe, (px, py) = self.render(text, s, x0, y0, ang, sx, box, pad=400)
                    ry, rx = np.nonzero(probe)
                    if len(rx) == 0:
                        continue
                    # Align the probe's bounding-box centre with the target's (both stage coords).
                    dx = (xs.min() + xs.max()) / 2 + l - ((rx.min() + rx.max()) / 2 + px)
                    dy = (ys.min() + ys.max()) / 2 + t - ((ry.min() + ry.max()) / 2 + py)
                    for ox in range(-4, 5, 2):
                        for oy in range(-4, 5, 2):
                            x = x0 + dx + ox
                            y = y0 + dy + oy
                            m, _ = self.render(text, s, x, y, ang, sx, box)
                            iou = (m & target).sum() / max(1, (m | target).sum())
                            if best is None or iou > best["iou"]:
                                width = ImageFont.truetype(FONT, s).getlength(text) * sx
                                best = {"iou": round(float(iou), 3), "size": s, "x": round(float(x), 1),
                                        "y": round(float(y), 1), "angle": ang, "scaleX": sx,
                                        "width": round(float(width), 1)}
        return best


def fit_all(ref=None):
    if ref is None:
        ref = np.array(Image.open(REF).convert("RGB"))
    f = Fitter(ref)
    combo_box, coins_box = layout.GOLD_TEXT_BOXES
    cl, ct, cr, cb = combo_box
    # "Combo" is the upper part of the combo box, "x9" the lower.
    word_box = (cl, ct, cr, ct + int((cb - ct) * 0.47))
    num_box = (cl + 30, ct + int((cb - ct) * 0.40), cr, cb)
    out = {
        "timer": f.fit("0:36", f.white, layout.TIMER_DIGITS, range(40, 56, 2)),
        "score": f.fit("1,760", f.cream | f.gold, layout.SCORE_DIGITS, range(56, 76, 2)),
        "coins": f.fit("+30", f.gold, coins_box, range(62, 82, 2)),
        "combo": f.fit("Combo", f.gold, word_box, range(68, 92, 3), angles=(-18, -15, -12, -9),
                       scales=(0.74, 0.8, 0.86, 0.92)),
        "comboNumber": f.fit("x9", f.gold, num_box, range(120, 164, 4), angles=(-14, -11, -8, -5),
                             scales=(0.9, 1.0, 1.1)),
    }
    return out


if __name__ == "__main__":
    for k, v in fit_all().items():
        print(k, v)
