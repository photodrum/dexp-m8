#!/usr/bin/env python3
"""Борды шахмат B77–B87 (п. 25.0, решение 2026-11-26) → /tmp/chess_draw/<борд>.json.

Собирает экраны из деталей снимка design/v5 (статус-бар и шапка — B75, пагинация — B11, иконки —
design/v5/icons) и фигур из design/icons.txt (`chessWk` … `chessBp`). Пластик — по формулам
docs/DESIGN_USAGE.md («Пластик»). Позиции на досках считаются из ходов (UCI), чтобы не ошибиться.
Рисует их на странице «chess» tools/penpot_boards_draw.js; обратно в design/v5 их снимает
tools/penpot_dump.js (PAGE = 'chess').

Запуск из корня репозитория:  python3 tools/chess_boards.py [pieces B79 B79а …]   (без списка — все)
"""
import copy
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
V5 = ROOT / 'design' / 'v5'
OUT = pathlib.Path('/tmp/chess_draw')
NUM = re.compile(r'-?\d*\.?\d+')

# --------------------------------------------------------------------------- снимок и иконки


def load(b):
    return json.loads((V5 / f'{b}.json').read_text(encoding='utf-8'))


def child(tree, name):
    return next(c for c in tree['c'] if c['n'] == name)


def shift_d(d, dx, dy, k=1.0):
    """Сдвиг (и масштаб k) абсолютного пути M L C Z."""
    def seg(m):
        nums = NUM.findall(m.group(2))
        return m.group(1) + ','.join(f'{round(float(v) * k + (dy if i % 2 else dx), 2):g}' for i, v in enumerate(nums))
    return re.sub(r'([MLCZ])([^MLCZ]*)', seg, d)


def shift(node, dx, dy):
    n = copy.deepcopy(node)

    def go(x):
        x['x'] = round(x['x'] + dx, 2)
        x['y'] = round(x['y'] + dy, 2)
        if 'd' in x:
            x['d'] = shift_d(x['d'], dx, dy)
        for c in x.get('c', []):
            go(c)
    go(n)
    return n


def icon_file(file):
    return json.loads((V5 / 'icons' / f'{file}.json').read_text(encoding='utf-8'))


def icon_size(file):
    d = icon_file(file)
    return d['w'], d['h']


def icon(file, x, y, k=None):
    """Иконка из design/v5/icons/<file>.json — группа «Иконка / имя», как её снимает penpot_dump.js.
    k — масштаб (контуры и толщины умножаются на k)."""
    d = icon_file(file)
    if not k:
        return {'n': d['name'], 't': 'group', 'x': x, 'y': y, 'w': d['w'], 'h': d['h'],
                'c': [shift(c, x, y) for c in d['c']]}

    def go(c):
        c = copy.deepcopy(c)
        c['x'], c['y'] = round(x + c['x'] * k, 2), round(y + c['y'] * k, 2)
        c['w'], c['h'] = round(c['w'] * k, 2), round(c['h'] * k, 2)
        for s in c.get('s', []):
            s['w'] = round(s['w'] * k, 2)
        if 'd' in c:
            c['d'] = shift_d(c['d'], x, y, k)
        if 'c' in c:
            c['c'] = [go(z) for z in c['c']]
        return c
    return {'n': d['name'], 't': 'group', 'x': x, 'y': y, 'w': round(d['w'] * k, 1), 'h': round(d['h'] * k, 1),
            'c': [go(c) for c in d['c']]}


def expand(node):
    """Узлы `icon` из снимка → раскрытые группы (для копий деталей с бордов v5)."""
    n = copy.deepcopy(node)
    if n.get('t') == 'icon':
        return icon(n['icon'], n['x'], n['y'])
    if 'c' in n:
        n['c'] = [expand(c) for c in n['c']]
    return n


HEXG = {'#' + format(i * 17, '02x') * 3: f'G{i}' for i in range(16)}


def specs():
    out = {}
    for line in (ROOT / 'design' / 'icons.txt').read_text(encoding='utf-8').splitlines():
        if line.startswith('chess') and '|' in line:
            p = line.split('|')
            out[p[0]] = (float(p[1]), float(p[2]), p[3:])
    return out


SPECS = specs()


def piece(name, x, y, size=150.0):
    """Фигура из design/icons.txt: группа «Иконка / chessWk» из примитивов в размере size."""
    w, h, prims = SPECS[name]
    k = size / w
    kids = []
    for i, chunk in enumerate(prims):
        tk = chunk.strip().split(' ')
        t = tk[0][0]
        col = tk[5 if t == 'e' else 6]
        n = {'n': f'{i + 1} {t}'}
        bx, by, bw, bh = (float(v) for v in tk[1:5])
        n.update(x=round(x + bx * k, 2), y=round(y + by * k, 2), w=round(bw * k, 2), h=round(bh * k, 2))
        if t == 'r':
            n['t'], n['r'] = 'rectangle', round(float(tk[5]) * k, 2)
        elif t == 'e':
            n['t'] = 'ellipse'
        else:
            n['t'], n['d'] = 'path', shift_d(' '.join(tk[7:]), x, y, k)
        if col.startswith('stroke:'):
            v = col.split(':')
            st = {'c': HEXG[v[1].lower()], 'w': round(float(v[2]) * k, 2)}
            if len(v) > 3:
                st['cap'] = 'round'
            n['s'] = [st]
        else:
            n['f'] = [{'c': HEXG[col.lower()]}]
        kids.append(n)
    return {'n': 'Иконка / ' + name, 't': 'group', 'x': x, 'y': y, 'w': size, 'h': size, 'c': kids}

