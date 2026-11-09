#!/usr/bin/env python3
"""Срез начертаний из вариативных шрифтов в app/src/main/res/font/.

Источники: tools/fonts/Manrope-var.ttf (wght 200..800), Literata-var.ttf (opsz 7..72, wght 200..900).
Набор символов берётся из уже лежащих в res/font manrope_400.ttf и literata_400.ttf —
новые начертания обязаны его повторять, иначе текст в одном экране «поедет» разными глифами.

Новые файлы (п. 2б, шаг 2): manrope_200/300/500, literata_500 (opsz 60), literata_600 (opsz 42).
Прежние файлы (400/600/700/800, literata_400 = opsz 12) не перезаписываются: срез с теми же
параметрами даёт те же advance (проверено), но бинарно файл мог бы отличаться.

Запуск: python3 tools/make_fonts.py   (Literata режется медленно — около минуты на файл)
Код возврата 0 — все файлы созданы и набор символов совпал.
"""
import os
import sys

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
SRC = os.path.join(ROOT, 'tools', 'fonts')
OUT = os.path.join(ROOT, 'app', 'src', 'main', 'res', 'font')

# (источник, файл результата, оси, эталон набора символов)
JOBS = [
    ('Manrope-var.ttf', 'manrope_200.ttf', {'wght': 200}, 'manrope_400.ttf'),
    ('Manrope-var.ttf', 'manrope_300.ttf', {'wght': 300}, 'manrope_400.ttf'),
    ('Manrope-var.ttf', 'manrope_500.ttf', {'wght': 500}, 'manrope_400.ttf'),
    ('Literata-var.ttf', 'literata_500.ttf', {'wght': 500, 'opsz': 60}, 'literata_400.ttf'),
    ('Literata-var.ttf', 'literata_600.ttf', {'wght': 600, 'opsz': 42}, 'literata_400.ttf'),
]


def charset(path):
    return set(TTFont(path).getBestCmap())


def build(src, name, axes, ref):
    wanted = charset(os.path.join(OUT, ref))
    font = TTFont(os.path.join(SRC, src))
    font = instancer.instantiateVariableFont(font, axes, inplace=False)

    opt = subset.Options()
    opt.layout_features = ['kern', 'liga']
    opt.hinting = False
    opt.notdef_outline = True
    opt.name_IDs = [1, 2, 3, 4, 6]
    opt.drop_tables += ['STAT']
    sub = subset.Subsetter(opt)
    sub.populate(unicodes=wanted)
    sub.subset(font)

    path = os.path.join(OUT, name)
    font.save(path)
    got = charset(path)
    ok = got == wanted
    print('%-18s %-22s %6d байт  символов %d/%d  %s' % (
        name, axes, os.path.getsize(path), len(got), len(wanted),
        'набор совпал' if ok else 'НАБОР НЕ СОВПАЛ: лишних %d, потеряно %d' % (
            len(got - wanted), len(wanted - got))))
    return ok


def main():
    ok = True
    for job in JOBS:
        ok = build(*job) and ok
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
