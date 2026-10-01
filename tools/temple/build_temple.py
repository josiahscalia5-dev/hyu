"""Builds Level 5 Temple Chase art from design/level5_temple_chase_reference.png.

Outputs into app/src/main/assets/temple/:
  backdrop.png   the painted far scene (temple, golem, lavafalls, far towers, top HUD) with the
                 live HUD numbers removed; it fades out above the scrolling ground and is
                 painted past the left, right and top edges for taller or wider phones
  ground.png     a seamless top-down texture of the stone path and the lava beside it. Its first
                 stretch is the painting itself (unprojected), the rest is quilted from it, so the
                 path that scrolls toward the runner is the painted path
  prop_*.png     side scenery cut from the painting (block stacks with the lavafall, chevron
                 blocks, rope fence, platforms, palms) that scrolls past as separate 3D props
  <sprite>.png   runner, coin, gem, boulder, buttons, hearts, stars
  temple.json    perspective, prop placements, sprite boxes, HUD placement and text fits

Run:  LAMA_MODEL=/path/big-lama.pt python3 tools/temple/build_temple.py [--reuse]
"""
import json
import os
import sys

import cv2
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "assets"))
sys.path.insert(0, os.path.join(HERE, "..", "level6"))
import cutout as C  # noqa: E402
import lama  # noqa: E402
import layout_temple as L  # noqa: E402

REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
OUT = os.path.join(REPO, "app", "src", "main", "assets", "temple")
EXT_X, EXT_Y = 180, 420
# Ground: the opening frame's painted ground (intro) is unprojected at GROUND_K texels per lane
# out to GROUND_HALF lanes each side. The repeating stone path is PATH_K texels per lane,
# PATH_HALF lanes each side, PATH_PERIOD long; its first rows (to PATH_FIXED_Z) are the painting.
# Rows start at z = GROUND_Z0 (just behind the runner).
GROUND_K, GROUND_HALF, GROUND_Z0 = 80, 7.5, -1.5
PATH_K, PATH_HALF, PATH_PERIOD = 112, 2.4, 24.0
PATH_FIXED_ROWS = 400
# Lava tile: lanes across and units along the path per repeat.
LAVA_X, LAVA_Z = 2.2, 3.4
# The painting is unprojected onto the ground from this row down.
GROUND_PAINTED_TOP = 868
PROP_SCALE = 2  # props are stored at twice the painted size: they grow as they approach

REUSE = "--reuse" in sys.argv


# ---- perspective ----------------------------------------------------------------------

def s_at(z):
    return L.DEPTH_D / (L.DEPTH_D + z)


def y_at(z):
    return L.HORIZON + (L.PLAYER_Y - L.HORIZON) * s_at(z)


def z_at_y(y):
    s = (y - L.HORIZON) / (L.PLAYER_Y - L.HORIZON)
    return L.DEPTH_D * (1.0 / s - 1.0)


def lanes_at(x, z):
    return (x - L.VANISH_X) / (L.LANE_W * s_at(z)) + L.CAM_BIAS


# ---- helpers --------------------------------------------------------------------------

def save_rgba(path, rgb, a):
    a8 = (np.clip(a, 0, 1) * 255).round().astype(np.uint8) if a.dtype != np.uint8 else a
    rgb = np.where((a8 > 0)[..., None], rgb, 0).astype(np.uint8)
    Image.fromarray(np.dstack([rgb, a8]), "RGBA").save(path, optimize=True)


def poly_mask(shape, pts, offset=(0, 0)):
    m = np.zeros(shape, np.uint8)
    p = np.array([(x - offset[0], y - offset[1]) for x, y in pts], np.int32)
    cv2.fillPoly(m, [p], 255)
    return m


def cached(name, fn):
    path = os.path.join(WORK, name)
    if REUSE and os.path.exists(path):
        return np.array(Image.open(path).convert("RGB"))
    img = fn()
    Image.fromarray(img).save(path)
    return img


# ---- HUD numbers ----------------------------------------------------------------------

def hud_number_mask(ref):
    """Painted live numbers (score, coins, timer) and the hearts/stars: the app draws these."""
    hsv = cv2.cvtColor(ref, cv2.COLOR_RGB2HSV).astype(int)
    m = np.zeros(ref.shape[:2], np.uint8)
    bright = hsv[..., 2] > 125
    for l, t, r, b in (L.SCORE_DIGITS, L.COIN_DIGITS, L.TIMER_DIGITS):
        m[t:b, l:r] |= (bright[t:b, l:r] * 255).astype(np.uint8)
    m = cv2.dilate(m, np.ones((9, 9), np.uint8))
    for l, t, r, b in L.HEARTS + L.STARS:
        cv2.rectangle(m, (l, t), (r, b), 255, -1)
    bl, bt, br, bb = L.BAR
    keep = np.zeros_like(m)
    keep[bt + 4:bb - 3, bl + 6:br - 6] = 255
    for box in (L.SCORE_DIGITS, L.COIN_DIGITS, L.TIMER_DIGITS):
        l, t, r, b = box
        keep[t - 6:b + 6, l - 8:r + 8] = 255
    return m & keep


