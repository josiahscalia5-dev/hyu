"""Level 4 "Mystic Harvest" layout, measured from design/level4_reference.png (941x1672).

Every box is (left, top, right, bottom) in reference pixels ("stage units").
build.py writes what the app needs into assets/level4/level4.json, so this file is
the single source of truth for where things sit on screen.
"""

REFERENCE = "level4_reference.png"
STAGE_W, STAGE_H = 941, 1672

# Treasure hovering over the lagoon: (id, kind, box). Every one is a target.
#   gem: shatters; coin: collected; barrel: bursts, adds time; crate: splinters;
#   chest: stops the bolt and bursts open.
TARGETS = [
    ("gem_green", "gem", (334, 386, 494, 562)),
    ("gem_red", "gem", (553, 450, 754, 660)),
    ("gem_blue", "gem", (281, 563, 467, 774)),
    ("gem_violet", "gem", (498, 810, 667, 954)),
    ("gem_green2", "gem", (658, 976, 794, 1122)),
    ("gem_violet2", "gem", (466, 573, 547, 692)),
    ("gem_violet3", "gem", (270, 848, 352, 920)),
    ("gem_violet4", "gem", (536, 1166, 616, 1250)),
    ("gem_violet5", "gem", (453, 1026, 502, 1084)),
    ("gem_sky", "gem", (513, 474, 562, 534)),
    ("gem_indigo", "gem", (662, 658, 724, 710)),
    ("gem_indigo2", "gem", (496, 760, 554, 844)),
    ("gem_cyan", "gem", (476, 676, 530, 734)),
    ("gem_emerald", "gem", (234, 559, 284, 604)),
    ("gem_ruby", "gem", (196, 602, 232, 633)),
    ("gem_ruby2", "gem", (227, 628, 262, 667)),
    ("gem_sky2", "gem", (248, 661, 278, 692)),
    ("gem_ruby3", "gem", (558, 638, 614, 692)),
    ("gem_ruby5", "gem", (326, 756, 370, 804)),
    ("gem_amber", "gem", (350, 806, 424, 880)),
    ("gem_amber2", "gem", (498, 1073, 558, 1142)),
    ("coin_left", "coin", (98, 630, 230, 764)),
    ("coin_right", "coin", (731, 586, 844, 710)),
    ("coin_low", "coin", (743, 893, 867, 1014)),
    ("coin_center", "coin", (381, 1098, 524, 1260)),
    ("barrel", "barrel", (150, 710, 344, 897)),
    ("barrel2", "barrel", (96, 838, 258, 998)),
    ("chest_gold", "chest", (596, 696, 858, 928)),
    ("chest_jewel", "chest", (153, 928, 462, 1177)),
    ("crate", "crate", (243, 438, 354, 558)),
    ("crate2", "crate", (273, 1158, 352, 1247)),
    ("crate3", "crate", (566, 698, 618, 764)),
    ("crate4", "crate", (814, 690, 862, 752)),
]

# The sword-hilt launcher; the bolt leaves from the tip of its gold guard.
LAUNCHER = (646, 1138, 928, 1438)
LAUNCHER_PIVOT = (858, 1388)     # pommel: the hilt turns around it to aim
LAUNCHER_TIP = (668, 1168)       # where the bolt appears

# The crescent trail of the shot that just exploded, and its burst of light.
TRAIL_BOX = (386, 684, 712, 1270)
BURST_BOX = (330, 600, 700, 1060)
BURST_CENTER = (470, 800)

# ---- HUD ---------------------------------------------------------------------
PAUSE = (24, 20, 147, 141)
SIGN = (186, 0, 750, 228)
STAR_BOXES = [(340, 148, 421, 222), (429, 146, 511, 220), (519, 146, 601, 222)]
TIMER_PANEL = (748, 36, 934, 120)
TIMER_DIGITS = (822, 54, 916, 104)
SCORE_PANEL = (33, 216, 245, 354)
SCORE_DIGITS = (50, 258, 232, 348)
COMBO_BOX = (704, 206, 932, 420)          # "Combo" and "x8", gold lettering
TIME_BAR = (60, 1422, 874, 1584)          # stopwatch dial and bar
TIME_BAR_TRACK = (211, 1470, 849, 1536)   # inside of the bar the fill runs in
TIME_BAR_FILL = (211, 1470, 489, 1536)    # the painted fill (0:28 left)
HUD_EXTENT = (20, 0, 936, 1586)

# ---- the explosion ---------------------------------------------------------------
# Inside this outline, the burst's glow and loose gem debris are painted out of the
# background (and put back, moving, by the opening frame's effect layer).
EXPLOSION = [(250, 430), (400, 395), (520, 410), (640, 430), (770, 540), (858, 690), (860, 1050),
             (800, 1190), (700, 1270), (380, 1280), (200, 1215), (80, 1040), (78, 780), (120, 560)]
# The heart of the burst, clear of palms and piers: orange and green debris is found here too.
EXPLOSION_CORE = [(300, 480), (620, 470), (760, 620), (805, 900), (725, 1150), (560, 1240),
                  (360, 1232), (228, 1100), (196, 850), (238, 620)]
# Loose debris the colour tests miss.
DEBRIS = [(615, 1212, 650, 1250)]
# Bright scenery inside that outline that is not glow: waterfalls, candle flames.
KEEP_BRIGHT = [(280, 300, 350, 440), (500, 330, 620, 420), (60, 440, 150, 520),
               (40, 990, 90, 1030), (880, 690, 935, 750), (860, 980, 935, 1030)]
# The crescent trail: outer (left) edge from its tip down to the launcher, then the
# inner edge back up.
TRAIL = [(578, 698), (500, 728), (440, 788), (393, 860), (396, 932), (428, 993), (488, 1053),
         (568, 1113), (640, 1163), (674, 1194),
         (694, 1152), (622, 1094), (547, 1034), (503, 979), (481, 920), (483, 860), (503, 800),
         (543, 744), (590, 710)]
