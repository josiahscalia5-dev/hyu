import numpy as np
from PIL import Image
import layout6 as L, cutout as C


def matte(name, rgb, box):
    kind = L.SPRITES[name][1]
    if kind == "disc":
        return C.disc_matte(rgb)
    if kind == "gold":
        return C.gold_matte(rgb)
    if kind == "foliage":
        return C.foliage_matte(rgb)
    if kind == "jetski":
        g = C.grabcut_matte(rgb) > 0.5
        hull = C.polygon_matte(rgb.shape[:2], L.JETSKI_HULL, box[:2])
        return C.soften(C.fill_holes(g | hull))
    exclude = C.red_glow(rgb) | (C.gold(rgb) if name == "crate_x" else False)
    return C.object_matte(rgb, exclude)


if __name__ == "__main__":
    ref = np.array(Image.open('/home/user/hyu/design/' + L.REFERENCE).convert('RGB'))
    S = '/tmp/claude-0/-home-user-hyu/71d2b697-13c8-5c8b-9f5b-392f0dfc1b2c/scratchpad/l6/'
    tiles = []
    for n, (b, k) in L.SPRITES.items():
        l, t, r, bb = b
        rgb = ref[t:bb, l:r]
        a = matte(n, rgb, b)
        bg = np.zeros_like(rgb); bg[...] = (255, 0, 255)
        tiles.append((rgb * a[..., None] + bg * (1 - a[..., None])).astype(np.uint8))
    W = sum(t.shape[1] for t in tiles) + 5 * len(tiles); H = max(t.shape[0] for t in tiles)
    o = Image.new('RGB', (W, H), (40, 40, 40)); x = 0
    for c in tiles:
        o.paste(Image.fromarray(c), (x, 0)); x += c.shape[1] + 5
    o.save(S + 'mattes.png')
