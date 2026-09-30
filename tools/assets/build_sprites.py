"""Stage 3: everything drawn on top of the plate, cut from the approved reference.

Outputs into app/src/main/assets/level5/ plus level5.json describing where each
piece sits. Run build_plate.py first (it writes <work>/plate.png).
"""
import json
import os
import sys

import cv2
import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(__file__))
import build_blocks  # noqa: E402
import build_plate  # noqa: E402
import layout  # noqa: E402

REPO = build_blocks.REPO
OUT = os.path.join(REPO, "app", "src", "main", "assets", "level5")

# Gameplay colour cycle. Blocks next to a cleared group step one place right.
CYCLE = ["cyan", "violet", "magenta", "red", "yellow"]
# The reference paints a few in-between hues; the game reads them as these colours.
LOGICAL = {"cyan": "cyan", "violet": "violet", "orchid": "violet", "magenta": "magenta",
           "pink": "red", "red": "red", "yellow": "yellow"}
# Blocks whose painted hue sits between two game colours (b07 is crimson-pink).
AMBIGUOUS = {"b03", "b04", "b07", "b08"}
# Pools of clean, canonical blocks used to learn each colour's shading ramp.
RAMP_POOL = {
    "cyan": ["b16", "b19", "b30", "b31"],
    "violet": ["b01", "b02", "b11", "b12", "b21", "b22", "b29"],
    "magenta": ["b06", "b28"],
    "red": ["b09", "b10"],
    "yellow": ["b05", "b13", "b14", "b23", "b24", "b25", "b26", "b27"],
}
# Display colour for UI accents (goal swatches, glows, tints), sampled from the art.
SWATCH = {"cyan": "#10A8FE", "violet": "#A044FD", "magenta": "#F81EF6", "red": "#F42238",
          "yellow": "#FDD21A"}

BALL_CENTER, BALL_R = (515, 1395), 63
BALL_HUE = {"cyan": None, "violet": 135, "magenta": 150, "red": 178, "yellow": 22}
BURST_CENTER = (515, 768)
# Boxes around painted gems thrown by the burst; reused as hit shards.
GEM_BOXES = [(341, 625, 389, 672), (416, 696, 460, 734), (398, 728, 459, 765),
             (658, 585, 702, 632), (651, 625, 685, 669), (811, 585, 849, 625)]
FLASH_CENTER, FLASH_R = (518, 768), 95
# Aim chevron traced from the reference: 36x42 at the ball, apex, shoulders, V notch.
CHEVRON_POLY = [(0.5, 0.0), (1.0, 0.32), (1.0, 1.0), (0.5, 0.71), (0.0, 1.0), (0.0, 0.32)]
CHEVRON_W, CHEVRON_H = 36, 42
CHEVRON_GLOW = {"cyan": (96, 120, 255), "violet": (176, 92, 255), "magenta": (255, 84, 238),
                "red": (255, 72, 84), "yellow": (255, 204, 48)}
STAR_BOXES = [(343, 106, 444, 202), (446, 106, 547, 202), (554, 106, 652, 202)]


def lum(a):
    a = a.astype(np.float32)
    return 0.299 * a[..., 0] + 0.587 * a[..., 1] + 0.114 * a[..., 2]


def block_pixels(img, bid, rects, inset=3):
    l, t, r, b = rects[bid]
    return img[t + inset:b - inset, l + inset:r - inset].reshape(-1, 3)


