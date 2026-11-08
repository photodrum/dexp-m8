// tools/penpot_dump.js — снимок макета Penpot в JSON (design/v5/*.json)
//
// Как запускать: передать ВЕСЬ этот файл как `code` в инструмент MCP Penpot
// `execute_code` (сервер «Penpot_Motion», файл «UI»). Перед запуском поправить BOARDS.
// Скрипт ничего не меняет в макете — только читает.
//
// Результат: { files: { "design/v5/B01.json": "...", "design/v5/icons/wifi~xxxx.json": "...", ... } }
// Каждую строку записать в файл с этим именем как есть и закоммитить.
//
// Почему по нескольку бордов: полный снимок 76 бордов ~1 МБ — через ответ MCP
// его не прогнать. Снимаем те экраны, которые сейчас делаем, и обновляем их при правках.
//
// Формат узла (короткие ключи, координаты в дизайн-px относительно борда, 0.1 px):
//   n имя слоя · t тип (rectangle|ellipse|text|path|group|board|icon) · x y w h
//   f заливки [{c,o?}] · s обводки [{c,w,a?,o?,st?,cap?}] · sh тени [{st,x,y,b,sp,c,o}]
//   r радиус (число или [tl,tr,br,bl]) · op прозрачность слоя · rot поворот · hid скрыт
//   текст: tx строка · ff шрифт · fs кегль · fw вес · lh интервал · ta выравнивание (если не left)
//          ls трекинг · tt регистр · va вертикаль · grow тип рамки
//   path:  d — SVG-путь в тех же координатах, что x/y узла-владельца
//   icon:  icon — имя файла design/v5/icons/<icon>.json (геометрия иконки относительно
//          её левого верхнего угла; разные цвет/размер одной иконки — разные файлы)
//   c дочерние узлы
// Цвета палитры пишутся токеном "G0".."G15", остальные — hex (их быть не должно).

const BOARDS = ['B1', 'B2'];      // какие борды снять; [] — только tokens.json
const PAGE = 'v5';

const page = penpotUtils.getPageByName(PAGE);
if (!page) throw new Error('Нет страницы ' + PAGE);
const boards = page.root.children.filter(s => s.type === 'board');
const boardBy = p => boards.find(b => b.name.startsWith(p + ' '));

const r1 = v => Math.round(v * 10) / 10;
const PAL = {};
for (let i = 0; i < 16; i++) PAL['#' + (i * 17).toString(16).padStart(2, '0').repeat(3)] = 'G' + i;
const col = c => (c ? (PAL[c.toLowerCase()] || c.toLowerCase()) : c);

function shiftPath(d, ox, oy) {
  if (!d) return d;
  if (/[HhVvAaSsQqTt]/.test(d)) throw new Error('Неожиданная команда в пути: ' + d.slice(0, 60));
  return d.replace(/([MLCZmlcz])([^MLCZmlcz]*)/g, (all, cmd, args) => {
    const nums = args.match(/-?\d*\.?\d+(e-?\d+)?/g);
    if (!nums) return cmd;
    const out = nums.map((v, i) => {
      const n = parseFloat(v) - (i % 2 === 0 ? ox : oy);
      return String(Math.round(n * 100) / 100);
    });
    return cmd + out.join(',');
  });
}

const fills = a => (a || []).filter(f => f.fillColor || f.fillImage).map(f => {
  const o = { c: col(f.fillColor) };
  if (f.fillOpacity != null && f.fillOpacity !== 1) o.o = f.fillOpacity;
  if (f.fillImage) o.img = 1;
  return o;
});
const strokes = a => (a || []).filter(s => s.strokeColor).map(s => {
  const o = { c: col(s.strokeColor), w: r1(s.strokeWidth || 1) };
  if (s.strokeAlignment && s.strokeAlignment !== 'center') o.a = s.strokeAlignment;
  if (s.strokeOpacity != null && s.strokeOpacity !== 1) o.o = s.strokeOpacity;
  if (s.strokeStyle && s.strokeStyle !== 'solid') o.st = s.strokeStyle;
  if (s.strokeCapStart && s.strokeCapStart !== 'none') o.cap = s.strokeCapStart;
  return o;
});
const shadows = a => (a || []).filter(s => !s.hidden).map(s => ({
  st: s.style, x: s.offsetX, y: s.offsetY, b: s.blur, sp: s.spread,
  c: col(s.color && s.color.color), o: s.color && s.color.opacity,
}));
function radius(s) {
  const a = [s.borderRadiusTopLeft, s.borderRadiusTopRight, s.borderRadiusBottomRight, s.borderRadiusBottomLeft];
  if (a.every(v => v == null)) return s.borderRadius || undefined;
  if (a.every(v => v === a[0])) return a[0] || undefined;
  return a.map(v => v || 0);
}
function hash(s) {
  let h = 5381;
  for (let i = 0; i < s.length; i++) h = ((h << 5) + h + s.charCodeAt(i)) | 0;
  return (h >>> 0).toString(36);
}

