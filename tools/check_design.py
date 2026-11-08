#!/usr/bin/env python3
"""Проверка снимка макета design/v5/*.json.

Что проверяет:
  * каждый файл — корректный JSON;
  * все цвета (заливки, обводки, тени) — токены палитры G0..G15, без hex;
  * нет полупрозрачности: ни o (прозрачность заливки/обводки), ни op (слой);
  * каждая ссылка "icon" ведёт на существующий файл design/v5/icons/<icon>.json.

Запуск из корня репозитория:  python3 tools/check_design.py
Код возврата 0 — всё чисто, 1 — есть нарушения (они перечислены).
"""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent / "design" / "v5"
TOKEN = re.compile(r"^G(1[0-5]|[0-9])$")


def walk(node, path, errors, icons_used):
    if isinstance(node, list):
        for i, item in enumerate(node):
            walk(item, f"{path}[{i}]", errors, icons_used)
        return
    if not isinstance(node, dict):
        return
    where = f"{path} «{node.get('n', '')}»"
    # скрытые служебные слои иконок (base-background) не рисуются — их цвет не важен
    hidden = node.get("hid") == 1
    for key in ("f", "s", "sh"):
        for paint in node.get(key, []) or []:
            c = paint.get("c")
            if c is not None and not TOKEN.match(str(c)) and not hidden:
                errors.append(f"{where}: цвет вне палитры {c}")
            o = paint.get("o")
            if o is not None and o != 1 and not hidden:
                errors.append(f"{where}: полупрозрачность {key}.o={o}")
    if node.get("op", 1) != 1 and not hidden:
        errors.append(f"{where}: прозрачность слоя op={node['op']}")
    if node.get("t") == "icon":
        icons_used.add(node.get("icon"))
    for child in node.get("c", []) or []:
        walk(child, path + "/" + str(child.get("n", "?") if isinstance(child, dict) else "?"), errors, icons_used)


def main():
    if not ROOT.is_dir():
        print(f"нет каталога {ROOT}")
        return 1
    errors, icons_used = [], set()
    files = sorted(ROOT.glob("*.json")) + sorted((ROOT / "icons").glob("*.json"))
    for f in files:
        try:
            data = json.loads(f.read_text(encoding="utf-8"))
        except json.JSONDecodeError as e:
            errors.append(f"{f.name}: битый JSON — {e}")
            continue
        if f.name == "tokens.json":
            continue
        walk(data, f.relative_to(ROOT).as_posix(), errors, icons_used)
    for icon in sorted(i for i in icons_used if i):
        if not (ROOT / "icons" / f"{icon}.json").is_file():
            errors.append(f"нет файла иконки icons/{icon}.json")
    for e in errors:
        print("✗", e)
    print(f"проверено файлов: {len(files)}, иконок по ссылкам: {len(icons_used)}, нарушений: {len(errors)}")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