class Ramp:
    """Luminance-ranked colour ramp learned from real blocks of one colour."""

    def __init__(self, px):
        L = lum(px[None])[0]
        self.sorted = np.sort(L)
        lut = np.zeros((256, 3), np.float32)
        cnt = np.zeros(256, np.float32)
        idx = np.clip(L.round().astype(int), 0, 255)
        np.add.at(lut, idx, px.astype(np.float32))
        np.add.at(cnt, idx, 1)
        have = cnt > 0
        xs = np.arange(256)
        for c in range(3):
            v = np.zeros(256, np.float32)
            v[have] = lut[have, c] / cnt[have]
            lut[:, c] = np.interp(xs, xs[have], v[have])
        self.lut = np.stack([np.convolve(np.pad(lut[:, c], 12, mode="edge"), _gauss(12, 5), "valid")
                             for c in range(3)], axis=1)
        self.q = np.quantile(self.sorted, np.linspace(0, 1, 257))
        self.q = np.maximum.accumulate(self.q + np.linspace(0, 1e-3, 257))

    def rank(self, L):
        return np.interp(L, self.q, np.linspace(0, 1, 257))

    def at_rank(self, p):
        return np.interp(p, np.linspace(0, 1, 257), self.q)

    def colour(self, L):
        xs = np.arange(256)
        return np.stack([np.interp(L, xs, self.lut[:, c]) for c in range(3)], axis=-1)


def _gauss(r, s):
    k = np.exp(-0.5 * (np.arange(-r, r + 1) / s) ** 2)
    return k / k.sum()


def recolour(rgb, src, dst, smooth=True):
    L = lum(rgb)
    if smooth:
        # Flatten broad lighting blotches (keeps edges and the engraved symbol).
        L = cv2.bilateralFilter(L.astype(np.float32), 5, 12, 3)
        base = cv2.GaussianBlur(L, (0, 0), 6)
        L = base.mean() + 0.65 * (base - base.mean()) + (L - base)
    out = np.clip(dst.colour(dst.at_rank(src.rank(L))), 0, 255).astype(np.uint8)
    return out


def tint_additive(add, colour, keep_core=0.55):
    """Re-tint an additive glow: bright core stays white, halo takes `colour`."""
    L = add.astype(np.float32).max(axis=2, keepdims=True) / 255.0
    col = np.array(colour, np.float32)[None, None] / 255.0
    w = np.clip((L - keep_core) / (1 - keep_core), 0, 1) ** 1.5
    out = L * (w + (1 - w) * col)
    return np.clip(out * 255, 0, 255).astype(np.uint8)


def hex_rgb(h):
    return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))


def save_rgba(path, rgb, a):
    rgb = np.where((a > 0)[..., None], rgb, 0)
    Image.fromarray(np.dstack([rgb.astype(np.uint8), a.astype(np.uint8)]), "RGBA").save(path, optimize=True)


