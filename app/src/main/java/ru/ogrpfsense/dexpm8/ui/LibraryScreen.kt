package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas

/**
 * B2 · Библиотека (список) и B11 · Библиотека (сетка). Числа — из design/v5/B02.json и
 * B11.json; базовые линии — по рамкам текста через [T.base] (top + fs·(lh/2 + k)).
 * Переключатель вида ([viewMode]) выбирает [list] или [grid]; шапка, поиск, сортировка и
 * пагинация общие (рамки, текст, круги — те же числа). Иконки в B11 другого размера (центры
 * те же): `back`/`list`/`grid` в шапке 43.9×45.7 против 45.7×47.6 на B2 — рисуем теми же
 * вызовами, что на B2 (разница 1.8 px в допуске ±2 px; `IconSpecs` не трогали, вопрос чату
 * дизайна). Поиск, фильтр (38.4×40.2) и пагинация (`back` 54.8×57.1, `next` 53×53) в режиме
 * сетки рисуются по размерам и позициям B11 ([searchField], [pager]).
 *
 *   статус-бар    [StatusBar]
 *   назад         41.1,112.3     106.9×112.4 (круг 102.4), иконка 72.2,144.3
 *   заголовок     175.5,104      60/700 lh 1.2 ; подзаголовок 175.5,180  32/600 lh 1.3 G4
 *   вид           1155.4,122.3   204.8×87.7 (желоб), сегмент 1159.9 (+95.1 для «сетки») 99.6×83.2
 *   поиск         43.9,248.6     1316.2×102.4 (вдавленное), плейсхолдер 153.6 38/500 G6,
 *                 фильтр 1262.3,256.9 85×90.5
 *   сортировка    43.9,387.6     1316.2×87.7, сегмент 48.4 + i·325.4, ширина 330, подпись 34
 *   панель        41.1,516.4     1320.8×1136.2
 *   строки        95.1, y = 569.2 + i·184; обложка 73.1×106, корешок 11 (G7); текст с x 204.8;
 *                 состояние книги — по правому краю 1309; разделитель 204.8, +145, 1104.2×1.8 G11
 *   выбранная     вдавленная плашка 65.8, y−22, 1272.4×149.9, дно G12; обложка G14, корешок G3
 *   страницы      41.1 / 1240.4, 1708.3, 121.6×127.1 (круг 117); номер 44/600, верх рамки 1745.3
 *
 * Сетка B11 — числа карточек в [LibraryGrid] (без панели, карточки прямо на фоне G12):
 *   карточка      рамка 414.1×558.5 (выпуклая, r 43.875), x 41.1 + i·453.4, y 509.1 / 1094.1;
 *                 лицо = рамка +2.8,+2.8 (409.5×548.4); зоны касания — рамки карточек
 *   выбранная     «Мастер и Маргарита» — вдавленная на месте лица, неподвижно
 *   гнездо        лицо +32.9,+29.2, 343.7×288; обложка 314.4×258.8; названия Literata 34 / 36
 */
class LibraryScreen(act: EinkActivity) : Screen(act) {

    /** [kind]: [ST_PROGRESS] с процентом [pct], [ST_NEW] или [ST_READ]. */
    private class Book(val title: String, val author: String, val kind: Int, val pct: Int)

    // Заглушка данных по снимку; настоящий список даст сканер (п. 3).
    private val books = arrayOf(
        Book("Анна Каренина", "Лев Толстой · FB2", ST_PROGRESS, 14),
        Book("Бесы", "Фёдор Достоевский · EPUB", ST_NEW, 0),
        Book("Война и мир", "Лев Толстой · FB2", ST_PROGRESS, 3),
        Book("Мастер и Маргарита", "Михаил Булгаков · FB2", ST_PROGRESS, 45),
        Book("Пикник на обочине", "А. и Б. Стругацкие · EPUB", ST_READ, 100),
        Book("Солярис", "Станислав Лем · MOBI", ST_PROGRESS, 62)
    )

    // Заглушка сетки B11 — шесть карточек из design/v5/B11.json в порядке макета (3×2). Это
    // другие книги, чем в списке B2 («Улисс» вместо «Войны и мира»); авторы — как в карточках
    // (без формата). Настоящие данные придут на шаге 3.7.
    private val gridBooks = arrayOf(
        GridCard("Мастер и Маргарита", "Михаил Булгаков", LibraryGrid.KIND_PROGRESS, 45),
        GridCard("Улисс", "Джеймс Джойс", LibraryGrid.KIND_PROGRESS, 8),
        GridCard("Бесы", "Ф. Достоевский", LibraryGrid.KIND_NEW, 0),
        GridCard("Солярис", "Станислав Лем", LibraryGrid.KIND_PROGRESS, 62),
        GridCard("Пикник на обочине", "А. и Б. Стругацкие", LibraryGrid.KIND_READ, 100),
        GridCard("Анна Каренина", "Лев Толстой", LibraryGrid.KIND_PROGRESS, 14)
    )

