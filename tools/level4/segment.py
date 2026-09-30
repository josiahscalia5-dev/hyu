"""Cuts every moving object of the Level 4 reference out as a mask (Segment Anything).

Writes tools/level4/masks/<id>.png, each the size of the object's layout box, so the
art build (build.py) does not need the model. Re-run only when layout boxes change.

  pip install segment-anything torchvision
  export SAM_MODEL=/path/to/sam_vit_h_4b8939.pth   # from github.com/facebookresearch/segment-anything
  python3 tools/level4/segment.py
"""
import os
import sys

import cv2
import numpy as np
import torch
from PIL import Image
from segment_anything import SamPredictor, sam_model_registry

sys.path.insert(0, os.path.dirname(__file__))
import layout  # noqa: E402

HERE = os.path.dirname(__file__)
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
MASKS = os.path.join(HERE, "masks")


def objects():
    for oid, _, box in layout.TARGETS:
        yield oid, box
    yield "launcher", layout.LAUNCHER


def main():
    ref = np.array(Image.open(os.path.join(REPO, "design", layout.REFERENCE)).convert("RGB"))
    sam = sam_model_registry["vit_h"](checkpoint=os.environ.get("SAM_MODEL", "/tmp/sam_vit_h.pth"))
    predictor = SamPredictor(sam)
    with torch.inference_mode():
        predictor.set_image(ref)
        os.makedirs(MASKS, exist_ok=True)
        for oid, (l, t, r, b) in objects():
            masks, scores, _ = predictor.predict(box=np.array([l, t, r, b]), multimask_output=True)
            # Prefer the most confident mask that covers a sensible share of the box.
            area = masks.reshape(3, -1).mean(axis=1) * ref.shape[0] * ref.shape[1] / ((r - l) * (b - t))
            order = np.argsort(-scores)
            pick = next((i for i in order if 0.25 < area[i] < 0.98), order[0])
            m = masks[pick][t:b, l:r].astype(np.uint8)
            # Keep the component(s) that make up the object, fill pinholes.
            n, lab, st, _ = cv2.connectedComponentsWithStats(m, 8)
            if n > 2:
                big = 1 + np.argmax(st[1:, cv2.CC_STAT_AREA])
                keep = [i for i in range(1, n) if st[i, cv2.CC_STAT_AREA] >= 0.05 * st[big, cv2.CC_STAT_AREA]]
                m = np.isin(lab, keep).astype(np.uint8)
            m = cv2.morphologyEx(m, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8))
            Image.fromarray(m * 255).save(os.path.join(MASKS, f"{oid}.png"), optimize=True)
            print(f"{oid:14s} score {scores[pick]:.3f} fill {m.mean():.2f}")


if __name__ == "__main__":
    main()
