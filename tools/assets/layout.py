"""Level 5 layout, measured from design/level5_fullscreen_reference.png (840x1871).

Every rectangle is (left, top, right, bottom) in reference pixels ("stage units").
build_sprites.py writes the parts the app needs into level5.json, so this file is
the single source of truth for where things sit on screen.
"""

REFERENCE = "level5_fullscreen_reference.png"
STAGE_W, STAGE_H = 840, 1871

# (id, left, top, right, bottom, colour as painted, symbol)
BLOCKS = [
    ("b01", 334, 518, 393, 568, "violet", "sparkle"),
    ("b02", 394, 518, 452, 568, "violet", "sparkle"),
    ("b03", 334, 569, 393, 622, "violet", "heart"),
    ("b04", 394, 569, 452, 622, "violet", "heart"),
    ("b05", 454, 568, 536, 623, "yellow", "star"),
    ("b06", 247, 622, 333, 676, "magenta", "heart"),
    ("b07", 333, 622, 392, 676, "red", "heart"),
    ("b08", 393, 622, 452, 676, "red", "heart"),
    ("b09", 452, 623, 536, 676, "red", "heart"),
    ("b10", 244, 677, 334, 754, "red", "heart"),
    ("b11", 334, 677, 393, 738, "violet", "sparkle"),
    ("b12", 393, 677, 452, 738, "violet", "sparkle"),
    ("b13", 452, 677, 522, 745, "yellow", "star"),
    ("b14", 522, 677, 593, 750, "yellow", "star"),
    ("b15", 333, 738, 393, 783, "cyan", "sparkle"),
    ("b16", 393, 738, 452, 783, "cyan", "sparkle"),
    ("b17", 453, 748, 512, 783, "cyan", "sparkle"),
    ("b18", 333, 783, 393, 842, "cyan", "sparkle"),
    ("b19", 393, 783, 452, 842, "cyan", "sparkle"),
    ("b20", 453, 783, 512, 843, "cyan", "sparkle"),
    ("b21", 195, 738, 277, 789, "violet", "star"),
    ("b22", 192, 789, 277, 841, "violet", "star"),
    ("b23", 183, 843, 258, 910, "yellow", "star"),
    ("b24", 258, 843, 343, 917, "yellow", "star"),
    ("b25", 180, 915, 275, 988, "yellow", "star"),
    ("b26", 566, 737, 653, 790, "yellow", "star"),
    ("b27", 567, 790, 653, 842, "yellow", "star"),
    ("b28", 493, 842, 583, 917, "magenta", "heart"),
    ("b29", 583, 842, 657, 912, "violet", "sparkle"),
    ("b30", 519, 917, 593, 988, "cyan", "sparkle"),
    ("b31", 593, 917, 663, 987, "cyan", "sparkle"),
]

# Where two measured rects overlap, the block listed later owns the overlap, except
# for these pairs (winner, loser): the painted strip there is the winner's edge.
OWNS_OVERLAP = [("b14", "b26")]

# Shards and light streaks painted on top of blocks. Inpainted off the block art so a
# surviving block does not keep a frozen shard; the opening frame's effect layer
# puts them back. ("rect", l, t, r, b) or ("line", x0, y0, x1, y1, thickness)
BLOCK_TOUCHUPS = [
    ("rect", 469, 751, 491, 773),       # b17 purple gem on the sparkle
    ("line", 486, 792, 513, 757, 10),   # b17 white streak
    ("rect", 566, 768, 582, 802),       # b26 left face: blue gem
    ("rect", 284, 740, 310, 764),       # b10/b21 red shard
]

# Thin streaks over flat block faces: smooth (Telea) fill keeps the face gradient.
SMOOTH_TOUCHUPS = [
    ("line", 494, 884, 545, 862, 9),    # b28 light ray
]

# Small spots where only the white sparkle pixels are filled (outline kept).
DESPARKLE_RECTS = []

# Symmetric symbols damaged on one side: (dest rect, mirror axis x).
BLOCK_MIRRORS = []

# Copy a clean region over a spot hidden by a neighbour: (dest rect, source top-left).
BLOCK_PATCHES = []

