#!/usr/bin/env python3
"""Генератор IconSpecs.kt из design/icons.txt.

design/icons.txt — единственный источник геометрии иконок (с 2026-11-23). Его правят на странице
«icons» файла «UI» в Penpot и снимают обратно (tools/penpot_icons_dump.js, design/README.md,
«Иконки: страница icons»). Снимок бордов v5 (design/v5/icons/*.json) нужен только проверке
экранов: из него IconSpecs.kt больше не собирается.

Формат строки спеки — см. Icons.kt:
  имя|ширина|высота|примитив|...
  r x y w h radius <#hex | stroke:#hex:w[:round]>
  e x y w h        <#hex | stroke:#hex:w[:round]>
  p x y w h sw     <#hex | stroke:#hex:w[:round]> d
Строки, начинающиеся с «#», — комментарии. Порядок строк = порядок в IconSpecs.kt.

Запуск:
  python3 tools/icon_specs.py          записать IconSpecs.kt
  python3 tools/icon_specs.py --check  сравнить с файлом в репозитории (код 1 при отличии)
"""
import re
import sys
from pathlib import Path

sys.dont_write_bytecode = True

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "design" / "icons.txt"
OUT = ROOT / "app/src/main/java/ru/ogrpfsense/dexpm8/ui/IconSpecs.kt"

HEADER = '''package ru.ogrpfsense.dexpm8.ui

/**
 * Геометрия иконок: design/icons.txt (правится на странице «icons» в Penpot).
 *
 * Каждая иконка — обычно две копии одной формы: нижняя белая (блик) со сдвигом
 * вниз и верхняя цветная. Координаты — в собственной системе иконки, поэтому
 * масштабирование в [Icons.draw] не плывёт относительно толщины штриха.
 * Концы линий: stroke:#hex:w:round — круглые, без суффикса — прямые.
 *
 * ФАЙЛ СГЕНЕРИРОВАН, руками не править: перегенерация — tools/icon_specs.py.
 */
object IconSpecs {

    val SPECS: Array<String> = arrayOf(
'''
FOOTER = "    )\n}\n"
PALETTE = {"#%02x%02x%02x" % (i * 17, i * 17, i * 17) for i in range(16)}


def fail(msg: str):
    print(f"icon_specs: {msg}", file=sys.stderr)
    sys.exit(1)


def load_specs() -> list:
    """[(имя, ширина, высота, [примитив, …])] в порядке файла; ошибка — с номером строки."""
    out, seen = [], set()
    for no, line in enumerate(SRC.read_text(encoding="utf-8").splitlines(), 1):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("|")
        where = f"{SRC.name}:{no}"
        if len(parts) < 4:
            fail(f"{where}: нужны имя|ширина|высота и хотя бы один примитив")
        name = parts[0]
        if name in seen:
            fail(f"{where}: иконка {name} уже есть выше")
        seen.add(name)
        for c in re.findall(r"#[0-9a-fA-F]{6}", "|".join(parts[3:])):
            if c.lower() not in PALETTE:
                fail(f"{where}/{name}: цвет {c} не из палитры G0–G15")
        out.append((name, parts[1], parts[2], parts[3:]))
    if not out:
        fail(f"{SRC.name}: нет ни одной иконки")
    return out


def entry(head: str, prims: list) -> str:
    lines = [f'        "{head}"']
    for p in prims:
        lines.append(f'            "|{p}"')
    return " +\n".join(lines)


def generate(specs: list) -> str:
    blocks = [entry(f"{n}|{w}|{h}", prims) for n, w, h, prims in specs]
    return HEADER + ",\n\n".join(blocks) + "\n" + FOOTER


def main() -> int:
    specs = load_specs()
    text = generate(specs)
    note = f"{len(specs)} иконок"
    if "--check" in sys.argv:
        cur = OUT.read_text(encoding="utf-8") if OUT.exists() else ""
        if cur != text:
            print("IconSpecs.kt не совпадает с design/icons.txt: запустить python3 tools/icon_specs.py")
            return 1
        print(f"IconSpecs.kt совпадает с design/icons.txt: {note}")
        return 0
    OUT.write_text(text, encoding="utf-8")
    print(f"записано {OUT.relative_to(ROOT)}: {note}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
