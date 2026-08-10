#!/usr/bin/env python3
"""Generate the "Rolo PvP" resource pack textures (Minecraft 1.21.11).

Recreates a PvP-style HUD: silver Y-shaped armor icons, flat red hearts,
orange/gold drumsticks, dark green XP bar, dark hotbar with a cyan
selection frame. All sprites are drawn at vanilla resolution from ASCII
pixel maps below, so the pack is fully reproducible from this script.

Usage: python3 generate_textures.py
Writes into ./rolo-pvp-pack/ and renders preview.png next to it.
"""
import os
import zipfile
from PIL import Image

ROOT = os.path.dirname(os.path.abspath(__file__))
PACK = os.path.join(ROOT, "rolo-pvp-pack")
HUD = os.path.join(PACK, "assets", "minecraft", "textures", "gui", "sprites", "hud")

# ---------------------------------------------------------------- palettes
T = (0, 0, 0, 0)  # transparent

def sprite(rows, palette):
    """Build an RGBA image from an ASCII pixel map."""
    h = len(rows)
    w = max(len(r) for r in rows)
    img = Image.new("RGBA", (w, h), T)
    px = img.load()
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch != ".":
                px[x, y] = palette[ch]
    return img

def save(img, *path):
    out = os.path.join(HUD, *path)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    img.save(out)
    return img

def left_half(img, keep_cols):
    """Copy of img with only the leftmost keep_cols columns kept."""
    out = img.copy()
    px = out.load()
    for y in range(out.height):
        for x in range(keep_cols, out.width):
            px[x, y] = T
    return out

def tinted(img, mapping):
    """Copy of img with exact-color substitutions applied."""
    out = img.copy()
    px = out.load()
    for y in range(out.height):
        for x in range(out.width):
            c = px[x, y]
            if c in mapping:
                px[x, y] = mapping[c]
    return out

# ---------------------------------------------------------------- hearts
DK = (35, 10, 10, 255)      # heart outline
RD = (235, 40, 45, 255)     # heart red
RS = (180, 20, 30, 255)     # heart shade
HI = (255, 195, 195, 255)   # highlight

HEART_FULL = [
    ".DD...DD.",
    "DHRD.DRRD",
    "DHRRDRRRD",
    "DRRRRRRRD",
    ".DRRRRRD.",
    "..DRRRD..",
    "...DSD...",
    "....D....",
    ".........",
]
heart_pal = {"D": DK, "R": RD, "S": RS, "H": HI}

CT = (25, 25, 25, 255)      # container outline
CB = (70, 70, 70, 255)      # container fill
container_pal = {"D": CT, "R": CB, "S": CB, "H": CB}

heart_full = sprite(HEART_FULL, heart_pal)
heart_container = sprite(HEART_FULL, container_pal)
heart_half = left_half(heart_full, 5)
blink = {RD: (255, 90, 90, 255), RS: (220, 60, 60, 255), HI: (255, 225, 225, 255)}

save(heart_full, "heart", "full.png")
save(heart_half, "heart", "half.png")
save(heart_container, "heart", "container.png")
save(heart_container, "heart", "container_blinking.png")
save(tinted(heart_full, blink), "heart", "full_blinking.png")
save(tinted(heart_half, blink), "heart", "half_blinking.png")

# ---------------------------------------------------------------- armor (silver Y)
AD = (25, 25, 30, 255)      # outline
AW = (245, 245, 250, 255)   # bright silver
AL = (185, 190, 200, 255)   # silver shade

ARMOR = [
    "DD.....DD",
    "DWD...DWD",
    "DWWD.DWWD",
    ".DWWDWWD.",
    "..DWWWD..",
    "..DWLWD..",
    "..DWLWD..",
    "..DWWWD..",
    "..DDDDD..",
]
armor_pal = {"D": AD, "W": AW, "L": AL}
armor_full = sprite(ARMOR, armor_pal)
armor_empty = sprite(ARMOR, {"D": (25, 25, 30, 255),
                             "W": (75, 75, 85, 255),
                             "L": (60, 60, 70, 255)})
armor_half = armor_empty.copy()
armor_half.paste(left_half(armor_full, 5), (0, 0), left_half(armor_full, 5))

save(armor_full, "armor_full.png")
save(armor_half, "armor_half.png")
save(armor_empty, "armor_empty.png")

# ---------------------------------------------------------------- food (drumstick)
FD = (40, 15, 5, 255)       # outline
FO = (220, 115, 40, 255)    # meat orange
FS = (175, 75, 25, 255)     # meat shade
FY = (250, 205, 95, 255)    # golden bone
FB = (255, 235, 170, 255)   # bone highlight