    private val cards = LibraryGrid(ink, icons)

    private val sortLabels = arrayOf("По автору", "По дате", "Новые", "Непрочитанные")

    private var page = 0
    private val perPage = 6
    private var sortMode = 0
    private var viewMode = 0     // 0 = список, 1 = сетка

    private val litTitle = T.literata(ink.res, 600)

    private val status = StatusBar(act, this, ink, icons)

    private val hits = ArrayList<Hit>(16)
    private class Hit(val id: Int, val x: Float, val y: Float, val w: Float, val h: Float)

    private companion object {
        const val HIT_BACK = 1
        const val HIT_PREV = 2
        const val HIT_NEXT = 3
        const val HIT_SEARCH = 4
        const val HIT_SORT = 5
        const val HIT_VIEW = 6
        const val HIT_BOOK = 7

        const val ST_PROGRESS = 0
        const val ST_NEW = 1
        const val ST_READ = 2

        /** Выбранная (нажатая) строка в макете — «Мастер и Маргарита»; рисуется неподвижно. */
        const val SELECTED = 3

        /** Выбранная (вдавленная) карточка сетки в макете B11 — первая, «Мастер и Маргарита». */
        const val SELECTED_GRID = 0

        const val ROW_Y0 = 569.2f
        const val ROW_STEP = 184f
        const val ROW_H = 146.8f
        const val TEXT_X = 204.8f
        const val TEXT_MAX_W = 730f      // до дорожки прогресса (950.6)
        const val RIGHT = 1309f          // правый край состояния книги (панель 1360 − 51)
        const val TS_BOOK = 42f          // название книги в строке (Literata 600)

        const val TRACK_X = 950.6f
        const val TRACK_W = 237.7f
        const val BAR_X = 953.4f
        const val BAR_W = 232.2f
        const val BAR_MIN = 21.9f        // «3 %» в макете нарисовано шириной 21.9, а не 7
        const val R_BAR = 6.85546875f

        const val SORT_X = 43.9f
        const val SORT_STEP = 325.4f

        const val R_ROUND_BACK = 51.2f   // круглые клавиши: диск 102.4 / 117 / 80.4
        const val R_ROUND_PAGE = 58.5f
        const val R_ROUND_FILTER = 40.2f
    }

    override fun layout(w: Float, h: Float) {
        super.layout(w, h)
        hits.clear()
        hits.add(Hit(HIT_BACK, 41.1f, 112.3f, 106.9f, 112.4f))
        hits.add(Hit(HIT_PREV, 41.1f, 1708.3f, 121.6f, 127.1f))
        hits.add(Hit(HIT_NEXT, 1240.4f, 1708.3f, 121.6f, 127.1f))
        hits.add(Hit(HIT_SEARCH, 43.9f, 248.6f, 1316.2f, 102.4f))
        hits.add(Hit(HIT_VIEW, 1155.4f, 122.3f, 204.8f, 87.7f))
        hits.add(Hit(HIT_SORT, SORT_X, 387.6f, 1316.2f, 87.7f))
        val start = page * perPage
        for (i in 0 until perPage) {
            if (start + i >= count()) break
            if (viewMode == 1) {
                // Сетка: зона карточки — её рамка в макете (414.1×558.5, зазор между рамками 39.3).
                hits.add(Hit(HIT_BOOK + i + 1, cards.frameX(i), cards.frameY(i),
                    LibraryGrid.FRAME_W, LibraryGrid.FRAME_H))
            } else {
                hits.add(Hit(HIT_BOOK + i + 1, 95.1f, ROW_Y0 + i * ROW_STEP, 1213.9f, ROW_H))
            }
        }
    }

    override fun render(c: Canvas) {
        ink.rect(c, 0f, 0f, W, H, T.BG)
        status.draw(c)
        header(c)
        searchField(c)
        sortBar(c)
        if (viewMode == 1) grid(c) else list(c)
        pager(c)
    }

    private fun count() = if (viewMode == 1) gridBooks.size else books.size

