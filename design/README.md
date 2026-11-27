# Снимок макета Penpot

Здесь лежит машиночитаемая копия макета: файл «UI» в Penpot, страница **v5**.
Код экранов берёт числа отсюда, а не из таблиц `docs/DESIGN.md` и не «на глаз» с экрана.

| Файл | Что внутри |
|---|---|
| `v5/tokens.json` | палитра G0–G15, 16 цветов и 17 текстовых стилей из библиотеки файла |
| `v5/B01.json`, `v5/B02.json`, … | один экран: все слои рекурсивно, координаты в дизайн-px от левого верхнего угла борда |
| `v5/icons/<имя>~<хеш>.json` | одна иконка: слои относительно левого верхнего угла иконки |

Сняты **все 76 бордов** страницы v5 (2026-11-15, последняя пачка — B7, B8, B9, B10, B19, B20, B27, B28,
B29, B30, B32–B35, B47, B50–B52, B56, B60–B63, B70–B76). К Penpot за геометрией больше ходить не нужно;
скрипт остаётся для обновления, если макет поменяется.

Плюс **19 бордов шахмат** со страницы «chess» (2026-11-26, п. 25.0): B77, B77а, B78, B79, B79а, B79б,
B80–B82, B83, B83а, B84, B85, B85а, B86, B86а, B87, B87а, B87б — см. «Шахматы» ниже.

Состояния одного экрана — отдельные файлы с буквой в имени: `B31.json` — основной вид,
`B31а.json` — выбранная ручка обрезки, `B31б.json` — желоб масштаба в режиме «− · 140 % · +».

## Как снять или обновить экран

1. Открыть `tools/penpot_dump.js`, в строке `const BOARDS = [...]` указать нужные борды,
   например `['B11']` или `['B1', 'B2']`.
2. Передать весь файл как `code` в инструмент MCP Penpot `execute_code`
   (сервер «Penpot_Motion», открыт файл «UI»). Скрипт только читает макет.
3. В ответе — объект `files`: имя файла → содержимое. Записать каждый файл как есть.
4. `python3 tools/check_design.py` — должно быть «нарушений: 0».
5. `git diff design/` — видно, какие слои изменились с прошлого снимка. Закоммитить.

Полный снимок всех 76 бордов (~1 МБ) за один вызов MCP не пройдёт **в ответе** — см. «Как снять
пачку бордов целиком» ниже: снимок кладётся в `storage`, а в ответ идёт только опись.

Ответ MCP длиннее ~20 КБ обрезается. Тогда результат сохранить в `storage` плагина и забирать
файлы по частям (`files[k].split('\n').slice(a, b)`). Текст может содержать неразрывные пробелы
(U+00A0, в B5 — абзацные отступы), при переносе они превращаются в обычные. Поэтому каждый
файл сверять с Penpot по длине и хешу (djb2 по UTF-16; пример — `docs/archive/DEVLOG-2026-11-12-p8.md`, 8.0).

## Как снять пачку бордов целиком

`tools/penpot_dump.js` с `INLINE = false` (значение по умолчанию) кладёт снимок в `storage`
плагина и возвращает только опись — так в один заход снимается 30 бордов, какими бы большими
они ни были.

1. Запустить файл как `code` (в `BOARDS` — список бордов). Ответ:
   `{ count, bytes, index: [{ k, len, h, parts }] }` — `k` имя файла, `len` длина текста,
   `h` djb2 по UTF-16, `parts` число кусков по 18 000 символов. Опись сохранить: по ней сверяются файлы.
   В ответе чат её обрежет, поэтому попросить опись строками: `k\tlen\th\tparts` по одной на файл —
   114 строк проходят целиком.
2. **Быстрый путь — прямо в репозиторий (проверен 2026-11-15, 93 файла за 8 запросов).**
   Плагин в браузере на машине разработчика, поэтому снимок можно отдать локальному приёмнику,
   минуя чат: `python3 /tmp/penpot_recv.py 8765 <путь-к-репозиторию>` (скрипт ниже, в репозиторий
   не кладётся) и из `execute_code` отправить пачками по ~60 КБ:

   ```js
   await fetch('http://<LAN-адрес>:8765', {
     method: 'POST', headers: { 'Content-Type': 'application/json' },
     body: JSON.stringify({ files: Object.fromEntries(list.map(k => [k, storage.snapshot.files[k]])) }),
   });
   ```

   Адрес — **LAN** (`hostname -I`), не `127.0.0.1`: с localhost и с внешней сетью `fetch` из плагина
   падает («Failed to fetch»), а LAN-адрес отвечает 200. Приёмник пишет файлы как есть (`newline=''`,
   UTF-8) — неразрывные пробелы не портятся. Сверка — как в п. 4.