def remove_hud_numbers(ref):
    m = hud_number_mask(ref)
    return cv2.inpaint(ref, m, 7, cv2.INPAINT_TELEA)


# ---- clean plate ------------------------------------------------------------------------

def object_mask(shape):
    m = poly_mask(shape, L.RUNNER) | poly_mask(shape, L.SHADOW)
    m = cv2.dilate(m, np.ones((9, 9), np.uint8))
    for x, y, r in L.COINS:
        cv2.circle(m, (x, y), int(r * 1.25) + 4, 255, -1)
    for l, t, r, b in L.GEMS:
        cx, cy = (l + r) // 2, (t + b) // 2
        cv2.ellipse(m, (cx, cy), (int((r - l) * 0.62) + 4, int((b - t) * 0.6) + 4), 0, 0, 360, 255, -1)
    for x, y, r in L.BOULDERS:
        cv2.circle(m, (x, y), int(r * 1.12) + 6, 255, -1)
    for cx, cy in (L.ARROW_LEFT_C, L.ARROW_RIGHT_C):
        cv2.circle(m, (cx, cy), L.ARROW_R + 8, 255, -1)
    for c, r, bc, br in L.POWERUPS:
        cv2.circle(m, c, r + 7, 255, -1)
        cv2.circle(m, bc, br + 6, 255, -1)
    return m


def big_fill_mask(shape):
    """The large areas the inpainting invented (runner and shadow, buttons)."""
    m = poly_mask(shape, L.RUNNER) | poly_mask(shape, L.SHADOW)
    m = cv2.dilate(m, np.ones((9, 9), np.uint8))
    for cx, cy in (L.ARROW_LEFT_C, L.ARROW_RIGHT_C):
        cv2.circle(m, (cx, cy), L.ARROW_R + 8, 255, -1)
    for c, r, bc, br in L.POWERUPS:
        cv2.circle(m, c, r + 7, 255, -1)
        cv2.circle(m, bc, br + 6, 255, -1)
    return m


def build_plate(ref):
    return lama.inpaint(ref, object_mask(ref.shape[:2]))


# ---- side props ---------------------------------------------------------------------------

def prop_alpha(name, crop, box):
    poly, kind = L.PROPS[name]
    l, t, r, b = box
    m = poly_mask(crop.shape[:2], poly, (l, t)).astype(np.float32) / 255
    if kind == "foliage":
        hsv = cv2.cvtColor(crop, cv2.COLOR_RGB2HSV).astype(int)
        r_, g_ = crop[..., 0].astype(int), crop[..., 1].astype(int)
        green = (hsv[..., 0] >= 34) & (hsv[..., 0] <= 90) & (hsv[..., 1] > 70) & (hsv[..., 2] > 35) & (g_ > r_ + 8)
        g = cv2.morphologyEx(green.astype(np.uint8), cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8))
        g = cv2.dilate(g, np.ones((3, 3), np.uint8))
        m = m * g
        return np.clip(cv2.GaussianBlur(m, (0, 0), 1.0) * 1.15, 0, 1)
    a = cv2.GaussianBlur(m, (0, 0), 1.4)
    if name == "fence_l":
        # rope strands: dark rope over bright lava
        band = poly_mask(crop.shape[:2], L.ROPE_L, (l, t)).astype(np.float32) / 255
        v = cv2.cvtColor(crop, cv2.COLOR_RGB2HSV)[..., 2].astype(np.float32)
        rope = np.clip((205 - v) / 55, 0, 1) * band
        a = np.maximum(a, cv2.GaussianBlur(rope, (0, 0), 0.8))
    # the foot of each prop melts into the lava/ground it stands in
    ys = np.arange(a.shape[0])[:, None]
    base = b - t
    a *= np.clip((base - ys) / 10.0, 0, 1) ** 0.7 * 0.25 + 0.75 * np.clip((base - ys) / 4.0, 0, 1)
    return np.clip(a, 0, 1)


def build_props(plate):
    meta = {}
    h, w = plate.shape[:2]
    for name, (poly, kind) in L.PROPS.items():
        if name == "fence_l":
            poly = poly + L.ROPE_L
        xs = [p[0] for p in poly]
        ys = [p[1] for p in poly]
        l, t, r, b = max(0, min(xs) - 2), max(0, min(ys) - 2), min(w, max(xs) + 2), min(h, max(ys) + 1)
        crop = plate[t:b, l:r]
        a = prop_alpha(name, crop, (l, t, r, b))
        nz = np.nonzero(a > 0.02)
        cl, ct, cr, cb = nz[1].min(), nz[0].min(), nz[1].max() + 1, nz[0].max() + 1
        rgb, a = crop[ct:cb, cl:cr], a[ct:cb, cl:cr]
        big = cv2.resize(rgb, (rgb.shape[1] * PROP_SCALE, rgb.shape[0] * PROP_SCALE), interpolation=cv2.INTER_LANCZOS4)
        sharp = cv2.addWeighted(big, 1.35, cv2.GaussianBlur(big, (0, 0), 1.6), -0.35, 0)
        ab = cv2.resize(a, (big.shape[1], big.shape[0]), interpolation=cv2.INTER_LINEAR)
        save_rgba(os.path.join(OUT, f"prop_{name}.png"), sharp, ab)
        bl, bt, br, bb = l + cl, t + ct, l + cr, t + cb
        z = z_at_y(bb)
        s = s_at(z)
        meta[name] = {
            "box": [int(bl), int(bt), int(br), int(bb)],
            "side": -1 if (bl + br) / 2 < L.VANISH_X else 1,
            "x": round(float(lanes_at((bl + br) / 2, z)), 3),
            "z": round(float(z), 3),
            "w": round(float((br - bl) / (L.LANE_W * s)), 3),
            "h": round(float((bb - bt) / (L.LANE_W * s)), 3),
        }
    return meta


