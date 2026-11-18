#!/usr/bin/env python3
"""Предпросмотр иконки из IconSpecs.kt и матрицы AuthorQr.kt в PNG (для проверки глазами).

Нужен только разработчику: сборка не использует. Требует Pillow.
    python3 tools/preview_icon.py cup            → /tmp/icon-cup.png
    python3 tools/preview_icon.py cup --on-dark  → белым по G3, как на кнопке B43
"""
import re
import sys
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
SPEC = ROOT / "app/src/main/java/ru/ogrpfsense/dexpm8/ui/IconSpecs.kt"


def spec_of(name: str) -> str:
    """Спека иконки целиком: в IconSpecs.kt она склеена из нескольких литералов через `+`."""
    src = SPEC.read_text(encoding="utf-8")
    start = src.index('"%s|' % name)
    parts = []
    for lit in re.finditer(r'"((?:[^"\\]|\\.)*)"', src[start:]):
        t = lit.group(1)
        if t.startswith("|") or not parts:
            parts.append(t)
        else:
            break
    if not parts:
        raise SystemExit(f"нет иконки {name}")
    return "".join(parts)


def bez(p0, p1, p2, p3, n=24):
    out = []
    for i in range(n + 1):
        t = i / n
        u = 1 - t
        out.append((u * u * u * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t * t * t * p3[0],
                    u * u * u * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t * t * t * p3[1]))
    return out


def path_points(d: str):
    """Разбор M/L/C/Z — как Icons.parseSvg, но с выборкой кривых."""
    tok = re.findall(r"([MLCZ])|(-?\d+(?:\.\d+)?)", d)
    cmd, nums, pts, cur = " ", [], [], (0.0, 0.0)
    start = None

    def flush():
        nonlocal nums, cur, start
        if cmd == "M" and len(nums) >= 2:
            cur = (nums[0], nums[1])
            start = cur
            pts.append(cur)
            i = 2
            while i + 1 < len(nums):
                cur = (nums[i], nums[i + 1])
                pts.append(cur)
                i += 2
        elif cmd == "L":
            i = 0
            while i + 1 < len(nums):
                cur = (nums[i], nums[i + 1])
                pts.append(cur)
                i += 2
        elif cmd == "C" and len(nums) >= 6:
            seg = bez(cur, (nums[0], nums[1]), (nums[2], nums[3]), (nums[4], nums[5]))
            cur = (nums[4], nums[5])
            pts.extend(seg[1:])
        elif cmd == "Z" and start:
            pts.append(start)
            cur = start
        nums = []

    for a, b in tok:
        if a:
            flush()
            cmd = a
        else:
            nums.append(float(b))
    flush()
    return pts


def main() -> int:
    name = sys.argv[1] if len(sys.argv) > 1 else "cup"
    dark = "--on-dark" in sys.argv
    scale = 12
    body = spec_of(name).split("|")
    w, h = float(body[1]), float(body[2])
    img = Image.new("RGB", (int(w * scale), int(h * scale)), (0x33, 0x33, 0x33) if dark else (255, 255, 255))
    dr = ImageDraw.Draw(img)
    for chunk in body[3:]:
        tk = chunk.strip().split(" ")
        t = tk[0][0]
        x, y, pw, ph = (float(v) for v in tk[1:5])
        col_idx = 5 if t == "e" else 6
        spec = tk[col_idx]
        sw = 0.0
        if spec.startswith("stroke:"):
            v = spec.split(":")
            col = v[1]
            sw = float(v[2])
        else:
            col = spec
            if t == "p":
                sw = float(tk[5])
        if dark and col == "#ffffff":
            col = "#ffffff"
        box = [x * scale, y * scale, (x + pw) * scale, (y + ph) * scale]
        if t == "e":
            dr.ellipse(box, outline=col, width=max(1, int(sw * scale)))
        elif t == "r":
            dr.rounded_rectangle(box, radius=float(tk[5]) * scale, outline=col,
                                 width=max(1, int(sw * scale)))
        else:
            pts = [(px * scale, py * scale) for px, py in path_points(" ".join(tk[col_idx + 1:]))]
            if sw > 0:
                dr.line(pts, fill=col, width=max(1, int(sw * scale)), joint="curve")
            else:
                dr.polygon(pts, fill=col)
    out = Path("/tmp") / f"icon-{name}.png"
    img.save(out)
    print(f"{out}  {img.size[0]}×{img.size[1]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
