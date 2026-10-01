"""Level 6 (Storm Dodge) layout, measured from design/level6_storm_dodge_reference.png (941x1672)."""

REFERENCE = "level6_storm_dodge_reference.png"
STAGE_W, STAGE_H = 941, 1672

# ---- perspective of the river --------------------------------------------------
# Screen position of a point X lanes to the side and z units ahead of the jet ski:
#   s = D / (D + z);  y = HORIZON + (PLAYER_Y - HORIZON) * s;  x = VANISH_X + X * LANE_W * s
HORIZON, PLAYER_Y, VANISH_X, LANE_W, DEPTH_D = 570, 1450, 470, 300, 10.0
# Water is drawn live below WATER_TOP (blending into the painted far water above it).
WATER_TOP, WATER_BLEND = 690, 90

# ---- sprites cut from the reference: name -> (box, kind) ---------------------------
SPRITES = {
    "barrel":      ((200, 684, 354, 826), "object"),
    "barrel_open": ((722, 914, 920, 1050), "object"),
    "barrel_small": ((350, 668, 433, 745), "object"),
    "mine":        ((275, 838, 458, 988), "object"),
    "crate_x":     ((666, 724, 852, 862), "object"),
    "logs":        ((570, 668, 750, 774), "object"),
    "plank":       ((150, 1016, 266, 1120), "object"),
    "coin":        ((448, 779, 535, 867), "disc"),
    "coin_side":   ((608, 839, 688, 943), "gold"),
    "shield":      ((58, 793, 208, 922), "disc"),
    "x_hazard":    ((764, 1042, 912, 1194), "disc"),
    "jetski":      ((292, 975, 650, 1492), "jetski"),
    "arrow_left":  ((26, 1389, 241, 1604), "disc"),
    "arrow_right": ((701, 1389, 916, 1604), "disc"),
    "leaves":      ((0, 1080, 245, 1400), "foliage"),
}
# Jet ski hull below the rider (white sides, grey skirt, nozzle), traced on the reference;
# merged with the GrabCut matte, whose colour seeds cannot tell white hull from foam.
JETSKI_HULL = [(330, 1200), (322, 1250), (310, 1305), (305, 1345), (312, 1390), (332, 1405), (345, 1418),
               (370, 1460), (405, 1480), (425, 1488), (525, 1488), (570, 1476), (605, 1436), (630, 1402),
               (640, 1355), (636, 1305), (618, 1255), (612, 1200)]

# Everything removed from the clean plate (objects, coins, spray) besides the sprites.
EXTRA_CLEAR = [
    (0, 636, 132, 760),        # mine at the left edge (cut off)
    (0, 900, 205, 1068),       # log pile at the left edge (cut off)
    (812, 798, 941, 916),      # log at the right edge (cut off)
    (300, 610, 760, 700),      # far barrels, mine and coins near the horizon
    (660, 1150, 745, 1196),    # small wood chunk
    (300, 1470, 650, 1672),    # water jet below the jet ski
]

# ---- HUD -----------------------------------------------------------------------
PAUSE = (822, 25, 916, 119)
ARROW_LEFT_C, ARROW_RIGHT_C, ARROW_R = (133, 1496), (808, 1496), 104
STARS = [(306, 222, 378, 292), (402, 222, 476, 292), (494, 222, 568, 292)]
STAR_BAR = (305, 240, 585, 280)
SCORE_DIGITS = (40, 245, 205, 308)
TIMER_DIGITS = (785, 232, 905, 300)
HUD_EXTENT = (18, 20, 922, 1610)