3. **Медленный путь — через чат**, если LAN недоступен: каждый файл забрать отдельным вызовом
   (один кусок в 18 КБ укладывается в лимит ответа):

   ```js
   const k = 'design/v5/B09.json';      // имя из описи
   const s = storage.snapshot.files[k];
   const CHUNK = 18000, part = 0;       // part — 0, 1, … parts-1
   return { k, len: s.length, part, txt: s.slice(part * CHUNK, part * CHUNK + CHUNK) };
   ```

   Строку `txt` из ответа записать в файл `k` как есть, склеивая куски по порядку
   (переносы строк внутри `txt` — часть файла: ничего не добавлять и не убирать).
3. Сверить: `len` — длина в **символах** UTF-16, а не в байтах:
   `python3 -c "import sys;print(len(open(sys.argv[1],encoding='utf-8').read()))" design/v5/B09.json`
   должно дать ровно `len` из описи. Хеш `h` — djb2 по тому же тексту; если `len` сошлась,
   файл перенесён без потерь.
4. `python3 tools/check_design.py` — «нарушений: 0», затем `git diff design/` и коммит.


## Приёмник снимка (для быстрого пути)

Временный HTTP-сервер на машине разработчика: `python3 /tmp/penpot_recv.py 8765 /home/denisle/projects/dexp-m8`.
Пишет `design/v5/**` из тела `{files: {путь: текст}}` как есть и печатает `ok <n>`. В репозиторий не кладётся —
это инструмент разового переноса.

```python
import json, sys
from http.server import BaseHTTPRequestHandler, HTTPServer

ROOT = sys.argv[2]

class H(BaseHTTPRequestHandler):
    def _c(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "*")
    def do_OPTIONS(self):
        self.send_response(204); self._c(); self.end_headers()
    def do_POST(self):
        data = json.loads(self.rfile.read(int(self.headers.get("Content-Length") or 0)).decode("utf-8"))
        files = data.get("files", data)
        n = 0
        for k, v in files.items():
            if not k.startswith("design/v5/") or ".." in k:
                continue
            with open(ROOT.rstrip("/") + "/" + k, "w", encoding="utf-8", newline="") as f:
                f.write(v)
            n += 1
        body = ("ok %d\n" % n).encode()
        self.send_response(200); self._c()
        self.send_header("Content-Length", str(len(body))); self.end_headers()
        self.wfile.write(body)

HTTPServer(("0.0.0.0", int(sys.argv[1])), H).serve_forever()
```

## Шахматы: страница chess (п. 25.0)

Борды B77–B87 (с вариантами `а`, `б`), борд «Фигуры · на светлой и тёмной клетке» и 12 бордов
`chessWk` … `chessBp` лежат на странице **chess** файла «UI». Рисует их Cline (исключение в
`.clinerules`, решение 2026-11-26); B1–B76, «v5», «v5 дизайн-система» и «icons» не трогает — ни слоёв,
ни имён. Снимок в `design/v5` — такой же, как у B1–B76.

**Перерисовать** (борды собираются из деталей снимка — шапка и статус-бар B75, пагинация B11, иконки):

1. `python3 tools/chess_boards.py [pieces B79 B79а …]` → `/tmp/chess_draw/<борд>.json` и `index.json`
   (без списка — все; позиции на досках считаются из ходов UCI).
2. CORS-сервер (текст ниже): `python3 /tmp/serve.py 8766 /tmp/chess_draw`.
3. `tools/penpot_boards_draw.js` как `code` в `execute_code` (открыта страница «chess»), затем
   `return await storage.drawBoards('http://<LAN-адрес>:8766/', ['B79', 'B79а'])` — борды с этими
   номерами рисуются заново (старый с тем же номером удаляется). Адрес — LAN, как у приёмника.

**Снять обратно:** в `tools/penpot_dump.js` `PAGE = 'chess'` и нужные борды в `BOARDS`, дальше —
быстрый путь («Приёмник снимка», порт 8765); **сразу вернуть `PAGE = 'v5'`**. Фигуры и значки попадают
в `v5/icons`. С шага 25.2 `tools/check_icons.py` видит все 12 `chess*` в коде (`ChessBoard.NAMES`,
имена записаны буквально — скрипт ищет их по тексту); «иконок, которых нет в коде» — 0.

```python
import sys
from http.server import SimpleHTTPRequestHandler, HTTPServer

ROOT = sys.argv[2]

class H(SimpleHTTPRequestHandler):
    def __init__(self, *a, **k):
        super().__init__(*a, directory=ROOT, **k)
    def end_headers(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "*")
        self.send_header("Cache-Control", "no-store")
        super().end_headers()
    def do_OPTIONS(self):
        self.send_response(204); self.end_headers()

HTTPServer(("0.0.0.0", int(sys.argv[1])), H).serve_forever()
```

