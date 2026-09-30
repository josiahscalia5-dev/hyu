"""Stage 1: build the clean background plate from the approved reference.

Removes everything that moves or changes during play (blocks, hit effects, the ball,
the aim chevrons, and the live HUD numbers/combo text) and fills the holes with
Big-LaMa, so those elements can be drawn on top as real game objects.

Output: <work>/plate.png (1024x1536) plus the masks used.
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

# Live HUD text boxes (left, top, right, bottom): timer digits, score digits.
HUD_TEXT_BOXES = [
    (868, 44, 988, 112),
    (62, 1378, 252, 1458),
]
# Gold text painted over scenery ("Combo x9" on the pillar, "+30" by the coin): masked
# by letter shape rather than by box so the fill blends into the untouched stone.
GOLD_TEXT_BOXES = [
    (726, 206, 1000, 456),
    (720, 1336, 884, 1448),
]
GOLD_TEXT_GROW = 15
# Left edge of the right-hand pillar behind "Combo": the box fill rebuilds the pillar
# face well, but left of this line the letters sit over the dark doorway.
PILLAR_EDGE_X = 805
COIN_CENTER, COIN_R = (932, 1390), 58
BALL_CENTER, BALL_CLEAR_R = (515, 1395), 100
AIM_STRIP = (486, 845, 544, 1340)
# Shards thrown outside the formation's bounding box.
STRAY_SHARDS = [(140, 685, 200, 745), (805, 575, 862, 630), (808, 682, 860, 730),
                (818, 728, 872, 772), (748, 455, 808, 545), (700, 420, 770, 490)]
FORMATION_BOX = (186, 255, 822, 866)


def formation_mask(shape):
    m = np.zeros(shape, np.uint8)
    cv2.rectangle(m, FORMATION_BOX[:2], FORMATION_BOX[2:], 255, -1)
    for b in STRAY_SHARDS:
        cv2.rectangle(m, b[:2], b[2:], 255, -1)
    for _, l, t, r, b, *_ in layout.BLOCKS:
        cv2.rectangle(m, (l - 4, t - 4), (r + 4, b + 4), 255, -1)
    return m


def gold_text_mask(ref):
    hsv = cv2.cvtColor(ref, cv2.COLOR_RGB2HSV)
    fill = ((hsv[..., 0] >= 16) & (hsv[..., 0] <= 38) & (hsv[..., 1] > 90) & (hsv[..., 2] > 215)).astype(np.uint8)
    rim = (((hsv[..., 0] <= 8) | (hsv[..., 0] >= 172)) & (hsv[..., 1] > 150) & (hsv[..., 2] > 150)).astype(np.uint8)
    m = np.zeros(ref.shape[:2], np.uint8)
    for l, t, r, b in GOLD_TEXT_BOXES:
        m[t:b, l:r] = (fill | rim)[t:b, l:r]
    m = cv2.morphologyEx(m, cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8))
    k = 2 * GOLD_TEXT_GROW + 1
    return cv2.dilate(m * 255, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k)))


def detail_mask(shape, ref=None):
    """Holes for the live HUD text, ball and aim guide.

    With `ref`, gold text is masked by letter shape (what finally gets replaced);
    without, by its whole box (what LaMa fills, which keeps the pillar's structure).
    """
    m = np.zeros(shape, np.uint8)
    for b in HUD_TEXT_BOXES:
        cv2.rectangle(m, b[:2], b[2:], 255, -1)
    if ref is not None:
        m = np.maximum(m, gold_text_mask(ref))
    else:
        for b in GOLD_TEXT_BOXES:
            cv2.rectangle(m, b[:2], b[2:], 255, -1)
    cv2.circle(m, COIN_CENTER, COIN_R, 0, -1)  # keep the coin icon itself
    cv2.circle(m, BALL_CENTER, BALL_CLEAR_R, 255, -1)
    cv2.rectangle(m, AIM_STRIP[:2], AIM_STRIP[2:], 255, -1)
    return m


def build(work):
    os.makedirs(work, exist_ok=True)
    ref = np.array(Image.open(REF).convert("RGB"))
    h, w = ref.shape[:2]

    # The doorway hole is large, so fill it at half resolution first (LaMa keeps
    # coherent brick courses at that scale), then refine a border band at full res.
    form = formation_mask((h, w))
    small = cv2.resize(ref, (w // 2, h // 2), interpolation=cv2.INTER_AREA)
    ms = cv2.resize(form, (w // 2, h // 2), interpolation=cv2.INTER_NEAREST)
    up = cv2.resize(lama.inpaint(small, ms), (w, h), interpolation=cv2.INTER_CUBIC)
    seed = ref.copy()
    seed[form > 0] = up[form > 0]
    inner = cv2.erode(form, np.ones((41, 41), np.uint8))
    plate = lama.inpaint(seed, cv2.subtract(form, inner))

    # Calm LaMa's colour speckle inside the doorway without touching real pixels.
    smooth = cv2.bilateralFilter(plate, 9, 18, 7)
    soft = cv2.GaussianBlur(inner.astype(np.float32) / 255.0, (0, 0), 6)[..., None]
    plate = (plate * (1 - soft) + smooth * soft).round().astype(np.uint8)

    det = detail_mask((h, w), ref)
    filled = lama.inpaint(plate, det)
    boxed = lama.inpaint(plate, detail_mask((h, w)))
    cl, ct, cr, cb = GOLD_TEXT_BOXES[0]
    filled[ct:cb, PILLAR_EDGE_X:cr] = boxed[ct:cb, PILLAR_EDGE_X:cr]
    a = cv2.GaussianBlur(det.astype(np.float32) / 255.0, (0, 0), 3)[..., None]
    a = np.maximum(a, (det > 0)[..., None].astype(np.float32))
    plate = (filled * a + plate * (1 - a)).round().astype(np.uint8)

    Image.fromarray(plate).save(os.path.join(work, "plate.png"))
    Image.fromarray(form).save(os.path.join(work, "mask_formation.png"))
    Image.fromarray(det).save(os.path.join(work, "mask_detail.png"))
    return plate


if __name__ == "__main__":
    build(sys.argv[1] if len(sys.argv) > 1 else "/tmp/level5_work")
