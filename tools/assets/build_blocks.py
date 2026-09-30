"""Stage 2: block art and the frame-0 effect layer.

* blocks_clean: the reference with shards/rays inpainted off block faces and
  buried blocks replaced by a clean twin.
* blocks atlas: the formation region of blocks_clean with alpha = union of the
  blocks' rounded rectangles. Each block is drawn from its own rect of this atlas.
* fx atlas: every pixel where (plate + blocks) still differs from the reference
  inside the formation (burst, shards, sparks, rays). Split into connected pieces
  so the opening frame can animate them outward from the reference pose.
"""
import os
import sys

import cv2
import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(__file__))
import lama  # noqa: E402
import layout  # noqa: E402

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
REF = os.path.join(REPO, "design", "level5_reference.png")
CORNER_R = 7
# Region of the stage the block/fx atlases cover.
ATLAS_BOX = (140, 250, 875, 875)


def rounded_rect_mask(shape, rect, r):
    l, t, rr, b = rect
    m = np.zeros(shape, np.uint8)
    cv2.rectangle(m, (l + r, t), (rr - r, b), 255, -1)
    cv2.rectangle(m, (l, t + r), (rr, b - r), 255, -1)
    for cx, cy in ((l + r, t + r), (rr - r, t + r), (l + r, b - r), (rr - r, b - r)):
        cv2.circle(m, (cx, cy), r, 255, -1, lineType=cv2.LINE_AA)
    return m


def clean_blocks(ref):
    h, w = ref.shape[:2]
    m = np.zeros((h, w), np.uint8)
    for t in layout.BLOCK_TOUCHUPS:
        if t[0] == "rect":
            cv2.rectangle(m, t[1:3], t[3:5], 255, -1)
        else:
            cv2.line(m, t[1:3], t[3:5], 255, t[5])
    out = lama.inpaint(ref, m)
    for (l, t, r, bt), (sx, sy) in layout.BLOCK_PATCHES:
        out[t:bt, l:r] = out[sy:sy + bt - t, sx:sx + r - l]
    rects = {b[0]: b[1:5] for b in layout.BLOCKS}
    for tgt, donor in layout.BLOCK_DONORS.items():
        dl, dt, dr, db = rects[donor]
        tl, tt, tr, tb = rects[tgt]
        patch = cv2.resize(out[dt:db, dl:dr], (tr - tl, tb - tt), interpolation=cv2.INTER_AREA)
        out[tt:tb, tl:tr] = patch
    return out, m


def build(work):
    ref = np.array(Image.open(REF).convert("RGB"))
    plate = np.array(Image.open(os.path.join(work, "plate.png")).convert("RGB"))
    h, w = ref.shape[:2]
    clean, touch = clean_blocks(ref)

    alpha = np.zeros((h, w), np.uint8)
    for _, l, t, r, b, *_ in layout.BLOCKS:
        alpha = np.maximum(alpha, rounded_rect_mask((h, w), (l, t, r, b), CORNER_R))
    a = alpha.astype(np.float32)[..., None] / 255.0
    comp = (clean * a + plate * (1 - a)).round().astype(np.uint8)

    x0, y0, x1, y1 = ATLAS_BOX
    rgba = np.dstack([clean, alpha])[y0:y1, x0:x1]
    Image.fromarray(rgba, "RGBA").save(os.path.join(work, "blocks_atlas.png"))

    # Frame-0 effects: whatever the reference still has that plate+blocks lacks.
    form = np.array(Image.open(os.path.join(work, "mask_formation.png")))
    form = cv2.dilate(form, np.ones((9, 9), np.uint8))
    diff = np.abs(ref.astype(np.int16) - comp.astype(np.int16)).sum(axis=2)
    fx = ((diff > 30) & (form > 0)).astype(np.uint8) * 255
    fx = cv2.morphologyEx(fx, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8))
    fx = cv2.dilate(fx, np.ones((3, 3), np.uint8))
    fx_soft = cv2.GaussianBlur(fx.astype(np.float32), (0, 0), 1.0)
    fx_soft = np.maximum(fx_soft, cv2.erode(fx, np.ones((3, 3), np.uint8)).astype(np.float32))
    fx_alpha = np.clip(fx_soft, 0, 255).astype(np.uint8)
    fx_rgba = np.dstack([ref, fx_alpha])[y0:y1, x0:x1]
    Image.fromarray(fx_rgba, "RGBA").save(os.path.join(work, "fx_atlas.png"))

    n, labels, stats, cents = cv2.connectedComponentsWithStats((fx_alpha > 0).astype(np.uint8), 8)
    pieces = []
    for i in range(1, n):
        x, y, bw, bh, area = stats[i]
        if area < 6:
            continue
        pieces.append({"rect": [int(x), int(y), int(x + bw), int(y + bh)],
                       "center": [round(float(cents[i][0]), 1), round(float(cents[i][1]), 1)],
                       "area": int(area)})
    Image.fromarray(clean).save(os.path.join(work, "blocks_clean.png"))
    Image.fromarray(comp).save(os.path.join(work, "composite_blocks.png"))
    Image.fromarray(fx).save(os.path.join(work, "fx_mask.png"))
    Image.fromarray(touch).save(os.path.join(work, "mask_touchups.png"))
    return pieces


if __name__ == "__main__":
    import json
    p = build(sys.argv[1] if len(sys.argv) > 1 else "/tmp/level5_work")
    print(len(p), "fx pieces")
    print(json.dumps(sorted(p, key=lambda q: -q["area"])[:12]))
