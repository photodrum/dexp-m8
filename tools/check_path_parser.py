#!/usr/bin/env python3
"""Эмуляция parseSvg из Icons.kt на реальных данных из IconSpecs.kt.

Ловит класс ошибок, которые не видны при компиляции:
  - flush() при пустом списке чисел -> IndexOutOfBoundsException;
  - однозначное число ("5") принимается за команду, потому что проверка длины 1.

Запуск: python3 tools/check_path_parser.py
"""
import re
import sys

sys.path.insert(0, "tools")
SPEC = "app/src/main/java/ru/ogrpfsense/dexpm8/ui/IconSpecs.kt"


def specs():
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


# --- версия "как в текущем Kotlin" ---
def parse_old(d):
    """Возвращает список проблем, которые приведут к исключению."""
    problems = []
    cmd = "M"
    nums = []

    def flush():
        nonlocal nums
        try:
            if cmd == "M":
                _ = nums[0], nums[1]
        except IndexError:
            problems.append(f"IndexOutOfBounds: cmd={cmd!r} nums={nums}")
        nums = []

    for m in re.finditer(r"([MLCQZmlcqz])|(-?\d+(?:\.\d+)?)", d):
        s = m.group(0)
        if len(s) == 1:
            flush()
            cmd = s
        else:
            nums.append(float(s))
    return problems


# --- версия "как надо" ---
def parse_new(d):
    """Правильная токенизация: команда определяется по классу символа."""
    problems = []
    cmd = " "
    nums = []

    def flush():
        nonlocal nums
        if nums and (len(nums) < 2):
            problems.append(f"nums={nums} при cmd={cmd!r}")
        nums = []

    for m in re.finditer(r"([MLCQZmlcqz])|(-?\d+(?:\.\d+)?)", d):
        if m.group(1):
            flush()
            cmd = m.group(1)
        else:
            nums.append(float(m.group(2)))
    flush()
    return problems


def main() -> int:
    bad_old = 0
    bad_new = 0
    total_d = 0
    for spec in specs():
        name = spec.split("|")[0]
        for chunk in spec.split("|")[3:]:
            tk = chunk.strip().split(" ")
            if tk and tk[0] == "p" and len(tk) > 7:
                d = " ".join(tk[7:])
                total_d += 1
                p_old = parse_old(d)
                p_new = parse_new(d)
                if p_old:
                    bad_old += 1
                    if bad_old <= 3:
                        print(f"[СТАРЫЙ КОД УПАДЁТ] {name}: {p_old[0]}")
                        print(f"    d={d[:70]}")
                if p_new:
                    bad_new += 1
                    print(f"[НОВЫЙ КОД] {name}: {p_new}")
    print()
    print(f"путей проверено: {total_d}")
    print(f"упадёт на старом коде: {bad_old}")
    print(f"проблем на новом коде: {bad_new}")
    return 1 if bad_old else 0


if __name__ == "__main__":
    sys.exit(main())
