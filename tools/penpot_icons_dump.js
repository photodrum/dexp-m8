// tools/penpot_icons_dump.js — снять иконки со страницы «icons» Penpot в строки спек.
//
// Как запускать (подробно — design/README.md, «Иконки: страница icons»): передать ВЕСЬ файл как
// `code` в `execute_code` — он кладёт в `storage` функции; затем отдельными вызовами:
//   return storage.verifyIcons(`<вывод python3 tools/icons_to_penpot.py --hashes>`);
//       сверка с IconSpecs.kt: что пропало, что лишнее, какие борды отличаются;
//   return storage.dumpIcons(0, 40);      строки спек бордов с 0-го по 39-й (порядок страницы)
//   return storage.redrawIcon(`<спека>`); перерисовать один борд на его месте
// Скрипт ничего не меняет в макете, кроме redrawIcon (ей нужен storage.drawIcons из
// penpot_icons_draw.js).
//
// Борд = иконка (имя борда = имя спеки). Слои борда → примитивы (первый слой — снизу):
//   rectangle → `r x y w h radius`, ellipse → `e x y w h`, path → `p x y w h sw … d`.
// Заливка — `#hex`, обводка — `stroke:#hex:w[:round]`. Группы раскрываются, скрытые слои
// пропускаются. Всё, чего спеки не умеют, — ошибка с именем борда, а не молчаливая потеря:
// цвет не из палитры G0–G15, прозрачность, градиент, тень, размытие, поворот, отражение, у слоя
// не одна заливка/обводка, обводка не по центру или не сплошная, текст, картинка, bool, борд в борде.

const PAGE_NAME = 'icons';

function iconsPage() {
  const page = penpot.currentPage;
  if (!page || page.name !== PAGE_NAME) throw new Error('Открыта не страница «' + PAGE_NAME + '»');
  return page;
}

const rr = (v, n) => Math.round(v * 10 ** n) / 10 ** n;
function num(v, n) {
  const s = rr(v, n).toFixed(n).replace(/\.?0+$/, '');
  return s === '' || s === '-0' ? '0' : s;
}
const n1 = v => num(v, 1);
const n3 = v => num(v, 3);

const PAL = new Set();
for (let i = 0; i < 16; i++) PAL.add('#' + (i * 17).toString(16).padStart(2, '0').repeat(3));

function djb2(s) {
  let h = 5381;
  for (let i = 0; i < s.length; i++) h = ((h << 5) + h + s.charCodeAt(i)) | 0;
  return (h >>> 0).toString(16).padStart(8, '0');
}

// Абсолютный путь страницы → путь в координатах иконки (M/L/C/Z, числа до 0.01).
function unshiftD(d, ox, oy, where) {
  if (/[HhVvAaSsQqTtmlcz]/.test(d)) throw new Error(where + ': в пути только M L C Z (абсолютные): ' + d.slice(0, 60));
  return d.replace(/([MLCZ])([^MLCZ]*)/g, (all, cmd, args) => {
    const nums = args.match(/-?\d*\.?\d+/g);
    if (!nums) return cmd;
    return cmd + nums.map((v, i) => num(parseFloat(v) - (i % 2 ? oy : ox), 2)).join(',');
  });
}

function paintOf(s, where) {
  if (s.opacity != null && s.opacity !== 1) throw new Error(where + ': прозрачность слоя');
  if (s.rotation) throw new Error(where + ': поворот');
  if (s.flipX || s.flipY) throw new Error(where + ': отражение');
  if ((s.shadows || []).length) throw new Error(where + ': тень');
  if (s.blur || s.backgroundBlur) throw new Error(where + ': размытие');
  const fills = (s.fills || []).filter(f => f.fillColor || f.fillColorGradient || f.fillImage);
  const strokes = (s.strokes || []).filter(k => k.strokeColor || k.strokeColorGradient || k.strokeImage);
  if (fills.length + strokes.length !== 1) throw new Error(where + ': нужна ровно одна заливка или обводка');
  const p = fills[0] || strokes[0];
  const color = fills[0] ? p.fillColor : p.strokeColor;
  const op = fills[0] ? p.fillOpacity : p.strokeOpacity;
  if (!color || p.fillColorGradient || p.strokeColorGradient || p.fillImage || p.strokeImage) {
    throw new Error(where + ': градиент или картинка');
  }
  if (op != null && op !== 1) throw new Error(where + ': прозрачность цвета');
  const hex = color.toLowerCase();
  if (!PAL.has(hex)) throw new Error(where + ': цвет ' + hex + ' не из палитры G0–G15');
  if (fills[0]) return { hex, stroke: false };
  if (p.strokeAlignment && p.strokeAlignment !== 'center') throw new Error(where + ': обводка не по центру');
  if (p.strokeStyle && p.strokeStyle !== 'solid') throw new Error(where + ': обводка не сплошная');
  const cap = p.strokeCapStart;
  if (cap && cap !== 'none' && cap !== 'round') throw new Error(where + ': конец линии ' + cap);
  return { hex, stroke: true, sw: p.strokeWidth, round: cap === 'round' };
}

