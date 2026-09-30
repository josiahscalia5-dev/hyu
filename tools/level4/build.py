"""Builds every Level 4 asset from the approved screen (design/level4_reference.png).

  * background.png / background_ext.png: the scene with everything that moves or
    changes painted out (Big-LaMa): treasure, launcher, the burst and its trail, the
    live timer/score/combo values and the time-bar fill. background_ext is the same
    scene painted past every edge so any phone shape is filled.
  * t_<id>.png: each target cut out with its Segment Anything mask (masks/, made by
    segment.py), launcher.png likewise.
  * fx_glow.png / fx_debris.png: the burst of the opening frame. Glow and trail fade
    where they are; loose debris flies apart (pieces listed in level4.json).
  * star_gold.png, bar_fill.png: HUD pieces that change during play.
  * level4.json: layout, hit shapes, text fits.

  export LAMA_MODEL=/path/to/big-lama.pt
  python3 tools/level4/build.py [work dir]
"""
import json
import os
import sys

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "assets"))   # lama.py (shared with Level 5)
sys.path.insert(0, HERE)                                  # this level's layout.py comes first
import lama  # noqa: E402
import layout  # noqa: E402

REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
REF = os.path.join(REPO, "design", layout.REFERENCE)
OUT = os.path.join(REPO, "app", "src", "main", "assets", "level4")
FONT = os.path.join(REPO, "app", "src", "main", "assets", "fonts", "Fredoka-Bold.ttf")
W, H = layout.STAGE_W, layout.STAGE_H
EXT_X, EXT_Y = 120, 300   # painted margins around the stage (left/right, top/bottom)


# ---- masks ------------------------------------------------------------------------

def object_masks():
    """Full-size bool mask per target (and the launcher); every pixel has one owner."""
    out = {}
    taken = np.zeros((H, W), bool)
    for oid, box in [(o, b) for o, _, b in layout.TARGETS] + [("launcher", layout.LAUNCHER)]:
        l, t, r, b = box
        m = np.zeros((H, W), bool)
        m[t:b, l:r] = np.array(Image.open(os.path.join(HERE, "masks", f"{oid}.png"))) > 0
        m &= ~taken
        taken |= m
        out[oid] = m
    return out


def poly_mask(points):
    m = np.zeros((H, W), np.uint8)
    cv2.fillPoly(m, [np.array(points, np.int32)], 255)
    return m > 0


def rect_mask(boxes):
    m = np.zeros((H, W), bool)
    for l, t, r, b in boxes:
        m[t:b, l:r] = True
    return m


def explosion_mask(ref, objects):
    """Everything the burst adds to the scene: glow, trail, loose debris."""
    hsv = cv2.cvtColor(ref, cv2.COLOR_RGB2HSV).astype(np.int16)
    h, s, v = hsv[..., 0], hsv[..., 1], hsv[..., 2]
    zone = poly_mask(layout.EXPLOSION) & ~rect_mask(layout.KEEP_BRIGHT)
    glow = (v > 215) & (s < 125)
    warm = (h >= 12) & (h <= 38) & (s > 60) & (v > 225)            # yellow haze and sparks
    gem = (s > 120) & (v > 100) & ((h <= 8) | (h >= 110))            # red, indigo, violet, magenta bits
    core = poly_mask(layout.EXPLOSION_CORE)
    shard = core & (s > 130) & (v > 130) & (((h >= 5) & (h <= 24)) | ((h >= 36) & (h <= 85)))   # amber, green
    m = (glow | warm | gem | shard) & zone | rect_mask(layout.DEBRIS)
    m = cv2.morphologyEx(m.astype(np.uint8), cv2.MORPH_CLOSE, np.ones((7, 7), np.uint8))
    m = cv2.dilate(m, np.ones((5, 5), np.uint8)) > 0
    # The trail: its white core is glow; its blue edges and cyan halo are not.
    trail = cv2.dilate(poly_mask(layout.TRAIL).astype(np.uint8), np.ones((41, 41), np.uint8)) > 0
    m |= poly_mask(layout.TRAIL) | (trail & (h >= 85) & (h <= 130) & (s > 60) & (v > 150))
    return m & zone