# --------------------------------------------------------------------------- примитивы и пластик


def rect(n, x, y, w, h, c=None, r=None, s=None):
    o = {'n': n, 't': 'rectangle', 'x': round(x, 2), 'y': round(y, 2), 'w': round(w, 2), 'h': round(h, 2)}
    if c:
        o['f'] = [{'c': c}]
    if s:
        o['s'] = [s]
    if r:
        o['r'] = r
    return o


def ellipse(n, x, y, w, h, c=None, s=None):
    o = {'n': n, 't': 'ellipse', 'x': round(x, 2), 'y': round(y, 2), 'w': round(w, 2), 'h': round(h, 2)}
    if c:
        o['f'] = [{'c': c}]
    if s:
        o['s'] = [s]
    return o


def path(n, d, c=None, s=None):
    v = [float(z) for z in NUM.findall(d)]
    xs, ys = v[0::2], v[1::2]
    o = {'n': n, 't': 'path', 'x': min(xs), 'y': min(ys), 'w': round(max(xs) - min(xs), 2),
         'h': round(max(ys) - min(ys), 2), 'd': d}
    if c:
        o['f'] = [{'c': c}]
    if s:
        o['s'] = [s]
    return o


def group(n, kids):
    kids = [k for k in kids if k]
    x1 = min(k['x'] for k in kids)
    y1 = min(k['y'] for k in kids)
    x2 = max(k['x'] + k['w'] for k in kids)
    y2 = max(k['y'] + k['h'] for k in kids)
    return {'n': n, 't': 'group', 'x': x1, 'y': y1, 'w': round(x2 - x1, 2), 'h': round(y2 - y1, 2), 'c': kids}


def text(n, x, y, tx, fs, fw, c, w=None, ta=None, lh='1.25', h=None):
    """Текст Manrope; с шириной w — рамка фиксированной ширины (auto-height), иначе auto-width."""
    h = h or round(fs * float(lh))
    o = {'n': n, 't': 'text', 'x': round(x, 2), 'y': round(y, 2), 'w': round(w or len(tx) * fs * 0.56, 1),
         'h': h, 'f': [{'c': c}], 'tx': tx, 'ff': 'Manrope', 'fs': str(fs), 'fw': str(fw), 'lh': lh,
         'grow': 'auto-height' if w else 'auto-width'}
    if ta:
        o['ta'] = ta
    return o


def convex(n, x, y, w, h, r, dark=False, ell=False):
    """Выпуклый пластик W×H: тень (+5, +10), блик (0, 0), поверхность (+3, +3), размер (W−5)×(H−10);
    тёмный (convexDark) — G8 / G6 / G3."""
    sh, hi, su = ('G8', 'G6', 'G3') if dark else ('G10', 'G15', 'G14')
    iw, ih = w - 5, h - 10
    if ell:
        mk = lambda nn, xx, yy, cc: ellipse(nn, xx, yy, iw, ih, cc)  # noqa: E731
    else:
        mk = lambda nn, xx, yy, cc: rect(nn, xx, yy, iw, ih, cc, r)  # noqa: E731
    return group(n, [mk('Тень', x + 5, y + 10, sh), mk('Блик', x, y, hi), mk('Поверхность', x + 3, y + 3, su)])


def inset(n, x, y, w, h, r, deep='G11'):
    """Вдавленный: нижний блик, внутренняя тень (W−3)×(H−3), углубление (+3, +5) (W−6)×(H−8);
    углубление G11 на фоне экрана, G12 — в карточке и листе."""
    return group(n, [rect('Нижний блик', x, y, w, h, 'G15', r),
                     rect('Внутренняя тень', x, y, w - 3, h - 3, 'G10', r),
                     rect('Углубление', x + 3, y + 5, w - 6, h - 8, deep, max(r - 3, 0) if r else None)])


def round_key(n, x, y, d, ico=None, dark=False, k=None):
    """Круглая клавиша диаметром d; иконка — по центру поверхности (+1.5 вниз — на блик иконки)."""
    kids = [convex('Круглая клавиша', x, y, d + 5, d + 10, None, dark, ell=True)]
    if ico:
        iw, ih = icon_size(ico)
        iw, ih = (iw * k, ih * k) if k else (iw, ih)
        cx, cy = x + 3 + d / 2, y + 3 + d / 2
        kids.append(icon(ico, round(cx - iw / 2, 1), round(cy - ih / 2 + 1.5, 1), k))
    return group(n, kids)


def btn(label, x, y, w, h, primary=False, fs=None):
    """Кнопка: основная — тёмный пластик, подпись G15 42/700; выпуклая — подпись G1 38/700."""
    fs = fs or (42 if primary else 38)
    th = round(fs * 1.25)
    face = convex('Поверхность', x, y, w, h, round((h - 10) / 2), dark=primary)
    lab = text('Подпись', x + 3, y + 3 + (h - 10) / 2 - th / 2, label, fs, 700, 'G15' if primary else 'G1',
               w=w - 5, ta='center')
    return group('Кнопка основная / default' if primary else 'Кнопка выпуклая / default', [face, lab])

# --------------------------------------------------------------------------- детали экранов

B75 = load('B75')
B11 = load('B11')


