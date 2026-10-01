"""Repaints Level 4's lagoon for the slicing game, and empties the plaque's stars.

The approved screen paints a burst of treasure over the middle of the lagoon. Painted
out, LaMa leaves a washed-out haze there (it smears holes this large), which would
sit in the middle of the screen all level once the treasure is sliced away. This
paints that stretch of water instead: each row takes the colour of the real lagoon
water beside it, with caustic ripples (finer toward the horizon) and sparkles in the
painted style. The mist at the foot of the far waterfalls stays.

The plaque paints two gold stars and one empty one; stars are earned during play, so
all three are made empty (copies of the painted empty star).

Also drops the painted HUD values from the opening burst's layers.

Run after build.py, or on its own over the committed assets:
  python3 tools/level4/lagoon.py
"""
import os
import sys

import cv2
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import build as B  # noqa: E402
import layout  # noqa: E402

TOP = 470          # above this row the far waterfalls and their mist stay as they are
BLEND = 90         # rows over which the mist gives way to open water
DOCK = 1262        # the near dock's edge


def noise(h, w, sx, sy, seed, octaves=4):
    r = np.random.default_rng(seed)
    out = np.zeros((h, w), np.float32)
    amp, tot = 1.0, 0.0
    for o in range(octaves):
        gx, gy = max(2, int(sx / 2 ** o)), max(2, int(sy / 2 ** o))
        g = r.random((h // gy + 3, w // gx + 3)).astype(np.float32)
        out += cv2.resize(g, ((w // gx + 3) * gx, (h // gy + 3) * gy), interpolation=cv2.INTER_CUBIC)[:h, :w] * amp
        tot += amp
        amp *= 0.5
    out /= tot
    return (out - out.min()) / (out.max() - out.min() + 1e-6)


def hole_mask(ref):
    """What build.py paints out: treasure, launcher, the burst, the HUD values."""
    objects = B.object_masks()
    fill = np.zeros((B.H, B.W), bool)
    for m in objects.values():
        fill |= m
    fill = cv2.dilate(fill.astype(np.uint8), cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (15, 15))) > 0
    return fill | B.explosion_mask(ref, objects)


def water(ref, hole):
    """Open lagoon water for the whole stage, coloured row by row like the real water."""
    H, W = hole.shape
    hsv = cv2.cvtColor(ref, cv2.COLOR_RGB2HSV).astype(np.float32)
    h, s, v = hsv[..., 0] * 2, hsv[..., 1] / 255, hsv[..., 2] / 255
    real = (h > 170) & (h < 215) & (s > 0.45) & (v > 0.55)
    real &= ~(cv2.dilate(hole.astype(np.uint8), np.ones((25, 25), np.uint8)) > 0)
    real[:TOP] = False
    rows = np.zeros((H, 3), np.float32)
    have = np.zeros(H, bool)
    for y in range(TOP, H):
        band = real[max(0, y - 30):y + 30]
        if band.sum() > 40:
            rows[y] = np.median(ref[max(0, y - 30):y + 30][band], axis=0)
            have[y] = True
    ys = np.where(have)[0]
    for c in range(3):
        rows[:, c] = np.interp(np.arange(H), ys, rows[ys, c])
    rows = cv2.GaussianBlur(rows[:, None, :], (0, 0), 20)[:, 0, :]
    out = np.repeat(rows[:, None, :], W, axis=1)
    # Caustic ripples: contour lines of stretched noise, as the painted water has.
    n1 = noise(H, W, 90, 28, 1, 3)
    n2 = noise(H, W, 60, 20, 2, 3)
    rip = np.exp(-((n1 - 0.5) * 14) ** 2) * 0.7 + np.exp(-((n2 - 0.5) * 16) ** 2) * 0.5
    rip = cv2.GaussianBlur(rip, (0, 0), 1.0)
    low = noise(H, W, 260, 140, 3, 3) - 0.5
    out = out * (1 + 0.10 * low[..., None])
    out = out * (1 - 0.42 * rip[..., None]) + np.array([235, 255, 255], np.float32) * 0.42 * rip[..., None]
    trough = np.exp(-((n2 - 0.2) * 10) ** 2) * 0.25
    out = out * (1 - trough[..., None] * 0.35)
    r = np.random.default_rng(9)
    sp = np.zeros((H, W), np.float32)
    for _ in range(500):
        cv2.circle(sp, (int(r.integers(0, W)), int(r.integers(TOP, DOCK))), int(r.integers(1, 3)), 1.0, -1)
    sp = cv2.GaussianBlur(sp, (0, 0), 0.8)
    return (out * (1 - sp[..., None]) + 255 * sp[..., None]).clip(0, 255)


def paint_lagoon(ref, plate, hole):
    """The plate with open water where the burst was (and its glow on the water around it)."""
    H, W = hole.shape
    hsv = cv2.cvtColor(plate, cv2.COLOR_RGB2HSV).astype(np.float32)
    s, v = hsv[..., 1] / 255, hsv[..., 2] / 255
    # The burst's light also washed over the water just outside the hole.
    glow = (v > 0.75) & (s < 0.45)
    zone = hole | ((cv2.dilate(hole.astype(np.uint8), np.ones((71, 71), np.uint8)) > 0) & glow)
    zone[:TOP] = False
    zone[DOCK:] = False
    # Never paint over the dock posts and piers at the sides.
    zone = cv2.morphologyEx(zone.astype(np.uint8), cv2.MORPH_CLOSE, np.ones((9, 9), np.uint8)) > 0
    k = cv2.GaussianBlur(zone.astype(np.float32), (0, 0), 7)
    yy = np.arange(H, dtype=np.float32)[:, None]
    k *= np.clip((yy - TOP) / BLEND, 0, 1)
    k = k[..., None]
    out = plate.astype(np.float32) * (1 - k) + water(ref, hole) * k
    return out.round().clip(0, 255).astype(np.uint8)


def empty_stars(plate):
    """Copies the painted empty star over the two painted gold ones."""
    out = plate.copy()
    el, et, er, eb = layout.STAR_BOXES[2]
    src = plate[et - 6:eb + 6, el - 6:er + 6].astype(np.float32)
    h, w = src.shape[:2]
    m = np.zeros((h, w), np.float32)
    cv2.ellipse(m, (w // 2, h // 2), (w // 2 - 2, h // 2 - 2), 0, 0, 360, 1.0, -1)
    m = cv2.GaussianBlur(m, (0, 0), 3)[..., None]
    for (l, t, r, b) in layout.STAR_BOXES[:2]:
        cx, cy = (l + r) // 2, (t + b) // 2
        y0, x0 = cy - h // 2, cx - w // 2
        dst = out[y0:y0 + h, x0:x0 + w].astype(np.float32)
        out[y0:y0 + h, x0:x0 + w] = (dst * (1 - m) + src * m).round().clip(0, 255).astype(np.uint8)
    return out


def clear_hud_from_intro():
    """The opening burst's layers must not carry the painted HUD values (the live HUD
    shows the level's own); drop those pixels and any debris piece among them."""
    import json
    spec_path = os.path.join(B.OUT, "layout.json")
    with open(spec_path) as f:
        spec = json.load(f)
    l, t = spec["intro"]["box"][0], spec["intro"]["box"][1]
    zone = B.hud_zone()
    for name in ("fx_glow.png", "fx_debris.png"):
        path = os.path.join(B.OUT, name)
        im = np.array(Image.open(path).convert("RGBA"))
        h, w = im.shape[:2]
        im[..., 3][zone[t:t + h, l:l + w]] = 0
        Image.fromarray(im).save(path, optimize=True)
    pieces = spec["intro"]["pieces"]
    keep = [p for p in pieces if not zone[int(p["center"][1]), int(p["center"][0])]]
    spec["intro"]["pieces"] = keep
    with open(spec_path, "w") as f:
        json.dump(spec, f, indent=1)
    print(len(pieces) - len(keep), "intro debris pieces were HUD lettering")


def main():
    ref = np.array(Image.open(B.REF).convert("RGB"))
    hole = hole_mask(ref)
    plate_path = os.path.join(B.OUT, "background.png")
    ext_path = os.path.join(B.OUT, "background_ext.png")
    plate = np.array(Image.open(plate_path).convert("RGB"))
    new = empty_stars(paint_lagoon(ref, plate, hole))
    Image.fromarray(new).save(plate_path, optimize=True)
    # The painted margins are untouched: put the new stage back inside them.
    ext = np.array(Image.open(ext_path).convert("RGB"))
    ext[B.EXT_Y:B.EXT_Y + B.H, B.EXT_X:B.EXT_X + B.W] = new
    Image.fromarray(ext).save(ext_path, optimize=True)
    print("lagoon repainted; plaque stars emptied")
    clear_hud_from_intro()


if __name__ == "__main__":
    main()
