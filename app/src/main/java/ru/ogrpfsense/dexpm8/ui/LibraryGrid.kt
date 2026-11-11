package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import android.graphics.Typeface

/** Карточка сетки B11; [kind] — одна из `LibraryGrid.KIND_*`, [pct] нужен только у KIND_PROGRESS. */
internal class GridCard(val title: String, val author: String, val kind: Int, val pct: Int)

/**
 * Рисунок карточки сетки B11 · Библиотека. Числа — из design/v5/B11.json, «Карточка / <название>».
 * Помощник экрана [LibraryScreen], не View. Карточки лежат прямо на фоне G12, без панели.
 *
 *   рамка        414.1×558.5 (выпуклая, r 43.875), x 41.1 + col·453.4, y 509.1 + row·585
 *   лицо         рамка +2.8,+2.8, 409.5×548.4; у выбранной карточки на его месте вдавленная плашка
 *   гнездо       лицо +32.9,+29.2, 343.7×288 (вдавленное, r 25.59, дно G12);
 *                обложка +14.6,+14.6 314.4×258.8 (r 14.625, G14), корешок 12.8 (G7, у выбранной G3)
 *   на обложке   Literata 34/600 lh 1.25 G1, x обложки +32.9, верх лица +73.1, ширина 255.9, до 2 строк
 *   название     в макете Literata 36/600 под обложкой — в коде не рисуется (дубль)
 *   автор        в коде: Manrope 30/600 lh 1.3 G4, x гнезда, верх лица +339.2, ширина 343.7,
 *         до 2 строк (в макете: одна строка на +433.2)
 *   прогресс     шкала 255.9×18.3 на лице +496.1 (вдавленная, дно G12), заливка +2.7,+5.5 высотой
 *                10.1 шириной 250.5·% (G3); «%» 30/700 lh 1.2 G1 по правому краю гнезда, верх +487.2
 *   «Новая»      метка 117×43.9 (r 21.94, G3) на лице +483.3; текст 28/800 lh 1.2 G15, по центру
 *                метки, верх +488.2
 *   «Прочитана»  check 36.6×36.6 на лице +486.9; текст 30/700 lh 1.3 G4, x гнезда +46.5, верх +485.7
 *
 * Слоёв у карточки 9 («Мастер и Маргарита», «Улисс», «Солярис», «Анна Каренина»: шкала и «%» —
 * два слоя) и 8 («Бесы» — одна группа «Метка», «Пикник на обочине» — одна группа «Статус»).
 * Плашки процента у непрочитанной и прочитанной книги нет, вместо шкалы стоит метка или статус.
 */
internal class LibraryGrid(private val ink: Ink, private val icons: Icons) {

    private val lit: Typeface = T.literata(ink.res, 600)

    private val manrope: Typeface = T.manrope(ink.res, 600)

    private val lines = arrayOfNulls<String>(2)

    companion object {
        const val KIND_PROGRESS = 0
        const val KIND_NEW = 1
        const val KIND_READ = 2

        const val COLS = 3

        const val X0 = 41.1f
        const val Y0 = 509.1f
        const val COL_STEP = 453.4f
        const val ROW_STEP = 585f

        const val FRAME_W = 414.1f
        const val FRAME_H = 558.5f
        const val FACE_W = 409.5f
        const val FACE_H = 548.4f

        private const val R_CARD = 43.875f
        private const val R_NEST = 25.59375f
        private const val R_COVER = 14.625f
        private const val R_TRACK = 9.140625f
        private const val R_FILL = 5.02734375f
        private const val R_BADGE = 21.9375f

        private const val NEST_DX = 32.9f
        private const val NEST_DY = 29.2f
        private const val NEST_W = 343.7f
        private const val NEST_H = 288f
        private const val COVER_D = 14.6f
        private const val COVER_W = 314.4f
        private const val COVER_H = 258.8f
        private const val SPINE_W = 12.8f
        private const val COVER_TEXT_DX = 32.9f
        private const val COVER_TEXT_W = 255.9f

        private const val TS_COVER = 34f
        private const val TS_NAME = 36f
        private const val TS_META = 30f
        private const val LH_COVER = 1.25f
        private const val LH_META = 1.3f

        private const val TRACK_DY = 496.1f
        private const val TRACK_W = 255.9f
        private const val TRACK_H = 18.3f
        private const val FILL_DX = 2.7f
        private const val FILL_DY = 5.5f
        private const val FILL_W = 250.5f
        private const val FILL_H = 10.1f
    }