def status_bar():
    return expand(child(B75, 'Статус-бар'))


def header(title, sub):
    """Шапка B75: «Назад», заголовок 60/700, подзаголовок 32/600 G4."""
    h = expand(child(B75, 'Шапка'))
    for c in h['c']:
        if c['n'] == 'Заголовок':
            c['tx'], c['w'] = title, round(len(title) * 33.5)
        elif c['n'] == 'Подзаголовок':
            c['tx'], c['w'] = sub, round(len(sub) * 17.5)
    h['w'] = max(c['x'] + c['w'] for c in h['c']) - h['x']
    return [h]


def section(tx, y, x=59):
    return text('Раздел', x, y, tx.upper(), 30, 700, 'G5')


def panel(n, x, y, w, h):
    return convex(n, x, y, w, h, 51)


def row13(name, desc, y, ico, sep=True, k=None):
    """Строка раздела как на B13: круглая клавиша с иконкой, название, описание, шеврон."""
    kids = [round_key('Клавиша', 77.7, y, 109.7, ico, k=k),
            text('Название', 226.7, y + 9.4, name, 42, 700, 'G1'),
            text('Описание', 226.7, y + 61.7, desc, 32, 600, 'G4', w=987.2)]
    if sep:
        kids.append(rect('Разделитель', 226.7, y + 153.8, 1096.9, 1.8, 'G11'))
    kids.append(icon('next~13ryxmq', 1295.2, y + 35.6))
    return group('Раздел / ' + name, kids)


def row75(name, desc, y, x=88, w=1228, sep=True):
    """Строка списка как на B75: название 42/700, описание 32/600 G4, разделитель G11."""
    kids = [text('Название', x, y, name, 42, 700, 'G1'),
            text('Описание', x, y + 53.5, desc, 32, 600, 'G4')]
    if sep:
        kids.append(rect('Разделитель', x, y + 124, w, 2, 'G11'))
    return group('Строка / ' + name, kids)


def segmented(n, y, items, active, deep='G11', x=56, w=1292):
    """Желоб с сегментами (B35, B22): активный — выпуклый, подпись G1 700; остальные G5 600."""
    seg = (w - 8) / len(items)
    kids = [inset('Желоб', x, y, w, 95, 47.5, deep),
            convex('Активный сегмент', x + 4 + active * seg, y + 4, seg, 90, 40)]
    for i, it in enumerate(items):
        on = i == active
        kids.append(text('Подпись', x + 4 + i * seg, y + 26 + (1 if on else 0), it, 32, 700 if on else 600,
                         'G1' if on else 'G5', w=seg - 5, ta='center'))
    return group(n, kids)


def bottom3(labels):
    """Нижний ряд из трёх выпуклых кнопок 425×142 (верх 1694, низ 1836), как плитки B44."""
    return [btn(t, x, 1694, 425, 142) for t, x in zip(labels, (41, 489, 937))]


def pagination(page):
    p = expand(child(B11, 'Пагинация'))
    for c in p['c']:
        if c['t'] == 'text':
            c['tx'], c['n'] = page, 'Страница'
    return p


def sheet(y, title, extra=()):
    """Нижний лист (B35): тень, блик, поверхность до низа экрана, захват, заголовок 60/700."""
    h = 1872 + 130 - y
    return [group('Нижний лист', [rect('Тень', 2, y + 10, 1404, h - 10, 'G10', 66),
                                  rect('Блик', -3, y, 1404, h - 10, 'G15', 66),
                                  rect('Поверхность', 0, y + 3, 1404, h - 10, 'G14', 66)]),
            rect('Захват', 644, y + 25, 117, 11, 'G11', 5),
            text('Заголовок', 59, y + 56, title, 60, 700, 'G1')] + list(extra)


def dialog(y, h, title, sub, nest, body=None, extra=()):
    """Диалог (B53, B57): плита 1225×H, гнездо значка 140, заголовок, подзаголовок, пояснение."""
    kids = [convex('Диалог', 92, y, 1225, h, 56), inset('Гнездо значка', 152, y + 64, 140, 140, 70, 'G12'), nest,
            text('Заголовок', 332, y + 70, title, 60, 700, 'G1'),
            text('Подзаголовок', 332, y + 152, sub, 32, 600, 'G4')]
    if body:
        kids.append(text('Пояснение', 152, y + 270, body, 38, 500, 'G1', w=1100, h=106, lh='1.4'))
    return kids + list(extra)


def nest_piece(name, y):
    """Фигура 96 в гнезде значка диалога (центр гнезда 222, y + 134)."""
    return piece(name, 222 - 48, y + 134 - 48 + 1, 96)


def nest_icon(file, y):
    w, h = icon_size(file)
    return icon(file, round(222 - w / 2, 1), round(y + 134 - h / 2 + 1.5, 1))


# Под листом и диалогом всё на 4–5 ступеней светлее (DESIGN_USAGE, «Сетка и правила»; как B53, B57).
DIM = {0: 7, 1: 8, 2: 8, 3: 9, 4: 9, 5: 10, 6: 10, 7: 10, 8: 11, 9: 11, 10: 11, 11: 12, 12: 12,
       13: 13, 14: 13, 15: 13}


def dim(nodes):
    out = copy.deepcopy(nodes)

    def go(n):
        for key in ('f', 's'):
            for p in n.get(key, []):
                p['c'] = f"G{DIM[int(p['c'][1:])]}"
        for c in n.get('c', []):
            go(c)
    for n in out:
        if n['n'] != 'Статус-бар':      # статус-бар не трогается
            go(n)
    return out