def prop_union_mask(shape):
    m = np.zeros(shape, np.uint8)
    for name, (poly, kind) in L.PROPS.items():
        m |= poly_mask(shape, poly)
    m |= poly_mask(shape, L.ROPE_L)
    return cv2.dilate(m, np.ones((11, 11), np.uint8))


def build_ground_source(plate):
    """The plate with the side props removed: the ground (path, platforms, lava) under them."""
    m = prop_union_mask(plate.shape[:2])
    m[:GROUND_PAINTED_TOP - 40] = 0
    return lama.inpaint(plate, m)


# ---- lava ---------------------------------------------------------------------------------------

def _tile_noise(N, cells, rng):
    g = rng.random((cells, cells)).astype(np.float32)
    up = cv2.resize(np.tile(g, (3, 3)), (N * 3, N * 3), interpolation=cv2.INTER_CUBIC)[N:2 * N, N:2 * N]
    return (up - up.min()) / (up.max() - up.min() + 1e-6)


def _voronoi(N, n, rng, warp):
    pts = rng.random((n, 2)) * N
    allp = np.concatenate([pts + np.array([dx, dy]) * N for dx in (-1, 0, 1) for dy in (-1, 0, 1)])
    yy, xx = np.mgrid[0:N, 0:N].astype(np.float32)
    xx, yy = xx + warp[0], yy + warp[1]
    d1 = np.full((N, N), 1e9, np.float32)
    d2 = np.full((N, N), 1e9, np.float32)
    for p in allp:
        d = np.hypot(xx - p[0], yy - p[1])
        d2 = np.minimum(d2, np.maximum(d1, d))
        d1 = np.minimum(d1, d)
    return d1, d2


def lava_ramp(ref):
    """Colours of the painted molten lava, darkest (veins) to brightest (hot cores)."""
    hsv = cv2.cvtColor(ref, cv2.COLOR_RGB2HSV).astype(int)
    m = (hsv[..., 1] > 160) & (hsv[..., 2] > 140) & (hsv[..., 0] <= 40)
    m[:740] = False
    px = ref[m].astype(np.float32)
    order = np.argsort(px @ np.array([0.299, 0.587, 0.114]))
    q = np.linspace(0, len(px) - 1, 64).astype(int)
    return np.stack([px[order[max(0, i - 200):i + 200]].mean(0) for i in q])


def build_lava_tile(ref, N=512):
    """Seamless molten lava like the painting's: bright yellow-orange cells, darker orange-red
    veins between them, hotter patches; coloured with the painted lava's own colours."""
    rng = np.random.default_rng(11)
    ramp = lava_ramp(ref)
    wx, wy = (_tile_noise(N, 5, rng) - 0.5) * 38, (_tile_noise(N, 5, rng) - 0.5) * 38
    d1, d2 = _voronoi(N, 70, rng, (wx, wy))
    cell = np.sqrt(N * N / 70)
    v = np.clip((d2 - d1) / (cell * 0.16), 0, 1)
    v = v * v * (3 - 2 * v)
    d1b, d2b = _voronoi(N, 260, rng, (wx * 0.6, wy * 0.6))
    cracks = np.clip((d2b - d1b) / (np.sqrt(N * N / 260) * 0.12), 0, 1)
    inner = np.clip(1 - d1 / (cell * 0.62), 0, 1) ** 0.8
    n1, n2 = _tile_noise(N, 6, rng), _tile_noise(N, 14, rng)
    val = 0.06 + 0.36 * v * (0.85 + 0.15 * cracks) + 0.52 * inner * v + 0.16 * (n1 - 0.5) + 0.06 * (n2 - 0.5)
    val = np.clip(val, 0, 1)
    idx = np.clip(val, 0, 1) * 63
    lo = np.floor(idx).astype(int)
    hi = np.minimum(lo + 1, 63)
    f = (idx - lo)[..., None]
    img = ramp[lo] * (1 - f) + ramp[hi] * f
    img = cv2.GaussianBlur(img, (0, 0), 0.9)
    tile = np.clip(img, 0, 255).astype(np.uint8)
    Image.fromarray(tile).save(os.path.join(WORK, "lava_tile.png"))
    return tile


# ---- ground texture ----------------------------------------------------------------------------