    private fun header(c: Canvas) {
        ink.convex(c, 41.1f, 112.3f, 106.9f, 112.4f, R_ROUND_BACK)
        icons.draw(c, "back", 72.2f, 144.3f, 45.7f, 47.6f)

        ink.text(c, "Библиотека", 175.5f, T.base(104f, T.TS_TITLE, 1.2f), T.TS_TITLE, 700, T.G1)
        ink.text(c, "128 книг · 4,2 ГБ", 175.5f, T.base(180f, T.TS_BODY_S, 1.3f),
            T.TS_BODY_S, 600, T.G4)

        // Переключатель вида: желоб 204.8×87.7, активный сегмент сдвигается на 95.1.
        ink.inset(c, 1155.4f, 122.3f, 204.8f, 87.7f, T.R_GROOVE)
        ink.convex(c, 1159.9f + viewMode * 95.1f, 126.9f, 99.6f, 83.2f, T.R_SEGMENT)
        icons.draw(c, "list", 1187.4f, 144.3f, 45.7f, 47.6f)
        icons.draw(c, "grid", 1282.4f, 144.3f, 45.7f, 47.6f)
    }

    private fun searchField(c: Canvas) {
        ink.inset(c, 43.9f, 248.6f, 1316.2f, 102.4f, T.R_TILE)
        if (viewMode == 1) {
            // B11: поиск 87.8,277.9 43.9×45.7; плейсхолдер 154,275.3; фильтр 1286.1,281.5 38.4×40.2.
            icons.draw(c, "search", 87.8f, 277.9f, 43.9f, 45.7f)
            ink.text(c, "Название, автор или серия", 154f, T.base(275.3f, T.TS_LABEL_L, 1.3f),
                T.TS_LABEL_L, 500, T.G6)
            ink.convex(c, 1262.3f, 256.9f, 85f, 90.5f, R_ROUND_FILTER)
            icons.draw(c, "filter", 1286.1f, 281.5f, 38.4f, 40.2f)
        } else {
            icons.draw(c, "search", 86.8f, 275.1f, 45.7f, 47.6f)
            ink.text(c, "Название, автор или серия", 153.6f, T.base(274.4f, T.TS_LABEL_L, 1.3f),
                T.TS_LABEL_L, 500, T.G6)
            ink.convex(c, 1262.3f, 256.9f, 85f, 90.5f, R_ROUND_FILTER)
            icons.draw(c, "filter", 1282.4f, 277.9f, 45.7f, 47.6f)
        }
    }

    private fun sortBar(c: Canvas) {
        ink.inset(c, SORT_X, 387.6f, 1316.2f, 87.7f, T.R_GROOVE)
        ink.convex(c, 48.4f + sortMode * SORT_STEP, 392.1f, 330f, 83.2f, T.R_SEGMENT)
        val base = T.base(409.5f, T.TS_BODY, 1.3f)
        for (i in sortLabels.indices) {
            val active = i == sortMode
            ink.textCenter(c, sortLabels[i], 51.2f + i * SORT_STEP, SORT_STEP, base, T.TS_BODY,
                if (active) 700 else 600, if (active) T.G1 else T.G5)
        }
    }

    private fun list(c: Canvas) {
        ink.convex(c, 41.1f, 516.4f, 1320.8f, 1136.2f, T.R_TILE)

        val start = page * perPage
        for (i in 0 until perPage) {
            val idx = start + i
            if (idx >= books.size) break
            val b = books[idx]
            val y = ROW_Y0 + i * ROW_STEP
            val sel = idx == SELECTED

            if (sel) ink.inset(c, 65.8f, y - 22f, 1272.4f, 149.9f, T.R_SEGMENT, T.G12)
            cover(c, y, if (sel) T.G14 else T.G12, if (sel) T.G3 else T.G7)

            ink.text(c, ink.ellipsize(b.title, TEXT_MAX_W, TS_BOOK, 600, litTitle),
                TEXT_X, T.base(y + 3f, TS_BOOK, 1.25f, true), TS_BOOK, 600, T.G1, litTitle)
            ink.text(c, ink.ellipsize(b.author, TEXT_MAX_W, T.TS_BODY_S, 600),
                TEXT_X, T.base(y + 61f, T.TS_BODY_S, 1.3f), T.TS_BODY_S, 600, T.G4)

            bookState(c, b, y)

            // Разделитель — на обложка + 145, кроме выбранной и последней строки.
            if (!sel && i < perPage - 1 && idx < books.size - 1) {
                ink.rect(c, TEXT_X, y + 145f, 1104.2f, 1.8f, T.G11)
            }
        }
    }

