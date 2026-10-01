"""Quick compositor for checking the Temple Chase art (mirrors the app's draw order).

    python3 tools/temple/preview.py OUT.png [scroll ...]
"""
import json
import os
import sys

import cv2
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
A = os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "temple")
spec = json.load(open(os.path.join(A, "temple.json")))
P = spec["perspective"]
H, PY, VX, LW, D, CB = P["horizon"], P["playerY"], P["vanishX"], P["laneW"], P["depth"], P["camBias"]
G = spec["ground"]
W, HH = spec["stage"]


def rgba(name):
    return np.array(Image.open(os.path.join(A, name)).convert("RGBA")).astype(np.float32) / 255


def over(dst, src, x, y, w, h, alpha=1.0):
    if w < 2 or h < 2:
        return
    s = cv2.resize(src, (int(round(w)), int(round(h))), interpolation=cv2.INTER_LINEAR)
    x0, y0 = int(round(x)), int(round(y))
    x1, y1 = x0 + s.shape[1], y0 + s.shape[0]
    cx0, cy0, cx1, cy1 = max(0, x0), max(0, y0), min(dst.shape[1], x1), min(dst.shape[0], y1)
    if cx0 >= cx1 or cy0 >= cy1:
        return
    part = s[cy0 - y0:cy1 - y0, cx0 - x0:cx1 - x0]
    a = part[..., 3:4] * alpha
    dst[cy0:cy1, cx0:cx1] = dst[cy0:cy1, cx0:cx1] * (1 - a) + part[..., :3] * a


def frame(scroll, cam=0.0):
    out = np.zeros((int(HH), int(W), 3), np.float32)
    ys, xs = np.mgrid[0:int(HH), 0:int(W)].astype(np.float32)
    s = np.maximum((ys + 0.5 - H) / (PY - H), 1e-3)
    z = D / s - D
    X = (xs + 0.5 - VX) / (LW * s) + CB + cam
    # lava
    lava = np.array(Image.open(os.path.join(A, "lava.jpg")).convert("RGB")).astype(np.float32) / 255
    lu = ((X / G["lavaX"]) % 1.0) * lava.shape[1]
    lv = (((z + scroll) / G["lavaZ"]) % 1.0) * lava.shape[0]
    out[:] = cv2.remap(lava, lu.astype(np.float32), lv.astype(np.float32), cv2.INTER_LINEAR, borderMode=cv2.BORDER_WRAP)
    # path
    path = rgba("path.png")
    K, half, period, z0 = G["pathK"], G["pathHalf"], G["pathPeriod"], G["z0"]
    pu = (X + half) * K
    pv = ((z + scroll - z0) * K) % path.shape[0]
    pp = cv2.remap(path, pu.astype(np.float32), pv.astype(np.float32), cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=0)
    a = pp[..., 3:4]
    out[:] = out * (1 - a) + pp[..., :3] * a
    intro = max(0.0, 1.0 - scroll / 2.5)
    if intro > 0:
        gi = rgba("ground_intro.png")
        Ki = G["introK"]
        u = (X + G["introHalf"]) * Ki
        vi = (z + scroll - z0) * Ki
        ok = (vi >= 0) & (vi < gi.shape[0] - 1)
        gg = cv2.remap(gi, u.astype(np.float32), np.clip(vi, 0, gi.shape[0] - 1).astype(np.float32), cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=0)
        a = gg[..., 3:4] * ok[..., None] * intro
        out[:] = out * (1 - a) + gg[..., :3] * a
    bd = rgba("backdrop.png")
    mx, my = spec["backdrop"]["margin"]
    over(out, bd, -mx, -my, bd.shape[1], bd.shape[0])
    # props, far to near; the painted set repeats every MODULE units per side
    items = []
    for name, p in spec["props"].items():
        period = 7.0 if p["side"] < 0 else 6.3
        for k in range(-1, 4):
            zz = p["z"] + k * period - scroll
            if -1.5 < zz < 14:
                items.append((zz, name, p))
    for zz, name, p in sorted(items, key=lambda t: -t[0]):
        img = rgba(f"prop_{name}.png")
        ss = D / (D + zz)
        w, h = p["w"] * LW * ss, p["h"] * LW * ss
        cx = VX + (p["x"] - CB - cam) * LW * ss
        by = H + (PY - H) * ss
        al = float(np.clip((14 - zz) / 3.0, 0, 1))
        over(out, img, cx - w / 2, by - h, w, h, al)
    r = rgba("runner.png")
    b = spec["sprites"]["runner"]["box"]
    over(out, r, b[0], b[1], b[2] - b[0], b[3] - b[1])
    return (np.clip(out, 0, 1) * 255).astype(np.uint8)


if __name__ == "__main__":
    dst = sys.argv[1]
    scrolls = [float(a) for a in sys.argv[2:]] or [0.0]
    frames = [frame(s) for s in scrolls]
    im = np.concatenate(frames, axis=1)
    Image.fromarray(im).save(dst)
