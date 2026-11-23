#!/usr/bin/env python3
"""Спеки иконок из IconSpecs.kt → JSON для рисования на странице «icons» в Penpot.

Что делает:
  - берёт спеки через check_icons.elements() (тот же разбор, что у проверок);
  - оставляет только иконки, чьё имя встречается в коде строкой-литералом
    (`icons.draw(c, "wifi", …)`, `key(…, "modeFastDark", …)`);
  - раскладывает их в порядке страницы (иконка и её варианты размера подряд) и пишет
    порции chunk_0.txt … chunk_N.txt: одна спека на строку, формат как в IconSpecs.kt.

Дальше `tools/penpot_icons_draw.js` рисует порции бордами на странице «icons»,
а `tools/penpot_icons_dump.js` снимает борды обратно в строки спек (design/icons.txt).

Запуск (из корня репозитория):
  python3 tools/icons_to_penpot.py [/каталог] [--chunks N]   по умолчанию /tmp/icons_serve, 6 порций
  python3 tools/icons_to_penpot.py --unused                  только список неиспользуемых спек
  python3 tools/icons_to_penpot.py --hashes                  «имя хеш» по каждой спеке (сверка с Penpot)
"""
import math
import pathlib
import re
import sys

sys.dont_write_bytecode = True
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from check_icons import elements  # noqa: E402

SRC = pathlib.Path("app/src/main/java")
LITERAL = re.compile(r'"([A-Za-z][A-Za-z0-9]*(?:@[0-9.]+x[0-9.]+)?)"')


def used_names(names: set) -> set:
    """Имена спек, которые встречаются в коде (кроме самого IconSpecs.kt) строкой-литералом."""
    lits = set()
    for path in SRC.rglob("*.kt"):
        if path.name == "IconSpecs.kt":
            continue
        lits.update(LITERAL.findall(path.read_text(encoding="utf-8")))
    return names & lits


def _r(v: float, n: int) -> str:
    """Округление «половина вверх», как Math.round в JS, и запись без хвостовых нулей."""
    k = 10 ** n
    s = ("%." + str(n) + "f") % (math.floor(v * k + 0.5) / k)
    s = s.rstrip("0").rstrip(".") if "." in s else s
    return "0" if s in ("", "-0") else s


def canon(spec: str) -> str:
    """Каноническая запись спеки для сверки Penpot ↔ IconSpecs (тот же алгоритм — в
    penpot_icons_dump.js). Числа округлены (рамки, радиус и толщина — до 0.1, путь — до 0.01),
    у path рамка x y w h не входит: Penpot считает её по кривой, а в спеке она нужна только
    как запасной вариант (Icons.kt берёт реальные границы пути)."""
    parts = spec.split("|")
    out = [parts[0], _r(float(parts[1]), 1), _r(float(parts[2]), 1)]
    for chunk in parts[3:]:
        if not chunk.strip():
            continue
        tk = chunk.strip().split(" ")
        t = tk[0][0]
        col = tk[5 if t == "e" else 6]
        if col.startswith("stroke:"):
            v = col.split(":")
            col = "stroke:" + v[1].lower() + ":" + _r(float(v[2]), 1) + (":round" if len(v) > 3 else "")
        else:
            col = col.lower()
        if t == "p":
            d = re.sub(r"-?\d*\.?\d+", lambda m: _r(float(m.group()), 2), " ".join(tk[7:]))
            out.append(f"p {col} {d}")
        elif t == "r":
            out.append("r " + " ".join(_r(float(v), 1) for v in tk[1:6]) + " " + col)
        else:
            out.append("e " + " ".join(_r(float(v), 1) for v in tk[1:5]) + " " + col)
    return "|".join(out)


def djb2(s: str) -> str:
    h = 5381
    for ch in s:
        h = ((h << 5) + h + ord(ch)) & 0xFFFFFFFF
    return format(h, "08x")


def layout_key(name: str):
    """Порядок на странице: иконка, затем её варианты размера; основная (без @) — первой."""
    base, _, size = name.partition("@")
    return (base.lower(), base, size != "", size)


def main() -> int:
    raw = elements()
    specs = {s.split("|")[0]: s for s in raw}
    used = used_names(set(specs))
    if "--unused" in sys.argv:
        print("\n".join(sorted(set(specs) - used)))
        return 0
    if "--hashes" in sys.argv:
        # «имя хеш» по каждой используемой спеке: сверка с бордами в Penpot (verifyIcons)
        print("\n".join(f"{n} {djb2(canon(specs[n]))}" for n in sorted(used, key=layout_key)))
        return 0
    chunks = int(sys.argv[sys.argv.index("--chunks") + 1]) if "--chunks" in sys.argv else 6
    out = pathlib.Path(next((a for a in sys.argv[1:] if a.startswith("/")), "/tmp/icons_serve"))
    out.mkdir(parents=True, exist_ok=True)
    order = sorted(used, key=layout_key)
    per = -(-len(order) // chunks)
    for f in out.glob("chunk_*.txt"):
        f.unlink()
    for k in range(chunks):
        part = order[k * per:(k + 1) * per]
        if part:
            (out / f"chunk_{k}.txt").write_text("\n".join(specs[n] for n in part), encoding="utf-8")
    size = sum(f.stat().st_size for f in out.glob("chunk_*.txt"))
    print(f"спек {len(specs)}, используется {len(order)}, порций {chunks} в {out} ({size} Б)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
