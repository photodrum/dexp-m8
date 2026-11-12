#!/usr/bin/env python3
"""Компактный вид снимка макета: одна строка на слой, отступ = глубина.

  python3 tools/design_flat.py B22          # весь экран, статус-бар свёрнут
  python3 tools/design_flat.py B22 --full   # и статус-бар
  python3 tools/design_flat.py B22 --y 300:900     # только слои, пересекающие полосу y
  python3 tools/design_flat.py B22 --grep Фильтр   # группы с этим текстом в имени (с детьми)

Строка: `имя тип x,y wxh заливка s:обводка r=радиус "текст" шрифт/кегль/вес[/выравнивание]`.
Числа те же, что в design/v5/B##.json. Если не хватает поля (тени, grow, lh) — открыть JSON.
"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / 'design' / 'v5'


def num(v):
    return str(int(v)) if float(v) == int(v) else f'{v:g}'


def line(n):
    p = [n.get('n', '?'), n.get('t', '?'),
        f"{num(n.get('x', 0))},{num(n.get('y', 0))}",
        f"{num(n.get('w', 0))}x{num(n.get('h', 0))}"]
    if n.get('f'):
        p.append('+'.join(f['c'] + ('/' + str(f['o']) if 'o' in f else '') for f in n['f']))
    if n.get('s'):
        p.append('s:' + '+'.join(f"{s['c']}/{s['w']}{s.get('a', '')[:1]}" for s in n['s']))
    if 'r' in n:
        r = n['r']
        p.append('r=' + (num(r) if not isinstance(r, list) else '/'.join(num(i) for i in r)))
    if n.get('hid'):
        p.append('HIDDEN')
    if 'tx' in n:
        p.append(json.dumps(n['tx'], ensure_ascii=False))
        p.append(f"{n.get('ff', '')}/{n.get('fs', '')}/{n.get('fw', '')}"
     + (f"/{n['ta']}" if 'ta' in n else ''))
    if 'icon' in n:
        p.append('icon=' + n['icon'])
    if 'd' in n:
        p.append(f"d[{len(n['d'])}]")
    return ' '.join(p)


def walk(n, depth, out, full, band):
    if n.get('n') == 'Статус-бар' and not full:
        out.append('  ' * depth + line(n) + ' (свёрнут, --full)')
        return
    y0 = n.get('y', 0)
    y1 = y0 + n.get('h', 0)
    if band and not (y1 >= band[0] and y0 <= band[1]):
        return
    out.append('  ' * depth + line(n))
    for c in n.get('c', []):
        walk(c, depth + 1, out, full, band)


def find(n, text, res):
    if text in n.get('n', ''):
        res.append(n)
        return
    for c in n.get('c', []):
        find(c, text, res)


def main():
    argv = sys.argv[1:]
    if not argv:
        sys.exit(__doc__)
    full = '--full' in argv
    band = None
    grep = None
    for i, v in enumerate(argv):
        if v == '--y':
            lo, hi = argv[i + 1].split(':')
            band = (float(lo), float(hi))
        if v == '--grep':
            grep = argv[i + 1]
    name = argv[0].upper()
    f = ROOT / (name[0] + name[1:].zfill(2) + '.json')
    d = json.loads(f.read_text(encoding='utf-8'))
    out = [f"{d['board']} {d['w']}x{d['h']} " + '+'.join(x['c'] for x in d.get('f', []))]
    roots = d.get('c', [])
    if grep:
        roots = []
        for c in d.get('c', []):
            find(c, grep, roots)
    for c in roots:
        walk(c, 1, out, full, band)
    print('\n'.join(out))


if __name__ == '__main__':
    main()
