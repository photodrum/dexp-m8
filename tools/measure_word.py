#!/usr/bin/env python3
"""Ширина строки в дизайн-px — как её считает Android `Paint.measureText`.

Нужно, когда строка в листе переносится не там, где ждёшь: меряем ту же строку тем же
шрифтом при том же кегле и сравниваем с шириной полосы. `measureText` суммирует advance
глифов (без учёта кернинга и лигатур — Android их тоже не применяет, шрифты в проекте
без GPOS-кернинга), поэтому сумма hmtx достаточна.

Запуск: python3 tools/measure_word.py <ttf> <кегль> <ширина полосы> "<строка>" ["<строка>"…]
"""
import sys

from fontTools.ttLib import TTFont

FONT = sys.argv[1]
SIZE = float(sys.argv[2])
MAXW = float(sys.argv[3])

font = TTFont(FONT)
upem = font["head"].unitsPerEm
cmap = font.getBestCmap()
hmtx = font["hmtx"]
scale = SIZE / upem


def width(s: str) -> float:
    total = 0
    for ch in s:
        name = cmap.get(ord(ch))
        if name is None:
            # Нет глифа — Android подставит .notdef; его advance тоже сдвигает строку.
            name = ".notdef"
        total += hmtx[name][0]
    return total * scale


for line in sys.argv[4:]:
    w = width(line)
    mark = "влезает" if w <= MAXW else "НЕ влезает"
    print(f"{w:8.1f} / {MAXW:.0f}  {mark}  «{line}»")
