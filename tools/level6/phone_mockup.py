"""Puts a 1080x2400 game screenshot into a phone mockup (rounded body, punch-hole camera)."""
import sys

from PIL import Image, ImageDraw, ImageFilter


def mockup(src, dst, scale=0.5):
    screen = Image.open(src).convert("RGB")
    W, H = screen.size
    bez, rad = 34, 120
    body = Image.new("RGBA", (W + 2 * bez + 160, H + 2 * bez + 160), (0, 0, 0, 0))
    ox, oy = 80, 80
    shadow = Image.new("L", body.size, 0)
    ImageDraw.Draw(shadow).rounded_rectangle((ox + 10, oy + 24, ox + W + 2 * bez + 10, oy + H + 2 * bez + 24), rad + bez, fill=170)
    shadow = shadow.filter(ImageFilter.GaussianBlur(28))
    body.paste((0, 0, 0, 255), (0, 0), shadow)
    d = ImageDraw.Draw(body)
    d.rounded_rectangle((ox, oy, ox + W + 2 * bez, oy + H + 2 * bez), rad + bez, fill=(22, 24, 30, 255))
    d.rounded_rectangle((ox + 4, oy + 4, ox + W + 2 * bez - 4, oy + H + 2 * bez - 4), rad + bez - 4, outline=(70, 74, 86, 255), width=3)
    mask = Image.new("L", (W, H), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, W - 1, H - 1), rad, fill=255)
    body.paste(screen, (ox + bez, oy + bez), mask)
    cx, cy, r = ox + bez + W // 2, oy + bez + 52, 22
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(8, 8, 10, 255))
    d.ellipse((cx - 8, cy - 8, cx + 2, cy + 2), fill=(40, 46, 70, 255))
    body = body.resize((int(body.width * scale), int(body.height * scale)), Image.LANCZOS)
    bg = Image.new("RGB", body.size, (244, 244, 247))
    bg.paste(body, (0, 0), body)
    bg.save(dst, optimize=True)


if __name__ == "__main__":
    mockup(sys.argv[1], sys.argv[2])