def unproject(src, rows, cols, K=GROUND_K, half=GROUND_HALF):
    """Top-down texture: row i is z = Z0 + (i + .5) / K, column j is X = -half + (j + .5) / K."""
    z = GROUND_Z0 + (np.arange(rows) + 0.5) / K
    X = -half + (np.arange(cols) + 0.5) / K
    Z, XX = np.meshgrid(z, X, indexing="ij")
    s = L.DEPTH_D / (L.DEPTH_D + Z)
    y = L.HORIZON + (L.PLAYER_Y - L.HORIZON) * s
    x = L.VANISH_X + (XX - L.CAM_BIAS) * L.LANE_W * s
    h, w = src.shape[:2]
    valid = (x >= 0) & (x <= w - 1) & (y >= GROUND_PAINTED_TOP) & (y <= h - 1)
    # pre-blur a little so near rows (several pixels per texel) don't alias
    soft = cv2.GaussianBlur(src, (0, 0), 0.7)
    tex = cv2.remap(soft, x.astype(np.float32), y.astype(np.float32), cv2.INTER_LINEAR,
                    borderMode=cv2.BORDER_REPLICATE)
    return tex, valid


def seam_cut(err, axis):
    """Minimum-error boundary cut through an overlap: True where the new patch wins.

    axis=1: vertical seam through a left overlap (new patch right of it);
    axis=0: horizontal seam through a top overlap (new patch below it).
    """
    e = err if axis == 1 else err.T
    h, w = e.shape
    cost = e.copy()
    for i in range(1, h):
        left = np.r_[np.inf, cost[i - 1, :-1]]
        right = np.r_[cost[i - 1, 1:], np.inf]
        cost[i] += np.minimum(np.minimum(left, cost[i - 1]), right)
    mask = np.zeros((h, w), bool)
    j = int(np.argmin(cost[-1]))
    for i in range(h - 1, -1, -1):
        mask[i, j:] = True
        if i:
            lo, hi = max(0, j - 1), min(w, j + 2)
            j = lo + int(np.argmin(cost[i - 1, lo:hi]))
    return mask if axis == 1 else mask.T


def quilt_ground(src_tex, valid, fixed_rows, rows_period, rng, src_ok, near_rows, K, P=64, O=16):
    """Fills the path's period by patch quilting from the sharp, near part of the painted path.

    The first `fixed_rows` stay the painting. Patches come from the painted path's flat slabs
    near the camera (rows < `near_rows`), keep roughly their place across the path, and join
    along minimum-error seams; the far end of the period meets the painted start, so the
    texture repeats seamlessly along the path.
    """
    step = P - O
    H, W = rows_period, src_tex.shape[1]
    T = np.zeros((H + P, W, 3), np.float32)
    known = np.zeros((H + P, W), bool)
    T[:fixed_rows] = src_tex[:fixed_rows]
    known[:fixed_rows] = valid[:fixed_rows]
    T[H:H + P] = T[:P]
    known[H:H + P] = known[:P]
    usable = valid & src_ok
    full = cv2.erode(usable.astype(np.uint8), np.ones((P, P), np.uint8), anchor=(0, 0)).astype(bool)
    full[near_rows:] = False
    # mostly plain stone: plant tufts would repeat down the path in visible columns
    hsv = cv2.cvtColor(src_tex, cv2.COLOR_RGB2HSV).astype(int)
    green = ((hsv[..., 0] >= 30) & (hsv[..., 0] <= 90) & (hsv[..., 1] > 60)).astype(np.float32)
    gfrac = cv2.boxFilter(green, -1, (P, P), normalize=True, anchor=(0, 0))
    full &= gfrac < 0.012
    full[:, W - P:] = False
    sy, sx = np.nonzero(full)
    print("path patch sources:", len(sy))
    srcf = src_tex.astype(np.float32)
    assert (H + O - P) % step == 0 and (fixed_rows - O) % step == 0
    for by in range(0, H + O - P + 1, step):
        for bx in list(range(0, W - P, step)) + [W - P]:
            have = known[by:by + P, bx:bx + P]
            if have.all():
                continue
            region = T[by:by + P, bx:bx + P]
            tol = 0.3 * K
            cand = np.nonzero(np.abs(sx - bx) <= tol)[0]
            while len(cand) < 80:
                tol *= 1.5
                cand = np.nonzero(np.abs(sx - bx) <= tol)[0]
            if len(cand) > 300:
                cand = rng.choice(cand, 300, replace=False)
            patches = np.stack([srcf[sy[c]:sy[c] + P, sx[c]:sx[c] + P] for c in cand])
            if have.any():
                err = (((patches - region[None]) ** 2).sum(-1) * have[None]).sum((1, 2)) / have.sum()
                order = np.argsort(err)[:3]
                pick = patches[order[rng.integers(len(order))]]
            else:
                pick = patches[rng.integers(len(patches))]
            e2 = ((pick - region) ** 2).sum(-1)
            left, top, bottom = have[:, :O].all(), have[:O, :].all(), have[P - O:, :].all()
            zone = np.zeros((P, P), bool)
            win = np.ones((P, P), bool)
            if left:
                zone[:, :O] = True
                win[:, :O] &= seam_cut(e2[:, :O], 1)
            if top:
                zone[:O, :] = True
                win[:O, :] &= seam_cut(e2[:O, :], 0)
            if bottom:  # wrap: the new patch stays above its seam with the period's start
                zone[P - O:, :] = True
                win[P - O:, :] &= ~seam_cut(e2[P - O:, :], 0)
            new = ~have | (have & zone & win)
            if have.any() and not zone.any():
                dist = cv2.distanceTransform((~have).astype(np.uint8), cv2.DIST_L2, 3)
                soft = np.clip(dist / 6.0, 0, 1)[..., None]
            else:
                soft = cv2.GaussianBlur(new.astype(np.float32), (0, 0), 1.0)[..., None]
                soft = np.where((~have)[..., None], 1.0, np.where((have & ~zone)[..., None], 0.0, soft))
            T[by:by + P, bx:bx + P] = region * (1 - soft) + pick * soft
            known[by:by + P, bx:bx + P] = True
        if by < P:  # keep the wrap copy of the start current
            T[H:H + P] = T[:P]
            known[H:H + P] = known[:P]
    T[:O] = T[H:H + O]
    return np.clip(T[:H], 0, 255).astype(np.uint8)


