"""Mattes for Level 6 sprites cut out of the painted river."""
import cv2
import numpy as np


def water_like(rgb):
    """Water pixels: blue/cyan dominant, or white-blue foam."""
    hsv = cv2.cvtColor(rgb, cv2.COLOR_RGB2HSV).astype(np.int16)
    r, g, b = (rgb[..., i].astype(np.int16) for i in range(3))
    blue = (hsv[..., 0] >= 85) & (hsv[..., 0] <= 125) & (hsv[..., 1] > 60) & (b > r + 40) & (hsv[..., 2] > 95)
    foam = ((hsv[..., 1] < 70) & (hsv[..., 2] > 170) & (b >= r - 5)) | \
           ((hsv[..., 1] < 50) & (hsv[..., 2] > 135) & (b > r + 8))
    return blue | foam


def largest(mask):
    n, lab, st, _ = cv2.connectedComponentsWithStats(mask.astype(np.uint8), 8)
    if n < 2:
        return mask.astype(bool)
    return lab == 1 + int(np.argmax(st[1:, 4]))


def fill_holes(m):
    m = m.astype(np.uint8) * 255
    ff = m.copy()
    cv2.floodFill(ff, np.zeros((m.shape[0] + 2, m.shape[1] + 2), np.uint8), (0, 0), 255)
    return (m | cv2.bitwise_not(ff)) > 0


def object_matte(rgb, exclude=None):
    m = ~water_like(rgb)
    if exclude is not None:
        m &= ~exclude
    m = cv2.morphologyEx(m.astype(np.uint8), cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
    m = cv2.morphologyEx(m, cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8))
    m = fill_holes(largest(m))
    return soften(m)


def disc_matte(rgb, pad=2):
    h, w = rgb.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w]
    r = min(h, w) / 2.0 - pad
    d = np.hypot(xx - (w - 1) / 2.0, yy - (h - 1) / 2.0)
    return np.clip((r - d) + 0.5, 0, 1)


def foliage_matte(rgb):
    hsv = cv2.cvtColor(rgb, cv2.COLOR_RGB2HSV)
    green = (hsv[..., 0] >= 28) & (hsv[..., 0] <= 85) & (hsv[..., 1] > 70) & (hsv[..., 2] > 25)
    m = cv2.morphologyEx(green.astype(np.uint8), cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
    m = cv2.morphologyEx(m, cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8))
    return soften(largest(m))


def gold_matte(rgb):
    hsv = cv2.cvtColor(rgb, cv2.COLOR_RGB2HSV)
    gold = (hsv[..., 0] >= 10) & (hsv[..., 0] <= 40) & (hsv[..., 1] > 90) & (hsv[..., 2] > 110)
    m = cv2.morphologyEx(gold.astype(np.uint8), cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8))
    return soften(fill_holes(largest(m)))


def red_glow(rgb):
    hsv = cv2.cvtColor(rgb, cv2.COLOR_RGB2HSV)
    return ((hsv[..., 0] >= 150) | (hsv[..., 0] <= 6)) & (hsv[..., 1] > 60) & (hsv[..., 2] > 150)


def gold(rgb):
    hsv = cv2.cvtColor(rgb, cv2.COLOR_RGB2HSV)
    return (hsv[..., 0] >= 20) & (hsv[..., 0] <= 40) & (hsv[..., 1] > 150) & (hsv[..., 2] > 190)


def polygon_matte(shape, pts, offset):
    m = np.zeros(shape, np.uint8)
    p = np.array([(x - offset[0], y - offset[1]) for x, y in pts], np.int32)
    cv2.fillPoly(m, [p], 1, lineType=cv2.LINE_8)
    return m > 0


def grabcut_matte(rgb, iters=8):
    """GrabCut seeded by colour: confident water is background, warm/dark pixels are foreground."""
    h, w = rgb.shape[:2]
    mask = np.full((h, w), cv2.GC_PR_BGD, np.uint8)
    hsv = cv2.cvtColor(rgb, cv2.COLOR_RGB2HSV)
    warm = ((hsv[..., 0] <= 22) | (hsv[..., 0] >= 160)) & (hsv[..., 1] > 90)
    dark = hsv[..., 2] < 70
    mask[~water_like(rgb)] = cv2.GC_PR_FGD
    mask[cv2.erode((warm | dark).astype(np.uint8), np.ones((5, 5), np.uint8)) > 0] = cv2.GC_FGD
    border = np.zeros((h, w), bool)
    border[:3], border[-3:], border[:, :3], border[:, -3:] = True, True, True, True
    mask[border & water_like(rgb)] = cv2.GC_BGD
    bgd, fgd = np.zeros((1, 65), np.float64), np.zeros((1, 65), np.float64)
    cv2.grabCut(np.ascontiguousarray(rgb[..., ::-1]), mask, None, bgd, fgd, iters, cv2.GC_INIT_WITH_MASK)
    m = (mask == cv2.GC_FGD) | (mask == cv2.GC_PR_FGD)
    m = cv2.morphologyEx(m.astype(np.uint8), cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
    return soften(fill_holes(largest(m)))


def soften(m, sigma=0.8):
    a = cv2.GaussianBlur(m.astype(np.float32), (0, 0), sigma)
    return np.clip(np.maximum(a, cv2.erode(m.astype(np.uint8), np.ones((3, 3), np.uint8))), 0, 1)