# --------------------------------------------------------------------------- шахматы: доска

START = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR'
ORDER = 'qrbnp'
CELL = 150
BX, BY = 102, 362          # доска 1200×1200: x 102–1302, y 362–1562


def fen_board(fen):
    b = {}
    for r, row in enumerate(fen.split('/')):
        f = 0
        for ch in row:
            if ch.isdigit():
                f += int(ch)
            else:
                b['abcdefgh'[f] + str(8 - r)] = ch
                f += 1
    return b


def play(moves, fen=START):
    """Ходы UCI → (позиция, последний ход, взятое). took['w'] — что взяли белые. Рокировка — ходом
    короля на две клетки; превращений и взятия на проходе в этих партиях нет."""
    b = fen_board(fen)
    took = {'w': [], 'b': []}
    last = None
    for m in moves.split():
        a, z = m[:2], m[2:4]
        p = b.pop(a)
        if z in b:
            took['w' if p.isupper() else 'b'].append(b[z])
        b[z] = p
        if p in 'Kk' and abs(ord(a[0]) - ord(z[0])) == 2:
            rk = a[1]
            if z[0] == 'g':
                b['f' + rk] = b.pop('h' + rk)
            else:
                b['d' + rk] = b.pop('a' + rk)
        last = (a, z)
    return b, last, took


def pname(ch):
    return 'chess' + ('W' if ch.isupper() else 'B') + ch.lower()


def cell_xy(sq, flip):
    f, r = 'abcdefgh'.index(sq[0]), int(sq[1]) - 1
    if flip:
        f, r = 7 - f, 7 - r
    return BX + f * CELL, BY + (7 - r) * CELL


def is_light(sq):
    return ('abcdefgh'.index(sq[0]) + int(sq[1])) % 2 == 0

def board(pos, flip=False, last=None, sel=None, dots=(), takes=(), check=None):
    """Доска во вдавленной рамке: клетки светлые G14 / тёмные G10. Отметки различаются формой:
    последний ход — тонкая рамка G3 4; выбрано — двойная рамка G0 10 + G15 3; шах — кольцо вокруг
    короля G0 6 + G15 3; ход на пустую — точка G3 Ø40 с ободком G15 3; взятие — уголки G3 36."""
    kids = [inset('Рамка', BX - 9, BY - 9, 1218, 1218, 24, 'G11')]
    cells = []
    for r in range(8, 0, -1):
        for f in 'abcdefgh':
            x, y = cell_xy(f + str(r), flip)
            cells.append(rect(f + str(r), x, y, CELL, CELL, 'G14' if is_light(f + str(r)) else 'G10'))
    kids.append(group('Клетки', cells))
    marks = []
    for sq in (last or ()):
        x, y = cell_xy(sq, flip)
        marks.append(rect('Последний ход / ' + sq, x + 6, y + 6, 138, 138, None, None, {'c': 'G3', 'w': 4}))
    if check:
        x, y = cell_xy(check, flip)
        marks += [ellipse('Шах / ' + check, x + 8, y + 8, 134, 134, None, {'c': 'G0', 'w': 6}),
                  ellipse('Шах / блик', x + 14, y + 14, 122, 122, None, {'c': 'G15', 'w': 3})]
    if sel:
        x, y = cell_xy(sel, flip)
        marks += [rect('Выбрано / ' + sel, x + 5, y + 5, 140, 140, None, None, {'c': 'G0', 'w': 10}),
                  rect('Выбрано / блик', x + 11.5, y + 11.5, 127, 127, None, None, {'c': 'G15', 'w': 3})]
    if marks:
        kids.append(group('Отметки', marks))
    coords = []
    for i in range(8):
        # цифры — в левом столбце у верхнего края клетки, буквы — в нижнем ряду справа
        rank = str(8 - i) if not flip else str(i + 1)
        sq = ('a' if not flip else 'h') + rank
        x, y = cell_xy(sq, flip)
        coords.append(text('Ряд ' + rank, x + 8, y + 4, rank, 26, 700, 'G8' if is_light(sq) else 'G14'))
        fl = 'abcdefgh'[i] if not flip else 'hgfedcba'[i]
        sq = fl + ('1' if not flip else '8')
        x, y = cell_xy(sq, flip)
        coords.append(text('Столбец ' + fl, x + 112, y + 112, fl, 26, 700, 'G8' if is_light(sq) else 'G14',
                           w=30, ta='right'))
    kids.append(group('Координаты', coords))
    pcs = []
    for r in range(8, 0, -1):
        for f in 'abcdefgh':
            if f + str(r) in pos:
                x, y = cell_xy(f + str(r), flip)
                pcs.append(piece(pname(pos[f + str(r)]), x, y))
    kids.append(group('Фигуры', pcs))
    hints = []
    for sq in dots:
        x, y = cell_xy(sq, flip)
        hints.append(ellipse('Ход / ' + sq, x + 55, y + 55, 40, 40, 'G3', {'c': 'G15', 'w': 3}))
    for sq in takes:
        x, y = cell_xy(sq, flip)
        L, C = 36, CELL
        d = (f'M{x},{y}L{x + L},{y}L{x},{y + L}Z'
             f'M{x + C},{y}L{x + C},{y + L}L{x + C - L},{y}Z'
             f'M{x + C},{y + C}L{x + C - L},{y + C}L{x + C},{y + C - L}Z'
             f'M{x},{y + C}L{x},{y + C - L}L{x + L},{y + C}Z')
        hints.append(path('Взятие / ' + sq, d, 'G3'))
    if hints:
        kids.append(group('Возможные ходы', hints))
    return group('Доска', kids)

