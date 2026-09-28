#!/usr/bin/env python3
"""Convert the SVG icons in ../icons-svg/ into Android VectorDrawables (res/drawable/mi_*.xml).

The SVG files are the source of truth (Material Symbols Rounded, 960x960 grid,
viewBox "0 -960 960 960").  Android draws them natively as vectors, tinted at
runtime with the current theme colour, so they stay sharp at every size.

Usage:
    python3 tools/svg_to_vector.py              # convert icons-svg/*.svg
    python3 tools/svg_to_vector.py --fetch DIR  # first copy icons listed in tools/icons.txt from an
                                                # extracted @material-symbols/svg-400 package (DIR/rounded)
"""
import os, re, sys, shutil

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SRC = os.path.join(ROOT, "icons-svg")
OUT = os.path.join(ROOT, "app", "src", "main", "res", "drawable")

def names():
    with open(os.path.join(HERE, "icons.txt"), encoding="utf-8") as f:
        return [l.strip() for l in f if l.strip() and not l.startswith("#")]

def fetch(pkg):
    os.makedirs(SRC, exist_ok=True)
    for n in names():
        shutil.copyfile(os.path.join(pkg, "rounded", n + ".svg"), os.path.join(SRC, n + ".svg"))

def convert(svg_path):
    s = open(svg_path, encoding="utf-8").read()
    vb = re.search(r'viewBox="([-\d.\s]+)"', s).group(1).split()
    minx, miny, w, h = [float(x) for x in vb]
    paths = re.findall(r'<path[^>]*\sd="([^"]+)"', s)
    if not paths:
        raise SystemExit("no <path> in " + svg_path)
    body = "\n".join('        <path android:fillColor="#FF000000" android:pathData="%s"/>' % p for p in paths)
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- Generated from icons-svg/%s by tools/svg_to_vector.py. Do not edit by hand. -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="24dp" android:height="24dp"\n'
            '    android:viewportWidth="%g" android:viewportHeight="%g">\n'
            '    <group android:translateX="%g" android:translateY="%g">\n%s\n    </group>\n</vector>\n'
            % (os.path.basename(svg_path), w, h, -minx + 0.0, -miny + 0.0, body))

def main():
    if len(sys.argv) > 2 and sys.argv[1] == "--fetch":
        fetch(sys.argv[2])
    count = 0
    for fn in sorted(os.listdir(SRC)):
        if not fn.endswith(".svg"):
            continue
        res = "mi_" + fn[:-4].replace("-", "_") + ".xml"
        with open(os.path.join(OUT, res), "w", encoding="utf-8") as f:
            f.write(convert(os.path.join(SRC, fn)))
        count += 1
    print("generated", count, "vector drawables")

if __name__ == "__main__":
    main()
