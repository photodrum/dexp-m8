// tools/penpot_boards_draw.js — нарисовать борды из JSON (формат узлов design/v5, design/README.md)
// на странице «chess» файла «UI» в Penpot. Нужен для п. 25.0 (макеты шахмат, решение 2026-11-26).
//
// Как запускать:
//   1. python3 tools/chess_boards.py            → /tmp/chess_draw/<борд>.json и index.json
//      python3 /tmp/serve.py 8766 /tmp/chess_draw (CORS-сервер; скрипт — в design/README.md, «Шахматы»)
//   2. Передать ВЕСЬ этот файл как `code` в `execute_code` (страница «chess» открыта): он кладёт в
//      `storage` функцию `drawBoards(base, names)`.
//   3. `return await storage.drawBoards('http://<LAN-адрес>:8766/', ['B79', 'B79а'])` — борды с этими
//      номерами перерисовываются заново (старый борд с тем же номером удаляется).
//
// Узел: n имя · t rectangle|ellipse|text|path|group|board · x y (от борда) w h · f [{c:'G3'}] ·
// s [{c, w, cap?}] · r радиус · tx ff fs fw lh ta grow (текст) · d путь в координатах борда · c дети.
// Иконки приходят уже раскрытыми: группа «Иконка / имя» с примитивами (так их узнаёт penpot_dump.js).
// Скрипт пишет только на странице «chess»; другие страницы не трогает.

const PAGE_NAME = 'chess';
const FONT_ID = { Manrope: 'gfont-manrope', Literata: 'gfont-literata' };

function chessPage() {
  const page = penpot.currentPage;
  if (!page || page.name !== PAGE_NAME) throw new Error('Открыта не страница «' + PAGE_NAME + '»');
  return page;
}

const hex = g => '#' + (Number(String(g).slice(1)) * 17).toString(16).padStart(2, '0').repeat(3);

function shiftD(d, ox, oy) {
  return d.replace(/([MLCZ])([^MLCZ]*)/g, (all, cmd, args) => {
    const nums = args.match(/-?\d*\.?\d+/g);
    if (!nums) return cmd;
    return cmd + nums.map((v, i) => String(Math.round((parseFloat(v) + (i % 2 ? oy : ox)) * 1000) / 1000)).join(',');
  });
}

function paint(s, n) {
  s.fills = (n.f || []).map(f => ({ fillColor: hex(f.c), fillOpacity: 1 }));
  s.strokes = (n.s || []).map(st => {
    const o = { strokeColor: hex(st.c), strokeOpacity: 1, strokeWidth: st.w, strokeStyle: 'solid',
      strokeAlignment: st.a || 'center' };
    if (st.cap === 'round') { o.strokeCapStart = 'round'; o.strokeCapEnd = 'round'; }
    return o;
  });
}

// Создать узел внутри борда b; возвращает фигуру (или null для пустой группы).
function make(n, b) {
  const ox = b.x, oy = b.y;
  let s;
  if (n.t === 'group') {
    const kids = (n.c || []).map(c => make(c, b)).filter(Boolean);
    if (!kids.length) return null;
    s = penpot.group(kids);
    s.name = n.n;
    if (n.hid) s.hidden = true;
    return s;
  }
  if (n.t === 'text') {
    s = penpot.createText(n.tx);
    b.appendChild(s);
    s.fontId = FONT_ID[n.ff] || FONT_ID.Manrope;
    s.fontFamily = n.ff || 'Manrope';
    s.fontWeight = String(n.fw);
    s.fontSize = String(n.fs);
    s.lineHeight = String(n.lh || '1.25');
    if (n.ta) s.align = n.ta;
    s.growType = n.grow || 'auto-width';
    if (s.growType !== 'auto-width') s.resize(n.w, n.h);
    s.x = ox + n.x; s.y = oy + n.y;
  } else if (n.t === 'path') {
    s = penpot.createPath();
    b.appendChild(s);
    s.d = shiftD(n.d, ox, oy);
  } else {
    s = n.t === 'ellipse' ? penpot.createEllipse() : penpot.createRectangle();
    b.appendChild(s);
    s.resize(Math.max(n.w, 0.01), Math.max(n.h, 0.01));
    s.x = ox + n.x; s.y = oy + n.y;
    if (n.r != null) {
      if (Array.isArray(n.r)) {
        [s.borderRadiusTopLeft, s.borderRadiusTopRight, s.borderRadiusBottomRight, s.borderRadiusBottomLeft] = n.r;
      } else s.borderRadius = n.r;
    }
  }
  s.name = n.n;
  paint(s, n);
  if (n.hid) s.hidden = true;
  return s;
}

function drawBoard(tree) {
  const page = chessPage();
  const num = tree.board.split(' ')[0];
  for (const old of page.root.children.filter(c => c.name.split(' ')[0] === num)) old.remove();
  const b = penpot.createBoard();
  b.name = tree.board;
  b.resize(tree.w, tree.h);
  b.x = tree.px; b.y = tree.py;
  b.clipContent = tree.clip !== false;
  b.fills = (tree.f || []).map(f => ({ fillColor: hex(f.c), fillOpacity: 1 }));
  let count = 0;
  const walk = n => { count++; (n.c || []).forEach(walk); };
  for (const n of tree.c) { make(n, b); walk(n); }
  return { board: b.name, id: b.id, nodes: count };
}

storage.drawBoards = async (base, names) => {
  chessPage();
  const out = [];
  for (const name of names) {
    const r = await fetch(base + encodeURIComponent(name) + '.json', { cache: 'no-store' });
    if (!r.ok) throw new Error('нет ' + name + ': ' + r.status);
    out.push(drawBoard(await r.json()));
  }
  return out;
};

return 'ok: storage.drawBoards готова';
