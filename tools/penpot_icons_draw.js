// tools/penpot_icons_draw.js — нарисовать иконки на странице «icons» файла «UI» в Penpot.
//
// Как запускать (подробно — design/README.md, «Иконки: страница icons»):
//   1. python3 tools/icons_to_penpot.py --chunks 4   → /tmp/icons_serve/chunk_0.txt … chunk_3.txt
//      (одна спека на строку, порядок — как класть на страницу; формат — как в IconSpecs.kt).
//   2. Передать ВЕСЬ этот файл как `code` в `execute_code`: он кладёт в `storage` функции
//      `drawIcons` и `resetIcons`. Страница «icons» должна быть открыта.
//   3. На каждый кусок — вызов `execute_code`:
//        return storage.drawIcons(`<содержимое chunk_N.txt>`.split('\n'));
//      Первый раз — `storage.resetIcons()` стирает все борды страницы и сбрасывает раскладку.
//
// Борд = одна иконка: имя борда = имя спеки, размер = размер спеки, слои — примитивы по порядку
// (первый — снизу). Прямоугольник и эллипс: обводка по центру. Путь: d в координатах страницы.
// Фон борда нужен только глазу (G12, а у иконок только из белого — G3), при снятии игнорируется.
// Скрипт рисует только на странице «icons» и ничего больше в файле не меняет.

const PAGE_NAME = 'icons';
const MAX_X = 2600;          // перенос строки раскладки
const GAP_SAME = 40;         // между вариантами одной иконки
const GAP_GROUP = 120;       // между разными иконками
const GAP_ROW = 100;

function iconsPage() {
  const page = penpot.getPage ? penpot.getPage() : penpot.currentPage;
  if (!page || page.name !== PAGE_NAME) throw new Error('Открыта не страница «' + PAGE_NAME + '»');
  return page;
}

function parsePrim(chunk) {
  const tk = chunk.trim().split(' ');
  const t = tk[0][0];
  const isE = t === 'e';
  const nums = tk.slice(1, isE ? 5 : 6).map(Number);
  const col = tk[isE ? 5 : 6];
  const p = { t, nums, stroke: false, round: false, sw: 0, d: null };
  if (col.startsWith('stroke:')) {
    const v = col.split(':');
    Object.assign(p, { stroke: true, color: v[1], sw: Number(v[2]), round: v[3] === 'round' });
  } else p.color = col;
  if (t === 'p') p.d = tk.slice(isE ? 6 : 7).join(' ');
  return p;
}

// Сдвиг абсолютного пути M/L/C/Z на (ox, oy).
function shiftD(d, ox, oy) {
  return d.replace(/([MLCZ])([^MLCZ]*)/g, (all, cmd, args) => {
    const nums = args.match(/-?\d*\.?\d+/g);
    if (!nums) return cmd;
    return cmd + nums.map((v, i) => String(Math.round((parseFloat(v) + (i % 2 ? oy : ox)) * 1000) / 1000)).join(',');
  });
}

function paint(s, p) {
  if (p.stroke) {
    s.fills = [];
    const st = { strokeColor: p.color, strokeOpacity: 1, strokeWidth: p.sw, strokeStyle: 'solid', strokeAlignment: 'center' };
    if (p.round) { st.strokeCapStart = 'round'; st.strokeCapEnd = 'round'; }
    s.strokes = [st];
  } else {
    s.fills = [{ fillColor: p.color, fillOpacity: 1 }];
    s.strokes = [];
  }
}

storage.resetIcons = () => {
  const page = iconsPage();
  for (const c of [...page.root.children]) c.remove();
  storage.lay = { x: 0, y: 0, rowH: 0, base: null, count: 0 };
  return 'сброшено';
};

storage.drawIcons = (lines) => {
  const page = iconsPage();
  const st = storage.lay || (storage.lay = { x: 0, y: 0, rowH: 0, base: null, count: 0 });
  const done = [];
  for (const line of lines) {
    if (!line.trim()) continue;
    const parts = line.split('|');
    const name = parts[0], w = Number(parts[1]), h = Number(parts[2]);
    const prims = parts.slice(3).filter(s => s.trim()).map(parsePrim);
    const base = name.split('@')[0];
    if (st.count > 0) st.x += base === st.base ? GAP_SAME : GAP_GROUP;
    if (st.x > 0 && st.x + w > MAX_X) { st.x = 0; st.y += st.rowH + GAP_ROW; st.rowH = 0; }

    const b = penpot.createBoard();
    b.name = name;
    b.resize(w, h);
    b.x = st.x; b.y = st.y;
    b.clipContent = false;
    const lightOnly = prims.every(p => p.color.toLowerCase() >= '#aaaaaa');
    b.fills = [{ fillColor: lightOnly ? '#333333' : '#cccccc', fillOpacity: 1 }];

    prims.forEach((p, i) => {
      const [x, y, pw, ph, extra] = p.nums;
      let s;
      if (p.t === 'r' || p.t === 'e') {
        s = p.t === 'r' ? penpot.createRectangle() : penpot.createEllipse();
        b.appendChild(s);
        s.resize(Math.max(pw, 0.01), Math.max(ph, 0.01));
        s.x = b.x + x; s.y = b.y + y;
        if (p.t === 'r') s.borderRadius = extra;
      } else {
        s = penpot.createPath();
        b.appendChild(s);
        s.d = shiftD(p.d, b.x, b.y);
      }
      s.name = (i + 1) + ' ' + p.t;
      paint(s, p);
    });
    st.x += w; st.rowH = Math.max(st.rowH, h); st.base = base; st.count++;
    done.push(name);
  }
  return { drawn: done.length, total: st.count, next: { x: st.x, y: st.y } };
};

return 'ok: storage.drawIcons / storage.resetIcons готовы';