// ox, oy — начало координат (левый верхний угол борда или иконки)
function node(s, ox, oy, icons) {
  const o = { n: s.name, t: s.type, x: r1(s.x - ox), y: r1(s.y - oy), w: r1(s.width), h: r1(s.height) };
  if (icons && /^Иконка \//.test(s.name) && s.children) {
    const body = s.children.map(c => node(c, s.x, s.y, null));
    const key = s.name.replace('Иконка / ', '') + '~' + hash(JSON.stringify(body) + o.w + 'x' + o.h);
    if (!icons[key]) icons[key] = { name: s.name, w: o.w, h: o.h, c: body };
    o.t = 'icon';
    o.icon = key.replace(/[^\w~.-]/g, '_');
    return o;
  }
  const f = fills(s.fills); if (f.length) o.f = f;
  const st = strokes(s.strokes); if (st.length) o.s = st;
  const sh = shadows(s.shadows); if (sh.length) o.sh = sh;
  const r = radius(s); if (r) o.r = r;
  if (s.opacity != null && s.opacity !== 1) o.op = s.opacity;
  if (s.rotation) o.rot = s.rotation;
  if (s.hidden) o.hid = 1;
  if (s.type === 'text') {
    o.tx = s.characters; o.ff = s.fontFamily; o.fs = s.fontSize; o.fw = s.fontWeight;
    o.lh = s.lineHeight;
    if (s.align && s.align !== 'left') o.ta = s.align;   // в API Penpot свойство называется align
    if (s.letterSpacing && s.letterSpacing !== '0') o.ls = s.letterSpacing;
    if (s.textTransform && s.textTransform !== 'none') o.tt = s.textTransform;
    if (s.verticalAlign && s.verticalAlign !== 'top') o.va = s.verticalAlign;
    o.grow = s.growType;
  }
  if (s.type === 'path') o.d = shiftPath(s.d, ox, oy);
  if (s.children && s.children.length) o.c = s.children.map(c => node(c, ox, oy, icons));
  return o;
}

// Один узел — одна строка: файл компактный, а git diff показывает, какой слой изменился.
function fmt(v, depth) {
  const pad = ' '.repeat(depth);
  if (Array.isArray(v)) return '[\n' + v.map(x => fmt(x, depth + 1)).join(',\n') + ']';
  const { c, ...rest } = v;
  const head = pad + JSON.stringify(rest);
  if (!c) return head;
  return head.slice(0, -1) + (Object.keys(rest).length ? ',' : '') + '"c":' + fmt(c, depth) + '}';
}
const pretty = v => (v && (v.c || Array.isArray(v)) ? fmt(v, 0) : JSON.stringify(v, null, 1)) + '\n';
const files = {};

// tokens.json — палитра и текстовые стили из библиотеки файла
const lib = penpot.library.local;
files['design/v5/tokens.json'] = pretty({
  source: 'Penpot «UI» / ' + PAGE,
  palette: Object.fromEntries(Object.entries(PAL).map(([hex, g]) => [g, hex.toUpperCase()])),
  libraryColors: lib.colors.map(c => ({ name: c.name, path: c.path, color: col(c.color) })),
  typographies: lib.typographies.map(t => ({
    name: t.name, path: t.path, ff: t.fontFamily, fs: t.fontSize, fw: t.fontWeight,
    lh: t.lineHeight, ls: t.letterSpacing, tt: t.textTransform,
  })),
});

// борды + общие иконки
const icons = {};
const missing = [];
for (const p of BOARDS) {
  const b = boardBy(p);
  if (!b) { missing.push(p); continue; }
  const num = p.replace('B', '').padStart(2, '0');
  files['design/v5/B' + num + '.json'] = pretty({
    board: b.name, w: r1(b.width), h: r1(b.height),
    f: fills(b.fills),
    c: b.children.map(c => node(c, b.x, b.y, icons)),
  });
}
// одна иконка — один файл: снимки разных бордов складываются без слияния
for (const k of Object.keys(icons)) {
  files['design/v5/icons/' + k.replace(/[^\w~.-]/g, '_') + '.json'] = pretty(icons[k]);
}

return { boards: BOARDS, missing, files };
