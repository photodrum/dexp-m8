package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

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
 *   r x y w h radius  <hex|stroke:hex:width>
 *   e x y w h         <hex|stroke:hex:width>
 *   p x y w h sw      <hex|stroke:hex:width>  d="..."
 */
class Icons {

    private class Prim(
        val t: Char,
        val x: Float, val y: Float, val w: Float, val h: Float,
        val rad: Float, val sw: Float,
        val color: Int, val d: Path?
    )

    private class Spec(val w: Float, val h: Float, val prims: Array<Prim>)

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
            if (spec.startsWith("stroke:")) {
                val v = spec.split(':')
                color = Color.parseColor(v[1])
                realSw = v[2].toFloat()
            } else {
                color = Color.parseColor(spec)
                realSw = sw
            }
            val d = if (t == 'p' && tk.size > colIdx + 1) {
                Path().apply { parseSvg(this, tk.subList(colIdx + 1, tk.size).joinToString(" ")) }
            } else null
            list.add(Prim(t, x, y, pw, ph, rad, realSw, color, d))
        }
        specs[name] = Spec(w, h, list.toTypedArray())
    }

    /** Разбор M/L/C/Q/Z без внешних зависимостей (только абсолютные команды Penpot). */
    private fun parseSvg(p: Path, d: String) {
        val re = Regex("([MLCQZmlcqz])|(-?\\d+(?:\\.\\d+)?)")
        var cmd = 'M'
        val nums = ArrayList<Float>(8)
        fun flush() {
            var i = 0
            when (cmd) {
                'M' -> { p.moveTo(nums[0], nums[1]); i = 2; while (i + 1 < nums.size) { p.lineTo(nums[i], nums[i + 1]); i += 2 } }
                'L' -> while (i + 1 < nums.size) { p.lineTo(nums[i], nums[i + 1]); i += 2 }
                'C' -> if (nums.size >= 6) p.cubicTo(nums[0], nums[1], nums[2], nums[3], nums[4], nums[5])
                'Q' -> if (nums.size >= 4) p.quadTo(nums[0], nums[1], nums[2], nums[3])
                'Z' -> p.close()
                else -> {}
            }
            nums.clear()
        }
        for (m in re.findAll(d)) {
            val s = m.value
            if (s.length == 1) { flush(); cmd = s[0] } else nums.add(s.toFloat())
        }
        flush()
    }

    fun has(name: String) = specs.containsKey(name)

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