function primsOf(nodes, b, where, out) {
  for (const s of nodes) {
    if (s.hidden) continue;
    const w = where + '/' + s.name;
    if (s.type === 'group') { primsOf(s.children || [], b, where, out); continue; }
    if (!['rectangle', 'ellipse', 'path'].includes(s.type)) throw new Error(w + ': тип ' + s.type + ' не поддерживается');
    const pt = paintOf(s, w);
    const col = pt.stroke ? 'stroke:' + pt.hex + ':' + n3(pt.sw) + (pt.round ? ':round' : '') : pt.hex;
    const box = [s.x - b.x, s.y - b.y, s.width, s.height].map(n1).join(' ');
    if (s.type === 'rectangle') {
      const r = [s.borderRadiusTopLeft, s.borderRadiusTopRight, s.borderRadiusBottomRight, s.borderRadiusBottomLeft]
        .map(v => v || 0);
      if (new Set(r.map(v => n3(v))).size > 1) throw new Error(w + ': разные радиусы углов');
      out.push('r ' + box + ' ' + n3(r[0]) + ' ' + col);
    } else if (s.type === 'ellipse') {
      out.push('e ' + box + ' ' + col);
    } else {
      out.push('p ' + box + ' ' + (pt.stroke ? n3(pt.sw) : '0') + ' ' + col + ' ' + unshiftD(s.d, b.x, b.y, w));
    }
  }
}

function specOf(b) {
  const prims = [];
  primsOf(b.children || [], b, b.name, prims);
  if (!prims.length) throw new Error(b.name + ': в борде нет слоёв');
  return b.name + '|' + n3(b.width) + '|' + n3(b.height) + '|' + prims.join('|');
}

// Каноническая запись для сверки — тот же алгоритм, что canon() в tools/icons_to_penpot.py.
function canon(spec) {
  const parts = spec.split('|');
  const out = [parts[0], num(parseFloat(parts[1]), 1), num(parseFloat(parts[2]), 1)];
  for (const chunk of parts.slice(3)) {
    if (!chunk.trim()) continue;
    const tk = chunk.trim().split(' ');
    const t = tk[0][0];
    let col = tk[t === 'e' ? 5 : 6];
    if (col.startsWith('stroke:')) {
      const v = col.split(':');
      col = 'stroke:' + v[1].toLowerCase() + ':' + num(parseFloat(v[2]), 1) + (v.length > 3 ? ':round' : '');
    } else col = col.toLowerCase();
    if (t === 'p') {
      out.push('p ' + col + ' ' + tk.slice(7).join(' ').replace(/-?\d*\.?\d+/g, m => num(parseFloat(m), 2)));
    } else if (t === 'r') {
      out.push('r ' + tk.slice(1, 6).map(v => num(parseFloat(v), 1)).join(' ') + ' ' + col);
    } else {
      out.push('e ' + tk.slice(1, 5).map(v => num(parseFloat(v), 1)).join(' ') + ' ' + col);
    }
  }
  return out.join('|');
}

storage.dumpIcons = (from, to) => iconsPage().root.children.slice(from, to).map(specOf);

storage.verifyIcons = (text) => {
  const want = new Map(text.split('\n').filter(l => l.trim()).map(l => l.trim().split(' ')));
  const boards = iconsPage().root.children;
  const seen = new Set(), bad = [], extra = [];
  for (const b of boards) {
    seen.add(b.name);
    if (!want.has(b.name)) { extra.push(b.name); continue; }
    let line;
    try { line = specOf(b); } catch (e) { bad.push({ name: b.name, err: String(e.message || e) }); continue; }
    if (djb2(canon(line)) !== want.get(b.name)) bad.push({ name: b.name, spec: line });
  }
  const missing = [...want.keys()].filter(n => !seen.has(n));
  return { boards: boards.length, expected: want.size, missing, extra, bad };
};

// Перерисовать один борд на его месте (после опечатки при переносе): спека — одна строка.
storage.redrawIcon = (line) => {
  const name = line.split('|')[0];
  const old = iconsPage().root.children.find(b => b.name === name);
  if (!old) throw new Error('нет борда ' + name);
  const keep = storage.lay;
  const at = { x: old.x, y: old.y };
  old.remove();
  storage.lay = { x: at.x, y: at.y, rowH: 0, base: null, count: 0 };
  const r = storage.drawIcons([line]);
  storage.lay = keep;
  return r;
};

return 'ok: storage.verifyIcons / dumpIcons / redrawIcon готовы';
