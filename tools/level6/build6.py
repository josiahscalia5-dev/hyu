"""Builds Level 6 (Storm Dodge) art from design/level6_storm_dodge_reference.png.

Outputs into app/src/main/assets/level6/:
  sky.png        the scene above the river (sky, lightning, ruins, waterfalls, banks),
                 with HUD numbers removed and the scene painted past both sides and the top
  water.png      a seamless, tileable patch of the painted river surface (top-down)
  <sprite>.png   barrel, mine, crate, logs, coins, shield, X hazard, jet ski, arrows, leaves
  level6.json    perspective, sprite anchors, HUD placement

Run:  LAMA_MODEL=/path/big-lama.pt python3 tools/level6/build6.py
"""
import json
import os
import sys

import cv2
import numpy as np
from PIL import Image

HERE = os.path.dirname(__file__)
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "assets"))
import cutout as C  # noqa: E402
import lama  # noqa: E402
import layout6 as L  # noqa: E402

REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
OUT = os.path.join(REPO, "app", "src", "main", "assets", "level6")
EXT_X, EXT_Y = 180, 420


def save_rgba(path, rgb, a):
    a8 = (np.clip(a, 0, 1) * 255).round().astype(np.uint8) if a.dtype != np.uint8 else a
    rgb = np.where((a8 > 0)[..., None], rgb, 0).astype(np.uint8)
    Image.fromarray(np.dstack([rgb, a8]), "RGBA").save(path, optimize=True)


def sprite_matte(name, rgb, box):
    kind = L.SPRITES[name][1]
    if kind == "disc":
        return C.disc_matte(rgb)
    if kind == "gold":
        return C.gold_matte(rgb)
    if kind == "foliage":
        return C.foliage_matte(rgb)
    if kind == "jetski":
        g = C.grabcut_matte(rgb) > 0.5
        hull = C.polygon_matte(rgb.shape[:2], L.JETSKI_HULL, box[:2])
        return C.soften(C.fill_holes(g | hull))
    exclude = C.red_glow(rgb) | (C.gold(rgb) if name == "crate_x" else False)
    return C.object_matte(rgb, exclude)


def hud_text_mask(ref):
    """Live HUD numbers (score, timer) are drawn by the app: remove the painted ones."""
    m = np.zeros(ref.shape[:2], np.uint8)
    for l, t, r, b in (L.SCORE_DIGITS, L.TIMER_DIGITS):
        cv2.rectangle(m, (l, t), (r, b), 255, -1)
    return m


def fill_panels(img, m):
    """Score/timer panels are flat dark glass: fill their digits from the panel's own colour."""
    out = img.copy()
    for l, t, r, b in (L.SCORE_DIGITS, L.TIMER_DIGITS):
        ring = img[t - 6:b + 6, l - 6:r + 6].reshape(-1, 3)
        hsv = cv2.cvtColor(ring[None], cv2.COLOR_RGB2HSV)[0]
        dark = ring[hsv[:, 2] < 80]
        col = np.median(dark, axis=0) if len(dark) else np.median(ring, axis=0)
        # vertical gradient like the glass panel: slightly lighter at the top
        h = b - t
        grad = np.linspace(1.12, 0.92, h)[:, None, None]
        out[t:b, l:r] = np.clip(col[None, None, :] * grad, 0, 255)
    soft = cv2.GaussianBlur(m.astype(np.float32) / 255, (0, 0), 1.5)[..., None]
    return (out * soft + img * (1 - soft)).astype(np.uint8)


def build_sky(ref, work):
    """The scene above the river (fading into it), extended left/right/up by outpainting."""
    h_sky = L.FADE_TOP_SIDES + L.FADE_LEN
    sky = fill_panels(ref, hud_text_mask(ref))[:h_sky].copy()
    # Clear objects floating in the far water band (they become live objects).
    m = np.zeros(sky.shape[:2], np.uint8)
    for l, t, r, b in L.EXTRA_CLEAR:
        if t < h_sky:
            cv2.rectangle(m, (l, t), (r, min(b, h_sky)), 255, -1)
    for name, ((l, t, r, b), _) in L.SPRITES.items():
        if t < h_sky and name not in ("arrow_left", "arrow_right", "leaves", "jetski"):
            cv2.rectangle(m, (l - 3, t - 3), (r + 3, min(b, h_sky)), 255, -1)
    m[: L.HORIZON - 30] = 0
    if m.any():
        sky = lama.inpaint(sky, m)
    # Outpaint margins (left, right, top) so wide or tall screens are filled.
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
    # Fade the painted far water so the live, moving river takes over right below the waterfalls.
    # The fade starts higher in the middle of the river than at the rocky banks on each side.
    hh_, ww_ = ext.shape[:2]
    xs = (np.arange(ww_) - EXT_X - L.VANISH_X) / float(L.VANISH_X)
    top = L.FADE_TOP_CENTRE + (L.FADE_TOP_SIDES - L.FADE_TOP_CENTRE) * np.clip(np.abs(xs), 0, 1) ** 2
    ys = np.arange(hh_)[:, None] - EXT_Y
    a = np.clip(1 - (ys - top[None, :]) / L.FADE_LEN, 0, 1).astype(np.float32) ** 1.3
    save_rgba(os.path.join(OUT, "sky.png"), ext, a)
    return ext


