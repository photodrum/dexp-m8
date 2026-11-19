package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import java.util.Locale
import ru.ogrpfsense.dexpm8.core.Prefs

/**
 * Общее для экранов плеера (п. 12: B7, B33, B34, B35): форматирование времени, слова
 * «книга/глава», дорожки прогресса и круглые клавиши. Числа — из `design/v5`, повторяются
 * у экранов один в один, поэтому собраны здесь, а не в каждом экране.
 *
 * Время: у книги — «ч ч мм мин» и «осталось 45:12», у главы — «24:10». Формат как в макете:
 * часы показываются только если они есть ([clock]) — «01:23:45» на циферблате B7,
 * «45:12» без часов.
 */
internal object AudioKit {

    /** «чч:мм:сс» или «мм:сс», если часов нет, — текущее время на циферблате B7. */
    fun clock(ms: Long): String {
        val total = (ms.coerceAtLeast(0L)) / 1000L
        val h = total / 3600L
        val m = total % 3600L / 60L
        val s = total % 60L
        return if (h > 0) String.format(Locale.ROOT, "%02d:%02d:%02d", h, m, s)
        else String.format(Locale.ROOT, "%02d:%02d", m, s)
    }

    /** «24:10» — длительность главы (B34): минуты без ведущего нуля, если часов нет. */
    fun dur(ms: Long): String {
        val total = ms.coerceAtLeast(0L) / 1000L
        val h = total / 3600L
        val m = total % 3600L / 60L
        val s = total % 60L
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    /**
     * «14 ч 50 мин» — длительность книги в списке B33; «45 мин» — если часов нет,
     * «меньше минуты» — если и минут нет.
     */
    fun hours(ms: Long): String {
        val min = ms.coerceAtLeast(0L) / 60_000L
        val h = min / 60L
        val m = min % 60L
        return when {
            h > 0 && m > 0 -> "$h ч $m мин"
            h > 0 -> "$h ч"
            m > 0 -> "$m мин"
            else -> "меньше минуты"
        }
    }

    /** «осталось 11 ч 20 мин» на карточке B33. */
    fun left(ms: Long): String = "осталось " + hours(ms)

    /** «5 книг»: слово по числу, как в подзаголовке B33. */
    fun books(n: Int): String {
        val m100 = n % 100
        val m10 = n % 10
        val w = if (m100 in 11..14) "книг" else when (m10) {
            1 -> "книга"
            2, 3, 4 -> "книги"
            else -> "книг"
        }
        return "$n $w"
    }

    /** «32 главы»: слово по числу, как в подзаголовке B34. */
    fun chapters(n: Int): String {
        val m100 = n % 100
        val m10 = n % 10
        val w = if (m100 in 11..14) "глав" else when (m10) {
            1 -> "глава"
            2, 3, 4 -> "главы"
            else -> "глав"
        }
        return "$n $w"
    }

    /**
     * Дорожка прогресса в макете: вдавленный желоб (блик G15, внутренняя тень G10, дно G12)
     * и заливка G3 поверх дна. [w] × [h] — габарит, как в снимке.
     *
     * Заливка не короче ручки ([minW]) — как у строк B2/B33: «4 %» в макете нарисовано
     * полоской 21.9 при честных 7.
     */
    fun track(ink: Ink, c: Canvas, x: Float, y: Float, w: Float, h: Float, pct: Int,
              minW: Float = 0f) {
        ink.inset(c, x, y, w, h, h / 2f, T.G12)
        val fill = maxOf(minW, (w - 5f) * pct.coerceIn(0, 100) / 100f)
        if (fill <= 0f) return
        ink.roundRect(c, x + 3f, y + 5f, fill, h - 8f, (h - 8f) / 2f, T.G3)
    }

    /** Подпись скорости для кнопки B7: «1,25×» (запятая, как в макете). */
    fun speed(pct: Int): String = Prefs.Speed.label(pct)
}