def text_mask(ref, box, kind):
    """Letters of live HUD text in `box`, grown over their outline."""
    l, t, r, b = box
    hsv = cv2.cvtColor(ref, cv2.COLOR_RGB2HSV).astype(np.int16)
    lum = ref.astype(np.float32) @ np.array([0.299, 0.587, 0.114], np.float32)
    if kind == "white":
        m = (lum > 170) & (hsv[..., 1] < 90)
        grow = 7
    else:  # gold lettering with a chunky dark outline
        m = (hsv[..., 0] >= 10) & (hsv[..., 0] <= 40) & (hsv[..., 1] > 80) & (hsv[..., 2] > 170)
        grow = 18
    out = np.zeros((H, W), np.uint8)
    out[t:b, l:r] = m[t:b, l:r]
    k = 2 * grow + 1
    out = cv2.dilate(out * 255, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k)))
    return out > 0


# ---- plate --------------------------------------------------------------------

def empty_bar(ref, plate):
    """The time bar with no fill: the empty track's own column profile, carried left."""
    l, t, r, b = layout.TIME_BAR_TRACK
    fl, ft, fr, fb = layout.TIME_BAR_FILL
    col = ref[t:b, fr + 40:fr + 120].astype(np.float32).mean(axis=1)    # an empty stretch
    out = plate.copy()
    out[t:b, l:fr + 6] = np.round(col)[:, None, :]
    # The track's rounded left end: keep the bar frame where the reference has it.
    frame = ref[t:b, l:fr + 6]
    dark = frame.astype(np.int16).sum(axis=2) < col.sum(axis=1)[:, None] * 0.75
    out[t:b, l:fr + 6][dark] = frame[dark]
    return out


def mystic_light(ref, plate, h8):
    """The lagoon under the burst stays lit: a softer copy of the painted glow.

    The fill is smoothed (LaMa leaves mottled water under so large a hole), then lifted
    toward warm white as much as the reference's own glow around it, blurred.
    """
    core = cv2.dilate(poly_mask(layout.EXPLOSION_CORE).astype(np.uint8) * 255, np.ones((81, 81), np.uint8))
    soft = cv2.GaussianBlur(cv2.erode(h8 & core, np.ones((9, 9), np.uint8)).astype(np.float32) / 255, (0, 0), 14)
    calm = plate.copy()
    for _ in range(3):
        calm = cv2.bilateralFilter(calm, 15, 40, 12)
    hsv = cv2.cvtColor(ref, cv2.COLOR_RGB2HSV).astype(np.float32) / 255
    glow = cv2.GaussianBlur(hsv[..., 2] ** 2 * (1 - hsv[..., 1]), (0, 0), 28)
    lift = (np.clip(glow * 1.2, 0, 0.55) * soft)[..., None]
    # Deep inside, LaMa echoes the trail and gems as faint streaks: let the mist cover them.
    deep = cv2.GaussianBlur(cv2.erode(h8 & core, np.ones((61, 61), np.uint8)).astype(np.float32) / 255,
                            (0, 0), 14)[..., None]
    calm = calm * (1 - deep) + cv2.GaussianBlur(calm, (0, 0), 12) * deep
    out = plate * (1 - soft[..., None]) + calm * soft[..., None]
    out = out * (1 - lift) + np.array([255, 247, 222], np.float32) * lift
    return out.round().clip(0, 255).astype(np.uint8)


def build_plate(ref, objects, work):
    fill = np.zeros((H, W), bool)
    for m in objects.values():
        fill |= m
    fill = cv2.dilate(fill.astype(np.uint8), cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (15, 15))) > 0
    boom = explosion_mask(ref, objects)
    hud = text_mask(ref, layout.TIMER_DIGITS, "white") | rect_mask([layout.SCORE_DIGITS]) \
        | text_mask(ref, layout.COMBO_BOX, "gold")
    hole = fill | boom | hud
    h8 = hole.astype(np.uint8) * 255
    plate = lama.inpaint(ref, h8)
    plate = mystic_light(ref, plate, h8)
    plate = empty_bar(ref, plate)
    Image.fromarray(hole.astype(np.uint8) * 255).save(os.path.join(work, "mask_hole.png"))
    Image.fromarray(boom.astype(np.uint8) * 255).save(os.path.join(work, "mask_boom.png"))
    Image.fromarray(plate).save(os.path.join(work, "plate.png"))
    return plate, hole, boom