def render_chevron(glow_rgb, scale=2, pad=14):
    """Chevron sprite at `scale`x the reference's largest size, with a soft glow."""
    ss = 4 * scale
    w, h = CHEVRON_W * ss, CHEVRON_H * ss
    P = pad * ss
    poly = np.array([(P + x * w, P + y * h) for x, y in CHEVRON_POLY], np.int32)
    core = np.zeros((h + 2 * P, w + 2 * P), np.uint8)
    cv2.fillPoly(core, [poly], 255, lineType=cv2.LINE_AA)
    glow = cv2.GaussianBlur(cv2.dilate(core, np.ones((3 * ss + 1, 3 * ss + 1), np.uint8)).astype(np.float32),
                            (0, 0), 4.5 * ss)
    size = (core.shape[1] // 4, core.shape[0] // 4)
    core = cv2.resize(core.astype(np.float32), size, interpolation=cv2.INTER_AREA) / 255.0
    glow = np.clip(cv2.resize(glow, size, interpolation=cv2.INTER_AREA) / 255.0 * 1.9, 0, 1)
    col = np.array(glow_rgb, np.float32) / 255.0
    a = np.clip(core + glow * 0.95 * (1 - core), 0, 1)
    rgb = (core[..., None] * 1.0 + (1 - core[..., None]) * col * glow[..., None] * 0.95) / np.maximum(a, 1e-4)[..., None]
    return np.dstack([np.clip(rgb * 255, 0, 255), a * 255]).astype(np.uint8)


def subtract_rect(r, c):
    """r minus c as a list of disjoint rects (l, t, r, b)."""
    l, t, rr, b = r
    cl, ct, cr, cb = c
    if cl >= rr or cr <= l or ct >= b or cb <= t:
        return [r]
    out = []
    if ct > t:
        out.append((l, t, rr, ct))
    if cb < b:
        out.append((l, cb, rr, b))
    mt, mb = max(t, ct), min(b, cb)
    if cl > l:
        out.append((l, mt, cl, mb))
    if cr < rr:
        out.append((cr, mt, rr, mb))
    return out


def block_parts():
    """Each block's visible rects: its rect minus every block drawn after it."""
    parts = {}
    for i, (bid, l, t, r, b, *_) in enumerate(layout.BLOCKS):
        pieces = [(l, t, r, b)]
        for later in layout.BLOCKS[i + 1:]:
            pieces = [p for q in pieces for p in subtract_rect(q, later[1:5])]
        parts[bid] = [p for p in pieces if p[2] - p[0] > 0 and p[3] - p[1] > 0]
    return parts


def star_mask(ref, box):
    l, t, r, b = box
    hsv = cv2.cvtColor(ref[t:b, l:r], cv2.COLOR_RGB2HSV)
    dark = hsv[..., 2] < 80
    gold = (hsv[..., 0] >= 18) & (hsv[..., 0] <= 36) & (hsv[..., 1] > 90) & (hsv[..., 2] > 170)
    m = (dark | gold).astype(np.uint8) * 255
    m = cv2.morphologyEx(m, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
    n, lab, st, _ = cv2.connectedComponentsWithStats(m, 8)
    keep = 1 + int(np.argmax(st[1:, 4]))
    m = (lab == keep).astype(np.uint8) * 255
    ff = m.copy()
    cv2.floodFill(ff, np.zeros((m.shape[0] + 2, m.shape[1] + 2), np.uint8), (0, 0), 255)
    m = m | cv2.bitwise_not(ff)
    m = cv2.dilate(m, np.ones((3, 3), np.uint8))
    return cv2.GaussianBlur(m, (0, 0), 0.8)


def build(work):
    os.makedirs(OUT, exist_ok=True)
    ref = np.array(Image.open(build_blocks.REF).convert("RGB"))
    plate = np.array(Image.open(os.path.join(work, "plate.png")).convert("RGB"))
    h, w = ref.shape[:2]
    rects = {b[0]: b[1:5] for b in layout.BLOCKS}
    colours = {b[0]: b[5] for b in layout.BLOCKS}

    # ---- blocks -----------------------------------------------------------
    clean, _ = build_blocks.clean_blocks(ref)
    parts = block_parts()
    alpha = np.zeros((h, w), np.uint8)
    owned = {}
    for bid, l, t, r, b, *_ in layout.BLOCKS:
        own = np.zeros((h, w), np.uint8)
        for pl, pt, pr, pb in parts[bid]:
            own[pt:pb, pl:pr] = 255
        owned[bid] = own > 0
        rr_mask = build_blocks.rounded_rect_mask((h, w), (l, t, r, b), build_blocks.CORNER_R)
        alpha = np.where(owned[bid], rr_mask, alpha)
    ax0, ay0, ax1, ay1 = build_blocks.ATLAS_BOX
    save_rgba(os.path.join(OUT, "blocks_ref.png"), clean[ay0:ay1, ax0:ax1], alpha[ay0:ay1, ax0:ax1])

    ramps = {c: Ramp(np.concatenate([block_pixels(clean, b, rects) for b in pool]))
             for c, pool in RAMP_POOL.items()}
    src_ramps = {}
    for bid, col in colours.items():
        pool = RAMP_POOL.get(col) if bid not in AMBIGUOUS else None
        src_ramps[bid] = ramps[col] if pool else Ramp(block_pixels(clean, bid, rects))
    for c in CYCLE:
        atlas = clean.copy()
        for bid, l, t, r, b, *_ in layout.BLOCKS:
            if LOGICAL[colours[bid]] == c and bid not in AMBIGUOUS:
                continue
            rec = recolour(clean[t:b, l:r], src_ramps[bid], ramps[c])
            sel = owned[bid][t:b, l:r]
            atlas[t:b, l:r][sel] = rec[sel]
        save_rgba(os.path.join(OUT, f"blocks_{c}.png"), atlas[ay0:ay1, ax0:ax1], alpha[ay0:ay1, ax0:ax1])

    a = alpha.astype(np.float32)[..., None] / 255.0
    comp = (clean * a + plate * (1 - a)).round().astype(np.uint8)

    # ---- frame-0 effects ---------------------------------------------------
    zone = cv2.dilate(np.array(Image.open(os.path.join(work, "mask_formation.png"))), np.ones((15, 15), np.uint8))
    for bx in build_plate.HUD_TEXT_BOXES:
        cv2.rectangle(zone, bx[:2], bx[2:], 0, -1)
    r16, c16 = ref.astype(np.int16), comp.astype(np.int16)
    diff = np.abs(r16 - c16).sum(axis=2)
    hsv = cv2.cvtColor(ref, cv2.COLOR_RGB2HSV).astype(np.int16)
    eff = ((lum(ref) - lum(comp) > 18) | ((hsv[..., 1] > 150) & (hsv[..., 2] > 120) & (diff > 60))) & (zone > 0)
    eff = cv2.morphologyEx(eff.astype(np.uint8) * 255, cv2.MORPH_OPEN, np.ones((2, 2), np.uint8))
    eff = cv2.dilate(eff, np.ones((5, 5), np.uint8))
    resid = ((diff > 24) & (zone > 0) & (eff == 0)).astype(np.uint8) * 255
    resid = cv2.dilate(resid, np.ones((3, 3), np.uint8))

    def soft(m):
        s = cv2.GaussianBlur(m.astype(np.float32), (0, 0), 1.0)
        return np.clip(np.maximum(s, cv2.erode(m, np.ones((3, 3), np.uint8))), 0, 255)

    burst_zone = np.zeros((h, w), np.uint8)
    cv2.ellipse(burst_zone, BURST_CENTER, (118, 104), 0, 0, 360, 255, -1)
    fx_a = soft(eff)
    save_rgba(os.path.join(OUT, "fx_intro.png"), ref[ay0:ay1, ax0:ax1], fx_a[ay0:ay1, ax0:ax1])
    save_rgba(os.path.join(OUT, "fx_intro_bg.png"), ref[ay0:ay1, ax0:ax1], soft(resid)[ay0:ay1, ax0:ax1])

    pieces = []
    burst_px = (eff > 0) & (burst_zone > 0)
    ys, xs = np.nonzero(burst_px)
    pieces.append({"kind": "burst", "rect": [int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1],
                   "center": list(BURST_CENTER)})
    rest = ((eff > 0) & ~burst_px).astype(np.uint8)
    n, lab, st, cents = cv2.connectedComponentsWithStats(rest, 8)
    shard_cands = []
    for i in range(1, n):
        x, y, bw, bh, area = (int(v) for v in st[i])
        if area < 8:
            continue
        pieces.append({"kind": "shard", "rect": [x, y, x + bw, y + bh],
                       "center": [round(float(cents[i][0]), 1), round(float(cents[i][1]), 1)]})
        if 250 <= area <= 3000 and max(bw, bh) / max(1, min(bw, bh)) < 2.2:
            shard_cands.append((area, i, x, y, bw, bh))
    # Burst pixels need their own alpha region so pieces never overlap in the atlas.
    fx_burst_a = np.where(burst_px[..., None].repeat(1, 2)[..., 0], fx_a, 0)
    fx_shard_a = np.where(burst_px, 0, fx_a)
    save_rgba(os.path.join(OUT, "fx_intro.png"), ref[ay0:ay1, ax0:ax1],
              np.maximum(fx_shard_a, 0)[ay0:ay1, ax0:ax1])
    save_rgba(os.path.join(OUT, "fx_intro_burst.png"), ref[ay0:ay1, ax0:ax1], fx_burst_a[ay0:ay1, ax0:ax1])

    # ---- gameplay flash: the jagged yellow-white star from the reference ------
    fx0, fy0 = FLASH_CENTER
    fb = (fx0 - FLASH_R, fy0 - FLASH_R, fx0 + FLASH_R, fy0 + FLASH_R)
    sub = ref[fb[1]:fb[3], fb[0]:fb[2]].astype(np.float32)
    hs = cv2.cvtColor(ref[fb[1]:fb[3], fb[0]:fb[2]], cv2.COLOR_RGB2HSV)
    warm = (hs[..., 0] <= 38) | (hs[..., 1] < 60)
    gy, gx = np.mgrid[0:2 * FLASH_R, 0:2 * FLASH_R]
    rr = np.sqrt((gx - FLASH_R) ** 2 + (gy - FLASH_R) ** 2)
    fa = np.clip((lum(sub) - 160) / 70, 0, 1) * warm * np.clip((FLASH_R - 3 - rr) / 30, 0, 1)
    flash = (sub * fa[..., None]).astype(np.uint8)
    for c in CYCLE:
        img = flash if c == "yellow" else tint_additive(flash, hex_rgb(SWATCH[c]), keep_core=0.72)
        Image.fromarray(img).save(os.path.join(OUT, f"burst_{c}.png"), optimize=True)

    # ---- shard sprites: the painted gems, recoloured to each game colour ----
    tight = ((diff > 70) & (zone > 0)).astype(np.uint8)
    shard_meta = []
    for k, (l, t, r, b) in enumerate(GEM_BOXES):
        n2, lab2, st2, _ = cv2.connectedComponentsWithStats(tight[t:b, l:r], 8)
        if n2 < 2:
            continue
        i = 1 + int(np.argmax(st2[1:, 4]))
        m = (lab2 == i).astype(np.uint8) * 255
        ff = m.copy()
        cv2.floodFill(ff, np.zeros((m.shape[0] + 2, m.shape[1] + 2), np.uint8), (0, 0), 255)
        m = cv2.GaussianBlur(m | cv2.bitwise_not(ff), (0, 0), 0.7)
        rgb = ref[t:b, l:r]
        src = Ramp(rgb.reshape(-1, 3)[m.reshape(-1) > 128])
        for c in CYCLE:
            save_rgba(os.path.join(OUT, f"shard{len(shard_meta)}_{c}.png"), recolour(rgb, src, ramps[c], False), m)
        shard_meta.append([r - l, b - t])

    # ---- ball body + halo --------------------------------------------------
    yy, xx = np.mgrid[0:h, 0:w]
    cx, cy = BALL_CENTER
    R = 112
    box = (cx - R, cy - R, cx + R, cy + R)
    crop = ref[box[1]:box[3], box[0]:box[2]]
    d = np.sqrt((xx[box[1]:box[3], box[0]:box[2]] - cx) ** 2 + (yy[box[1]:box[3], box[0]:box[2]] - cy) ** 2)
    body_a = np.clip((BALL_R + 0.5 - d) * 255, 0, 255)
    halo = np.clip(ref[box[1]:box[3], box[0]:box[2]].astype(np.int16)
                   - plate[box[1]:box[3], box[0]:box[2]].astype(np.int16), 0, 255).astype(np.float32)
    strip = (np.abs(xx[box[1]:box[3], box[0]:box[2]] - cx) < 24) & (yy[box[1]:box[3], box[0]:box[2]] < cy - BALL_R + 2)
    halo[strip] *= 0.0  # the aim chevron above the ball is drawn separately
    halo *= np.clip((R - d) / 30.0, 0, 1)[..., None]
    halo[d < BALL_R - 2] = 0
    Image.fromarray(halo.astype(np.uint8)).save(os.path.join(OUT, "ball_halo.png"), optimize=True)
    hsvb = cv2.cvtColor(crop, cv2.COLOR_RGB2HSV)
    for c in CYCLE:
        if BALL_HUE[c] is None:
            rgb = crop
        else:
            hb = hsvb.copy().astype(np.int16)
            blue = (hb[..., 0] >= 80) & (hb[..., 0] <= 130)
            hb[..., 0] = np.where(blue, (BALL_HUE[c] + (hb[..., 0] - 106)) % 180, hb[..., 0])
            if c == "yellow":
                # a golden shell: amber in the shadows, bright yellow in the core
                hb[..., 0] = np.where(blue, 12 + hb[..., 2] * 14 // 255, hb[..., 0])
                hb[..., 2] = np.where(blue, np.clip(hb[..., 2] * 1.1 + 12, 0, 255), hb[..., 2])
            rgb = cv2.cvtColor(hb.astype(np.uint8), cv2.COLOR_HSV2RGB)
        save_rgba(os.path.join(OUT, f"ball_{c}.png"), rgb, body_a)

    # ---- aim chevron: where the reference paints them (straight-up shot) ------
    Lr = lum(ref)
    core = np.zeros((h, w), np.uint8)
    sat = cv2.cvtColor(ref, cv2.COLOR_RGB2HSV)[..., 1]
    core[830:1340, 490:540] = (Lr[830:1340, 490:540] > 215) & (sat[830:1340, 490:540] < 60)
    n3, lab3, st3, c3 = cv2.connectedComponentsWithStats(core, 8)
    track = []
    for i in range(1, n3):
        x, y, bw, bh, area = (int(v) for v in st3[i])
        if area < 40 or bw < 10:
            continue
        track.append([round(BALL_CENTER[1] - (y + bh / 2.0), 1), float(bw)])
    track.sort()
    # Widths get polluted where a chevron crosses a lit step edge or the ball; the
    # painted chevrons shrink linearly with distance, so keep a robust line fit.
    ss = np.array([t[0] for t in track])
    ww = np.array([t[1] for t in track])
    fit = np.polyfit(ss, ww, 1)
    for _ in range(3):
        resid = ww - np.polyval(fit, ss)
        keep = np.abs(resid) < max(1.5, 2.0 * np.median(np.abs(resid)))
        fit = np.polyfit(ss[keep], ww[keep], 1)
    track = [[t[0], round(float(np.polyval(fit, t[0])), 1)] for t in track]
    print("chevron track", track)

    # ---- aim chevron: the traced reference shape, white core + coloured glow --
    for c in CYCLE:
        Image.fromarray(render_chevron(CHEVRON_GLOW[c]), "RGBA").save(
            os.path.join(OUT, f"chevron_{c}.png"), optimize=True)

    # ---- stars ---------------------------------------------------------------
    for name, bx in (("star_gold", STAR_BOXES[0]), ("star_empty", STAR_BOXES[2])):
        l, t, r, b = bx
        save_rgba(os.path.join(OUT, f"{name}.png"), ref[t:b, l:r], star_mask(ref, bx))

    Image.fromarray(plate).save(os.path.join(OUT, "background.png"), optimize=True)

    spec = {
        "stage": [layout.STAGE_W, layout.STAGE_H],
        "atlasOrigin": [ax0, ay0],
        "cycle": CYCLE,
        "swatch": SWATCH,
        "blocks": [{"id": bid, "rect": [l, t, r, b], "parts": [list(p) for p in parts[bid]],
                    "art": col, "color": LOGICAL[col], "normalize": bid in AMBIGUOUS}
                   for bid, l, t, r, b, col, _ in layout.BLOCKS],
        "introPieces": pieces,
        "burstRect": pieces[0]["rect"],
        "burstCenter": list(BURST_CENTER),
        "shards": shard_meta,
        "ball": {"center": list(BALL_CENTER), "radius": BALL_R, "spriteBox": list(box)},
        "chevron": {"size": [CHEVRON_W, CHEVRON_H], "pad": 14, "scale": 2, "track": track},
        "flash": {"center": list(FLASH_CENTER), "radius": FLASH_R},
        "stars": [list(b) for b in STAR_BOXES],
    }
    with open(os.path.join(OUT, "level5.json"), "w") as f:
        json.dump(spec, f, indent=1)
    return spec


if __name__ == "__main__":
    s = build(sys.argv[1] if len(sys.argv) > 1 else "/tmp/level5_work")
    print(len(s["introPieces"]), "intro pieces;", len(s["shards"]), "shard sprites")
