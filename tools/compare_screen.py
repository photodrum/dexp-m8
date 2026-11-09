#!/usr/bin/env python3
"""Сверка снимка экрана книги с эталоном из Penpot по слоям верхнего уровня.

Запуск из корня репозитория:
  python3 tools/compare_screen.py B01 build/shots/b1.png build/ref/b1.png
  python3 tools/compare_screen.py B02 build/shots/b2.png build/ref/b2.png

Аргументы: имя файла в design/v5 (без .json), снимок с книги, эталон Penpot.
Нужен Pillow (pip install --user pillow).

Что делает:
  * переводит обе картинки в градации серого;
  * закрашивает одним цветом слои, значения которых на книге другие: время, дата,
    заряд батареи (по имени слоя и по тексту вида «10:42»);
  * для каждого слоя верхнего уровня из JSON берёт рамку (+ поле 12 px) и считает долю
    пикселей, отличающихся больше чем на 2 ступени палитры (34 из 255), и сдвиг краёв
    содержимого (слева, сверху, справа, снизу) между эталоном и снимком;
  * допуск сдвига ±2 px; для слоёв, где только текст, ±4 px (глифы браузера и Android
    различаются);
  * пишет картинку различий build/diff_<экран>.png: эталон бледно, отличия красным.
Код возврата 0 — все слои в допуске, 1 — есть слои вне допуска.
"""
import json
import pathlib
import re
import sys

from PIL import Image, ImageChops

ROOT = pathlib.Path(__file__).resolve().parent.parent
PAD = 12
DIFF_LEVEL = 34
EDGE_LEVEL = 12      # рамка содержимого: плитка G14 на фоне G12 отличается ровно на 34
MASK_GROW = 250      # текст даты на книге длиннее эталонного — маска шире вправо
TOL = 2
TOL_TEXT = 4
MASK_NAME = re.compile(r"Врем|Дат|Батаре|Заряд", re.I)
MASK_TEXT = re.compile(r"^\d{1,2}:\d{2}$")
MASK_FILL = 128


def load_gray(path, size):
    img = Image.open(path).convert("RGBA")
    flat = Image.new("RGBA", img.size, (255, 255, 255, 255))
    flat.alpha_composite(img)
    gray = flat.convert("L")
    if size and gray.size != size:
        sys.exit(f"{path}: размер {gray.size}, ожидался {size}")
    return gray


def masks(node, out):
    """Рамки слоёв, значения которых на книге другие."""
    if not isinstance(node, dict):
        return
    if node.get("hid") == 1:
        return
    name = node.get("n", "")
    tx = node.get("tx", "")
    if MASK_NAME.search(name) or (node.get("t") == "text" and MASK_TEXT.match(tx.strip())):
        grow = MASK_GROW if re.search(r"Дат", name) else 0
        out.append((node["x"], node["y"], node["w"] + grow, node["h"]))
        return
    for child in node.get("c", []) or []:
        masks(child, out)


def only_text(node):
    if node.get("t") == "text":
        return True
    kids = node.get("c", []) or []
    return bool(kids) and all(only_text(k) for k in kids)


def box(x, y, w, h, size):
    x0 = max(0, int(x - PAD))
    y0 = max(0, int(y - PAD))
    x1 = min(size[0], int(x + w + PAD + 0.999))
    y1 = min(size[1], int(y + h + PAD + 0.999))
    return x0, y0, x1, y1


def background(img, b):
    """Фон области — медиана пикселей по её периметру."""
    x0, y0, x1, y1 = b
    ring = []
    for x in range(x0, x1):
        ring.append(img.getpixel((x, y0)))
        ring.append(img.getpixel((x, y1 - 1)))
    for y in range(y0, y1):
        ring.append(img.getpixel((x0, y)))
        ring.append(img.getpixel((x1 - 1, y)))
    ring.sort()
    return ring[len(ring) // 2]


def content_edges(img, b):
    """Рамка содержимого: пиксели, отличные от фона области больше чем на DIFF_LEVEL."""
    bg = background(img, b)
    crop = img.crop(b)
    mask = crop.point(lambda v: 255 if abs(v - bg) > EDGE_LEVEL else 0)
    bb = mask.getbbox()
    if bb is None:
        return None
    return (b[0] + bb[0], b[1] + bb[1], b[0] + bb[2], b[1] + bb[3])


def main():
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    screen, shot_path, ref_path = sys.argv[1:]
    design = json.load(open(ROOT / "design" / "v5" / f"{screen}.json"))
    size = (design["w"], design["h"])
    shot = load_gray(shot_path, size)
    ref = load_gray(ref_path, size)

    skip = []
    for g in design["c"]:
        masks(g, skip)
    for x, y, w, h in skip:
        r = (int(x), int(y), int(x + w + 0.999), int(y + h + 0.999))
        for img in (shot, ref):
            img.paste(MASK_FILL, r)

    diff = ImageChops.difference(shot, ref).point(lambda v: 255 if v > DIFF_LEVEL else 0)
    total = diff.histogram()[255]
    print(f"{screen}: исключено областей {len(skip)}, "
          f"несовпавших пикселей {total} из {size[0] * size[1]} "
          f"({100.0 * total / (size[0] * size[1]):.2f} %)")

    pale = ref.point(lambda v: 160 + v // 4)
    keep = ImageChops.multiply(pale, diff.point(lambda v: 255 - v))
    out = Image.merge("RGB", (ImageChops.lighter(pale, diff), keep, keep))
    out_path = ROOT / "build" / f"diff_{screen}.png"
    out_path.parent.mkdir(exist_ok=True)
    out.save(out_path)
    print(f"картинка различий: {out_path}")

    print(f"{'слой':<34}{'тип':<7}{'% откл.':>8}  сдвиг краёв L T R B, px   итог")
    bad = 0
    for g in design["c"]:
        if g.get("hid") == 1:
            continue
        b = box(g["x"], g["y"], g["w"], g["h"], size)
        d = diff.crop(b)
        n = (b[2] - b[0]) * (b[3] - b[1])
        pct = 100.0 * d.histogram()[255] / n
        er = content_edges(ref, b)
        es = content_edges(shot, b)
        text = only_text(g)
        tol = TOL_TEXT if text else TOL
        if er is None and es is None:
            shift, ok = "—", True
        elif er is None or es is None:
            shift, ok = "нет содержимого на " + ("снимке" if es is None else "эталоне"), False
        else:
            s = [es[i] - er[i] for i in range(4)]
            shift = " ".join(f"{v:+d}" for v in s)
            ok = max(abs(v) for v in s) <= tol
        bad += 0 if ok else 1
        name = g.get("n", "")[:32]
        print(f"{name:<34}{'текст' if text else 'фигура':<7}{pct:>8.2f}  {shift:<26}"
              f"{'ок' if ok else 'ВНЕ ДОПУСКА (±%d)' % tol}")
    print(f"слоёв вне допуска: {bad}")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