def captured(lst, x, y):
    """Взятые фигуры: те же фигуры в размере 44, по ценности, шаг 38."""
    lst = sorted(lst, key=lambda c: ORDER.index(c.lower()))
    if not lst:
        return None
    return group('Взятые', [piece(pname(c), x + i * 38, y, 44) for i, c in enumerate(lst)])


def clock(n, x, y, tx, run=False, low=False):
    """Часы 220×86: идут — тёмный пластик (convexDark), цифры G15; стоят — выпуклый светлый, G1;
    мало времени — тёмный пластик и рамка G0 4 вокруг, цифры 800 (отличие рамкой, не цветом)."""
    kids = []
    if low:
        kids.append(rect('Рамка «мало времени»', x - 7, y - 7, 229, 100, None, 46, {'c': 'G0', 'w': 4}))
    kids.append(convex('Поверхность', x, y, 220, 86, 38, dark=run or low))
    kids.append(text('Время', x + 3, y + 12, tx, 48, 800 if low else 700, 'G15' if (run or low) else 'G1',
                     w=215, ta='center', lh='1.2'))
    return group(n, kids)


def strip(top, name, status=None, active=False, took=(), moves=None, clk=None):
    """Полоса игрока: имя 42/700; справа — состояние 32 (идёт ход — G1 700) или часы; второй строкой —
    взятые фигуры слева и последние ходы справа 32/600 G4."""
    kids = [text('Имя', 41, top, name, 42, 700, 'G1')]
    right = 1360
    if clk:
        kids.append(clk)
        right = 1116
    elif status:
        kids.append(text('Состояние', 660, top + 6, status, 32, 700 if active else 600, 'G1' if active else 'G4',
                         w=700, ta='right'))
    kids.append(captured(list(took), 41, top + 56))
    if moves:
        kids.append(text('Последние ходы', right - 700, top + 58, moves, 32, 600, 'G4', w=700, ta='right'))
    return group('Полоса / ' + name, kids)


# --------------------------------------------------------------------------- партии на макетах

GIUOCO = ('e2e4 e7e5 g1f3 b8c6 f1c4 f8c5 c2c3 g8f6 d2d4 e5d4 c3d4 c5b4 c1d2 b4d2 b1d2 d7d5 '
          'e4d5 f6d5 d1b3 c6e7 e1g1 e8g8 f1e1 c7c6')
G12, G12_LAST, G12_TOOK = play(GIUOCO)                   # после 12… c6, ход белых
G13, G13_LAST, G13_TOOK = play(GIUOCO + ' d2e4')         # после 13. Кe4, ход чёрных
CHECK, CHECK_LAST, _ = play('e2e4 e7e5 b1c3 g8f6 d2d3 b8c6 c3d5 f8b4')   # 4… Сb4+
OPERA = ('e2e4 e7e5 g1f3 d7d6 d2d4 c8g4 d4e5 g4f3 d1f3 d6e5 f1c4 g8f6 f3b3 d8e7 b1c3 c7c6 c1g5 b7b5 '
         'c3b5 c6b5 c4b5 b8d7 e1c1 a8d8 d1d7 d8d7 h1d1 e7e6 b5d7 f6d7 b3b8 d7b8 d1d8')   # Морфи, 1858
OP, OP_LAST, _ = play(OPERA)
PROMO = fen_board('4r3/1P3pkp/6p1/8/8/6P1/5PKP/2R5')
OPERA_SAN = [('e4', 'e5'), ('Кf3', 'd6'), ('d4', 'Сg4'), ('dxe5', 'Сxf3'), ('Фxf3', 'dxe5'), ('Сc4', 'Кf6'),
             ('Фb3', 'Фe7'), ('Кc3', 'c6'), ('Сg5', 'b5'), ('Кxb5', 'cxb5'), ('Сxb5+', 'Кbd7'),
             ('O-O-O', 'Лd8'), ('Лxd7', 'Лxd7'), ('Лd1', 'Фe6'), ('Сxd7+', 'Кxd7'), ('Фb8+', 'Кxb8'),
             ('Лd8#', '')]
SUB_PC = 'С компьютером · уровень 4'
SUB_NET = 'FICS · 15 + 10 · без рейтинга'


def game_pc(v):
    """B79 — после 13. Кe4 думает компьютер; B79а — все отметки сразу (4… Сb4+, выбран Кd5);
    B79б — играем чёрными, доска перевёрнута."""
    kids = [status_bar()] + header('Шахматы', SUB_PC)
    if v == '':
        kids += [strip(246, 'Компьютер', 'Думает…', True, G13_TOOK['b']), board(G13, last=G13_LAST),
                 strip(1578, 'Вы', 'белые', False, G13_TOOK['w'], '12. Лfe1 c6  13. Кe4')]
    elif v == 'а':
        kids += [strip(246, 'Компьютер', 'чёрные'),
                 board(CHECK, last=CHECK_LAST, sel='d5', dots=['c3'], takes=['b4'], check='e1'),
                 strip(1578, 'Вы', 'Шах · ваш ход', True, (), '3. d3 Кc6  4. Кd5 Сb4+')]
    else:
        kids += [strip(246, 'Компьютер', 'белые', False, G13_TOOK['w']), board(G13, flip=True, last=G13_LAST),
                 strip(1578, 'Вы', 'Ваш ход', True, G13_TOOK['b'], '12. Лfe1 c6  13. Кe4')]
    return kids + bottom3(['Ходы', 'Отменить ход', 'Меню'])