FOOD = [
    ".....DDD.",
    "....DYBYD",
    "...DYDYYD",
    "..DYYD.D.",
    ".DODYD...",
    "DOOODD...",
    "DOOOOD...",
    "DSOOD....",
    ".DDD.....",
]
food_pal = {"D": FD, "O": FO, "S": FS, "Y": FY, "B": FB}
food_full = sprite(FOOD, food_pal)
food_empty = sprite(FOOD, {"D": (30, 30, 30, 255),
                           "O": (70, 70, 70, 255),
                           "S": (60, 60, 60, 255),
                           "Y": (85, 85, 85, 255),
                           "B": (95, 95, 95, 255)})
food_half = food_empty.copy()
fh = left_half(food_full, 5)
food_half.paste(fh, (0, 0), fh)

save(food_full, "food_full.png")
save(food_half, "food_half.png")
save(food_empty, "food_empty.png")
# hunger-effect variants: greenish tint on the meat
hunger = {FO: (170, 140, 50, 255), FS: (130, 105, 35, 255)}
save(tinted(food_full, hunger), "food_full_hunger.png")
save(tinted(food_half, hunger), "food_half_hunger.png")
save(food_empty, "food_empty_hunger.png")

# ---------------------------------------------------------------- XP bar (182x5)
def xp_bar(border, fill, top):
    img = Image.new("RGBA", (182, 5), border)
    px = img.load()
    for x in range(1, 181):
        px[x, 1] = top
        px[x, 2] = fill
        px[x, 3] = fill
    return img

save(xp_bar((10, 12, 10, 255), (35, 45, 35, 255), (45, 58, 45, 255)),
     "experience_bar_background.png")
save(xp_bar((10, 12, 10, 255), (46, 92, 50, 255), (68, 122, 72, 255)),
     "experience_bar_progress.png")

# ---------------------------------------------------------------- hotbar (182x22)
hotbar = Image.new("RGBA", (182, 22), (0, 0, 0, 0))
px = hotbar.load()
for y in range(22):
    for x in range(182):
        edge = x == 0 or x == 181 or y == 0 or y == 21
        if edge:
            px[x, y] = (150, 150, 150, 220)
        else:
            px[x, y] = (10, 10, 10, 160)
# subtle slot dividers (9 slots, 20px apart starting at x=20)
for i in range(1, 9):
    for y in range(1, 21):
        px[i * 20, y] = (60, 60, 60, 180)
save(hotbar, "hotbar.png")

# selection frame: 24x23, 2px cyan border
sel = Image.new("RGBA", (24, 23), (0, 0, 0, 0))
px = sel.load()
CY = (70, 185, 255, 255)
CI = (150, 220, 255, 255)
for y in range(23):
    for x in range(24):
        if x in (0, 23) or y in (0, 22):
            px[x, y] = CY
        elif x in (1, 22) or y in (1, 21):
            px[x, y] = CI
save(sel, "hotbar_selection.png")

# ---------------------------------------------------------------- pack meta + icon
os.makedirs(PACK, exist_ok=True)
with open(os.path.join(PACK, "pack.mcmeta"), "w") as f:
    f.write("""{
  "pack": {
    "pack_format": 69,
    "supported_formats": { "min_inclusive": 46, "max_inclusive": 9999 },
    "min_format": [46, 0],
    "max_format": [9999, 9999],
    "description": "Rolo PvP Pack \\u00a7 7for 1.21.11"
  }
}
""".replace("\\u00a7 7", "\\u00a77"))

icon = Image.new("RGBA", (64, 64), (28, 30, 36, 255))
big_heart = heart_full.resize((54, 54), Image.NEAREST)
icon.paste(big_heart, (5, 5), big_heart)
icon.save(os.path.join(PACK, "pack.png"))

# ---------------------------------------------------------------- preview render
def paste_scaled(canvas, img, xy, s=4):
    up = img.resize((img.width * s, img.height * s), Image.NEAREST)
    canvas.paste(up, xy, up)

prev = Image.new("RGBA", (782, 240), (120, 88, 62, 255))
for i in range(10):
    paste_scaled(prev, armor_full, (20 + i * 34, 16))
    paste_scaled(prev, heart_full, (20 + i * 34, 56))
    paste_scaled(prev, food_full, (420 + i * 34, 56))
paste_scaled(prev, xp_bar((10, 12, 10, 255), (46, 92, 50, 255), (68, 122, 72, 255)), (20, 104))
paste_scaled(prev, hotbar, (20, 130))
paste_scaled(prev, sel, (94, 126))
prev.save(os.path.join(ROOT, "preview.png"))

# ---------------------------------------------------------------- zip dist
zip_path = os.path.join(ROOT, "rolo-pvp-pack-1.21.11.zip")
with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
    for base, _, files in os.walk(PACK):
        for name in sorted(files):
            full = os.path.join(base, name)
            z.write(full, os.path.relpath(full, PACK))

print("done:", zip_path)
