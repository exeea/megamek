"""Generates the static Roboto faces that Swing needs from the shipped Roboto variable font.

Java2D only draws a variable font's default (regular) instance, so Swing reads medium (500) and condensed medium
(500, wdth 75) from static files; libGDX reads the variable font directly. Run from mm-data/data/fonts/Roboto (needs
`pip install fonttools`):

    python make_static_roboto.py Roboto-VariableFont_wdth,wght.ttf Roboto-Medium.ttf
    python make_static_roboto.py Roboto-VariableFont_wdth,wght.ttf Roboto-CondensedMedium.ttf \
        --width 75 --family "Roboto Condensed Medium"

The face is named so that Java2D finds it by family (name ID 1) and full name (ID 4), both set to --family.
"""
import argparse

from fontTools.ttLib import TTFont
from fontTools.varLib import instancer


def make(source, target, weight, width, family):
    font = instancer.instantiateVariableFont(TTFont(source), {"wght": weight, "wdth": width})
    postscript = family.replace(" ", "-", 1).replace(" ", "")
    names = {1: family, 2: "Regular", 3: postscript + " static instance", 4: family, 6: postscript, 16: "Roboto",
             17: family[len("Roboto "):] if family.startswith("Roboto ") else "Regular"}
    name = font["name"]
    for record in list(name.names):
        if record.nameID in (1, 2, 3, 4, 6, 16, 17, 21, 22, 25) or record.nameID >= 256:
            name.removeNames(nameID=record.nameID)
    # Java2D looks fonts up by family (ID 1) or full name (ID 4), so both carry the family.
    for name_id, value in names.items():
        name.setName(value, name_id, 3, 1, 0x409)
        name.setName(value, name_id, 1, 0, 0)
    font["OS/2"].usWeightClass = weight
    font["OS/2"].usWidthClass = {75: 3, 87.5: 4}.get(width, 5)
    font["OS/2"].fsSelection = (font["OS/2"].fsSelection & ~0b1100001) | 0b1000000
    font["head"].macStyle = 0
    font.save(target)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("source", help="the Roboto variable font")
    parser.add_argument("target", help="the static face to write")
    parser.add_argument("--weight", type=int, default=500, help="wght axis value (default 500)")
    parser.add_argument("--width", type=float, default=100, help="wdth axis value (default 100, condensed is 75)")
    parser.add_argument("--family", default="Roboto Medium", help="family and full name (default Roboto Medium)")
    args = parser.parse_args()
    make(args.source, args.target, args.weight, args.width, args.family)


if __name__ == "__main__":
    main()