def game_net(v):
    """B86 — идут часы соперника; B86а — у игрока мало времени."""
    kids = [status_bar()] + header('По сети', SUB_NET)
    if v == '':
        kids += [strip(246, 'GuestQRTZ', took=G13_TOOK['b'], clk=clock('Часы / идут', 1140, 250, '11:58', run=True)),
                 board(G13, last=G13_LAST),
                 strip(1578, 'Вы · GuestMBKL', took=G13_TOOK['w'], moves='12. Лfe1 c6  13. Кe4',
                       clk=clock('Часы / стоят', 1140, 1584, '13:20'))]
    else:
        kids += [strip(246, 'GuestQRTZ', took=G12_TOOK['b'], clk=clock('Часы / стоят', 1140, 250, '4:05')),
                 board(G12, last=G12_LAST),
                 strip(1578, 'Вы · GuestMBKL', took=G12_TOOK['w'], moves='11. O-O O-O  12. Лfe1 c6',
                       clk=clock('Часы / мало времени', 1140, 1584, '0:42', low=True))]
    return kids + bottom3(['Ходы', 'Ничья', 'Сдаться'])

def menu(v):
    """B77 — меню «Шахматы» (вход — значок `chess` на B6); B77а — нет сохранённой партии."""
    kids = [status_bar()] + header('Шахматы', 'С компьютером или по сети')
    rows = []
    if v == '':
        rows.append(('Продолжить партию', 'С компьютером, уровень 4 · ход 13 · сегодня, 14:20', 'clock~1vi369', None))
    rows += [('С компьютером', 'Без сети · 8 уровней силы', 'chess~1rhfx44', 0.537),
             ('По сети', 'FICS, гостем · без регистрации и рейтинга', 'wifi~1qmu6za', None)]
    ph = 41.4 + 175.5 * (len(rows) - 1) + 119.7 + 41.4
    kids.append(panel('Панель разделов', 41.1, 245.9, 1320.8, ph + 10))
    for i, (n, d, ico, k) in enumerate(rows):
        kids.append(row13(n, d, 287.3 + 175.5 * i, ico, sep=i < len(rows) - 1, k=k))
    kids.append(text('Пояснение', 59, 245.9 + ph + 50,
                     'Партия сохраняется сама: из неё можно выйти в любой момент.', 32, 600, 'G4', w=1290))
    return kids


def new_pc():
    """B78 — новая партия с компьютером: уровень ‹ 4 ›, цвет, часы, «Начать партию»."""
    return [status_bar()] + header('С компьютером', 'Новая партия') + [
        section('Уровень', 250),
        round_key('Слабее', 56, 300, 110, 'back~19mwmtk'),
        inset('Значение уровня', 196, 300, 240, 116, 40, 'G11'),
        text('Уровень', 199, 328.3, '4', 48, 700, 'G1', w=236, ta='center', lh='1.2'),
        round_key('Сильнее', 470, 300, 110, 'next~13ryxmq', k=1.207),   # шеврон B13 в размере «Слабее»
        text('Название уровня', 625, 306, 'Уровень 4 из 8', 42, 700, 'G1'),
        text('Описание уровня', 625, 360, 'Ход за доли секунды · иногда ошибается', 32, 600, 'G4', w=735),
        section('Цвет', 480),
        segmented('Цвет', 530, ['Белые', 'Чёрные', 'Случайно'], 0),
        section('Часы', 680),
        segmented('Часы', 730, ['Без часов', '15 + 10', '30 + 0'], 0),
        text('Пояснение', 59, 850, 'Без часов партию можно отложить и продолжить позже. С часами книга '
             'не засыпает до конца партии.', 32, 600, 'G4', w=1290, h=80),
        btn('Начать партию', 41, 1694, 1321, 142, primary=True)]


def promo():
    """B80 — превращение пешки: лист с четырьмя фигурами и «Отмена»; под ним доска светлее."""
    under = [status_bar()] + header('Шахматы', SUB_PC) + [
        strip(246, 'Компьютер', 'чёрные'), board(PROMO, last=('f8', 'g7'), sel='b7', dots=['b8'])]
    y = 1080
    tiles = []
    for i, (k, lab) in enumerate([('q', 'Ферзь'), ('r', 'Ладья'), ('b', 'Слон'), ('n', 'Конь')]):
        x = 56 + i * 329
        tiles.append(group('Фигура / ' + lab, [convex('Клавиша', x, y + 210, 305, 330, 44),
                                               piece('chessW' + k, x + 78, y + 250),
                                               text('Подпись', x + 3, y + 430, lab, 38, 700, 'G1', w=300, ta='center')]))
    return dim(under) + sheet(y, 'Превращение пешки', [
        text('Подзаголовок', 59, y + 140, 'b7 – b8 · выберите фигуру', 32, 600, 'G4')] + tiles +
        [btn('Отмена', 56, 1694, 1292, 142)])