    /** Левый край рамки карточки с номером [i] на странице (0…5, порядок 3×2). */
    fun frameX(i: Int) = X0 + (i % COLS) * COL_STEP

    /** Верх рамки карточки с номером [i] на странице. */
    fun frameY(i: Int) = Y0 + (i / COLS) * ROW_STEP

    /** Рисует карточку [i]; [selected] — вдавленная плашка вместо выпуклой, неподвижная. */
    fun draw(c: Canvas, i: Int, card: GridCard, selected: Boolean) {
        val fx = frameX(i) + T.SURFACE_DX
        val fy = frameY(i) + T.SURFACE_DY
        if (selected) ink.inset(c, fx, fy, FACE_W, FACE_H, R_CARD)
        else ink.convex(c, frameX(i), frameY(i), FRAME_W, FRAME_H, R_CARD)

        val nx = fx + NEST_DX
        val ny = fy + NEST_DY
        ink.inset(c, nx, ny, NEST_W, NEST_H, R_NEST, T.G12)

        // Обложка G14 с корешком слева: скруглённый прямоугольник корешка, правая часть — прямая.
        val cx = nx + COVER_D
        val cy = ny + COVER_D
        ink.roundRect(c, cx, cy, COVER_W, COVER_H, R_COVER, T.G14)
        ink.roundRect(c, cx, cy, 2f * R_COVER, COVER_H, R_COVER, if (selected) T.G3 else T.G7)
        ink.rect(c, cx + SPINE_W, cy, 2f * R_COVER, COVER_H, T.G14)

        // Название — только на обложке; под обложкой на его месте автор до 2 строк
        // (отступ от макета, решение пользователя 2026-11-11).
        wrapped(c, card.title, cx + COVER_TEXT_DX, fy + 73.1f, COVER_TEXT_W, TS_COVER,
            lit, LH_COVER, T.G1, true)
        wrapped(c, card.author, nx, fy + 339.2f, NEST_W, TS_META, manrope, LH_META, T.G4, false)

        when (card.kind) {
            KIND_PROGRESS -> {
                val ty = fy + TRACK_DY
                ink.inset(c, nx, ty, TRACK_W, TRACK_H, R_TRACK, T.G12)
                ink.roundRect(c, nx + FILL_DX, ty + FILL_DY, FILL_W * card.pct / 100f, FILL_H,
                    R_FILL, T.G3)
                val s = "${card.pct}%"
                ink.text(c, s, nx + NEST_W - ink.measure(s, TS_META, 700),
                    T.base(fy + 487.2f, TS_META, 1.2f), TS_META, 700, T.G1)
            }
            KIND_NEW -> {
                ink.roundRect(c, nx, fy + 483.3f, 117f, 43.9f, R_BADGE, T.G3)
                ink.textCenter(c, "Новая", nx, 117f, T.base(fy + 488.2f, 28f, 1.2f), 28f, 800, T.G15)
            }
            else -> {
                icons.draw(c, "check", nx, fy + 486.9f, 36.6f, 36.6f)
                ink.text(c, "Прочитана", nx + 46.5f, T.base(fy + 485.7f, TS_META, 1.3f),
                    TS_META, 700, T.G4)
            }
        }
    }

    /**
     * Текст шрифта [font] (600) в рамку шириной [maxW]: одна строка, если влезает; иначе перенос по
     * последнему пробелу, который влезает, и «…» в конце второй строки. [top] — верх рамки текста
     * в макете, шаг строк fs·[lh].
     */
    private fun wrapped(c: Canvas, s: String, x: Float, top: Float, maxW: Float, size: Float,
       font: Typeface, lh: Float, color: Int, literata: Boolean) {
        var n = 1
        if (ink.measure(s, size, 600, font) <= maxW) {
            lines[0] = s
        } else {
            var cut = -1
            var sp = s.indexOf(' ')
            while (sp >= 0 && ink.measure(s.substring(0, sp), size, 600, font) <= maxW) {
                cut = sp
                sp = s.indexOf(' ', sp + 1)
            }
            if (cut < 0) {
                lines[0] = ink.ellipsize(s, maxW, size, 600, font)
            } else {
                lines[0] = s.substring(0, cut)
                lines[1] = ink.ellipsize(s.substring(cut + 1), maxW, size, 600, font)
                n = 2
            }
        }
        for (k in 0 until n) {
            ink.text(c, lines[k]!!, x, T.base(top + k * size * lh, size, lh, literata),
  size, 600, color, font)
        }
    }
}
