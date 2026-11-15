package ru.ogrpfsense.dexpm8.reader

/**
 * «Авто» обрезка полей скана (R2.5, желоб B31 «Авто / Вручную / Без обрезки»).
 *
 * Движок обрезки сам не ищет: `dexp_djvu_page_view` ждёт готовые промилле. Поэтому рамку
 * полей находим в Kotlin по превью страницы — тому же кадру 600×820, что рисуется на листе
 * B31 и по которому человек видит, что именно отрежется.
 *
 * Зерно ищется по уровням самой алфы, а не по «серому»: движок отдаёт буфер уже после
 * `dexp_gray_to_palette` (`cre_gray.h`: шаг 17 плюс инверсия), то есть в шкале `ALPHA_8` —
 * 0 это бумага, 255 это штрих (буфер рисуется цветом G0 поверх G15).
 *
 * Порог: пиксель с алфой не меньше [DARK] — содержимое; строка или столбец, где таких
 * меньше [MIN_ROW] (0,4 %), — поле. К найденной рамке добавляется отступ [PAD] промилле —
 * иначе обрезка прижмёт текст к линии обреза и колонтитул скана врежется в край экрана.
 * Ответ — промилле исходной страницы в порядке движка: слева, сверху, справа, снизу.
 * null — содержимое не нашлось (пустая страница) или занимает почти весь лист: тогда
 * честнее не резать вовсе, чем срезать край текста.
 */
object DjvuAuto {

    /** Пиксель с алфой не меньше этой — содержимое (бумага скана 0…80, штрих 255). */
    const val DARK = 170

    /** Доля тёмных в строке/столбце, ниже которой она считается полем: 0,4 %. */
    const val MIN_ROW = 0.004f

    /** Отступ от найденной рамки — 2 % стороны (решение пользователя 2026-11-14). */
    const val PAD = 20

    /** Меньше этого остатка по стороне (промилле) — обрезка съест страницу, не режем. */
    const val KEEP_MIN = 250

    /** Больше этого остатка по обеим сторонам — содержимое на весь лист, край не трогаем. */
    const val KEEP_MAX = 995

    /**
     * Промилле обрезки по кадру [g] (8 бит на точку, stride = [w], шкала `ALPHA_8`).
     * null — резать нечего.
     */
    fun measure(g: ByteArray, w: Int, h: Int): IntArray? {
        if (w <= 0 || h <= 0 || g.size < w * h) return null
        var y0 = -1
        var y1 = -1
        for (y in 0 until h) {
            if (rowHasInk(g, w, y, 0, w)) {
                if (y0 < 0) y0 = y
                y1 = y
            }
        }
        if (y0 < 0) return null                    // ни одного тёмного пикселя
        var x0 = -1
        var x1 = -1
        for (x in 0 until w) {
            if (colHasInk(g, w, x, y0, y1)) {
                if (x0 < 0) x0 = x
                x1 = x
            }
        }
        if (x0 < 0) return null
        return ratios(x0, y0, x1 + 1, y1 + 1, w, h)
    }

    /**
     * Есть ли тёмное в строке [y] между [from] и [to] (столбцы). Порог — [MIN_ROW] от длины
     * проверяемого отрезка: одна случайная пылинка строку содержимым не делает.
     */
    private fun rowHasInk(g: ByteArray, w: Int, y: Int, from: Int, to: Int): Boolean {
        val need = Math.max(1, ((to - from) * MIN_ROW).toInt())
        var n = 0
        val base = y * w
        for (x in from until to) {
            if ((g[base + x].toInt() and 0xFF) >= DARK && ++n >= need) return true
        }
        return false
    }

    /** То же по столбцу [x] между строками [y0] и [y1] — по ним уже найден вертикальный отступ. */
    private fun colHasInk(g: ByteArray, w: Int, x: Int, y0: Int, y1: Int): Boolean {
        val need = Math.max(1, ((y1 - y0 + 1) * MIN_ROW).toInt())
        var n = 0
        var i = y0 * w + x
        for (y in y0..y1) {
            if ((g[i].toInt() and 0xFF) >= DARK && ++n >= need) return true
            i += w
        }
        return false
    }

    /** Пиксели рамки → промилле с отступом; null — обрезка вышла за разумные пределы. */
    private fun ratios(x0: Int, y0: Int, x1: Int, y1: Int, w: Int, h: Int): IntArray? {
        val cl = Math.max(0, pm(x0, w) - PAD)
        val ct = Math.max(0, pm(y0, h) - PAD)
        val cr = Math.max(0, 1000 - pm(x1, w) - PAD)
        val cb = Math.max(0, 1000 - pm(y1, h) - PAD)
        if (1000 - cl - cr < KEEP_MIN || 1000 - ct - cb < KEEP_MIN) return null
        if (1000 - cl - cr > KEEP_MAX && 1000 - ct - cb > KEEP_MAX) return null
        if (cl == 0 && ct == 0 && cr == 0 && cb == 0) return null
        return intArrayOf(cl, ct, cr, cb)
    }

    private fun pm(v: Int, total: Int): Int = (v * 1000 + total / 2) / total
}