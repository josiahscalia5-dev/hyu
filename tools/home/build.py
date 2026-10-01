"""Builds the home screen and World 1 map art from design/home_reference_upload.png.

Outputs (app/src/main/assets/home/):
  * background_ext.png: the scene with the top bar, PLAY button and bottom nav painted
    out (Big-LaMa), painted past every edge so any phone shape is filled. Logo and
    character stay painted in it.
  * topbar.png, play.png, nav.png: those three layers on their own, with alpha.
  * logo_mask.png: the logo's shape, for the light that sweeps across it.
  * home.json: where everything sits, in art pixels.

  pip install -r tools/assets/requirements.txt
  export LAMA_MODEL=/path/to/big-lama.pt
  python3 tools/home/segment.py      # once: masks/ (Segment Anything)
  python3 tools/home/build.py /tmp/home_work
"""
import json
import os
import sys

import cv2
import numpy as np
from PIL import Image

HERE = os.path.dirname(__file__)
sys.path.insert(0, HERE)
sys.path.append(os.path.join(HERE, "..", "assets"))   # lama.py (shared with Levels 4 and 5)
import layout  # noqa: E402
import lama  # noqa: E402

REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
OUT = os.path.join(REPO, "app", "src", "main", "assets", "home")
W, H = layout.ART_W, layout.ART_H
# How far the scene is painted past each edge: tall phones show more sky and beach.
EXT_X, EXT_TOP, EXT_BOTTOM = 96, 420, 360
NAV_BAR_TOP = 1516


def art():
    return np.array(Image.open(os.path.join(REPO, "design", layout.UPLOAD)).convert("RGB").crop(layout.ART))


def mask(name):
    return np.array(Image.open(os.path.join(HERE, "masks", name + ".png"))) > 0


def dilate(m, r):
    k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * r + 1, 2 * r + 1))
    return cv2.dilate(m.astype(np.uint8), k) > 0


def soft(m):
    """Anti-aliased alpha (0..255) from a bool mask."""
    m8 = m.astype(np.uint8) * 255
    s = cv2.GaussianBlur(m8.astype(np.float32), (0, 0), 0.8)
    return np.clip(np.maximum(s, cv2.erode(m8, np.ones((3, 3), np.uint8))), 0, 255)


def save_rgba(path, rgb, a):
    Image.fromarray(np.dstack([rgb, a.astype(np.uint8)])).save(path, optimize=True)


def fix_edges(ref):
    """The capture's white right edge and rounded top-right corner, filled locally."""
    m = np.zeros((H, W), np.uint8)
    m[:, W - 5:] = 255
    corner = np.zeros((H, W), bool)
    corner[:70, W - 80:] = True
    m[corner & (ref.min(axis=2) > 236)] = 255
    m = cv2.dilate(m, np.ones((5, 5), np.uint8))
    return cv2.inpaint(ref, m, 9, cv2.INPAINT_TELEA)


def layer_masks():
    top = np.zeros((H, W), bool)
    for k in layout.TOP_ITEMS:
        top |= mask(k)
    play = mask("play")
    nav = np.zeros((H, W), bool)
    for k in layout.NAV_TILES:
        nav |= mask("nav_" + k)
    nav[NAV_BAR_TOP:, :] = True
    # Fill pinholes (icon highlights SAM left out).
    for m in (top, play, nav):
        filled = m.astype(np.uint8)
        cnts, _ = cv2.findContours(filled, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
        cv2.drawContours(filled, cnts, -1, 1, cv2.FILLED)
        m |= filled > 0
    return top, play, nav


def extend(plate):
    """Paint the scene past every edge so any phone shape is covered.

    Above: more of the same sky, deepening a little with height. Below and beside: a
    softened mirror of the edge (the pinned nav bar covers the bottom; the sides only
    show on tablets).
    """
    padded = cv2.copyMakeBorder(plate, EXT_TOP, EXT_BOTTOM, EXT_X, EXT_X, cv2.BORDER_REFLECT_101).astype(np.float32)
    soft_mirror = cv2.GaussianBlur(padded, (0, 0), 6)
    out = soft_mirror.copy()
    # Sky: the art's top rows, smoothed wide (so clouds do not streak upward), deepening
    # gently with height; the plate's own top rows melt into it over a long seam.
    top = padded[EXT_TOP:EXT_TOP + 40].mean(axis=0, keepdims=True)
    top = cv2.GaussianBlur(top, (0, 0), 140)[0]
    deep = np.array([0, 92, 226], np.float32)
    for y in range(EXT_TOP):
        k = (EXT_TOP - y) / EXT_TOP
        out[y] = top * (1 - 0.3 * k) + deep * (0.3 * k)
    seam = 90
    for i in range(seam):
        k = (i / seam) ** 1.5
        y = EXT_TOP + i
        out[y] = padded[y] * k + top * (1 - k)
    # Real pixels inside the art below the seam.
    out[EXT_TOP + seam:EXT_TOP + H, EXT_X:EXT_X + W] = plate[seam:]
    return out.round().clip(0, 255).astype(np.uint8)


def crop_layer(ref, m, box, name):
    l, t, r, b = box
    save_rgba(os.path.join(OUT, name), ref[t:b, l:r], soft(m)[t:b, l:r])


def bbox(m):
    ys, xs = np.nonzero(m)
    return [int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1]


def build(work):
    os.makedirs(work, exist_ok=True)
    os.makedirs(OUT, exist_ok=True)
    ref = fix_edges(art())
    top, play, nav = layer_masks()

    # The scene behind the three layers (the logo and character stay painted). LaMa
    # ghosts tightly object-shaped holes, so each hole is grown well past its object.
    plate = lama.inpaint(ref, (dilate(top, 25) | dilate(play, 20)).astype(np.uint8) * 255)
    plate = lama.inpaint(plate, dilate(nav, 12).astype(np.uint8) * 255)
    Image.fromarray(plate).save(os.path.join(work, "plate.png"))
    ext = extend(plate)
    Image.fromarray(ext).save(os.path.join(OUT, "background_ext.png"), optimize=True)

    crop_layer(ref, top, layout.TOP_BAR, "topbar.png")
    crop_layer(ref, play, layout.PLAY, "play.png")
    nav_box = (layout.NAV[0], layout.NAV[1], layout.NAV[2], layout.NAV[3])
    crop_layer(ref, nav, nav_box, "nav.png")
    logo = mask("logo")
    lb = bbox(logo)
    a = soft(logo)[lb[1]:lb[3], lb[0]:lb[2]]
    save_rgba(os.path.join(OUT, "logo_mask.png"), np.full(a.shape + (3,), 255, np.uint8), a)

    spec = {
        "art": [W, H],
        "backgroundExt": {"left": EXT_X, "top": EXT_TOP, "right": EXT_X, "bottom": EXT_BOTTOM},
        "topBar": {"box": list(layout.TOP_BAR), "items": {k: list(v) for k, v in layout.TOP_ITEMS.items()}},
        "play": {"box": list(layout.PLAY), "shape": bbox(play)},
        "nav": {"box": list(nav_box), "tiles": {k: bbox(mask("nav_" + k)) for k in layout.NAV_TILES},
                "barTop": NAV_BAR_TOP},
        "logo": {"box": lb},
        "character": {"box": bbox(mask("character"))},
        "hero": list(layout.HERO),
    }
    with open(os.path.join(OUT, "home.json"), "w") as f:
        json.dump(spec, f, indent=1)
    print(json.dumps(spec))


if __name__ == "__main__":
    build(sys.argv[1] if len(sys.argv) > 1 else "/tmp/home_work")
