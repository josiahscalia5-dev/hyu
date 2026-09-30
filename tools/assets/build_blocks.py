"""Block clean-up helpers used by build_sprites.py.

clean_blocks() returns the reference with shards and light rays removed from block
faces, damaged symbols rebuilt and buried blocks replaced by a clean twin.
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
REF = os.path.join(REPO, "design", layout.REFERENCE)
CORNER_R = layout.CORNER_R
# Region of the stage the block/fx atlases cover.
ATLAS_BOX = layout.ATLAS_BOX


def rounded_rect_mask(shape, rect, r):
    l, t, rr, b = rect
    m = np.zeros(shape, np.uint8)
    cv2.rectangle(m, (l + r, t), (rr - r, b), 255, -1)
    cv2.rectangle(m, (l, t + r), (rr, b - r), 255, -1)
    for cx, cy in ((l + r, t + r), (rr - r, t + r), (l + r, b - r), (rr - r, b - r)):
        cv2.circle(m, (cx, cy), r, 255, -1, lineType=cv2.LINE_AA)
    return m


def shapes_mask(shape, shapes):
    m = np.zeros(shape, np.uint8)
    for t in shapes:
        if t[0] == "rect":
            cv2.rectangle(m, t[1:3], t[3:5], 255, -1)
        else:
            cv2.line(m, t[1:3], t[3:5], 255, t[5])
    return m


def mirror_patch(img, rect, axis):
    """Replace rect with the mirror image of the other side of `axis`.

    The axis is refined by matching the undamaged lower rows of the rect against
    their mirror, so the rebuilt half lines up with the symbol's real centre.
    """
    l, t, r, b = rect
    probe = img[b - 12:b, :].astype(np.float32)

    def cost(ax):
        xs = np.arange(l, r)
        src = np.round(2 * ax - xs).astype(int)
        return float(np.abs(probe[:, xs] - probe[:, src]).mean())

    best = min(np.arange(axis - 3, axis + 3.01, 0.5), key=cost)
    out = img.copy()
    xs = np.arange(l, r)
    src = np.round(2 * best - xs).astype(int)
    out[t:b, l:r] = img[t:b][:, src]
    # Feather the seam at the top/bottom/left edges of the patch.
    a = np.zeros((b - t, r - l), np.float32)
    a[2:-2, 2:] = 1
    a = cv2.GaussianBlur(a, (0, 0), 1.2)[..., None]
    out[t:b, l:r] = (out[t:b, l:r] * a + img[t:b, l:r] * (1 - a)).round().astype(np.uint8)
    return out


def clean_blocks(ref):
    h, w = ref.shape[:2]
    m = shapes_mask((h, w), layout.BLOCK_TOUCHUPS)
    out = lama.inpaint(ref, m)
    for (l, t, r, bt), axis in layout.BLOCK_MIRRORS:
        out = mirror_patch(out, (l, t, r, bt), axis)
    hsv = cv2.cvtColor(out, cv2.COLOR_RGB2HSV)
    for l, t, r, bt in layout.DESPARKLE_RECTS:
        sp = np.zeros((h, w), np.uint8)
        sp[t:bt, l:r] = ((hsv[t:bt, l:r, 2] > 200) & (hsv[t:bt, l:r, 1] < 110)).astype(np.uint8) * 255
        sp = cv2.dilate(sp, np.ones((3, 3), np.uint8))
        out = cv2.inpaint(out, sp, 2, cv2.INPAINT_TELEA)
    sm = shapes_mask((h, w), layout.SMOOTH_TOUCHUPS)
    out = cv2.inpaint(out, sm, 4, cv2.INPAINT_TELEA)
    m = np.maximum(m, sm)
    for (l, t, r, bt), (sx, sy) in layout.BLOCK_PATCHES:
        out[t:bt, l:r] = out[sy:sy + bt - t, sx:sx + r - l]
    rects = {b[0]: b[1:5] for b in layout.BLOCKS}
    for tgt, donor in layout.BLOCK_DONORS.items():
        dl, dt, dr, db = rects[donor]
        tl, tt, tr, tb = rects[tgt]
        patch = cv2.resize(out[dt:db, dl:dr], (tr - tl, tb - tt), interpolation=cv2.INTER_AREA)
        out[tt:tb, tl:tr] = patch
    return out, m
