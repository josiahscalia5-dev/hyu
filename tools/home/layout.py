"""Home screen layout, measured from design/home_reference_upload.png (1080x1920).

The upload is a phone capture: the approved screen sits at ART inside it (blurred
bands above and below, a white strip and rounded corner on the right). Every other
box here is (left, top, right, bottom) in ART pixels ("stage units").
"""

UPLOAD = "home_reference_upload.png"
ART = (0, 97, 1062, 1822)
ART_W, ART_H = ART[2] - ART[0], ART[3] - ART[1]


def a(l, t, r, b):
    """Upload coordinates to art coordinates."""
    return (l - ART[0], t - ART[1], r - ART[0], b - ART[1])


# Top bar: profile badge, coins, gems, settings. Pinned to the top of the screen.
TOP_ITEMS = {
    "avatar": a(18, 116, 154, 252),
    "level": a(138, 132, 292, 218),
    "coins": a(312, 126, 620, 226),
    "gems": a(642, 132, 902, 222),
    "settings": a(924, 118, 1046, 240),
}
TOP_BAR = a(0, 108, 1062, 262)

# The big green PLAY button; drawn on its own so it can be pressed.
PLAY = a(180, 1314, 940, 1558)

# Bottom navigation: blue bar and four tiles, pinned to the bottom of the screen.
NAV = a(0, 1576, 1062, 1822)
NAV_TILES = {
    "levels": a(44, 1582, 280, 1822),
    "minigames": a(300, 1582, 536, 1822),
    "shop": a(556, 1582, 790, 1822),
    "characters": a(810, 1582, 1040, 1822),
}

# Painted into the scene; the World map's backdrop paints them out.
LOGO = a(10, 286, 932, 760)
CHARACTER = a(188, 726, 728, 1336)

# What must always be on screen, uncropped: the logo and the PLAY button.
HERO = (min(LOGO[0], PLAY[0]) + 150, LOGO[1], max(LOGO[2], PLAY[2]), PLAY[3])