def path_edge(rows, X, K, rng):
    """Alpha of the stone path: a straight core with ragged, block-shaped edges (stones of
    different lengths sticking out into the lava), different on each side."""
    z = (np.arange(rows) + 0.5) / K
    alpha = np.zeros((rows, len(X)), np.float32)
    for side in (-1, 1):
        # block lengths 0.35-0.9 units, each sticking out a different amount
        edge = np.zeros(rows, np.float32)
        pos = 0.0
        period = rows / K
        while pos < period:
            ln = rng.uniform(0.35, 0.9)
            out = rng.uniform(1.55, 1.85)
            i0, i1 = int(pos * K), int(min(period, pos + ln) * K)
            edge[i0:i1] = out
            pos += ln
        edge = np.convolve(np.r_[edge[-6:], edge, edge[:6]], np.ones(5) / 5, mode="same")[6:-6]
        d = (edge[:, None] - side * X[None, :]) * K  # texels inside the edge
        a = np.clip(d / 2.5 + 0.5, 0, 1)
        if side < 0:
            alpha = np.where(X[None, :] < 0, a, alpha)
        else:
            alpha = np.where(X[None, :] >= 0, a, alpha)
    return alpha


def build_path(gsrc):
    """The stone path that scrolls toward the runner: RGBA, with a glowing rim of hot lava."""
    rng = np.random.default_rng(5)
    K = PATH_K
    rows = int(round(PATH_PERIOD * K))
    cols = int(round(2 * PATH_HALF * K))
    tex, valid = unproject(gsrc, rows, cols, K, PATH_HALF)
    synth = big_fill_mask(gsrc.shape[:2]) | prop_union_mask(gsrc.shape[:2])
    synth_t, _ = unproject(np.dstack([synth] * 3), rows, cols, K, PATH_HALF)
    src_ok = synth_t[..., 0] < 20
    X = -PATH_HALF + (np.arange(cols) + 0.5) / K
    near_rows = int((2.6 - GROUND_Z0) * K)
    path = quilt_ground(tex, valid, PATH_FIXED_ROWS, rows, rng, src_ok, near_rows, K).astype(np.float32)
    # alpha: the painted rows keep everything they show (the opening frame); elsewhere the
    # path has ragged stone edges with lava beyond. Blend between them along the path.
    shaped = path_edge(rows, X, K, rng)
    zr = np.arange(rows)[:, None]
    fixed_end = PATH_FIXED_ROWS
    t = np.clip((fixed_end - zr) / (0.35 * K), 0, 1) * np.clip((zr - 0.12 * K) / (0.35 * K), 0, 1)
    painted = valid.astype(np.float32)
    a = shaped * (1 - t) + np.maximum(shaped, painted) * t
    # lit stone edges and a hot rim of lava around the path
    solid = (a > 0.5).astype(np.uint8)
    d_in = cv2.distanceTransform(solid, cv2.DIST_L2, 5)
    d_out = cv2.distanceTransform(1 - solid, cv2.DIST_L2, 5)
    lit = (np.exp(-d_in / (0.05 * K)) * 0.4)[..., None]
    path = path * (1 - lit) + np.array([255, 150, 60], np.float32) * lit
    rim = np.exp(-d_out / (0.07 * K)) * 0.85
    halo = np.exp(-d_out / (0.25 * K)) * 0.35
    glow_a = np.maximum(rim, halo) * (1 - a)
    hot = np.array([255, 232, 140], np.float32)
    rgb = (path * a[..., None] + hot * glow_a[..., None]) / np.maximum(a + glow_a, 1e-4)[..., None]
    alpha = np.clip(a + glow_a, 0, 1)
    save_rgba(os.path.join(OUT, "path.png"), np.clip(rgb, 0, 255).astype(np.uint8), alpha)


