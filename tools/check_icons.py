#!/usr/bin/env python3
"""Строгая проверка спек иконок ровно так, как их читает Icons.parse().

Kotlin:
    val tk = parts[i].trim().split(' ')
    val t = tk[0][0]
    val rad = if (t == 'r') tk[5].toFloat() else 0f
    val sw  = if (t == 'p') tk[5].toFloat() else 0f
    val colIdx = if (t == 'e') 5 else 6

Любой пустой токен (двойной пробел) даёт NumberFormatException,
отсутствие поля — IndexOutOfBoundsException.
"""
import re
import sys

SPEC = "app/src/main/java/ru/ogrpfsense/dexpm8/ui/IconSpecs.kt"


def elements():
    src = open(SPEC).read()
    start = src.index("arrayOf(") + len("arrayOf(")
    end = src.rindex(")")
    body = src[start:end]
    out, cur, in_str, esc = [], [], False, False
    for ch in body:
        if in_str:
            cur.append(ch)
            if esc:
                esc = False
            elif ch == "\\":
                esc = True
            elif ch == '"':
                in_str = False
            continue
        if ch == '"':
            in_str = True
            cur.append(ch)
        elif ch == ",":
            out.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
    if "".join(cur).strip():
        out.append("".join(cur))
    res = []
    for el in out:
        pieces = re.findall(r'"((?:[^"\\]|\\.)*)"', el)
        if pieces:
            res.append("".join(pieces))
    return res


def num(tk, idx, name, where, problems):
    if idx >= len(tk):
        problems.append(f"{name}: нет поля #{idx} в {where!r} (всего {len(tk)})")
        return None
    v = tk[idx]
    if v == "":
        problems.append(f"{name}: пустое поле #{idx} в {where!r} -> NumberFormatException")
        return None
    try:
        float(v)
    except ValueError:
        problems.append(f"{name}: поле #{idx}={v!r} не число в {where!r}")
        return None
    return float(v)


def main() -> int:
    specs = elements()
    print(f"иконок: {len(specs)}")
    problems = []

    for spec in specs:
        parts = spec.split("|")
        name = parts[0]
        if len(parts) < 3:
            problems.append(f"{name}: секций {len(parts)}, нужно >= 3")
            continue
        num(parts, 1, name, "size", problems)
        num(parts, 2, name, "size", problems)

        for i in range(3, len(parts)):
            chunk = parts[i].strip()
            if not chunk:
                continue
            tk = chunk.split(" ")
            if not tk or tk[0] == "":
                problems.append(f"{name}: пустой тип примитива в {chunk[:40]!r}")
                continue
            t = tk[0][0]
            if t not in "rep":
                problems.append(f"{name}: неизвестный тип {t!r} в {chunk[:40]!r}")
                continue

            for idx in (1, 2, 3, 4):
                num(tk, idx, name, chunk[:50], problems)

            if t == "r":
                num(tk, 5, name, chunk[:50], problems)
                col_idx = 6
            elif t == "p":
                num(tk, 5, name, chunk[:50], problems)
                col_idx = 6
            else:
                col_idx = 5

            if col_idx >= len(tk):
                problems.append(f"{name}: нет цвета (поле {col_idx}) в {chunk[:50]!r}")
                continue
            col = tk[col_idx]
            if col == "":
                problems.append(f"{name}: пустой цвет в {chunk[:50]!r}")
                continue
            if not (col.startswith("#") or col.startswith("stroke:")):
                problems.append(f"{name}: цвет не на месте {col_idx}: {col!r} в {chunk[:60]!r}")
                continue
            if col.startswith("stroke:"):
                v = col.split(":")
                if len(v) != 3:
                    problems.append(f"{name}: плохой stroke {col!r}")
                else:
                    try:
                        float(v[2])
                    except ValueError:
                        problems.append(f"{name}: толщина stroke не число: {col!r}")
            if t == "p":
                if len(tk) <= col_idx + 1:
                    problems.append(f"{name}: path без d в {chunk[:50]!r}")
                else:
                    d = " ".join(tk[col_idx + 1:])
                    if not re.match(r"^[MLCQZmlcqz0-9.,\-]+$", d):
                        problems.append(f"{name}: странный d {d[:60]!r}")

    print(f"проблем: {len(problems)}")
    for p in problems[:25]:
        print("  -", p)
    return 0 if not problems else 1


if __name__ == "__main__":
    sys.exit(main())