def build_water(ref, work):
    """A seamless top-down tile of the river surface, made by texture quilting.

    Patches are taken only from open water (no objects, jet ski or HUD) in the
    near river, flattened slightly to undo the perspective squash, and quilted
    with minimum-error seams into a tile that wraps in both directions.
    """
    rng = np.random.default_rng(6)
    water = C.water_like(ref)
    hud = np.zeros(ref.shape[:2], bool)
    for (cx, cy) in (L.ARROW_LEFT_C, L.ARROW_RIGHT_C):
        cv2.circle(hud.view(np.uint8), (cx, cy), L.ARROW_R + 12, 1, -1)
    P = 96
    cands = []
    for y in range(760, ref.shape[0] - P, 8):
        for x in range(0, ref.shape[1] - P, 8):
            if water[y:y + P, x:x + P].mean() > 0.985 and not hud[y:y + P, x:x + P].any():
                cands.append((x, y))
    patches = [ref[y:y + P, x:x + P].astype(np.float32) for x, y in cands]
    print("water patches:", len(patches))
    T, O = 512, 24
    step = P - O
    tile = np.zeros((T + P, T + P, 3), np.float32)
    filled = np.zeros((T + P, T + P), bool)

    def seam_mask(err, axis):
        # minimum-cost vertical (axis=1) or horizontal (axis=0) seam through the overlap
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

    for by in range(0, T + 1, step):
        for bx in range(0, T + 1, step):
            region = tile[by:by + P, bx:bx + P]
            have = filled[by:by + P, bx:bx + P]
            if not have.any():
                pick = patches[rng.integers(len(patches))]
            else:
                sample = rng.choice(len(patches), size=min(160, len(patches)), replace=False)
                errs = [((patches[i] - region) ** 2).sum(axis=2)[have].mean() for i in sample]
                order = np.argsort(errs)[:4]
                pick = patches[sample[order[rng.integers(len(order))]]]
            new = np.ones((P, P), bool)
            err = ((pick - region) ** 2).sum(axis=2)
            if bx > 0:
                new[:, :O] &= seam_mask(err[:, :O], 1)
            if by > 0:
                new[:O, :] &= seam_mask(err[:O, :], 0)
            new |= ~have
            region[new] = pick[new]
            filled[by:by + P, bx:bx + P] = True
    tile = tile[:T, :T]
    # Wrap: cross-fade the far edges into the near ones.
    for axis in (0, 1):
        n = tile.shape[axis]
        rolled = np.roll(tile, n // 2, axis=axis)
        w = np.abs(np.linspace(-1, 1, n))
        w = np.clip((w - 0.7) / 0.3, 0, 1)
        w = w[:, None, None] if axis == 0 else w[None, :, None]
        tile = tile * (1 - w) + rolled * w
    # Stretch vertically a little: the near river is seen at a grazing angle.
    tile = cv2.resize(np.clip(tile, 0, 255).astype(np.uint8), (T, T), interpolation=cv2.INTER_CUBIC)
    Image.fromarray(tile).save(os.path.join(OUT, "water.png"), optimize=True)
    return tile


def build_stars(ref):
    import build_sprites as level5_sprites  # star matte helper (gold or dark star, holes filled)
    for name, box in (("star_gold", L.STARS[0]), ("star_empty", L.STARS[2])):
        l, t, r, b = box
        m = level5_sprites.star_mask(ref, box)
        save_rgba(os.path.join(OUT, f"{name}.png"), ref[t:b, l:r], m)


def build_sprites(ref):
    meta = {}
    for name, (box, kind) in L.SPRITES.items():
        l, t, r, b = box
        rgb = ref[t:b, l:r]
        a = sprite_matte(name, rgb, box)
        ys, xs = np.nonzero(a > 0.05)
        cl, ct, cr, cb = xs.min(), ys.min(), xs.max() + 1, ys.max() + 1
        save_rgba(os.path.join(OUT, f"{name}.png"), rgb[ct:cb, cl:cr], a[ct:cb, cl:cr])
        meta[name] = {"box": [int(l + cl), int(t + ct), int(l + cr), int(t + cb)]}
    return meta


def main(work="/tmp/level6_work"):
    os.makedirs(OUT, exist_ok=True)
    os.makedirs(work, exist_ok=True)
    ref = np.array(Image.open(os.path.join(REPO, "design", L.REFERENCE)).convert("RGB"))
    sprites = build_sprites(ref)
    build_stars(ref)
    if "--water-only" not in sys.argv:
        build_sky(ref, work)
    build_water(ref, work)
    spec = {
        "stage": [L.STAGE_W, L.STAGE_H],
        "sky": {"margin": [EXT_X, EXT_Y], "height": L.FADE_TOP_SIDES + L.FADE_LEN,
                "waterTop": L.WATER_TOP, "blend": L.WATER_BLEND},
        "perspective": {"horizon": L.HORIZON, "playerY": L.PLAYER_Y, "vanishX": L.VANISH_X,
                        "laneW": L.LANE_W, "depth": L.DEPTH_D,
                        "skiAnchorY": L.SKI_ANCHOR_Y, "skiScale": L.SKI_SCALE},
        "sprites": sprites,
        "hud": {"pause": list(L.PAUSE), "stars": [list(b) for b in L.STARS], "starBar": list(L.STAR_BAR),
                "score": list(L.SCORE_DIGITS), "timer": list(L.TIMER_DIGITS),
                "arrowLeft": list(L.ARROW_LEFT_C) + [L.ARROW_R], "arrowRight": list(L.ARROW_RIGHT_C) + [L.ARROW_R],
                "extent": list(L.HUD_EXTENT),
                # Live numbers: Fredoka Bold, left-baseline anchors fitted to the painted ones.
                "scoreText": {"x": 48.0, "y": 299.5, "size": 64, "width": 147.0},
                "timerText": {"x": 793.0, "y": 281.0, "size": 54, "width": 101.1}},
    }
    with open(os.path.join(OUT, "level6.json"), "w") as f:
        json.dump(spec, f, indent=1)
    print("sprites:", {k: v["box"] for k, v in sprites.items()})


if __name__ == "__main__":
    main(*[a for a in sys.argv[1:] if not a.startswith("--")])