def moves_list():
    """B81 — ходы партии: две колонки, номер, SAN; страницы «‹ N / M ›»; текущий ход вдавлен."""
    kids = [status_bar()] + header('Ходы партии', SUB_PC + ' · 17 ходов') + [
        panel('Панель', 41, 246, 1321, 1430),
        text('Раздел', 88, 286, 'ХОД', 30, 700, 'G5'), text('Раздел', 300, 286, 'БЕЛЫЕ', 30, 700, 'G5'),
        text('Раздел', 830, 286, 'ЧЁРНЫЕ', 30, 700, 'G5'), rect('Разделитель', 88, 340, 1228, 2, 'G11')]
    y = 352
    for i in range(10, 17):
        w, b = OPERA_SAN[i]
        n = i + 1
        rk = [inset('Текущий ход', 270, y + 14, 480, 100, 36, 'G12')] if n == 17 else []
        rk += [text('Номер', 88, y + 38, f'{n}.', 42, 600, 'G4'), text('Белые', 300, y + 38, w, 42, 700, 'G1')]
        if b:
            rk.append(text('Чёрные', 830, y + 38, b, 42, 700, 'G1'))
        if n != 17:
            rk.append(rect('Разделитель', 88, y + 127, 1228, 2, 'G11'))
        kids.append(group(f'Ход / {n}', rk))
        y += 128
    return kids + [text('Итог', 88, y + 30, '1–0 · мат на 17-м ходу', 32, 600, 'G4'), pagination('2 / 2')]


def game_menu():
    """B82 — меню партии (лист) поверх доски B79."""
    y = 860
    rows = [('Предложить ничью', 'Компьютер решит по оценке позиции'),
            ('Отменить ход', 'Вернуть свой последний ход — только с компьютером'),
            ('Перевернуть доску', 'Смотреть со стороны чёрных'),
            ('Новая партия', 'Эта партия не сохранится'),
            ('Сдаться', 'Партия закончится поражением')]
    extra = [btn('Закрыть', 1080, y + 42.6, 273, 108),
             text('Подзаголовок', 59, y + 136, SUB_PC + ' · ход 13', 32, 600, 'G4')]
    for i, (n, d) in enumerate(rows):
        extra.append(row75(n, d, y + 220 + 152 * i, x=59, w=1289, sep=i < len(rows) - 1))
    return dim(game_pc('')) + sheet(y, 'Партия', extra)

def game_over(v):
    """B83 — мат (партия Морфи, 17. Лd8#); B83а — ничья троекратным повторением."""
    if v == '':
        under = [status_bar()] + header('Шахматы', SUB_PC) + [
            strip(246, 'Компьютер', 'чёрные'), board(OP, last=OP_LAST, check='e8'),
            strip(1578, 'Вы', 'белые', False, (), '16. Фb8+ Кxb8  17. Лd8#')] + bottom3(['Ходы', 'Отменить ход', 'Меню'])
        t, s, b = 'Мат. Вы победили', 'Белые · 17. Лd8#', 'Компьютер, уровень 4. Партию можно посмотреть по ходам.'
    else:
        under = game_pc('')
        t, s, b = 'Ничья', 'Троекратное повторение позиции', 'Одна и та же позиция повторилась три раза.'
    y = 520
    extra = [btn('Новая партия', 152, y + 446, 1100, 142, primary=True),
             btn('Смотреть ходы', 152, y + 618, 538, 112), btn('В меню', 714, y + 618, 538, 112)]
    return dim(under) + dialog(y, 800, t, s, nest_piece('chessWk', y), b, extra)


def resign():
    """B84 — «Сдаться?» — подтверждение, как B53."""
    y = 560
    extra = [btn('Отмена', 152, y + 461, 425, 112), btn('Сдаться', 612, y + 446, 645, 142, primary=True)]
    return dim(game_pc('')) + dialog(y, 662, 'Сдаться?', SUB_PC + ' · ход 13', nest_piece('chessWk', y),
                                     'Партия закончится вашим поражением.', extra)


def net_search(v):
    """B85 — поиск соперника; B85а — ожидание (диалог с секундомером и «Отмена»)."""
    kids = [status_bar()] + header('По сети', 'FICS · freechess.org') + [
        section('Контроль времени', 250),
        segmented('Контроль', 300, ['15 + 10', '30 + 0', '10 + 5'], 0),
        text('Пояснение', 59, 420, 'Минуты на партию + секунды за ход. Короче 10 минут на E-Ink неудобно.',
             32, 600, 'G4', w=1290),
        section('Вы играете', 520),
        panel('Панель', 41, 570, 1321, 336),
        row75('Гость', 'Имя выдаст сервер: Guest и четыре буквы', 611),
        row75('Без рейтинга', 'Гости FICS играют только безрейтинговые партии', 763, sep=False),
        text('Пояснение', 59, 946, 'Во время партии с часами книга не засыпает.', 32, 600, 'G4', w=1290),
        btn('Искать соперника', 41, 1694, 1321, 142, primary=True)]
    if v == '':
        return kids
    y = 560
    return dim(kids) + dialog(y, 662, 'Ищем соперника…', '15 + 10 · FICS, гостем', nest_icon('clock~1vi369', y), None, [
        text('Ожидание', 152, y + 250, '0:42', 108, 200, 'G1', w=1100, ta='center', lh='1.2'),
        text('Пояснение', 152, y + 390, 'Ждём человека с тем же контролем времени', 32, 600, 'G4', w=1100, ta='center'),
        btn('Отмена', 152, y + 486, 1100, 112)])


