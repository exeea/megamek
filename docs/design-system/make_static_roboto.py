"""Generates the static Roboto Medium face that Swing needs from the shipped Roboto variable font.

Java2D only draws a variable font's default (regular) instance, so Swing reads medium (500) from this static file;
libGDX reads the variable font directly. Run from mm-data/data/fonts/Roboto (needs `pip install fonttools`):

    python make_static_roboto.py Roboto-VariableFont_wdth,wght.ttf Roboto-Medium.ttf
"""
import sys

from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

NAMES = {1: "Roboto Medium", 2: "Regular", 3: "Roboto-Medium static instance", 4: "Roboto Medium",
         6: "Roboto-Medium", 16: "Roboto", 17: "Medium"}


def main(source, target):
    font = instancer.instantiateVariableFont(TTFont(source), {"wght": 500, "wdth": 100})
    name = font["name"]
    for record in list(name.names):
        if record.nameID in (1, 2, 3, 4, 6, 16, 17, 21, 22, 25) or record.nameID >= 256:
            name.removeNames(nameID=record.nameID)
    # Java2D looks fonts up by family (ID 1) or full name (ID 4), so both say "Roboto Medium".
    for name_id, value in NAMES.items():
        name.setName(value, name_id, 3, 1, 0x409)
        name.setName(value, name_id, 1, 0, 0)
    font["OS/2"].usWeightClass = 500
    font["OS/2"].fsSelection = (font["OS/2"].fsSelection & ~0b1100001) | 0b1000000
    font["head"].macStyle = 0
    font.save(target)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
