"""Cuts the home screen's interactive parts out as masks (Segment Anything).

Writes tools/home/masks/<id>.png at full art size, so build.py does not need the
model. Re-run only when layout boxes change.

  pip install segment-anything torchvision
  export SAM_MODEL=/path/to/sam_vit_h_4b8939.pth
  python3 tools/home/segment.py
"""
import os
import sys

import numpy as np
import torch
from PIL import Image
from segment_anything import SamPredictor, sam_model_registry

sys.path.insert(0, os.path.dirname(__file__))
import layout  # noqa: E402

HERE = os.path.dirname(__file__)
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
MASKS = os.path.join(HERE, "masks")


def art():
    im = Image.open(os.path.join(REPO, "design", layout.UPLOAD)).convert("RGB")
    return np.array(im.crop(layout.ART))


def objects():
    for k, box in layout.TOP_ITEMS.items():
        yield k, box
    yield "play", layout.PLAY
    for k, box in layout.NAV_TILES.items():
        yield "nav_" + k, box
    yield "logo", layout.LOGO
    yield "character", layout.CHARACTER


def main():
    ref = art()
    sam = sam_model_registry["vit_h"](checkpoint=os.environ.get("SAM_MODEL", "/tmp/sam_vit_h.pth"))
    predictor = SamPredictor(sam)
    os.makedirs(MASKS, exist_ok=True)
    with torch.inference_mode():
        predictor.set_image(ref)
        for oid, (l, t, r, b) in objects():
            masks, scores, _ = predictor.predict(box=np.array([l, t, r, b]), multimask_output=True)
            # The largest confident mask: the whole badge/button, not one detail of it.
            order = sorted(range(len(scores)), key=lambda i: (scores[i] > 0.8, masks[i].sum()), reverse=True)
            m = masks[order[0]]
            Image.fromarray(m.astype(np.uint8) * 255).save(os.path.join(MASKS, oid + ".png"), optimize=True)
            print(oid, [round(float(s), 3) for s in scores], int(m.sum()))


if __name__ == "__main__":
    main()
