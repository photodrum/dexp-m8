package ru.ogrpfsense.dexpm8.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.content.res.Resources

/**
 * Всё рисование интерфейса. Один экземпляр на экран, ноль аллокаций на кадр:
 * Paint, RectF и Path переиспользуются.
 *
 * Правила E-Ink, которые здесь зашиты:
 *  - ни одного blur/shadowLayer/elevation — только сдвинутые копии формы;
 *  - ни одной полупрозрачности — альфа всегда 0xFF;
 *  - «объём» даётся ступенями G10/G15/G14 для выпуклости и G15/G10/G11|G12 для вдавленности.
 */
class Ink(ctx: Context) {

    val res: Resources = ctx.resources

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        strokeJoin = Paint.Join.MITER
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isSubpixelText = false    // на E-Ink субпиксельный рендер только мылит
        isLinearText = false
    }
    private val r = RectF()
    private val path = Path()

    // ---------------------------------------------------------------- формы

    /**
     * Выпуклая «пластиковая» клавиша; W×H — габарит вместе с тенью. Слои снизу вверх
     * (все одного размера W-4.6 × H-10.1, как в макете):
     *   тень G10 @ +4.6,+10.1 ; блик G15 @ 0,0 ; поверхность G14 @ +2.8,+2.8.
     * Блик раньше был размером W×H и закрывал тень целиком.
     */
    fun convex(c: Canvas, x: Float, y: Float, w: Float, h: Float, rad: Float,
               surface: Int = T.G14, highlight: Int = T.G15, shadow: Int = T.G10) {
        val lw = w - T.SHADOW_DX
        val lh = h - T.SHADOW_DY
        roundRect(c, x + T.SHADOW_DX, y + T.SHADOW_DY, lw, lh, rad, shadow)
        roundRect(c, x, y, lw, lh, rad, highlight)
        roundRect(c, x + T.SURFACE_DX, y + T.SURFACE_DY, lw, lh, rad, surface)
    }

    /** Тёмная клавиша (главная кнопка, «вперёд»): тень G8, блик G6, поверхность G3. */
    fun convexDark(c: Canvas, x: Float, y: Float, w: Float, h: Float, rad: Float) =
        convex(c, x, y, w, h, rad, T.G3, T.G6, T.G8)

    /**
     * Вдавленная форма (гнездо обложки, желоб сегментов, дорожка). Слои снизу вверх:
     *   нижний блик G15 @ 0,0 ; внутренняя тень G10 (W-2.7, H-2.8) ;
     *   дно @ +2.7,+5.5 (W-5.5, H-8.3), радиус дна на 2.74 меньше.
     * [bottom]: G11 на фоне экрана, G12 внутри карточки и в дорожках прогресса.
     */
    fun inset(c: Canvas, x: Float, y: Float, w: Float, h: Float, rad: Float,
              bottom: Int = T.G11, rim: Int = T.G10) {
        roundRect(c, x, y, w, h, rad, T.G15)
        roundRect(c, x, y, w - T.INSET_RIM_W, h - T.INSET_RIM_H, rad, rim)
        roundRect(c, x + T.INSET_DX, y + T.INSET_DY, w - T.INSET_W, h - T.INSET_H,
            rad - T.INSET_DR, bottom)
    }

    /** Плоская поверхность (панель, поле). */
    fun flat(c: Canvas, x: Float, y: Float, w: Float, h: Float, rad: Float, color: Int) =
        roundRect(c, x, y, w, h, rad, color)

    /** Прямоугольник без скругления — линии, полосы прогресса, разделители. */
    fun rect(c: Canvas, x: Float, y: Float, w: Float, h: Float, color: Int) {
        fill.color = color
        c.drawRect(x, y, x + w, y + h, fill)
    }

    fun roundRect(c: Canvas, x: Float, y: Float, w: Float, h: Float, rad: Float, color: Int) {
        if (w <= 0f || h <= 0f) return
        fill.color = color
        if (rad <= 0f) {
            c.drawRect(x, y, x + w, y + h, fill)
        } else {
            r.set(x, y, x + w, y + h)
            c.drawRoundRect(r, rad, rad, fill)
        }
    }

    fun ring(c: Canvas, x: Float, y: Float, w: Float, h: Float, rad: Float, color: Int, width: Float) {
        if (w <= 0f || h <= 0f) return
        stroke.color = color
        stroke.strokeWidth = width
        r.set(x, y, x + w, y + h)
        c.drawRoundRect(r, rad, rad, stroke)
    }

    // ---------------------------------------------------------------- текст

    /** Текст по базовой линии, выравнивание слева. */
    fun text(c: Canvas, s: CharSequence, x: Float, baseline: Float, size: Float,
             weight: Int, color: Int, font: Typeface = T.manrope(res, weight)) {
        text.typeface = font
        text.textSize = size
        text.color = color
        text.textAlign = Paint.Align.LEFT
        c.drawText(s, 0, s.length, x, baseline, text)
    }

    /** Текст, центрированный по горизонтали внутри полосы [x, x+w]. */
    fun textCenter(c: Canvas, s: CharSequence, x: Float, w: Float, baseline: Float, size: Float,
                   weight: Int, color: Int, font: Typeface = T.manrope(res, weight)) {
        text.typeface = font
        text.textSize = size
        text.color = color
        text.textAlign = Paint.Align.CENTER
        c.drawText(s, 0, s.length, x + w / 2f, baseline, text)
        text.textAlign = Paint.Align.LEFT
    }

    fun measure(s: CharSequence, size: Float, weight: Int,
                font: Typeface = T.manrope(res, weight)): Float {
        text.typeface = font
        text.textSize = size
        text.textAlign = Paint.Align.LEFT
        return text.measureText(s, 0, s.length)
    }

    /** Базовая линия для текста, отцентрованного по вертикали в полосе [top, top+h]. */
    fun baselineIn(top: Float, h: Float, size: Float, weight: Int): Float {
        text.typeface = T.manrope(res, weight)
        text.textSize = size
        val fm = text.fontMetrics
        return top + h / 2f - (fm.ascent + fm.descent) / 2f
    }

    /** Однострочная обрезка по «…» — дешевле, чем ellipsize в TextView. */
    fun ellipsize(s: String, maxW: Float, size: Float, weight: Int,
                  font: Typeface = T.manrope(res, weight)): String {
        if (measure(s, size, weight, font) <= maxW) return s
        var lo = 0
        var hi = s.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (measure(s.substring(0, mid) + "…", size, weight, font) <= maxW) lo = mid else hi = mid - 1
        }
        return s.substring(0, lo).trimEnd() + "…"
    }

    /**
     * Абзац с переносом по словам. Возвращает использованную высоту.
     * Рисует в дизайн-px, [lineGap] добавляется к межстрочному.
     */
    fun paragraph(c: Canvas, s: String, x: Float, top: Float, maxW: Float, size: Float,
                  weight: Int, color: Int, lineGap: Float,
                  font: Typeface = T.manrope(res, weight)): Float {
        text.typeface = font
        text.textSize = size
        text.color = color
        text.textAlign = Paint.Align.LEFT
        val fm = text.fontMetrics
        val step = (fm.descent - fm.ascent) + lineGap
        var y = top - fm.ascent
        var lineStart = 0
        var lastSpace = -1
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\n') {
                c.drawText(s, lineStart, i, x, y, text)
                y += step
                lineStart = i + 1
                lastSpace = -1
            } else if (ch == ' ') {
                if (text.measureText(s, lineStart, i) > maxW && lastSpace > lineStart) {
                    // Перенос по предыдущему пробелу: текущее слово целиком уходит вниз.
                    c.drawText(s, lineStart, lastSpace, x, y, text)
                    y += step
                    lineStart = lastSpace + 1
                }
                lastSpace = i
            }
            i++
        }
        if (lineStart < s.length) {
            c.drawText(s, lineStart, s.length, x, y, text)
            y += step
        }
        return y - top
    }
}
