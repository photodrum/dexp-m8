package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Иконки оболочки. Каждая — набор примитивов ровно в той геометрии, что лежит
 * в Penpot: два слоя (блик со смещением +3,+3 и контур), прямоугольники,
 * эллипсы и path'ы. Никаких SVG в assets и никаких Bitmap: при отрисовке
 * на Canvas они всегда резкие, весят ноль байт в APK и ноль в куче.
 *
 * Спеки разбираются один раз в конструкторе, Path'ы кэшируются.
 *
 * Формат строки спеки:
 *   имя|ширина|высота|примитив|примитив|...
 * Примитив:
 *   r x y w h radius  <hex|stroke:hex:width[:round]>
 *   e x y w h         <hex|stroke:hex:width[:round]>
 *   p x y w h sw      <hex|stroke:hex:width[:round]>  d="..."
 * Суффикс :round — круглые концы линий (strokeCap = ROUND), без него — прямые.
 */
class Icons {

    private class Prim(
        val t: Char,
        val x: Float, val y: Float, val w: Float, val h: Float,
        val rad: Float, val sw: Float,
        val color: Int, val d: Path?,
        val round: Boolean
    )

    private class Spec(
        val w: Float, val h: Float, val prims: Array<Prim>,
        /**
         * Рамка видимого контура иконки: белый блик макета (+2.8,+2.8) в неё не входит,
         * у штриховых примитивов учтена полутолщина. Нужна для центрирования: у половины
         * иконок рисунок смещён внутри своей рамки, и без этого иконка в круге «висит».
         */
        val ink: RectF
    )

