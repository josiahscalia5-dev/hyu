"""Quality checks on the generated Level 5 art.

  * every block is fully opaque inside its own area in all six block atlases
    (no broken transparent areas)
  * every block reads as the intended colour in every atlas (median hue in that
    colour's window: no incorrect colours)
  * no fragments: no patch of pixels whose hue is far from the block's own
    (leftover bits of a neighbour, painted shards, reflections)
  * block areas are disjoint (no overlapping sprite pieces)
  * ball sprites are opaque discs; no sprite is empty

Exit code 1 if anything fails.   python3 tools/assets/check_assets.py
"""
import json
import os
import sys

import cv2
import numpy as np
from PIL import Image

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ASSETS = os.path.join(REPO, "app", "src", "main", "assets", "level5")

# OpenCV hue (0..180) window a block's median hue must fall in, per game colour.
HUE = {"cyan": (92, 112), "violet": (128, 146), "magenta": (144, 158), "red": (170, 186), "yellow": (18, 32)}
INSET = 4            # skip the rounded, anti-aliased rim
FAR = 24             # hue distance from the block's median that counts as foreign
MAX_STRAY = 0.004    # tolerated share of foreign pixels (specks)
MIN_BLOB = 10        # a connected foreign patch this big is a fragment


def rgba(name):
    return np.array(Image.open(os.path.join(ASSETS, name)).convert("RGBA"))


def in_window(h, lo, hi):
    h = h.astype(np.int16)
    if hi > 180:
        return (h >= lo) | (h <= hi - 180)
    return (h >= lo) & (h <= hi)


def hue_dist(h, h0):
    d = np.abs(h.astype(np.int16) - int(h0))
    return np.minimum(d, 180 - d)


def circ_median(h):
    h = h.astype(np.int16)
    shifted = (h + 90) % 180
    if np.std(shifted) < np.std(h):
        return int((np.median(shifted) - 90) % 180)
    return int(np.median(h))


def main():
    spec = json.load(open(os.path.join(ASSETS, "level5.json")))
    ox, oy = spec["atlasOrigin"]
    problems = []
    report = []
    for atlas_name in ["ref", "cyan", "violet", "magenta", "red", "yellow"]:
        a = rgba(f"blocks_{atlas_name}.png")
        hsv = cv2.cvtColor(np.ascontiguousarray(a[..., :3]), cv2.COLOR_RGB2HSV)
        worst = 0.0
        for b in spec["blocks"]:
            colour = b["color"] if atlas_name == "ref" else atlas_name
            if atlas_name == "ref" and b["normalize"]:
                continue  # painted in-between hue, shown only on the opening frame
            lo, hi = HUE[colour]
            own = np.zeros(a.shape[:2], bool)
            for (l, t, r, bt) in b["parts"]:
                own[t - oy:bt - oy, l - ox:r - ox] = True
            core = cv2.erode(own.astype(np.uint8), np.ones((2 * INSET + 1, 2 * INSET + 1), np.uint8)) > 0
            if a[..., 3][core].min() < 255:
                problems.append(f"{atlas_name}: {b['id']} has transparent pixels inside its area "
                                f"({(a[..., 3][core] < 255).sum()} px)")
            saturated = core & (hsv[..., 1] > 90) & (hsv[..., 2] > 60)
            h0 = circ_median(hsv[..., 0][saturated])
            if not in_window(np.array([h0]), lo, hi)[0]:
                problems.append(f"{atlas_name}: {b['id']} reads as hue {h0}, not {colour} {HUE[colour]}")
            stray = saturated & (hue_dist(hsv[..., 0], h0) > FAR)
            share = stray.sum() / max(1, saturated.sum())
            worst = max(worst, share)
            n, lab, st, _ = cv2.connectedComponentsWithStats(stray.astype(np.uint8), 8)
            big = [int(x) for x in st[1:, 4] if x >= MIN_BLOB]
            if share > MAX_STRAY or big:
                problems.append(f"{atlas_name}: {b['id']} ({colour}) has foreign-hue pixels: "
                                f"{share:.2%}, patches {big}")
        report.append(f"blocks_{atlas_name}.png: worst off-colour share {worst:.2%}")

    boxes = [(b["id"], p) for b in spec["blocks"] for p in b["parts"]]
    for i, (ia, pa) in enumerate(boxes):
        for ib, pb in boxes[i + 1:]:
            if ia != ib and min(pa[2], pb[2]) > max(pa[0], pb[0]) and min(pa[3], pb[3]) > max(pa[1], pb[1]):
                problems.append(f"overlapping block pieces: {ia} {pa} and {ib} {pb}")
    report.append(f"{len(boxes)} block pieces, pairwise disjoint checked")

    for c in HUE:
        ball = rgba(f"ball_{c}.png")
        cy, cx = ball.shape[0] // 2, ball.shape[1] // 2
        yy, xx = np.mgrid[0:ball.shape[0], 0:ball.shape[1]]
        inside = (yy - cy) ** 2 + (xx - cx) ** 2 < (spec["ball"]["radius"] - 2) ** 2
        if ball[..., 3][inside].min() < 255:
            problems.append(f"ball_{c}.png: transparent pixels inside the ball")
        hsv = cv2.cvtColor(np.ascontiguousarray(ball[..., :3]), cv2.COLOR_RGB2HSV)
        core = inside & ((yy - cy) ** 2 + (xx - cx) ** 2 < 30 ** 2) & (hsv[..., 1] > 110)
        share = in_window(hsv[..., 0][core], *HUE[c]).mean()
        report.append(f"ball_{c}.png: {share:.0%} of the saturated core is {c}")
        if share < 0.6:
            problems.append(f"ball_{c}.png: core colour does not read as {c} ({share:.0%})")

    for f in sorted(os.listdir(ASSETS)):
        if f.endswith(".png"):
            im = Image.open(os.path.join(ASSETS, f))
            if im.getbbox() is None:
                problems.append(f"{f}: empty image")

    print("\n".join(report))
    if problems:
        print("\nPROBLEMS:\n  " + "\n  ".join(problems))
        return 1
    print("\nAll asset checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