## Формат узла

Короткие ключи, координаты округлены до 0.1 px. Один узел — одна строка, поэтому `git diff`
показывает изменённый слой целиком.

| Ключ | Значение |
|---|---|
| `n` | имя слоя в Penpot |
| `t` | тип: `rectangle`, `ellipse`, `text`, `path`, `group`, `board`, `icon` |
| `x` `y` `w` `h` | положение и размер, дизайн-px |
| `f` | заливки `[{c, o?}]`, `c` — токен `G0`…`G15` |
| `s` | обводки `[{c, w, a?, cap?}]` (`a` — inner/outer, `cap` — round) |
| `sh` | тени (в макете их нет — объём сделан слоями) |
| `r` | радиус: число или `[tl, tr, br, bl]` |
| `hid` | слой скрыт (служебный `base-background` в иконках) — не рисовать |
| `tx` `ff` `fs` `fw` `lh` | текст, шрифт, кегль, вес, межстрочный множитель |
| `ta` | выравнивание текста, если не `left` (`center`, `right`) |
| `grow` | `auto-width` — рамка по тексту; `auto-height`/`fixed` — ширина задана, `ta` внутри неё |
| `d` | SVG-путь (`M`, `L`, `C`, `Z`) в координатах владельца |
| `icon` | имя файла в `v5/icons/` |
| `c` | дочерние слои, порядок = порядок отрисовки (первый — снизу) |

Иконка в макете — две копии одной формы: нижняя белая (блик) со сдвигом вниз на ~3 px
и верхняя цветная. Обе есть в файле иконки.

## Иконки: страница icons

Все иконки, которые рисует код (130 бордов, список — `design/icons.txt`; `next@54.8x57.1` без борда), лежат в Penpot, файл «UI»,
страница **icons**: один борд = одна иконка, имя борда = имя спеки (`wifi`, `wifi@27.5x23.6`),
размер борда = размер спеки, слои — примитивы снизу вверх (`r` — прямоугольник, `e` — эллипс,
`p` — путь). Источник для `IconSpecs.kt` — `design/icons.txt` (`python3 tools/icon_specs.py`);
снимок `v5/icons` нужен только `check_design.py`. Из Cline на этой странице можно писать, на
остальных — нет (`.clinerules`); исключение — страница **chess** (решение 2026-11-26, п. 25.0): борды
шахмат B77–B87 и фигуры `chess*` рисует Cline, B1–B76 и «v5 дизайн-система» не трогает.

**Что править в Penpot.** Форму, толщину, цвет и порядок слоёв, добавлять и удалять слои. Цвета —
только G0–G15, без прозрачности, градиентов, теней, поворота и отражения; обводка — по центру,
сплошная, круглые концы — свойство линии `round`; путь — только `M L C Z` (абсолютные), группы можно,
boolean-операции нужно превратить в path. **Имя борда не менять** (по нему код находит иконку),
размер борда — тоже (это масштаб). Новый борд — новая иконка (код начнёт её рисовать, когда в нём
появится имя); удалять можно только иконку, которой нет в коде (`tools/check_icons.py` скажет).

**Нарисовать на странице** (после правки `design/icons.txt`): `python3 tools/icons_to_penpot.py` →
`/tmp/icons_serve/chunk_N.txt`; `tools/penpot_icons_draw.js` — как `code` в `execute_code`, затем
`storage.resetIcons()` и по порции `storage.drawIcons(<текст порции>.split('\n'))`. `fetch` из плагина до
рабочей машины не проходит (браузер на другом хосте), поэтому порции вставляются в текст вызова.

**Снять правки обратно.** `python3 tools/icons_to_penpot.py --hashes` → `tools/penpot_icons_dump.js`
как `code`, затем `storage.verifyIcons(<текст хешей>)`: `bad` — изменённые борды (со спекой),
`missing` — пропавшие, `extra` — новые (их спеки — `storage.dumpIcons(от, до)`; порядок страницы).
Строки записать в `design/icons.txt`, затем `python3 tools/icon_specs.py`, `tools/check_icons.py`,
`tools/typecheck.sh`, `assembleDebug`, смотреть на книге. Рамка `x y w h` у path в сверку не входит:
Penpot считает её по кривой, а `Icons.kt` берёт `computeBounds`.

## Чтение

`python3 tools/design_flat.py B22 [--full] [--y 300:900] [--grep имя]` — одна строка на слой, примерно вдвое короче JSON; статус-бар свёрнут без `--full`.

## Проверка

`tools/check_design.py` (и задача `design:check` в CI): JSON читается, все цвета из палитры,
нет полупрозрачности, все ссылки на иконки ведут на существующие файлы.
