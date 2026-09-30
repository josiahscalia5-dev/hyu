"""Level 5 layout measured from design/level5_reference.png (1024x1536 stage units).

Every rectangle here is (left, top, right, bottom) in reference pixels. The Android
app reads the generated level5.json, so this file is the single source of truth
for where things sit on screen.
"""

STAGE_W, STAGE_H = 1024, 1536

# Block kinds: colour + engraved symbol, as painted in the reference.
# (id, left, top, right, bottom, colour, symbol)
BLOCKS = [
    ("b01", 387, 261, 469, 327, "violet", "sparkle"),
    ("b02", 470, 261, 548, 327, "violet", "heart"),
    ("b03", 387, 327, 469, 392, "orchid", "heart"),
    ("b04", 470, 327, 549, 392, "orchid", "heart"),
    ("b05", 550, 327, 657, 393, "yellow", "star"),
    ("b06", 260, 392, 384, 461, "magenta", "heart"),
    ("b07", 385, 392, 469, 461, "red", "heart"),
    ("b08", 470, 392, 549, 461, "pink", "heart"),
    ("b09", 550, 393, 660, 462, "red", "heart"),
    ("b10", 257, 461, 390, 573, "red", "heart"),
    ("b11", 385, 461, 469, 541, "violet", "heart"),
    ("b12", 470, 461, 549, 541, "violet", "heart"),
    ("b13", 550, 462, 641, 553, "yellow", "star"),
    ("b14", 641, 462, 735, 555, "yellow", "star"),
    ("b15", 391, 540, 469, 600, "cyan", "sparkle"),
    ("b16", 470, 540, 549, 600, "cyan", "sparkle"),
    ("b17", 550, 553, 622, 600, "cyan", "sparkle"),
    ("b18", 390, 600, 469, 679, "cyan", "sparkle"),
    ("b19", 470, 600, 549, 679, "cyan", "sparkle"),
    ("b20", 550, 600, 622, 679, "cyan", "sparkle"),
    ("b21", 192, 541, 307, 606, "violet", "star"),
    ("b22", 192, 606, 307, 673, "violet", "heart"),
    ("b23", 191, 674, 287, 764, "yellow", "star"),
    ("b24", 287, 674, 400, 764, "yellow", "star"),
    ("b25", 191, 765, 307, 860, "yellow", "star"),
    ("b26", 698, 542, 812, 606, "yellow", "star"),
    ("b27", 698, 606, 812, 672, "yellow", "star"),
    ("b28", 600, 674, 720, 765, "magenta", "heart"),
    ("b29", 720, 674, 816, 765, "violet", "sparkle"),
    ("b30", 639, 764, 727, 862, "cyan", "sparkle"),
    ("b31", 727, 764, 818, 862, "cyan", "sparkle"),
]

# Shards and light streaks from the painted explosion that sit on top of blocks.
# These are inpainted off the block art so a surviving block does not keep a
# frozen shard on it; the frame-0 effect layer puts them back for the opening frame.
# ("rect", l, t, r, b) or ("line", x0, y0, x1, y1, thickness)
BLOCK_TOUCHUPS = [
    ("rect", 500, 666, 520, 679),   # b19 crystal tip
    ("rect", 500, 583, 516, 599),   # b16 glint
    ("rect", 398, 589, 416, 600),   # b15 glint
    ("rect", 584, 589, 622, 600),   # b17 streak
    ("rect", 332, 552, 351, 574),   # b10 spark streak
    ("rect", 714, 459, 736, 479),   # b14 corner gem
    ("rect", 290, 603, 308, 652),   # b22 side face gem
    ("line", 597, 741, 680, 694, 13),  # b28 light ray
    ("rect", 695, 580, 714, 636),   # b26/b27 left face: blue gem edge
    ("rect", 606, 747, 632, 765),   # b28 corner sparkle
    ("rect", 637, 816, 670, 840),   # b30 light ray
]

# Copy a clean region over a spot hidden by a neighbour: (dest rect, source top-left).
BLOCK_PATCHES = [
    ((279, 540, 308, 552), (279, 605)),  # b21 top-right corner shows b10's red base
]

# Blocks whose painted face is buried under shards: copy a clean twin instead.
# target -> donor (donor art is resized to the target rect).
BLOCK_DONORS = {"b18": "b19", "b20": "b19"}