    /** Обложка 73.1×106 и корешок 11 со скруглением только слева (радиус 10.97). */
    private fun cover(c: Canvas, y: Float, body: Int, spine: Int) {
        ink.roundRect(c, 95.1f, y, 73.1f, 106f, T.R_COVER, body)
        ink.roundRect(c, 95.1f, y, 22f, 106f, T.R_COVER, spine)
        ink.rect(c, 106.1f, y, 11f, 106f, body)
    }

    /** Состояние книги по правому краю 1309: прогресс, «Новая» или «Прочитана». */
    private fun bookState(c: Canvas, b: Book, y: Float) {
        when (b.kind) {
            ST_PROGRESS -> {
                ink.inset(c, TRACK_X, y + 45.1f, TRACK_W, 21.9f, T.R_COVER, T.G12)
                val fw = Math.max(BAR_MIN, BAR_W * b.pct / 100f)
                ink.roundRect(c, BAR_X, y + 50.6f, fw, 13.7f, R_BAR, T.G3)
                rightText(c, "${b.pct}%", y + 35f, T.TS_BODY, 700, 1.2f, T.G1)
            }
            ST_NEW -> rightText(c, "Новая", y + 34.6f, T.TS_BODY_S, 700, 1.3f, T.G4)
            else -> {
                icons.draw(c, "check", 1084f, y + 34.5f, 42f, 43.9f)
                rightText(c, "Прочитана", y + 34.6f, T.TS_BODY_S, 700, 1.3f, T.G4)
            }
        }
    }

    /** Текст по правому краю [RIGHT]; [top] — верх рамки текста в макете. */
    private fun rightText(c: Canvas, s: String, top: Float, size: Float, weight: Int,
                          lh: Float, color: Int) {
        ink.text(c, s, RIGHT - ink.measure(s, size, weight), T.base(top, size, lh),
            size, weight, color)
    }

    private fun grid(c: Canvas) {
        val start = page * perPage
        for (i in 0 until perPage) {
            val idx = start + i
            if (idx >= gridBooks.size) break
            cards.draw(c, i, gridBooks[idx], idx == SELECTED_GRID)
        }
    }

    private fun totalPages() = Math.max(1, (count() + perPage - 1) / perPage)

    private fun pager(c: Canvas) {
        ink.convex(c, 41.1f, 1708.3f, 121.6f, 127.1f, R_ROUND_PAGE)
        ink.convexDark(c, 1240.4f, 1708.3f, 121.6f, 127.1f, R_ROUND_PAGE)
        if (viewMode == 1) {
            // B11: back 75,1743.2 54.8×57.1; next 1275.1,1743 53×53.
            icons.draw(c, "back", 75f, 1743.2f, 54.8f, 57.1f)
            icons.draw(c, "next", 1275.1f, 1743f, 53f, 53f)
        } else {
            icons.draw(c, "back", 79.5f, 1747.6f, 45.7f, 47.6f)
            icons.draw(c, "next", 1279.7f, 1747.6f, 43.9f, 43.9f)
        }
        ink.textCenter(c, "${page + 1} / ${totalPages()}", 0f, W,
            T.base(1745.3f, T.TS_PAGE, 1.2f), T.TS_PAGE, 600, T.G1)
    }

    // --------------------------------------------------------------- ввод

    override fun onUp(x: Float, y: Float) {
        for (h in hits) {
            if (x >= h.x && x <= h.x + h.w && y >= h.y && y <= h.y + h.h) {
                when (h.id) {
                    HIT_BACK -> act.pop()
                    HIT_PREV -> if (page > 0) { page--; layout(W, H); redraw() }
                    HIT_NEXT -> if (page < totalPages() - 1) { page++; layout(W, H); redraw() }
                    HIT_VIEW -> {
                        viewMode = if (viewMode == 0) 1 else 0
                        page = 0
                        layout(W, H)    // зоны касания строк и карточек различаются
                        redraw()
                    }
                    HIT_SORT -> {
                        val i = ((x - SORT_X) / SORT_STEP).toInt().coerceIn(0, sortLabels.size - 1)
                        if (i != sortMode) { sortMode = i; redraw() }
                    }
                    HIT_SEARCH -> { /* экран ввода — следующий этап */ }
                    else -> if (h.id > HIT_BOOK) act.go(EinkActivity.SCR_READER)
                }
                return
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
    }

    override fun onDetachedFromWindow() {
        status.detach()
        super.onDetachedFromWindow()
    }
}