    private val specs = HashMap<String, Spec>(64)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        strokeJoin = Paint.Join.MITER
    }

    init {
        for (line in IconSpecs.SPECS) parse(line)
    }

    private fun parse(line: String) {
        val parts = line.split('|')
        val name = parts[0]
        val w = parts[1].toFloat()
        val h = parts[2].toFloat()
        val list = ArrayList<Prim>(parts.size - 3)
        val inkBox = RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        var inkAny = false
        for (i in 3 until parts.size) {
            val tk = parts[i].trim().split(' ')
            if (tk.size < 6) continue
            val t = tk[0][0]
            val x = tk[1].toFloat(); val y = tk[2].toFloat()
            val pw = tk[3].toFloat(); val ph = tk[4].toFloat()
            // Для 'p' пятое поле — толщина штриха, для 'r' — радиус, у 'e' его нет.
            val rad = if (t == 'r') tk[5].toFloat() else 0f
            val sw = if (t == 'p') tk[5].toFloat() else 0f
            val colIdx = if (t == 'e') 5 else 6
            val spec = tk[colIdx]
            val color: Int
            val realSw: Float
            var round = false
            if (spec.startsWith("stroke:")) {
                val v = spec.split(':')
                color = Color.parseColor(v[1])
                realSw = v[2].toFloat()
                round = v.size > 3 && v[3] == "round"
            } else {
                color = Color.parseColor(spec)
                realSw = sw
            }
            val d = if (t == 'p' && tk.size > colIdx + 1) {
                Path().apply { parseSvg(this, tk.subList(colIdx + 1, tk.size).joinToString(" ")) }
            } else null
            list.add(Prim(t, x, y, pw, ph, rad, realSw, color, d, round))
            // Видимый контур: белый блик макета не берём (он раздут вправо-вниз и уводит
            // центр), у path объявленная рамка не совпадает с самим рисунком — берём фактические
            // границы пути; штрих расширяет контур на полтолщины.
            if (color != Color.WHITE) {
                val half = realSw / 2f
                val box = RectF(x - half, y - half, x + pw + half, y + ph + half)
                if (t == 'p' && d != null) d.computeBounds(box, true); box.inset(-half, -half)
                if (!inkAny) { inkBox.set(box); inkAny = true } else unionInk(inkBox, box)
            }
        }
        if (!inkAny) inkBox.set(0f, 0f, w, h)
        specs[name] = Spec(w, h, list.toTypedArray(), inkBox)
    }

    /** Дополнить рамку [acc] рамкой [r]: объединение габаритов. */
    private fun unionInk(acc: RectF, r: RectF) {
        acc.left = Math.min(acc.left, r.left)
        acc.top = Math.min(acc.top, r.top)
        acc.right = Math.max(acc.right, r.right)
        acc.bottom = Math.max(acc.bottom, r.bottom)
    }

    /**
     * Разбор M/L/C/Q/Z без внешних зависимостей.
     *
     * Важно: команда определяется по группе регулярки, а НЕ по длине токена.
     * Раньше стояла проверка `s.length == 1`, и однозначные координаты
     * (например `1` в `M1,2`) принимались за команду — это роняло
     * IndexOutOfBoundsException на всех 44 путях иконок.
     */
    private fun parseSvg(p: Path, d: String) {
        val re = Regex("([MLCQZmlcqz])|(-?\\d+(?:\\.\\d+)?)")
        var cmd = ' '
        val nums = ArrayList<Float>(8)

        fun flush() {
            if (nums.isEmpty()) return
            var i = 0
            when (cmd) {
                'M' -> {
                    p.moveTo(nums[0], nums[1]); i = 2
                    while (i + 1 < nums.size) { p.lineTo(nums[i], nums[i + 1]); i += 2 }
                }
                'L' -> while (i + 1 < nums.size) { p.lineTo(nums[i], nums[i + 1]); i += 2 }
                'C' -> if (nums.size >= 6) p.cubicTo(nums[0], nums[1], nums[2], nums[3], nums[4], nums[5])
                'Q' -> if (nums.size >= 4) p.quadTo(nums[0], nums[1], nums[2], nums[3])
                'Z' -> p.close()
            }
            nums.clear()
        }

        for (m in re.findAll(d)) {
            val cmdGroup = m.groupValues[1]
            if (cmdGroup.isNotEmpty()) {
                flush()
                cmd = cmdGroup[0]
            } else {
                nums.add(m.groupValues[2].toFloat())
            }
        }
        flush()
    }

    fun has(name: String) = specs.containsKey(name)

    /**
     * Рисует иконку по центру выпуклой круглой клавиши.
     *
     * [bx], [by], [bw], [bh] — габарит кнопки, те же числа, что получает `Ink.convex`:
     * видимая поверхность G14 там меньше на тень и сдвинута на `SURFACE_DX/DY`, поэтому
     * иконка, поставленная в центр габарита, уезжает вниз-вправо на (2.8, 7.3)/2+2.8.
     * Сам рисунок иконки сдвигается как единое целое — рамка макета остаётся как была:
     * дизайнер уже выставил в ней оптический центр фигуры (у шеврона bbox смещён от центра).
     *
     * [iw], [ih] — рамка иконки, как её передают в [draw]; размер не меняется,
     * меняется только положение.
     */
    fun drawRound(c: Canvas, name: String, bx: Float, by: Float, bw: Float, bh: Float,
                  iw: Float, ih: Float) {
        val s = specs[name] ?: return
        val d = disc(bx, by, bw, bh)
        drawAt(c, s, d[0], d[1], iw, ih)
    }

    /**
     * То же, что [drawRound], но возвращает рамку, в которой иконка легла на экран
     * (`width`/`height` — её размер после масштаба, `left`/`top` — левый верхний угол).
     *
     * Нужна там, где поверх иконки рисуется текст: в макете B07 цифра «10» на кнопках ±10 с
     * выровнена по центру рамки иконки (у `back10` — 418.7, у `fwd10` — 987.0) и отстоит от её
     * верха на 29.3. Иконка же ставится по оптическому центру диска, поэтому макетные
     * абсолютные координаты цифры дают сдвиг вниз на 2.6 px (замечание 2026-11-20).
     */
    fun drawRoundBox(c: Canvas, name: String, bx: Float, by: Float, bw: Float, bh: Float,
                     iw: Float, ih: Float): RectF {
        val s = specs[name] ?: return RectF()
        val d = disc(bx, by, bw, bh)
        return drawBoxAt(c, s, d[0], d[1], iw, ih)
    }

    /**
     * Иконка по центру круглой клавиши, у которой поверхность ТОГО ЖЕ тона, что подложка
     * (плитки B1: и плитка, и клавиша — G14).
     *
     * Тогда тела клавиши не видно, и глаз читает круг по внешнему кольцу: белой дуге блика
     * слева-сверху (G15 @ 0,0, 131.6) и серому серпу тени справа-снизу (G10 @ +4.6,+10.1).
     * Центр этого кольца — середина полного габарита кнопки, то есть [bw]/2, [bh]/2. У [drawRound]
     * центр другой (середина силуэта «блик+поверхность»): он на 3.65 выше и на 0.9 левее, и
     * пиктограмма на плите «висит» вверх-влево. Замер по снимку главного экрана 2026-11-17:
     * было 41.2 / 43.0 / 42.8 сверху / 50.0 снизу, после правки 42.25 / 42.25 / 46.4 / 46.5.
     */
    fun drawRoundRing(c: Canvas, name: String, bx: Float, by: Float, bw: Float, bh: Float,
                      iw: Float, ih: Float) {
        val s = specs[name] ?: return
        drawAt(c, s, bx + bw / 2f, by + bh / 2f, iw, ih)
    }

    /** Иконка по центру вдавленной круглой клавиши (`Ink.inset`): центр — середина габарита. */
    fun drawInset(c: Canvas, name: String, x: Float, y: Float, w: Float, h: Float,
                  iw: Float, ih: Float) {
        val s = specs[name] ?: return
        drawAt(c, s, x + w / 2f, y + h / 2f, iw, ih)
    }

    /** Иконка с центром контура в ([cx], [cy]) — для клавиш с собственной обёрткой. */
    fun drawCentered(c: Canvas, name: String, cx: Float, cy: Float, iw: Float, ih: Float) {
        val s = specs[name] ?: return
        drawAt(c, s, cx, cy, iw, ih)
    }

    /**
     * Центр видимого диска выпуклой круглой клавиши.
     *
     * `Ink.convex` рисует три слоя: тень G10 @ +4.6,+10.1, блик G15 @ 0,0 и поверхность G14
     * @ +2.8,+2.8, все размером W−4.6 × H−10.1. Тень внизу-справа — это не кнопка, а её
     * отброшенная тень, поэтому силуэт кнопки = объединение блика и поверхности: он начинается
     * в (bx, by) и на 1.8/7.3 больше слоя. Центр этого силуэта и есть оптический центр.
     */
    private fun disc(bx: Float, by: Float, bw: Float, bh: Float): FloatArray {
        val dw = bw - T.SHADOW_DX
        val dh = bh - T.SHADOW_DY
        return floatArrayOf(bx + (dw + T.SURFACE_DX) / 2f, by + (dh + T.SURFACE_DY) / 2f)
    }

    /**
     * Рисует [s] так, чтобы центр её видимого контура [Spec.ink] попал в ([cx], [cy]),
     * при габарите рамки [iw] × [ih]. Пропорции сохраняются.
     *
     * Центр берётся по контуру, а не по рамке: у части иконок рисунок смещён внутри рамки
     * (у шеврона `back` — на 0.95 влево-вверх), и тогда в круге он выглядит «висящим».
     */
    private fun drawAt(c: Canvas, s: Spec, cx: Float, cy: Float, iw: Float, ih: Float) {
        val sx = iw / s.w
        val sy = ih / s.h
        val sc = if (sx < sy) sx else sy
        val ox = cx - ((s.ink.left + s.ink.right) / 2f) * sc
        val oy = cy - ((s.ink.top + s.ink.bottom) / 2f) * sc
        val saved = c.save()
        c.translate(ox, oy)
        c.scale(sc, sc)
        for (p in s.prims) drawPrim(c, p)
        c.restoreToCount(saved)
    }

    /** [drawAt] плюс рамка иконки на экране — для текста поверх (см. [drawRoundBox]). */
    private fun drawBoxAt(c: Canvas, s: Spec, cx: Float, cy: Float, iw: Float, ih: Float): RectF {
        val sx = iw / s.w
        val sy = ih / s.h
        val sc = if (sx < sy) sx else sy
        val ox = cx - ((s.ink.left + s.ink.right) / 2f) * sc
        val oy = cy - ((s.ink.top + s.ink.bottom) / 2f) * sc
        val saved = c.save()
        c.translate(ox, oy)
        c.scale(sc, sc)
        for (p in s.prims) drawPrim(c, p)
        c.restoreToCount(saved)
        return RectF(ox, oy, ox + s.w * sc, oy + s.h * sc)
    }

    /**
     * Рисует иконку в прямоугольник [x, y, w, h]. Пропорции сохраняются,
     * иконка центрируется внутри прямоугольника.
     */
    fun draw(c: Canvas, name: String, x: Float, y: Float, w: Float, h: Float) {
        val s = specs[name] ?: return
        val sx = w / s.w
        val sy = h / s.h
        val sc = if (sx < sy) sx else sy
        val ox = x + (w - s.w * sc) / 2f
        val oy = y + (h - s.h * sc) / 2f
        val saved = c.save()
        c.translate(ox, oy)
        c.scale(sc, sc)
        for (p in s.prims) drawPrim(c, p)
        c.restoreToCount(saved)
    }

    private fun drawPrim(c: Canvas, p: Prim) {
        stroke.strokeCap = if (p.round) Paint.Cap.ROUND else Paint.Cap.BUTT
        when (p.t) {
            'r' -> if (p.sw > 0f) {
                stroke.color = p.color
                stroke.strokeWidth = p.sw
                c.drawRoundRect(p.x, p.y, p.x + p.w, p.y + p.h, p.rad, p.rad, stroke)
            } else {
                fill.color = p.color
                c.drawRoundRect(p.x, p.y, p.x + p.w, p.y + p.h, p.rad, p.rad, fill)
            }
            'e' -> {
                if (p.sw > 0f) {
                    stroke.color = p.color
                    stroke.strokeWidth = p.sw
                    c.drawOval(p.x, p.y, p.x + p.w, p.y + p.h, stroke)
                } else {
                    fill.color = p.color
                    c.drawOval(p.x, p.y, p.x + p.w, p.y + p.h, fill)
                }
            }
            'p' -> {
                val path = p.d ?: return
                if (p.sw > 0f) {
                    stroke.color = p.color
                    stroke.strokeWidth = p.sw
                    c.drawPath(path, stroke)
                } else {
                    fill.color = p.color
                    c.drawPath(path, fill)
                }
            }
        }
    }
}