def build_intro_ground(gsrc):
    """The painted ground of the opening frame (path, platforms, lava), unprojected: drawn over
    the live ground at the start so the first frame is the painting, then blended away."""
    K = GROUND_K
    rows = int((z_at_y(GROUND_PAINTED_TOP) - GROUND_Z0) * K) + 2
    cols = int(round(2 * GROUND_HALF * K))
    tex, valid = unproject(gsrc, rows, cols)
    a = cv2.GaussianBlur(valid.astype(np.float32), (0, 0), 2.0) * valid
    save_rgba(os.path.join(OUT, "ground_intro.png"), tex, a)


# ---- backdrop -----------------------------------------------------------------------------

def fade_top(xs):
    """Backdrop fade line: high over the path, lower over the side scenery."""
    d = np.abs(xs - 488.0)
    k = np.clip((d - 95.0) / 150.0, 0, 1)
    k = k * k * (3 - 2 * k)
    return L.FADE_TOP_CENTRE + (L.FADE_TOP_SIDES - L.FADE_TOP_CENTRE) * k


def build_backdrop(plate_hud):
    hb = L.FADE_TOP_SIDES + L.FADE_LEN + 4
    sky = plate_hud[:hb].copy()

    def outpaint():
        hh, ww = sky.shape[:2]
        padded = cv2.copyMakeBorder(sky, EXT_Y, 0, EXT_X, EXT_X, cv2.BORDER_REFLECT_101)
        mm = np.full(padded.shape[:2], 255, np.uint8)
        mm[EXT_Y:, EXT_X:EXT_X + ww] = 0
        ph, pw = padded.shape[:2]
        small = cv2.resize(padded, (pw // 2, ph // 2), interpolation=cv2.INTER_AREA)
        ms = cv2.resize(mm, (pw // 2, ph // 2), interpolation=cv2.INTER_NEAREST)
        up = cv2.resize(lama.inpaint(small, ms), (pw, ph), interpolation=cv2.INTER_CUBIC)
        seed = padded.copy()
        seed[mm > 0] = up[mm > 0]
        inner = cv2.erode(mm, np.ones((49, 49), np.uint8))
        ext = lama.inpaint(seed, cv2.subtract(mm, inner))
        ext[EXT_Y:, EXT_X:EXT_X + ww] = sky
        return ext

    ext = cached("backdrop_ext.png", outpaint)
    hh, ww = ext.shape[:2]
    xs = np.arange(ww) - EXT_X
    top = fade_top(np.clip(xs, 0, L.STAGE_W))
    ys = np.arange(hh)[:, None] - EXT_Y
    a = np.clip(1 - (ys - top[None, :]) / L.FADE_LEN, 0, 1).astype(np.float32)
    a = a * a * (3 - 2 * a)
    save_rgba(os.path.join(OUT, "backdrop.png"), ext, a)
    return ext


# ---- sprites ---------------------------------------------------------------------------------

def grabcut_poly(rgb, poly, box):
    l, t, r, b = box
    pm = poly_mask(rgb.shape[:2], poly, (l, t))
    mask = np.full(rgb.shape[:2], cv2.GC_BGD, np.uint8)
    mask[cv2.dilate(pm, np.ones((15, 15), np.uint8)) > 0] = cv2.GC_PR_BGD
    mask[pm > 0] = cv2.GC_PR_FGD
    mask[cv2.erode(pm, np.ones((17, 17), np.uint8)) > 0] = cv2.GC_FGD
    bgd, fgd = np.zeros((1, 65), np.float64), np.zeros((1, 65), np.float64)
    cv2.grabCut(np.ascontiguousarray(rgb[..., ::-1]), mask, None, bgd, fgd, 6, cv2.GC_INIT_WITH_MASK)
    m = (mask == cv2.GC_FGD) | (mask == cv2.GC_PR_FGD)
    m = cv2.morphologyEx(m.astype(np.uint8), cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
    return C.soften(C.fill_holes(C.largest(m)))


def disc(rgb, cx, cy, r, box):
    l, t, _, _ = box
    h, w = rgb.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w]
    d = np.hypot(xx + l - cx, yy + t - cy)
    return np.clip(r - d + 0.5, 0, 1)


def cut(name, ref, box, a, meta, scale=1):
    l, t, r, b = box
    rgb = ref[t:b, l:r]
    nz = np.nonzero(a > 0.03)
    cl, ct, cr, cb = nz[1].min(), nz[0].min(), nz[1].max() + 1, nz[0].max() + 1
    rgb, a = rgb[ct:cb, cl:cr], a[ct:cb, cl:cr]
    if scale != 1:
        rgb = cv2.resize(rgb, (rgb.shape[1] * scale, rgb.shape[0] * scale), interpolation=cv2.INTER_LANCZOS4)
        a = cv2.resize(a, (rgb.shape[1], rgb.shape[0]), interpolation=cv2.INTER_LINEAR)
    save_rgba(os.path.join(OUT, f"{name}.png"), rgb, a)
    meta[name] = {"box": [int(l + cl), int(t + ct), int(l + cr), int(t + cb)]}


def remove_badge_digit(rgb, bc, br, box):
    """Power-up count badge: erase the painted '3' (the app draws the live count)."""
    l, t, _, _ = box
    out = rgb.copy()
    hsv = cv2.cvtColor(rgb, cv2.COLOR_RGB2HSV).astype(int)
    h, w = rgb.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w]
    inside = np.hypot(xx + l - bc[0], yy + t - bc[1]) < br * 0.78
    m = (inside & (hsv[..., 2] > 120)).astype(np.uint8) * 255
    m = cv2.dilate(m, np.ones((5, 5), np.uint8)) & (inside * 255).astype(np.uint8)
    return cv2.inpaint(out, m, 5, cv2.INPAINT_TELEA)


def build_sprites(ref):
    meta = {}
    # runner
    xs = [p[0] for p in L.RUNNER]
    ys = [p[1] for p in L.RUNNER]
    box = (min(xs) - 20, min(ys) - 20, max(xs) + 20, max(ys) + 12)
    l, t, r, b = box
    g = grabcut_poly(ref[t:b, l:r], L.RUNNER_TIGHT, box) > 0.5
    tight = poly_mask(g.shape, L.RUNNER_TIGHT, (l, t))
    keep = (g & (cv2.dilate(tight, np.ones((7, 7), np.uint8)) > 0)) | (cv2.erode(tight, np.ones((9, 9), np.uint8)) > 0)
    cut("runner", ref, box, C.soften(C.fill_holes(C.largest(keep.astype(np.uint8)))), meta)
    # coin (the big near one), gem, boulder
    x, y, rr = L.COINS[-1]
    box = (x - rr - 2, y - rr - 2, x + rr + 3, y + rr + 3)
    cut("coin", ref, box, disc(ref[box[1]:box[3], box[0]:box[2]], x, y, rr - 1.5, box), meta)
    box = L.GEMS[2]
    l, t, r, b = box
    crop = ref[t:b, l:r]
    hsv = cv2.cvtColor(crop, cv2.COLOR_RGB2HSV).astype(int)
    gem = ((hsv[..., 0] >= 125) & (hsv[..., 0] <= 172) & (hsv[..., 1] > 70) & (hsv[..., 2] > 110)) | \
          ((hsv[..., 1] < 70) & (hsv[..., 2] > 225))
    gem = cv2.morphologyEx(gem.astype(np.uint8), cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8))
    gem = C.fill_holes(C.largest(cv2.morphologyEx(gem, cv2.MORPH_OPEN, np.ones((5, 5), np.uint8))))
    gem &= cv2.dilate(poly_mask(gem.shape, L.GEM_OUTLINE, (l, t)), np.ones((5, 5), np.uint8)) > 0
    cut("gem", ref, box, C.soften(gem), meta, scale=2)
    x, y, rr = L.BOULDERS[0]
    box = (x - rr - 4, y - rr - 4, x + rr + 5, y + rr + 5)
    l, t, r, b = box
    crop = ref[t:b, l:r]
    hsv = cv2.cvtColor(crop, cv2.COLOR_RGB2HSV).astype(int)
    lava = (hsv[..., 2] > 215) & (hsv[..., 1] > 120)
    rock = ~lava & (disc(crop, x, y, rr - 2, box) > 0.5)
    rock = cv2.morphologyEx(rock.astype(np.uint8), cv2.MORPH_OPEN, np.ones((5, 5), np.uint8))
    rock = C.fill_holes(C.largest(rock))
    cut("boulder", ref, box, C.soften(rock), meta, scale=2)
    # buttons
    for name, (cx, cy) in (("arrow_left", L.ARROW_LEFT_C), ("arrow_right", L.ARROW_RIGHT_C)):
        r0 = L.ARROW_R
        box = (cx - r0 - 2, cy - r0 - 2, cx + r0 + 3, cy + r0 + 3)
        cut(name, ref, box, disc(ref[box[1]:box[3], box[0]:box[2]], cx, cy, r0, box), meta)
    for name, (c, r0, bc, br) in zip(("pu_lightning", "pu_shield", "pu_magnet"), L.POWERUPS):
        box = (c[0] - r0 - 2, c[1] - r0 - 2, max(c[0] + r0, bc[0] + br) + 3, max(c[1] + r0, bc[1] + br) + 3)
        l, t, r, b = box
        crop = remove_badge_digit(ref[t:b, l:r], bc, br, box)
        a = np.maximum(disc(crop, c[0], c[1], r0, box), disc(crop, bc[0], bc[1], br, box))
        tmp = ref.copy()
        tmp[t:b, l:r] = crop
        cut(name, tmp, box, a, meta)
        meta[name]["centre"] = [c[0], c[1], r0]
        meta[name]["badge"] = [bc[0], bc[1], br]
    # hearts and stars
    l, t, r, b = L.HEARTS[0]
    crop = ref[t:b, l:r]
    hsv = cv2.cvtColor(crop, cv2.COLOR_RGB2HSV).astype(int)
    red = (((hsv[..., 0] <= 10) | (hsv[..., 0] >= 165)) & (hsv[..., 1] > 90) & (hsv[..., 2] > 90)) | \
          ((hsv[..., 1] < 80) & (hsv[..., 2] > 200))
    red = C.fill_holes(C.largest(cv2.morphologyEx(red.astype(np.uint8), cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8))))
    a = cv2.GaussianBlur(cv2.dilate(red.astype(np.uint8) * 255, np.ones((5, 5), np.uint8)), (0, 0), 0.9) / 255.0
    cut("heart", ref, L.HEARTS[0], a, meta)
    # an empty heart: the same shape in the bar's dark navy, like the painted empty star
    hl, ht, hr, hbm = meta["heart"]["box"]
    heart = np.array(Image.open(os.path.join(OUT, "heart.png")))
    lum = heart[..., :3].astype(np.float32).mean(-1, keepdims=True) / 255
    navy = np.array([22, 30, 58], np.float32)
    empty = np.clip(navy * (0.7 + 0.8 * lum), 0, 255).astype(np.uint8)
    Image.fromarray(np.dstack([empty, heart[..., 3]]), "RGBA").save(os.path.join(OUT, "heart_empty.png"))
    meta["heart_empty"] = dict(meta["heart"])
    import build_sprites as level5_sprites  # star matte (gold or dark star, holes filled)
    for name, box in (("star_gold", L.STARS[0]), ("star_empty", L.STARS[2])):
        l, t, r, b = box
        cut(name, ref, box, level5_sprites.star_mask(ref, box) / 255.0, meta)
    return meta


# ---- HUD text fits --------------------------------------------------------------------------

def fit_texts(ref):
    import fit_text
    f = fit_text.Fitter(ref)
    hsv = cv2.cvtColor(ref, cv2.COLOR_RGB2HSV).astype(int)
    coral = (((hsv[..., 0] <= 10) | (hsv[..., 0] >= 168)) & (hsv[..., 1] > 70) & (hsv[..., 2] > 170))
    out = {
        "score": f.fit("1,960", f.cream | f.gold | f.white, L.SCORE_DIGITS, range(50, 72, 2)),
        "coins": f.fit("124", f.white, L.COIN_DIGITS, range(34, 50, 2)),
        "timer": f.fit("0:35", coral, L.TIMER_DIGITS, range(48, 66, 2)),
    }
    bc, br = L.POWERUPS[0][2], L.POWERUPS[0][3]
    badge = (bc[0] - br, bc[1] - br, bc[0] + br, bc[1] + br)
    out["count"] = f.fit("3", f.white, badge, range(26, 40, 2))
    out["count"]["dx"] = round(out["count"]["x"] - bc[0], 1)
    out["count"]["dy"] = round(out["count"]["y"] - bc[1], 1)
    return out


# ---- main ---------------------------------------------------------------------------------------

WORK = "/tmp/temple_work/build"


def main():
    os.makedirs(OUT, exist_ok=True)
    os.makedirs(WORK, exist_ok=True)
    ref = np.array(Image.open(os.path.join(REPO, "design", L.REFERENCE)).convert("RGB"))
    plate = cached("plate.png", lambda: build_plate(ref))
    plate_hud = remove_hud_numbers(plate)
    props = build_props(plate)
    gsrc = cached("ground_src.png", lambda: build_ground_source(plate))
    tile = build_lava_tile(ref)
    Image.fromarray(tile).save(os.path.join(OUT, "lava.jpg"), quality=92)
    build_path(gsrc)
    build_intro_ground(gsrc)
    build_backdrop(plate_hud)
    sprites = build_sprites(ref)
    texts = fit_texts(ref)
    print("texts:", texts)
    spec = {
        "stage": [L.STAGE_W, L.STAGE_H],
        "backdrop": {"margin": [EXT_X, EXT_Y]},
        "perspective": {"horizon": L.HORIZON, "playerY": L.PLAYER_Y, "vanishX": L.VANISH_X,
                        "laneW": L.LANE_W, "depth": L.DEPTH_D, "camBias": round(L.CAM_BIAS, 4)},
        "ground": {"z0": GROUND_Z0, "introK": GROUND_K, "introHalf": GROUND_HALF,
                   "pathK": PATH_K, "pathHalf": PATH_HALF, "pathPeriod": PATH_PERIOD,
                   "lavaX": LAVA_X, "lavaZ": LAVA_Z},
        "props": props,
        "propScale": PROP_SCALE,
        "sprites": sprites,
        "hud": {"pause": list(L.PAUSE), "hearts": [list(b) for b in L.HEARTS],
                "stars": [list(b) for b in L.STARS], "bar": list(L.BAR),
                "arrowLeft": list(L.ARROW_LEFT_C) + [L.ARROW_R], "arrowRight": list(L.ARROW_RIGHT_C) + [L.ARROW_R],
                "extent": list(L.HUD_EXTENT), "text": texts},
    }
    with open(os.path.join(OUT, "temple.json"), "w") as fh:
        json.dump(spec, fh, indent=1)
    print("props:", {k: (v["x"], v["z"], v["w"], v["h"]) for k, v in props.items()})


if __name__ == "__main__":
    main()
