"""Paints the World 1 "Tropical Islands" map backdrop (assets/maps/w1/).

A top-down sea with raised sandy islands under the level path: turquoise shallows and
foam around each island, a cliff edge for depth, grass, palm trees, and built levels'
own painted props as landmarks (Level 5's colour blocks; Level 4's treasure chest and
gems only while Level 4 is the treasure-slicing level). Painted past every edge so any
phone shape is covered.

The island chain is laid out for ten stops (LAYOUT). World 1 uses the first six for
Levels 1-6 and the seventh for the gate to World 2 (assets/worlds.json); the rest are
scenery.

  pip install -r tools/assets/requirements.txt
  python3 tools/map/build.py
"""
import json
import math
import os

import cv2
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
ASSETS = os.path.join(REPO, "app", "src", "main", "assets")
OUT = os.path.join(ASSETS, "maps", "w1")

W, H = 1080, 2340          # map art (node coordinates are in these pixels)
MX, MY = 140, 160          # painted margin past each edge
PW, PH = W + 2 * MX, H + 2 * MY
rng = np.random.default_rng(7)


def noise(h, w, scale, octaves=4, seed=0):
    """Smooth value noise in [0, 1]."""
    r = np.random.default_rng(seed)
    out = np.zeros((h, w), np.float32)
    amp, total = 1.0, 0.0
    for o in range(octaves):
        s = max(2, int(scale / (2 ** o)))
        g = r.random((h // s + 3, w // s + 3)).astype(np.float32)
        up = cv2.resize(g, ((w // s + 3) * s, (h // s + 3) * s), interpolation=cv2.INTER_CUBIC)[:h, :w]
        out += up * amp
        total += amp
        amp *= 0.5
    out /= total
    return (out - out.min()) / (out.max() - out.min() + 1e-6)


# The stops the island chain is drawn around, in map-art pixels.
LAYOUT = {1: (300, 2085), 2: (640, 2000), 3: (850, 1800), 4: (610, 1590), 5: (290, 1400),
          6: (460, 1180), 7: (790, 1040), 8: (620, 850), 9: (300, 720), 10: (560, 540)}


def world1():
    with open(os.path.join(ASSETS, "worlds.json")) as f:
        return json.load(f)["worlds"][0]


def nodes():
    w = world1()
    # World 1's level buttons and its gate must sit on the stops the islands are drawn for.
    stops = [(l["number"], l["node"]) for l in w["levels"]] + [(len(w["levels"]) + 1, w["gate"]["node"])]
    for k, node in stops:
        assert tuple(node) == LAYOUT[k], f"stop {k} at {node}, but the islands are drawn for {LAYOUT[k]}"
    return [(k, x + MX, y + MY) for k, (x, y) in LAYOUT.items()]


def kind(number):
    """The gameplay kind of World 1's level [number] (None while it is not built)."""
    return next((l.get("kind") for l in world1()["levels"] if l["number"] == number), None)


# Islands as blobs (centre x, centre y, radius x, radius y) in padded pixels: one island
# per stretch of the path, with water between them that the path crosses on bridges.
def islands():
    n = {k: (x, y) for k, x, y in nodes()}

    def at(k, dx=0, dy=0, rx=150, ry=120):
        return (n[k][0] + dx, n[k][1] + dy, rx, ry)

    return [
        # Beach island: levels 1-3.
        [at(1, 20, 30, 190, 130), at(2, 0, 20, 180, 125), at(3, -10, 30, 140, 140),
         ((n[1][0] + n[2][0]) / 2, n[1][1] + 70, 220, 100)],
        # Lagoon island: level 4, its treasure beside it.
        [at(4, 50, 10, 200, 130), at(4, 180, -40, 110, 100)],
        # Temple island: level 5, its blocks beside it.
        [at(5, -40, 10, 170, 130), at(5, -120, 175, 150, 100)],
        # Small islands: levels 6 and 7.
        [at(6, 0, 10, 135, 105)],
        [at(7, 10, 10, 140, 110), at(7, 90, 80, 80, 60)],
        # Mountain island: levels 8-10.
        [at(8, 10, 20, 150, 115), at(9, -10, 10, 145, 115), at(10, 0, 30, 175, 140),
         ((n[8][0] + n[9][0]) / 2 + 10, (n[8][1] + n[9][1]) / 2 + 20, 130, 80),
         ((n[9][0] + n[10][0]) / 2, (n[9][1] + n[10][1]) / 2 + 20, 110, 80)],
        # Little islets for scenery.
        [(MX + 960, MY + 400, 80, 60)], [(MX + 90, MY + 1010, 85, 65)], [(MX + 1000, MY + 1400, 70, 55)],
        [(MX + 100, MY + 1800, 75, 60)], [(MX + 990, MY + 2250, 90, 60)], [(MX + 960, MY + 760, 60, 45)],
    ]


def path_points(step=6.0):
    """The level path as the app draws it: Catmull-Rom through the level buttons."""
    pts = [(x, y) for _, x, y in nodes()]
    out = []

    def cat(p0, p1, p2, p3, t):
        t2, t3 = t * t, t * t * t
        return 0.5 * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3)

    for i in range(len(pts) - 1):
        p0 = pts[max(0, i - 1)]; p1 = pts[i]; p2 = pts[i + 1]; p3 = pts[min(len(pts) - 1, i + 2)]
        n = max(2, int(math.hypot(p2[0] - p1[0], p2[1] - p1[1]) / step))
        for j in range(n):
            t = j / n
            out.append((cat(p0[0], p1[0], p2[0], p3[0], t), cat(p0[1], p1[1], p2[1], p3[1], t)))
    out.append(pts[-1])
    return out


def island_mask(blobs, seed):
    m = np.zeros((PH, PW), np.float32)
    for (cx, cy, rx, ry) in blobs:
        cv2.ellipse(m, (int(cx), int(cy)), (int(rx), int(ry)), 0, 0, 360, 1.0, -1)
    # Organic coastline: warp the outline with noise, then smooth.
    nx = (noise(PH, PW, 160, 3, seed) - 0.5) * 70
    ny = (noise(PH, PW, 160, 3, seed + 1) - 0.5) * 70
    gx, gy = np.meshgrid(np.arange(PW, dtype=np.float32), np.arange(PH, dtype=np.float32))
    m = cv2.remap(m, gx + nx, gy + ny, cv2.INTER_LINEAR)
    m = cv2.GaussianBlur(m, (0, 0), 9)
    return m > 0.5


def mix(a, b, t):
    t = np.clip(t, 0, 1)[..., None]
    return a * (1 - t) + b * t


def col(hexs):
    h = hexs.lstrip("#")
    return np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)], np.float32)


def paint():
    yy = np.linspace(0, 1, PH, dtype=np.float32)[:, None] * np.ones((1, PW), np.float32)
    # Sea: deep blue at the top, brighter toward the beach at the bottom, with soft swells.
    sea = mix(np.broadcast_to(col("#0d63b8"), (PH, PW, 3)), np.broadcast_to(col("#1a9be0"), (PH, PW, 3)), yy)
    sw = noise(PH, PW, 260, 4, 3)
    sea = sea * (0.9 + 0.2 * sw[..., None])

    land = np.zeros((PH, PW), bool)
    masks = []
    for i, blobs in enumerate(islands()):
        m = island_mask(blobs, 10 + i)
        masks.append(m)
        land |= m

    # Shallows: turquoise fading out from every shore, with a foam line.
    dist_out = cv2.distanceTransform((~land).astype(np.uint8), cv2.DIST_L2, 5)
    shallow = np.exp(-dist_out / 55.0)
    img = mix(sea, np.broadcast_to(col("#3fd7e6"), (PH, PW, 3)), shallow * 0.95)
    img = mix(img, np.broadcast_to(col("#8ef2ef"), (PH, PW, 3)), np.exp(-dist_out / 14.0) * 0.8)
    foam_n = noise(PH, PW, 30, 3, 21)
    foam = np.exp(-((dist_out - 7) ** 2) / 18.0) * (foam_n > 0.38)
    img = mix(img, np.broadcast_to(col("#ffffff"), (PH, PW, 3)), foam * 0.85)

    # Wave glints across open water: short white strokes.
    glint = np.zeros((PH, PW), np.float32)
    for _ in range(170):
        x, y = rng.integers(0, PW), rng.integers(0, PH)
        if dist_out[y, x] < 60:
            continue
        L = rng.integers(14, 34)
        cv2.ellipse(glint, (int(x), int(y)), (int(L), 5), 0, 200, 340, 1.0, 3)
    glint = cv2.GaussianBlur(glint, (0, 0), 1.2)
    img = mix(img, np.broadcast_to(col("#e8fbff"), (PH, PW, 3)), glint * 0.6)

    # Raised islands: a sandy cliff face under each, then sand and grass on top.
    depth = 26
    side = np.zeros((PH, PW), bool)
    side[depth:] = land[:-depth]
    side &= ~land
    shadow = np.zeros((PH, PW), np.float32)
    shadow[depth + 10:] = land[:-(depth + 10)]
    shadow = cv2.GaussianBlur(shadow, (0, 0), 10) * (~land) * (~side)
    img = img * (1 - 0.35 * shadow[..., None])
    rock = noise(PH, PW, 18, 3, 31)
    face = mix(np.broadcast_to(col("#b97a3c"), (PH, PW, 3)), np.broadcast_to(col("#8a5428"), (PH, PW, 3)),
               (np.arange(PH)[:, None] % 9 < 2) * 0.5 + rock * 0.4)
    img[side] = face[side]

    dist_in = cv2.distanceTransform(land.astype(np.uint8), cv2.DIST_L2, 5)
    sand_n = noise(PH, PW, 12, 2, 41)
    sand = mix(np.broadcast_to(col("#f8dc95"), (PH, PW, 3)), np.broadcast_to(col("#e8bf6d"), (PH, PW, 3)), sand_n * 0.8)
    grass_edge = 30 + 14 * noise(PH, PW, 60, 2, 51)
    g_n = noise(PH, PW, 40, 4, 61)
    grass = mix(np.broadcast_to(col("#4aa82c"), (PH, PW, 3)), np.broadcast_to(col("#86d84a"), (PH, PW, 3)), g_n)
    # Light from the top left: brighter on that side of each island.
    soft = cv2.GaussianBlur(land.astype(np.float32), (0, 0), 30)
    gy, gx = np.gradient(soft)
    light = np.clip(0.5 - (gx + gy) * 40, 0, 1)
    grass = grass * (0.8 + 0.35 * light[..., None])
    top = np.where((dist_in > grass_edge)[..., None], grass, sand)
    # Grass rim: a darker line where it meets the sand.
    rim = np.exp(-((dist_in - grass_edge) ** 2) / 6.0) * (dist_in > grass_edge - 3)
    top = mix(top, np.broadcast_to(col("#2f7a1e"), (PH, PW, 3)), rim * 0.6)
    img[land] = top[land]
    return img.clip(0, 255).astype(np.uint8), land, masks


# ---- props -------------------------------------------------------------------------

def load_rgba(path):
    return np.array(Image.open(path).convert("RGBA")).astype(np.float32)


def paste(img, sprite, cx, cy, scale, shadow=True, rot=0):
    s = sprite
    h, w = s.shape[:2]
    nw, nh = max(1, int(w * scale)), max(1, int(h * scale))
    s = cv2.resize(s, (nw, nh), interpolation=cv2.INTER_AREA)
    if rot:
        M = cv2.getRotationMatrix2D((nw / 2, nh / 2), rot, 1.0)
        s = cv2.warpAffine(s, M, (nw, nh), flags=cv2.INTER_LINEAR, borderValue=(0, 0, 0, 0))
    x0, y0 = int(cx - nw / 2), int(cy - nh / 2)
    if shadow:
        a = s[..., 3] / 255.0
        sh = cv2.GaussianBlur(a, (0, 0), 4) * 0.35
        blend(img, np.zeros((nh, nw, 3), np.float32), sh, x0 + 8, y0 + 12)
    blend(img, s[..., :3], s[..., 3] / 255.0, x0, y0)


def blend(img, rgb, a, x0, y0):
    h, w = a.shape
    xa, ya = max(0, x0), max(0, y0)
    xb, yb = min(img.shape[1], x0 + w), min(img.shape[0], y0 + h)
    if xb <= xa or yb <= ya:
        return
    sub = img[ya:yb, xa:xb].astype(np.float32)
    aa = a[ya - y0:yb - y0, xa - x0:xb - x0][..., None]
    img[ya:yb, xa:xb] = (sub * (1 - aa) + rgb[ya - y0:yb - y0, xa - x0:xb - x0] * aa).clip(0, 255).astype(np.uint8)


def palm(size, lean, seed):
    """A palm tree, top-down-ish three-quarter view: curved trunk, star of fronds."""
    r = np.random.default_rng(seed)
    S = int(size * 2.4)
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    a = np.zeros((S, S, 4), np.float32)
    cx, base = S / 2, S * 0.92
    top = (cx + lean * size * 0.5, S * 0.42)
    # Trunk: segments from the base to the crown.
    pts = []
    for i in range(14):
        t = i / 13
        x = cx + (top[0] - cx) * t + math.sin(t * math.pi) * lean * size * 0.18
        y = base + (top[1] - base) * t
        pts.append((x, y))
    for i, (x, y) in enumerate(pts):
        w = size * (0.11 - 0.04 * i / 13)
        shade = 0.85 + 0.15 * (i % 2)
        cv2.ellipse(a, (int(x), int(y)), (int(w), int(w * 0.75)), 0, 0, 360,
                    (int(150 * shade), int(98 * shade), int(52 * shade), 255), -1, cv2.LINE_AA)
    # Fronds.
    n = 7
    for k in range(n):
        ang = k / n * 2 * math.pi + r.random() * 0.4
        L = size * (0.75 + r.random() * 0.25)
        tip = (top[0] + math.cos(ang) * L, top[1] + math.sin(ang) * L * 0.62 + L * 0.12)
        mid = (top[0] + math.cos(ang) * L * 0.5, top[1] + math.sin(ang) * L * 0.31 - L * 0.08)
        poly = []
        for t in np.linspace(0, 1, 12):
            bx = (1 - t) ** 2 * top[0] + 2 * (1 - t) * t * mid[0] + t * t * tip[0]
            by = (1 - t) ** 2 * top[1] + 2 * (1 - t) * t * mid[1] + t * t * tip[1]
            poly.append((bx, by))
        width = size * 0.16
        left, right = [], []
        for i in range(len(poly)):
            x0, y0 = poly[max(0, i - 1)]
            x1, y1 = poly[min(len(poly) - 1, i + 1)]
            dx, dy = x1 - x0, y1 - y0
            ln = math.hypot(dx, dy) + 1e-6
            nx, ny = -dy / ln, dx / ln
            t = i / (len(poly) - 1)
            ww = width * math.sin(math.pi * min(1, t * 1.15)) + 1
            left.append((poly[i][0] + nx * ww, poly[i][1] + ny * ww))
            right.append((poly[i][0] - nx * ww, poly[i][1] - ny * ww))
        shape = np.array(left + right[::-1], np.int32)
        g = 0.85 + 0.3 * r.random()
        cv2.fillPoly(a, [shape], (int(46 * g), int(150 * g), int(42 * g), 255), cv2.LINE_AA)
        half = np.array(left + poly[::-1], np.int32)
        cv2.fillPoly(a, [half], (int(92 * g), int(196 * g), int(64 * g), 255), cv2.LINE_AA)
        cv2.polylines(a, [np.array(poly, np.int32)], False, (30, 100, 28, 255), max(1, int(size * 0.03)), cv2.LINE_AA)
    # Coconuts.
    for k in range(3):
        cv2.circle(a, (int(top[0] + (k - 1) * size * 0.08), int(top[1] + size * 0.06)), int(size * 0.07),
                   (110, 70, 30, 255), -1, cv2.LINE_AA)
    del im
    return a


def rock(size, seed):
    r = np.random.default_rng(seed)
    S = int(size * 2)
    a = np.zeros((S, S, 4), np.float32)
    pts = []
    for k in range(9):
        ang = k / 9 * 2 * math.pi
        rad = size * (0.6 + r.random() * 0.3)
        pts.append((S / 2 + math.cos(ang) * rad, S / 2 + math.sin(ang) * rad * 0.7))
    cv2.fillPoly(a, [np.array(pts, np.int32)], (128, 122, 132, 255), cv2.LINE_AA)
    hi = [(x - size * 0.12, y - size * 0.15) for x, y in pts[4:8]] + [(S / 2, S / 2 - size * 0.1)]
    cv2.fillPoly(a, [np.array(hi, np.int32)], (178, 172, 184, 255), cv2.LINE_AA)
    return a


def props(img, land):
    n = {k: (x, y) for k, x, y in nodes()}
    l4 = os.path.join(ASSETS, "level4")
    l5 = os.path.join(ASSETS, "level5")
    # Level 4: the treasure chest, gems and a coin beside its button (only while Level 4
    # is the treasure-slicing level; its spot stays clear of palms either way).
    if kind(4) == "treasure-slice":
        chest = load_rgba(os.path.join(l4, "t_chest_jewel.png"))
        paste(img, chest, n[4][0] + 195, n[4][1] - 45, 0.46)
        paste(img, load_rgba(os.path.join(l4, "t_gem_red.png")), n[4][0] + 205, n[4][1] + 70, 0.3, rot=-15)
        paste(img, load_rgba(os.path.join(l4, "t_gem_blue.png")), n[4][0] + 290, n[4][1] + 25, 0.28, rot=12)
        paste(img, load_rgba(os.path.join(l4, "t_coin_center.png")), n[4][0] - 120, n[4][1] - 95, 0.3)
    # Level 5: a little stack of its colour blocks.
    atlas = {c: np.array(Image.open(os.path.join(l5, f"blocks_{c}.png")).convert("RGBA")).astype(np.float32)
             for c in ("cyan", "magenta", "yellow", "red", "violet")}
    with open(os.path.join(l5, "level5.json")) as f:
        spec = json.load(f)
    blocks = spec["blocks"]
    ax, ay = spec["atlasOrigin"]
    one = blocks[0]["rect"]
    bw, bh = one[2] - one[0], one[3] - one[1]

    def block(colour, x, y, s):
        b = blocks[0]["rect"]
        crop = atlas[colour][int(b[1] - ay):int(b[3] - ay), int(b[0] - ax):int(b[2] - ax)]
        if crop.size:
            paste(img, crop, x, y, s)

    bx, by = n[5][0] - 150, n[5][1] + 205
    s = 50 / max(bw, 1)
    for i, c in enumerate(("cyan", "magenta", "yellow")):
        block(c, bx + (i - 1) * 46, by + 30, s)
    for i, c in enumerate(("red", "violet")):
        block(c, bx + (i - 0.5) * 46, by - 13, s)
    block("yellow", bx, by - 56, s)
    # Palms and rocks around the islands (never on a level button or its path).
    keep = np.zeros(land.shape, np.uint8)
    for _, x, y in nodes():
        cv2.circle(keep, (int(x), int(y)), 150, 1, -1)
    cv2.polylines(keep, [np.array(path_points(), np.int32)], False, 1, 120)
    for (dx, dy, r) in ((195, -45, 120), (240, 50, 90), (-120, -95, 70)):
        cv2.circle(keep, (int(n[4][0] + dx), int(n[4][1] + dy)), r, 1, -1)
    cv2.circle(keep, (int(n[5][0] - 150), int(n[5][1] + 190)), 120, 1, -1)
    keep = keep > 0
    ys, xs = np.nonzero(land & ~keep)
    order = rng.permutation(len(xs))
    placed = []
    for idx in order:
        x, y = xs[idx], ys[idx]
        if len(placed) >= 46:
            break
        if any(math.hypot(x - px, y - py) < 95 for px, py in placed):
            continue
        placed.append((x, y))
    for i, (x, y) in enumerate(sorted(placed, key=lambda p: p[1])):
        if i % 4 == 3:
            paste(img, rock(26 + (i % 3) * 6, i), x, y, 1.0)
        else:
            size = 52 + (i * 7) % 26
            paste(img, palm(size, ((i * 37) % 9 - 4) / 6, i), x, y - size * 0.8, 1.0)


def bridges(img, land):
    """Wooden plank bridges where the level path crosses open water."""
    pts = path_points(4.0)
    over = [not land[int(min(PH - 1, max(0, y))), int(min(PW - 1, max(0, x)))] for x, y in pts]
    runs, i = [], 0
    while i < len(pts):
        if over[i]:
            j = i
            while j < len(pts) and over[j]:
                j += 1
            runs.append((max(0, i - 6), min(len(pts) - 1, j + 6)))
            i = j
        else:
            i += 1
    r = np.random.default_rng(5)
    out = img.astype(np.float32)
    for a, b in runs:
        seg = pts[a:b + 1]
        # Shadow on the water, then rails' posts, planks, ropes.
        sh = np.zeros(img.shape[:2], np.float32)
        cv2.polylines(sh, [np.array([(x + 6, y + 16) for x, y in seg], np.int32)], False, 1.0, 64, cv2.LINE_AA)
        sh = cv2.GaussianBlur(sh, (0, 0), 6)
        out *= 1 - 0.35 * sh[..., None]
        for k in range(0, len(seg), 3):
            x, y = seg[k]
            x0, y0 = seg[max(0, k - 1)]
            x1, y1 = seg[min(len(seg) - 1, k + 1)]
            dx, dy = x1 - x0, y1 - y0
            ln = math.hypot(dx, dy) + 1e-6
            dx, dy = dx / ln, dy / ln
            nx, ny = -dy, dx
            half, thick = 30 + r.random() * 3, 5.0
            tint = 0.85 + 0.25 * r.random()
            quad = np.array([(x + nx * half - dx * thick, y + ny * half - dy * thick),
                             (x + nx * half + dx * thick, y + ny * half + dy * thick),
                             (x - nx * half + dx * thick, y - ny * half + dy * thick),
                             (x - nx * half - dx * thick, y - ny * half - dy * thick)], np.int32)
            cv2.fillPoly(out, [quad], (int(176 * tint), int(118 * tint), int(62 * tint)), cv2.LINE_AA)
            cv2.polylines(out, [quad], True, (92, 54, 24), 2, cv2.LINE_AA)
        for side in (1, -1):
            rope = []
            for k in range(len(seg)):
                x, y = seg[k]
                x0, y0 = seg[max(0, k - 1)]
                x1, y1 = seg[min(len(seg) - 1, k + 1)]
                dx, dy = x1 - x0, y1 - y0
                ln = math.hypot(dx, dy) + 1e-6
                rope.append((x - dy / ln * 33 * side, y + dx / ln * 33 * side))
            cv2.polylines(out, [np.array(rope, np.int32)], False, (70, 40, 16), 5, cv2.LINE_AA)
            cv2.polylines(out, [np.array(rope, np.int32)], False, (196, 150, 92), 2, cv2.LINE_AA)
            for k in range(0, len(rope), 9):
                cv2.circle(out, (int(rope[k][0]), int(rope[k][1])), 6, (84, 48, 20), -1, cv2.LINE_AA)
    img[:] = out.clip(0, 255).astype(np.uint8)


def clouds(img):
    """Soft clouds drifting over the top of the map: the levels still to come."""
    cl = np.zeros(img.shape[:2], np.float32)
    for k in range(9):
        x = MX + rng.integers(-60, W + 60)
        y = MY + rng.integers(-120, 260)
        for j in range(5):
            cv2.ellipse(cl, (int(x + rng.integers(-110, 110)), int(y + rng.integers(-30, 30))),
                        (int(rng.integers(70, 150)), int(rng.integers(40, 70))), 0, 0, 360, 1.0, -1)
    cl = cv2.GaussianBlur(cl, (0, 0), 18) * 0.85
    sh = np.roll(cl, 22, axis=0) * 0.25
    out = img.astype(np.float32) * (1 - sh[..., None])
    out = out * (1 - cl[..., None]) + np.array([255, 255, 255], np.float32) * cl[..., None]
    return out.clip(0, 255).astype(np.uint8)


def main():
    os.makedirs(OUT, exist_ok=True)
    img, land, _ = paint()
    bridges(img, land)
    props(img, land)
    img = clouds(img)
    Image.fromarray(img).save(os.path.join(OUT, "backdrop.png"), optimize=True)
    with open(os.path.join(OUT, "map.json"), "w") as f:
        json.dump({"size": [W, H], "margin": [MX, MY], "backdrop": "backdrop.png"}, f, indent=1)
    print("map", img.shape)


if __name__ == "__main__":
    main()
