#!/usr/bin/env python3
"""Генератор IconSpecs.kt из снимка макета design/v5/icons/*.json.

Что делает:
  - читает tokens.json (палитра G0..G15 -> hex) и все файлы иконок;
  - раскрывает группы, пропускает скрытые слои (hid), порядок слоёв сохраняет
    (первый — снизу);
  - варианты одной иконки (wifi~mpgmg0 и wifi~1pen2hs) сливает по имени:
    разница чисел до 0.1 допустима, больше — ошибка;
  - иконки, которых нет в снимке, берёт без изменений из tools/icon_specs_extra.txt;
  - пишет app/src/main/java/ru/ogrpfsense/dexpm8/ui/IconSpecs.kt.

Формат строки спеки — см. Icons.kt:
  имя|ширина|высота|примитив|...
  r x y w h radius <#hex | stroke:#hex:w[:round]>
  e x y w h        <#hex | stroke:#hex:w[:round]>
  p x y w h sw     <#hex | stroke:#hex:w[:round]> d

Запуск:
  python3 tools/icon_specs.py          записать IconSpecs.kt
  python3 tools/icon_specs.py --check  сравнить с файлом в репозитории (код 1 при отличии)
"""
import json
import re
import sys
from pathlib import Path

sys.dont_write_bytecode = True

ROOT = Path(__file__).resolve().parent.parent
ICONS_DIR = ROOT / "design" / "v5" / "icons"
TOKENS = ROOT / "design" / "v5" / "tokens.json"
EXTRA = ROOT / "tools" / "icon_specs_extra.txt"
OUT = ROOT / "app/src/main/java/ru/ogrpfsense/dexpm8/ui/IconSpecs.kt"

TOL = 0.1

# Иконки, которые есть в снимке в нескольких размерах: размер, который остаётся под обычным
# именем (на него ссылается код). Остальные размеры — `имя@ШxВ`, например `wifi@53x55.2`.
PRIMARY = {
    "wifi": (38.4, 40.2),
    "back": (45.7, 47.6),
    "list": (45.7, 47.6),
    "grid": (45.7, 47.6),
    "search": (45.7, 47.6),
    "filter": (45.7, 47.6),
    "next": (43.9, 43.9),
    "arrowR": (43.9, 43.9),
    "more": (49.4, 51.4),
    "check": (42, 43.9),
    "moon": (53, 55.2),
    "font": (53, 55.2),
    "files": (53, 55.2),
    "info": (53, 55.2),
    "refresh": (32.9, 36.9),
    "clock": (53, 55.2),
    "dict": (98.7, 102.8),
    "gallery": (39.8, 35.4),
    "trash": (45.7, 47.6),
    "sunLow": (16.1, 18),
    "sunHigh": (41, 42.9),
    "lock": (27.5, 35.8),
}
# Цветовой вариант той же формы под своим именем: файл → имя в IconSpecs.kt.
# search~fapjtl (G1, шапка B6/B36) отличается от search~hhvmhx (G5, B2) только цветом.
COLOR_ALIAS = {
    "search~fapjtl.json": "searchDark",
    # wifi~vtqgbs (G1, «Сеть» включена на B5) — та же форма, что wifi~1qmu6za (G4, B13).
    "wifi~vtqgbs.json": "wifiDark",
}
NUM_RE = re.compile(r"-?\d+(?:\.\d+)?")

# Файлы снимка, которые пока не берём: иконки B4 (R3) остаются из icon_specs_extra.txt.
# plus~oohzz2 снят неполным (только блик G15, без контура) — переснять с B4 в R3.
SKIP = {
    "plus~oohzz2.json",
    "minus~kgoluh.json",
}

HEADER = '''package ru.ogrpfsense.dexpm8.ui

/**
 * Геометрия иконок, снятая с макета Penpot: design/v5/icons.
 *
 * Каждая иконка в макете — две копии одной формы: нижняя белая (блик) со сдвигом
 * вниз и верхняя цветная. Координаты — в собственной системе иконки, поэтому
 * масштабирование в [Icons.draw] не плывёт относительно толщины штриха.
 * Концы линий: stroke:#hex:w:round — круглые, без суффикса — прямые.
 *
 * ФАЙЛ СГЕНЕРИРОВАН, руками не править: перегенерация — tools/icon_specs.py,
 * иконки не из снимка — tools/icon_specs_extra.txt.
 */
object IconSpecs {

    val SPECS: Array<String> = arrayOf(
'''
EXTRA_COMMENT = (
    "        // Иконки не из снимка: перенесены без изменений из tools/icon_specs_extra.txt\n"
)
FOOTER = "    )\n}\n"