def net_state(v):
    """B87 — нет сети; B87а — соперник отключился; B87б — соперник предлагает ничью."""
    y = 610
    if v == '':
        return dim(net_search('')) + dialog(
            y, 662, 'Нет подключения', 'Игра по сети · FICS', nest_icon('wifi~1fcfkco', y),
            'Wi-Fi выключен или сервер недоступен. С компьютером можно играть и без сети.',
            [btn('Повторить', 152, y + 461, 425, 112), btn('Настройки Wi-Fi', 612, y + 446, 645, 142, primary=True)])
    if v == 'а':
        return dim(game_net('')) + dialog(
            y, 662, 'Соперник отключился', 'GuestQRTZ · ход 13', nest_icon('wifi~1fcfkco', y),
            'Часы остановлены. Если соперник не вернётся, сервер прервёт партию.',
            [btn('В меню', 152, y + 461, 425, 112), btn('Ждать', 612, y + 446, 645, 142, primary=True)])
    return dim(game_net('')) + dialog(
        y, 662, 'Ничья?', 'GuestQRTZ предлагает ничью · ход 13', nest_piece('chessBk', y),
        'Если отклонить, партия продолжится. Часы идут.',
        [btn('Отклонить', 152, y + 461, 425, 112), btn('Принять', 612, y + 446, 645, 142, primary=True)])

# --------------------------------------------------------------------------- раскладка на странице

GAP = 200
BOARDS = [  # номер, экран, функция, столбец, ряд (состояния — под основным бордом)
    ('B77', 'Шахматы · меню', lambda: menu(''), 0, 0),
    ('B77а', 'Шахматы · меню · нет партии', lambda: menu('а'), 0, 1),
    ('B78', 'Шахматы · новая партия с компьютером', new_pc, 1, 0),
    ('B79', 'Шахматы · партия с компьютером', lambda: game_pc(''), 2, 0),
    ('B79а', 'Шахматы · партия · отметки на доске', lambda: game_pc('а'), 2, 1),
    ('B79б', 'Шахматы · партия · чёрными', lambda: game_pc('б'), 2, 2),
    ('B80', 'Шахматы · превращение пешки · лист', promo, 3, 0),
    ('B81', 'Шахматы · ходы партии', moves_list, 4, 0),
    ('B82', 'Шахматы · меню партии · лист', game_menu, 5, 0),
    ('B83', 'Шахматы · конец партии · диалог', lambda: game_over(''), 6, 0),
    ('B83а', 'Шахматы · конец партии · ничья', lambda: game_over('а'), 6, 1),
    ('B84', 'Шахматы · сдаться · подтверждение', resign, 7, 0),
    ('B85', 'Шахматы · по сети · поиск', lambda: net_search(''), 8, 0),
    ('B85а', 'Шахматы · по сети · ожидание', lambda: net_search('а'), 8, 1),
    ('B86', 'Шахматы · партия по сети', lambda: game_net(''), 9, 0),
    ('B86а', 'Шахматы · партия по сети · мало времени', lambda: game_net('а'), 9, 1),
    ('B87', 'Шахматы · нет сети · диалог', lambda: net_state(''), 10, 0),
    ('B87а', 'Шахматы · соперник отключился', lambda: net_state('а'), 10, 1),
    ('B87б', 'Шахматы · соперник предлагает ничью', lambda: net_state('б'), 10, 2),
]


def piece_boards():
    """Фигуры отдельными бордами 150×150 (имя борда = имя спеки) и проверочная доска 6×4."""
    out = {}
    for i, n in enumerate(f'chess{c}{k}' for c in 'WB' for k in 'kqrbnp'):
        w, h, _ = SPECS[n]
        out[n] = {'board': n, 'w': w, 'h': h, 'px': i * 190, 'py': -900, 'f': [{'c': 'G12'}],
                  'c': piece(n, 0, 0)['c']}
    kids = [rect(f'Клетка {r}{f}', f * 150, r * 150, 150, 150, 'G14' if (r + f) % 2 == 0 else 'G10')
            for r in range(4) for f in range(6)]
    kids += [piece(f"chess{'W' if r < 2 else 'B'}{k}", f * 150, r * 150)
             for r in range(4) for f, k in enumerate('kqrbnp')]
    out['Фигуры'] = {'board': 'Фигуры · на светлой и тёмной клетке', 'w': 900, 'h': 600, 'px': 2400, 'py': -900,
                     'f': [{'c': 'G12'}], 'c': kids}
    return out


def main():
    want = set(sys.argv[1:])
    OUT.mkdir(parents=True, exist_ok=True)
    made = {}
    if not want or 'pieces' in want:
        made.update(piece_boards())
    for num, screen, fn, col, row in BOARDS:
        if not want or num in want:
            made[num] = {'board': f'{num} · {screen} · Пластик · 1404×1872', 'w': 1404, 'h': 1872,
                         'px': col * (1404 + GAP), 'py': row * (1872 + GAP), 'f': [{'c': 'G12'}], 'c': fn()}
    for k, tree in made.items():
        (OUT / f'{k}.json').write_text(json.dumps(tree, ensure_ascii=False), encoding='utf-8')
    print(' '.join(f"{k}:{(OUT / f'{k}.json').stat().st_size // 1024}K" for k in made))
    return 0


if __name__ == '__main__':
    sys.exit(main())