# Blocks buried under shards: copy a clean twin (resized to the target rect).
BLOCK_DONORS = {"b18": "b19", "b20": "b19"}

# ---- scene ------------------------------------------------------------------
FORMATION_BOX = (172, 505, 675, 1000)
ATLAS_BOX = (110, 495, 700, 1010)   # wide enough for the gems thrown clear (STRAY_SHARDS)
CORNER_R = 6
# Painted gems away from the blocks (thrown clear, or caught on the scenery): removed
# from the plate so none stays frozen there; the opening frame's effect layer throws them.
STRAY_SHARDS = [
    (160, 850, 186, 888),   # yellow chip left of b23
    (120, 895, 174, 928),   # orange gem, left foliage
    (116, 942, 172, 978),   # cyan gem, left foliage
    (158, 922, 184, 940),   # lilac gem beside b25
    (664, 886, 693, 912),   # teal gem, right foliage
    (650, 770, 682, 806),   # yellow gem right of b27
    (608, 708, 630, 726),   # blue gem under the right torch
]

# The blue ball; its gold cup belongs to the launcher and stays in the background.
BALL_CENTER, BALL_R = (421, 1654), 74
BALL_CLEAR_R = 78
AIM_STRIP = (386, 930, 458, 1566)
# Painted aim chevrons (white cores) are found inside this box.
CHEVRON_ZONE = (390, 1005, 455, 1556)
BURST_CENTER, BURST_R = (428, 910), 92

# Walls the ball bounces off, where it counts as a miss, and the perspective of the
# path: the ball shrinks from full size at the launcher to depthMin at depthTop.
ARENA = {"left": 150, "right": 690, "top": 470, "missY": 1070, "depthTop": 960, "depthMin": 0.38,
         "ballSpeed": 2500}

# ---- HUD --------------------------------------------------------------------
PAUSE = (27, 150, 133, 247)
STAR_BOXES = [(290, 211, 372, 290), (375, 211, 459, 290), (465, 211, 549, 290)]
# Live text painted in the reference, removed from the plate and drawn by the app.
TIMER_DIGITS = (718, 172, 818, 222)
SCORE_DIGITS = (38, 1657, 200, 1724)
GOLD_TEXT_BOXES = [(598, 366, 826, 574), (606, 1630, 732, 1698)]   # "Combo x9", "+30"
COIN_CENTER, COIN_R = (773, 1663), 44
# Goal board: the painted instructions and swatches are replaced by live ones.
GOAL_BOARD = (15, 297, 236, 560)
GOAL_TEXT_AREA = (36, 388, 212, 542)
# Extents of the HUD, used to keep it inside the phone's safe area.
HUD_EXTENT = (12, 138, 830, 1745)

# ---- colours ------------------------------------------------------------------
# Blocks painted in an in-between hue (orchid, crimson-pink): shown as painted on the
# opening frame, then eased to their game colour so the colour rule is never ambiguous.
AMBIGUOUS = {"b01", "b02", "b03", "b04", "b21", "b07", "b08"}
# Clean, canonical blocks used to learn each colour's shading ramp.
RAMP_POOL = {
    "cyan": ["b16", "b19", "b30", "b31"],
    "violet": ["b11", "b12", "b22", "b29"],
    "magenta": ["b06", "b28"],
    "red": ["b09", "b10"],
    "yellow": ["b05", "b13", "b14", "b23", "b24", "b25", "b26", "b27"],
}

# Scenery inside the formation area that must survive the plate fill (wall torches),
# and painted shards overlapping it that must still go.
KEEP_RECTS = [(160, 548, 229, 694), (601, 548, 683, 690)]
KEEP_EXCEPT = [
    (572, 648, 620, 702),   # gem left of the right torch
    (624, 671, 663, 703),   # gem hanging under the right torch
    (194, 680, 229, 707),   # gem hanging under the left torch
]

# Painted gems thrown by the explosion, reused as hit shards: (box, seed point inside).
GEMS = [
    ((536, 762, 579, 808), (560, 786)),   # big blue gem
    ((298, 800, 336, 838), (318, 818)),   # cyan drop
    ((353, 856, 385, 885), (369, 870)),   # purple gem
    ((469, 522, 494, 555), (481, 537)),   # ember
]
