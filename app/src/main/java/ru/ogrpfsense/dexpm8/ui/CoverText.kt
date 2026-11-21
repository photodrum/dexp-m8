package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas

/**
 * Единая раскладка текста на обложке книги (замечание 2026-11-21): **название — сверху**, до двух
 * строк с переносом по словам; **автор — снизу**, прижат к низу обложки, тоже с переносом по
 * словам (имя / фамилия), «…» только в последней строке. Один код для всех обложек с текстом:
 * плитка библиотеки B11, карточка главной B1, «О книге» B12, карточка каталога B17, заставка B19,
 * карточка «Сейчас слушаете» B33.
 *
 * Название Literata 600, автор Manrope 600; цвета G1 и G4. Автор получает столько строк, сколько
 * осталось между низом названия и [bottom], но не больше [authorLines]: на низкой обложке автор
 * не налезает на название, а теряет лишние строки.
 */
internal class CoverText(private val ink: Ink) {

    private val lit = T.literata(ink.res, 600)
    private val manrope = T.manrope(ink.res, 600)

    /**
     * Рисует название и автора на обложке.
     *
     * @param x левый край текста, [w] — ширина строки
     * @param top верх рамки первой строки названия, [bottom] — низ рамки последней строки автора
     * @param titleSize кегль названия, [titleLh] — его межстрочный коэффициент
     * @param authorSize кегль автора, [authorLh] — его межстрочный коэффициент
     * @param author null или пусто — автора на обложке нет (названия «Автор неизвестен» не ставим)
     */
    fun draw(c: Canvas, title: String, author: String?, x: Float, w: Float, top: Float,
             bottom: Float, titleSize: Float, titleLh: Float, authorSize: Float,
             authorLh: Float, titleLines: Int = 2, authorLines: Int = 3) {
        val tStep = titleSize * titleLh
        val tl = ink.ellipsize2(title, w, titleSize, 600, lit, titleLines)
        for (k in tl.indices) {
            ink.text(c, tl[k], x, T.base(top + k * tStep, titleSize, titleLh, true), titleSize,
                600, T.G1, lit)
        }
        val a = author?.trim().orEmpty()
        if (a.isEmpty()) return
        val aStep = authorSize * authorLh
        // Свободно под названием, минус зазор в пол-кегля автора.
        val free = bottom - (top + tl.size * tStep) - authorSize * 0.5f
        val n = minOf(authorLines, (free / aStep).toInt())
        if (n <= 0) return
        val al = ink.ellipsize2(a, w, authorSize, 600, manrope, n)
        val y0 = bottom - al.size * aStep
        for (k in al.indices) {
            ink.text(c, al[k], x, T.base(y0 + k * aStep, authorSize, authorLh), authorSize, 600,
                T.G4, manrope)
        }
    }
}
