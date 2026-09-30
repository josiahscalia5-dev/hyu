"""Stage 1: the clean background plate, from the approved full-screen reference.

Removes everything that moves or changes during play (blocks, hit effects, the ball,
the aim chevrons, the live HUD numbers, the combo text and the Goal instructions) and
fills the holes with Big-LaMa, so those elements can be drawn on top as real game
objects. Then extends the plate past every edge so screens of any shape are filled
edge to edge.

Output: <work>/plate.png (stage size), <work>/plate_ext.png (with margins).
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

GOLD_TEXT_GROW = 12
# Margins painted around the stage (left/right, top/bottom), in stage pixels.
EXT_X, EXT_Y = 180, 120


def formation_mask(ref):
    """Blocks plus every lit/coloured pixel of the explosion in the formation area.

    The dark arch between the blocks is kept, so the fill continues the dark stone
    instead of inventing bright brick.
    """
    shape = ref.shape[:2]
    hsv = cv2.cvtColor(ref, cv2.COLOR_RGB2HSV)
    lit = ((hsv[..., 2] > 105) | ((hsv[..., 1] > 150) & (hsv[..., 2] > 70))).astype(np.uint8) * 255
    box = np.zeros(shape, np.uint8)
    cv2.rectangle(box, layout.FORMATION_BOX[:2], layout.FORMATION_BOX[2:], 255, -1)
    m = cv2.dilate(cv2.bitwise_and(lit, box), np.ones((9, 9), np.uint8))
    m = cv2.bitwise_and(m, cv2.dilate(box, np.ones((9, 9), np.uint8)))
    for b in layout.STRAY_SHARDS:
        cv2.rectangle(m, b[:2], b[2:], 255, -1)
    for b in layout.KEEP_RECTS:
        cv2.rectangle(m, b[:2], b[2:], 0, -1)
    for b in layout.KEEP_EXCEPT:
        cv2.rectangle(m, b[:2], b[2:], 255, -1)
    for _, l, t, r, b, *_ in layout.BLOCKS:
        cv2.rectangle(m, (l - 4, t - 4), (r + 4, b + 4), 255, -1)
    return m


def gold_text_mask(ref):
    """Letter-shaped mask of the gold HUD text (fill, red rim), grown over its outline."""
    hsv = cv2.cvtColor(ref, cv2.COLOR_RGB2HSV)
    fill = ((hsv[..., 0] >= 14) & (hsv[..., 0] <= 38) & (hsv[..., 1] > 90) & (hsv[..., 2] > 205)).astype(np.uint8)
    rim = (((hsv[..., 0] <= 10) | (hsv[..., 0] >= 170)) & (hsv[..., 1] > 140) & (hsv[..., 2] > 140)).astype(np.uint8)
    m = np.zeros(ref.shape[:2], np.uint8)
    # The chunky dark outline and drop shadow hug the letters: grab dark pixels next to them.
    dark = (hsv[..., 2] < 90).astype(np.uint8)
    for l, t, r, b in layout.GOLD_TEXT_BOXES:
        core = (fill | rim)[t:b, l:r]
        near = cv2.dilate(core, np.ones((25, 25), np.uint8))
        m[t:b, l:r] = core | (dark[t:b, l:r] & near)
    m = cv2.morphologyEx(m, cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8))
    k = 2 * GOLD_TEXT_GROW + 1
    m = cv2.dilate(m * 255, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k)))
    cv2.circle(m, layout.COIN_CENTER, layout.COIN_R, 0, -1)  # keep the coin icon
    return m


def detail_mask(shape, ref):
    m = np.zeros(shape, np.uint8)
    for b in (layout.TIMER_DIGITS, layout.SCORE_DIGITS, layout.GOAL_TEXT_AREA):
        cv2.rectangle(m, b[:2], b[2:], 255, -1)
    m = np.maximum(m, gold_text_mask(ref))
    cv2.circle(m, layout.BALL_CENTER, layout.BALL_CLEAR_R, 255, -1)
    cv2.rectangle(m, layout.AIM_STRIP[:2], layout.AIM_STRIP[2:], 255, -1)
    return m


def extend(plate):
    """Paint margins around the plate (LaMa outpainting at half resolution)."""
    h, w = plate.shape[:2]
    padded = cv2.copyMakeBorder(plate, EXT_Y, EXT_Y, EXT_X, EXT_X, cv2.BORDER_REFLECT_101)
    m = np.full(padded.shape[:2], 255, np.uint8)
    m[EXT_Y:EXT_Y + h, EXT_X:EXT_X + w] = 0
    ph, pw = padded.shape[:2]
    small = cv2.resize(padded, (pw // 2, ph // 2), interpolation=cv2.INTER_AREA)
    ms = cv2.resize(m, (pw // 2, ph // 2), interpolation=cv2.INTER_NEAREST)
    up = cv2.resize(lama.inpaint(small, ms), (pw, ph), interpolation=cv2.INTER_CUBIC)
    # Refine a band next to the real pixels at full resolution, keep them untouched.
    seed = padded.copy()
    seed[m > 0] = up[m > 0]
    inner = cv2.erode(m, np.ones((49, 49), np.uint8))
    out = lama.inpaint(seed, cv2.subtract(m, inner))
    out[EXT_Y:EXT_Y + h, EXT_X:EXT_X + w] = plate
    return out


def build(work):
    os.makedirs(work, exist_ok=True)
    ref = np.array(Image.open(REF).convert("RGB"))
    h, w = ref.shape[:2]

    # The formation hole is large, so fill it at half resolution first (LaMa keeps the
    # arch and stair courses coherent at that scale), then refine a border band.
    form = formation_mask(ref)
    small = cv2.resize(ref, (w // 2, h // 2), interpolation=cv2.INTER_AREA)
    ms = cv2.resize(form, (w // 2, h // 2), interpolation=cv2.INTER_NEAREST)
    up = cv2.resize(lama.inpaint(small, ms), (w, h), interpolation=cv2.INTER_CUBIC)
    seed = ref.copy()
    seed[form > 0] = up[form > 0]
    inner = cv2.erode(form, np.ones((41, 41), np.uint8))
    plate = lama.inpaint(seed, cv2.subtract(form, inner))

    # Calm LaMa's colour speckle deep inside the hole without touching real pixels.
    smooth = cv2.bilateralFilter(plate, 9, 18, 7)
    soft = cv2.GaussianBlur(inner.astype(np.float32) / 255.0, (0, 0), 6)[..., None]
    plate = (plate * (1 - soft) + smooth * soft).round().astype(np.uint8)

    det = detail_mask((h, w), ref)
    filled = lama.inpaint(plate, det)
    a = cv2.GaussianBlur(det.astype(np.float32) / 255.0, (0, 0), 2)[..., None]
    a = np.maximum(a, (det > 0)[..., None].astype(np.float32))
    plate = (filled * a + plate * (1 - a)).round().astype(np.uint8)

    Image.fromarray(plate).save(os.path.join(work, "plate.png"))
    Image.fromarray(extend(plate)).save(os.path.join(work, "plate_ext.png"))
    Image.fromarray(form).save(os.path.join(work, "mask_formation.png"))
    Image.fromarray(det).save(os.path.join(work, "mask_detail.png"))
    return plate


if __name__ == "__main__":
    build(sys.argv[1] if len(sys.argv) > 1 else "/tmp/level5_fs")