# ---- margins ------------------------------------------------------------------

def extend(plate):
    """Paint the scene past every edge (LaMa outpainting), tone-matched to the edges."""
    padded = cv2.copyMakeBorder(plate, EXT_Y, EXT_Y, EXT_X, EXT_X, cv2.BORDER_REFLECT_101)
    m = np.full(padded.shape[:2], 255, np.uint8)
    m[EXT_Y:EXT_Y + H, EXT_X:EXT_X + W] = 0
    ph, pw = padded.shape[:2]
    small = cv2.resize(padded, (pw // 2, ph // 2), interpolation=cv2.INTER_AREA)
    ms = cv2.resize(m, (pw // 2, ph // 2), interpolation=cv2.INTER_NEAREST)
    up = cv2.resize(lama.inpaint(small, ms), (pw, ph), interpolation=cv2.INTER_CUBIC)
    seed = padded.copy()
    seed[m > 0] = up[m > 0]
    inner = cv2.erode(m, np.ones((49, 49), np.uint8))
    out = lama.inpaint(seed, cv2.subtract(m, inner)).astype(np.float32)
    out = match_edge_tone(out, plate)
    out[EXT_Y:EXT_Y + H, EXT_X:EXT_X + W] = plate
    return out.round().clip(0, 255).astype(np.uint8)


def match_edge_tone(out, plate, strip=48, sigma=40.0):
    """Outpainting darkens with distance; give each margin the tone of the scene's edge."""
    ph, pw = out.shape[:2]

    def gain(edge_mean, margin_mean):
        g = (edge_mean + 4.0) / (margin_mean + 4.0)
        return np.clip(cv2.GaussianBlur(g[:, None, :], (0, 0), sigma)[:, 0, :], 1.0, 2.2)

    ramp_x = np.clip(np.abs(np.arange(pw) - (EXT_X + W / 2)) - W / 2, 0, None)[None, :] / 60.0
    ramp_y = np.clip(np.abs(np.arange(ph) - (EXT_Y + H / 2)) - H / 2, 0, None)[:, None] / 60.0
    rows = slice(EXT_Y, EXT_Y + H)
    for sl_edge, sl_margin in ((slice(0, strip), slice(0, EXT_X)), (slice(W - strip, W), slice(EXT_X + W, pw))):
        g = gain(plate[:, sl_edge].mean(axis=1), out[rows, sl_margin].mean(axis=1))
        k = np.clip(ramp_x[:, sl_margin], 0, 1)[..., None]
        out[rows, sl_margin] *= 1 + (g[:, None, :] - 1) * k
    for sl_edge, sl_margin in ((slice(0, strip), slice(0, EXT_Y)), (slice(H - strip, H), slice(EXT_Y + H, ph))):
        edge = np.pad(plate[sl_edge].mean(axis=0), ((EXT_X, EXT_X), (0, 0)), mode="edge")
        g = gain(edge, out[sl_margin, :].mean(axis=0))
        k = np.clip(ramp_y[sl_margin], 0, 1)[..., None]
        out[sl_margin, :] *= 1 + (g[None, :, :] - 1) * k
    return out


# ---- sprites ------------------------------------------------------------------

def soft(m):
    """Anti-aliased alpha from a bool mask (0..255 float)."""
    m8 = m.astype(np.uint8) * 255
    s = cv2.GaussianBlur(m8.astype(np.float32), (0, 0), 0.8)
    return np.clip(np.maximum(s, cv2.erode(m8, np.ones((3, 3), np.uint8))), 0, 255)


def save_rgba(path, rgb, a):
    rgb = np.where((a > 0)[..., None], rgb, 0)
    Image.fromarray(np.dstack([rgb.astype(np.uint8), a.astype(np.uint8)]), "RGBA").save(path, optimize=True)


def hull(m, max_points=18):
    """Convex outline of a mask, as the target's hit shape (stage coordinates)."""
    pts = cv2.findNonZero(m.astype(np.uint8))
    hl = cv2.convexHull(pts)
    eps = 0.5
    while True:
        poly = cv2.approxPolyDP(hl, eps, True)
        if len(poly) <= max_points:
            break
        eps += 0.5
    return [[int(p[0][0]), int(p[0][1])] for p in poly]


def sprites(ref, objects):
    out = []
    for oid, kind, (l, t, r, b) in layout.TARGETS:
        m = objects[oid]
        a = soft(m)
        save_rgba(os.path.join(OUT, f"t_{oid}.png"), ref[t:b, l:r], a[t:b, l:r])
        ys, xs = np.nonzero(m)
        out.append({"id": oid, "kind": kind, "box": [l, t, r, b],
                    "center": [round(float(xs.mean()), 1), round(float(ys.mean()), 1)],
                    "radius": round(float(np.sqrt(m.sum() / np.pi)), 1), "hull": hull(m)})
    l, t, r, b = layout.LAUNCHER
    save_rgba(os.path.join(OUT, "launcher.png"), ref[t:b, l:r], soft(objects["launcher"])[t:b, l:r])
    return out


def star_gold(ref):
    l, t, r, b = layout.STAR_BOXES[0]
    hsv = cv2.cvtColor(ref[t:b, l:r], cv2.COLOR_RGB2HSV)
    gold = (hsv[..., 0] >= 15) & (hsv[..., 0] <= 38) & (hsv[..., 1] > 90) & (hsv[..., 2] > 150)
    dark = hsv[..., 2] < 80
    m = (gold | dark).astype(np.uint8) * 255
    m = cv2.morphologyEx(m, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
    n, lab, st, _ = cv2.connectedComponentsWithStats(m, 8)
    m = (lab == 1 + int(np.argmax(st[1:, 4]))).astype(np.uint8) * 255
    ff = m.copy()
    cv2.floodFill(ff, np.zeros((m.shape[0] + 2, m.shape[1] + 2), np.uint8), (0, 0), 255)
    m = cv2.dilate(m | cv2.bitwise_not(ff), np.ones((3, 3), np.uint8))
    save_rgba(os.path.join(OUT, "star_gold.png"), ref[t:b, l:r], cv2.GaussianBlur(m, (0, 0), 0.8))


def bar_fill(ref):
    """The painted yellow fill of the time bar (drawn 3-slice at any length)."""
    l, t, r, b = layout.TIME_BAR_FILL
    hsv = cv2.cvtColor(ref[t:b, l:r], cv2.COLOR_RGB2HSV)
    m = (hsv[..., 0] >= 12) & (hsv[..., 0] <= 40) & (hsv[..., 1] > 90) & (hsv[..., 2] > 150)
    m = cv2.morphologyEx(m.astype(np.uint8), cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8)) > 0
    ys, xs = np.nonzero(m)
    t2, b2, l2, r2 = ys.min(), ys.max() + 1, xs.min(), xs.max() + 1
    save_rgba(os.path.join(OUT, "bar_fill.png"), ref[t + t2:t + b2, l + l2:l + r2], soft(m)[t2:b2, l2:r2])
    return [int(l + l2), int(t + t2), int(l + r2), int(t + b2)]


# ---- the opening frame's burst -----------------------------------------------------

def merge_overlapping(boxes):
    boxes = [list(b) for b in boxes]
    merged = True
    while merged:
        merged = False
        for i in range(len(boxes)):
            for j in range(i + 1, len(boxes)):
                a, c = boxes[i], boxes[j]
                if a[0] < c[2] and c[0] < a[2] and a[1] < c[3] and c[1] < a[3]:
                    boxes[i] = [min(a[0], c[0]), min(a[1], c[1]), max(a[2], c[2]), max(a[3], c[3])]
                    del boxes[j]
                    merged = True
                    break
            if merged:
                break
    return boxes


def intro_fx(ref, plate, hole, objects):
    """What the frame-0 burst adds over plate + sprites: fading glow, flying debris."""
    owned = np.zeros((H, W), bool)
    for m in objects.values():
        owned |= m
    fx = hole & ~owned
    fx |= (np.abs(ref.astype(np.int16) - plate.astype(np.int16)).sum(axis=2) > 24) & ~owned & \
        (cv2.dilate(hole.astype(np.uint8), np.ones((5, 5), np.uint8)) > 0)
    hsv = cv2.cvtColor(ref, cv2.COLOR_RGB2HSV).astype(np.int16)
    h, s, v = hsv[..., 0], hsv[..., 1], hsv[..., 2]
    vivid = fx & (s > 130) & (v > 110) & ~poly_mask(layout.TRAIL)
    vivid &= ~(cv2.dilate(poly_mask(layout.TRAIL).astype(np.uint8), np.ones((41, 41), np.uint8)) > 0)
    vivid = cv2.morphologyEx(vivid.astype(np.uint8), cv2.MORPH_OPEN, np.ones((2, 2), np.uint8))
    vivid = cv2.dilate(vivid, np.ones((3, 3), np.uint8)) > 0
    n, lab, st, _ = cv2.connectedComponentsWithStats(vivid.astype(np.uint8), 8)
    debris = np.zeros((H, W), bool)
    boxes = []
    for i in range(1, n):
        x, y, bw, bh, area = (int(q) for q in st[i])
        if area < 12 or area > 2500 or max(bw, bh) > 3 * min(bw, bh) + 4:
            continue
        debris |= lab == i
        boxes.append([x - 2, y - 2, x + bw + 2, y + bh + 2])
    debris &= fx
    glow = fx & ~debris
    ys, xs = np.nonzero(fx)
    fb = [int(xs.min()) - 2, int(ys.min()) - 2, int(xs.max()) + 3, int(ys.max()) + 3]
    l, t, r, b = fb
    boxes = [[max(q[0], l), max(q[1], t), min(q[2], r), min(q[3], b)] for q in boxes]
    save_rgba(os.path.join(OUT, "fx_glow.png"), ref[t:b, l:r], soft(glow)[t:b, l:r])
    save_rgba(os.path.join(OUT, "fx_debris.png"), ref[t:b, l:r], soft(debris)[t:b, l:r])
    pieces = []
    for q in merge_overlapping(boxes):
        yy, xx = np.nonzero(debris[q[1]:q[3], q[0]:q[2]])
        if len(xx) == 0:
            continue
        pieces.append({"rect": q, "center": [round(float(xx.mean()) + q[0], 1), round(float(yy.mean()) + q[1], 1)]})
    print(len(pieces), "debris pieces;", int(glow.sum()), "px of glow fade in place")
    return fb, pieces


# ---- live text ------------------------------------------------------------------

class Fitter:
    """Slides, scales, squeezes and rotates Fredoka Bold over painted letters (max IoU)."""

    def __init__(self, ref):
        hsv = cv2.cvtColor(ref, cv2.COLOR_RGB2HSV)
        lum = 0.299 * ref[..., 0] + 0.587 * ref[..., 1] + 0.114 * ref[..., 2]
        self.white = (lum > 185) & (hsv[..., 1] < 70)
        self.gold = (hsv[..., 0] >= 8) & (hsv[..., 0] <= 40) & (hsv[..., 1] > 80) & (hsv[..., 2] > 180)
        self.cream = ((lum > 170) & (hsv[..., 1] < 160) & (hsv[..., 0] >= 12) & (hsv[..., 0] <= 45)) | self.white

    @staticmethod
    def render(text, size, x, y, angle, scale_x, box, pad=0):
        l, t, r, b = box
        keep = pad
        pad = max(pad, 60)
        w, h = r - l + 2 * pad, b - t + 2 * pad
        f = ImageFont.truetype(FONT, size)
        im = Image.new("L", (w, h), 0)
        ox, oy = x - l + pad, y - t + pad
        ImageDraw.Draw(im).text((ox, oy), text, font=f, fill=255, anchor="ls")
        if scale_x != 1.0:
            im = im.transform(im.size, Image.AFFINE, (1 / scale_x, 0, ox - ox / scale_x, 0, 1, 0),
                              resample=Image.BILINEAR)
        if angle:
            im = im.rotate(-angle, resample=Image.BICUBIC, center=(ox, oy))
        m = np.array(im) > 127
        k0 = pad - keep
        return m[k0:k0 + b - t + 2 * keep, k0:k0 + r - l + 2 * keep]

    def fit(self, text, mask, box, sizes, angles=(0,), scales=(1.0,)):
        l, t, r, b = box
        target = mask[t:b, l:r]
        ys, xs = np.nonzero(target)
        best = None
        for ang in angles:
            for sx in scales:
                for sz in sizes:
                    x0, y0 = l + (r - l) // 3, (t + b) // 2
                    probe = self.render(text, sz, x0, y0, ang, sx, box, pad=400)
                    ry, rx = np.nonzero(probe)
                    if len(rx) == 0:
                        continue
                    dx = (xs.min() + xs.max()) / 2 + l - ((rx.min() + rx.max()) / 2 + l - 400)
                    dy = (ys.min() + ys.max()) / 2 + t - ((ry.min() + ry.max()) / 2 + t - 400)
                    for ox in range(-4, 5, 2):
                        for oy in range(-4, 5, 2):
                            x, y = x0 + dx + ox, y0 + dy + oy
                            m = self.render(text, sz, x, y, ang, sx, box)
                            iou = (m & target).sum() / max(1, (m | target).sum())
                            if best is None or iou > best["iou"]:
                                width = ImageFont.truetype(FONT, sz).getlength(text) * sx
                                best = {"iou": round(float(iou), 3), "size": sz, "x": round(float(x), 1),
                                        "y": round(float(y), 1), "angle": ang, "scaleX": sx,
                                        "width": round(float(width), 1)}
        return best


def fit_text(ref):
    f = Fitter(ref)
    cl, ct, cr, cb = layout.COMBO_BOX
    word = (cl, ct, cr, ct + int((cb - ct) * 0.48))
    num = (cl + 30, ct + int((cb - ct) * 0.38), cr, cb)
    return {
        "timer": f.fit("0:28", f.white, layout.TIMER_DIGITS, range(38, 54, 2)),
        "score": f.fit("1,240", f.cream | f.gold, layout.SCORE_DIGITS, range(58, 84, 2)),
        "combo": f.fit("Combo", f.gold, word, range(70, 100, 3), angles=(-16, -13, -10, -7),
                       scales=(0.8, 0.86, 0.92, 1.0)),
        "comboNumber": f.fit("x8", f.gold, num, range(112, 160, 4), angles=(-12, -9, -6, -3),
                             scales=(0.9, 1.0, 1.1)),
    }


# ---- everything ------------------------------------------------------------------

def build(work):
    os.makedirs(work, exist_ok=True)
    os.makedirs(OUT, exist_ok=True)
    ref = np.array(Image.open(REF).convert("RGB"))
    objects = object_masks()
    plate, hole, _ = build_plate(ref, objects, work)
    Image.fromarray(plate).save(os.path.join(OUT, "background.png"), optimize=True)
    Image.fromarray(extend(plate)).save(os.path.join(OUT, "background_ext.png"), optimize=True)
    targets = sprites(ref, objects)
    star_gold(ref)
    fill_box = bar_fill(ref)
    fx_box, pieces = intro_fx(ref, plate, hole, objects)
    text = fit_text(ref)
    for k, v in text.items():
        print("text", k, v)
    spec = {
        "stage": [W, H],
        "backgroundExt": {"margin": [EXT_X, EXT_Y]},
        "targets": targets,
        "launcher": {"box": list(layout.LAUNCHER), "pivot": list(layout.LAUNCHER_PIVOT),
                     "tip": list(layout.LAUNCHER_TIP)},
        "intro": {"box": fx_box, "pieces": pieces, "burstCenter": list(layout.BURST_CENTER)},
        "hud": {
            "pause": list(layout.PAUSE),
            "stars": [list(b) for b in layout.STAR_BOXES],
            "timeBarTrack": list(layout.TIME_BAR_TRACK),
            "timeBarFill": fill_box,
            "comboBox": list(layout.COMBO_BOX),
            "extent": list(layout.HUD_EXTENT),
            "text": {k: {kk: v[kk] for kk in ("size", "x", "y", "angle", "scaleX", "width")}
                     for k, v in text.items()},
        },
    }
    with open(os.path.join(OUT, "level4.json"), "w") as f:
        json.dump(spec, f, indent=1)
    print(len(targets), "targets")
    return spec


if __name__ == "__main__":
    build(sys.argv[1] if len(sys.argv) > 1 else "/tmp/level4_work")