def fmt(v) -> str:
    s = ("%.3f" % float(v)).rstrip("0").rstrip(".")
    return "0" if s in ("", "-0") else s


def fail(msg: str):
    print(f"icon_specs: {msg}", file=sys.stderr)
    sys.exit(1)


def load_palette() -> dict:
    pal = json.loads(TOKENS.read_text(encoding="utf-8"))["palette"]
    return {k: v.lower() for k, v in pal.items()}


def check_d(d: str, where: str):
    if not re.fullmatch(r"[MLCZ0-9.,\-]+", d):
        fail(f"{where}: в пути допустимы только M L C Z и числа: {d[:60]!r}")


def make_prim(n: dict, pal: dict, where: str) -> dict:
    fills = n.get("f") or []
    strokes = n.get("s") or []
    name = n.get("n")
    if len(fills) + len(strokes) != 1:
        fail(f"{where}/{name}: нужна ровно одна заливка или одна обводка")
    paint = fills[0] if fills else strokes[0]
    if "o" in paint:
        fail(f"{where}/{name}: прозрачность запрещена")
    if "a" in paint:
        fail(f"{where}/{name}: выравнивание обводки inner/outer не поддерживается")
    color = pal[paint["c"]]
    is_stroke = bool(strokes)
    cap = paint.get("cap")
    if cap not in (None, "round"):
        fail(f"{where}/{name}: cap {cap!r} не поддерживается")
    geom = [n["x"], n["y"], n["w"], n["h"]]
    t = n["t"]
    prim = {"color": color, "stroke": is_stroke, "round": cap == "round", "d": None}
    if is_stroke:
        prim["sw"] = paint["w"]
    if t == "rectangle":
        r = n.get("r", 0)
        if isinstance(r, list):
            if len(set(r)) != 1:
                fail(f"{where}/{name}: разные радиусы углов {r}")
            r = r[0]
        prim["t"] = "r"
        prim["nums"] = geom + [r]
    elif t == "ellipse":
        prim["t"] = "e"
        prim["nums"] = geom
    elif t == "path":
        d = n.get("d")
        if not d:
            fail(f"{where}/{name}: у path нет d")
        check_d(d, f"{where}/{name}")
        prim["t"] = "p"
        prim["nums"] = geom + [paint["w"] if is_stroke else 0]
        prim["d"] = d
    else:
        fail(f"{where}/{name}: тип {t!r} не поддерживается")
    return prim


def walk(nodes: list, pal: dict, where: str, out: list):
    for n in nodes:
        if n.get("hid"):
            continue
        if n["t"] in ("group", "board"):
            walk(n.get("c", []), pal, where, out)
        else:
            out.append(make_prim(n, pal, where))


def d_skeleton(d: str):
    return NUM_RE.sub("#", d), [float(x) for x in NUM_RE.findall(d)]


def same(a: dict, b: dict) -> str:
    """Пустая строка, если примитивы совпадают в пределах TOL, иначе причина."""
    for key in ("t", "color", "stroke", "round"):
        if a[key] != b[key]:
            return f"{key}: {a[key]!r} != {b[key]!r}"
    if abs(a.get("sw", 0) - b.get("sw", 0)) > TOL:
        return f"штрих {a.get('sw')} != {b.get('sw')}"
    if len(a["nums"]) != len(b["nums"]):
        return "разное число полей"
    for u, v in zip(a["nums"], b["nums"]):
        if abs(u - v) > TOL:
            return f"{u} != {v}"
    if (a["d"] is None) != (b["d"] is None):
        return "path и не path"
    if a["d"] is not None:
        sa, na = d_skeleton(a["d"])
        sb, nb = d_skeleton(b["d"])
        if sa != sb:
            return "разные команды пути"
        for u, v in zip(na, nb):
            if abs(u - v) > TOL:
                return f"в пути {u} != {v}"
    return ""


def render(p: dict) -> str:
    nums = [fmt(v) for v in p["nums"]]
    if p["stroke"]:
        paint = f"stroke:{p['color']}:{fmt(p['sw'])}" + (":round" if p["round"] else "")
    else:
        paint = p["color"]
    s = f"{p['t']} " + " ".join(nums) + " " + paint
    if p["d"] is not None:
        s += " " + p["d"]
    return s


def load_snapshot(pal: dict) -> dict:
    """имя иконки -> (w, h, [примитивы]).

    Файлы одного имени и одного размера (с допуском TOL) — цветовые варианты одной формы,
    их геометрия должна совпасть. Файлы другого размера — другой рисунок (штрих, концы линий
    зависят от размера), поэтому получают имя `имя@ШxВ`. Обычное имя остаётся у размера из
    PRIMARY: на него ссылается код экранов.
    """
    groups: dict = {}   # имя -> список [w, h, prims, файл]
    for path in sorted(ICONS_DIR.glob("*.json")):
        if path.name in SKIP:
            continue
        data = json.loads(path.read_text(encoding="utf-8"))
        name = data["name"].split("/")[-1].strip()
        file_name = path.name.split("~")[0]
        if name != file_name:
            fail(f"{path.name}: имя иконки {name!r} не совпадает с именем файла")
        name = COLOR_ALIAS.get(path.name, name)
        prims: list = []
        walk(data["c"], pal, path.name, prims)
        if not prims:
            fail(f"{path.name}: нет видимых слоёв")
        found = None
        for g in groups.setdefault(name, []):
            if abs(g[0] - data["w"]) <= TOL and abs(g[1] - data["h"]) <= TOL:
                found = g
                break
        if found is None:
            groups[name].append([data["w"], data["h"], prims, path.name])
            continue
        old = found[2]
        if len(old) != len(prims):
            fail(f"{path.name}: число слоёв {len(prims)} != {len(old)}")
        for i, (a, b) in enumerate(zip(old, prims)):
            why = same(a, b)
            if why:
                fail(f"{path.name}: вариант отличается от первого больше {TOL}, слой {i}: {why}")

    icons = {}
    for name, sizes in groups.items():
        if len(sizes) == 1:
            icons[name] = (sizes[0][0], sizes[0][1], sizes[0][2])
            continue
        pin = PRIMARY.get(name)
        if pin is None:
            fail(f"{name}: в снимке несколько размеров, а в PRIMARY нет основного: "
                 + ", ".join(f"{fmt(g[0])}x{fmt(g[1])}" for g in sizes))
        if not any(abs(g[0] - pin[0]) <= TOL and abs(g[1] - pin[1]) <= TOL for g in sizes):
            fail(f"{name}: основного размера {pin[0]}x{pin[1]} из PRIMARY нет в снимке")
        for w, h, prims, _ in sizes:
            main = abs(w - pin[0]) <= TOL and abs(h - pin[1]) <= TOL
            icons[name if main else f"{name}@{fmt(w)}x{fmt(h)}"] = (w, h, prims)
    return icons


def load_extra() -> list:
    out = []
    for line in EXTRA.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            out.append(line)
    return out


def entry(head: str, prims: list) -> str:
    lines = [f'        "{head}"']
    for p in prims:
        lines.append(f'            "|{p}"')
    return " +\n".join(lines)


def generate(icons: dict) -> str:
    blocks = []
    for name in sorted(icons):
        w, h, prims = icons[name]
        blocks.append(entry(f"{name}|{fmt(w)}|{fmt(h)}", [render(p) for p in prims]))
    extra_blocks = []
    for line in load_extra():
        parts = line.split("|")
        if parts[0] in icons:
            fail(f"{parts[0]}: есть и в снимке, и в icon_specs_extra.txt")
        extra_blocks.append(entry("|".join(parts[:3]), parts[3:]))
    body = ",\n\n".join(blocks)
    if extra_blocks:
        body += ",\n\n" + EXTRA_COMMENT + ",\n\n".join(extra_blocks)
    return HEADER + body + "\n" + FOOTER


def main() -> int:
    icons = load_snapshot(load_palette())
    text = generate(icons)
    note = f"{len(icons)} из снимка + {len(load_extra())} не из снимка"
    if "--check" in sys.argv:
        cur = OUT.read_text(encoding="utf-8") if OUT.exists() else ""
        if cur != text:
            print("IconSpecs.kt не совпадает со снимком: запустить python3 tools/icon_specs.py")
            return 1
        print(f"IconSpecs.kt совпадает со снимком: {note}")
        return 0
    OUT.write_text(text, encoding="utf-8")
    print(f"записано {OUT.relative_to(ROOT)}: {note}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

