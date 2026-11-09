package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas

/**
 * B2 · Библиотека (список). Геометрия с борда «B2 · Библиотека · Пластик»:
 *
 *   назад        41,107   107×112
 *   заголовок   176,104   60/700
 *   подзаголовок 176,180  32/600 G4
 *   вид         1155,117  205×88 (желоб + активный сегмент)
 *   поиск        44,249  1316×102
 *   сортировка   44,388  1316×88 (4 сегмента по 330)
 *   панель       41,516  1321×1136
 *   строки       95,567  шаг 168, высота 147, разделитель 1104×2 G11
 *   страница     41/1240, 1701  122×127 ; номер 44/600
 */
class LibraryScreen(act: EinkActivity) : Screen(act) {

    private class Book(val title: String, val author: String, val status: String, val rim: Int)

    private val books = arrayOf(
        Book("Анна Каренина", "Л. Толстой · FB2", "45%", T.G7),
        Book("Бесы", "Ф. Достоевский · FB2", "12%", T.G7),
        Book("Война и мир", "Л. Толстой · EPUB", "новое", T.G9),
        Book("Мастер и Маргарита", "М. Булгаков · FB2", "45%", T.G7),
        Book("Пикник на обочине", "А. и Б. Стругацкие · FB2", "78%", T.G7),
        Book("Солярис", "С. Лем · EPUB", "прочитано", T.G9)
    )

    private var page = 0
    private val perPage = 6
    private var sortMode = 0
    private var viewMode = 0     // 0 = список, 1 = сетка

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
    }

    override fun layout(w: Float, h: Float) {
        super.layout(w, h)
        hits.clear()
        hits.add(Hit(HIT_BACK, 41f, 107f, 107f, 112f))
        hits.add(Hit(HIT_PREV, 41f, 1701f, 122f, 127f))
        hits.add(Hit(HIT_NEXT, 1240f, 1701f, 122f, 127f))
        hits.add(Hit(HIT_SEARCH, 44f, 249f, 1316f, 102f))
        hits.add(Hit(HIT_VIEW, 1155f, 117f, 205f, 88f))
        hits.add(Hit(HIT_SORT, 44f, 388f, 1316f, 88f))
        val start = page * perPage
        for (i in 0 until perPage) {
            if (start + i >= books.size) break
            hits.add(Hit(HIT_BOOK + i + 1, 95f, 516f + 51f + i * 168f, 1214f, 147f))
        }
    }

    override fun render(c: Canvas) {
        ink.rect(c, 0f, 0f, W, H, T.BG)
        header(c)
        searchField(c)
        sortBar(c)
        list(c)
        pager(c)
    }

    private fun header(c: Canvas) {
        ink.convex(c, 41f, 107f, 107f, 112f, 33f)
        icons.draw(c, "back", 41f + 30f, 107f + 32f, 46f, 48f)

        ink.text(c, "Библиотека", 176f, 104f + 56f, T.TS_TITLE, 700, T.G1)
        ink.text(c, "128 книг · 4,2 ГБ", 176f, 180f + 32f, T.TS_BODY_S, 600, T.G4)

        // Переключатель вид: список / сетка — вдавленный желоб 205×88
        ink.inset(c, 1155f, 117f, 205f, 88f, 12f)
        ink.convex(c, 1160f, 122f, 100f, 83f, 10f)
        icons.draw(c, "list", 1155f + 32f, 117f + 22f, 46f, 48f)
        icons.draw(c, "grid", 1155f + 127f, 117f + 22f, 46f, 48f)
    }

    private fun searchField(c: Canvas) {
        ink.inset(c, 44f, 249f, 1316f, 102f, 16f)
        icons.draw(c, "search", 44f + 43f, 249f + 29f, 46f, 48f)
        ink.text(c, "Поиск по названию или автору", 44f + 110f, 249f + 29f + 34f,
            T.TS_BODY_S, 600, T.G6)
        icons.draw(c, "filter", 44f + 1218f, 249f + 8f, 46f, 48f)
    }

    private fun sortBar(c: Canvas) {
        val labels = arrayOf("По автору", "По названию", "Недавние", "По размеру")
        ink.inset(c, 44f, 388f, 1316f, 88f, 12f)
        ink.convex(c, 49f + sortMode * 325.5f, 393f, 330f, 83f, 10f)
        for (i in labels.indices) {
            val color = if (i == sortMode) T.G1 else T.G5
            ink.textCenter(c, labels[i], 44f + i * 325.5f, 330f, 388f + 44f, T.TS_META, 600, color)
        }
    }

    private fun list(c: Canvas) {
        ink.convex(c, 41f, 516f, 1321f, 1136f, T.R_TILE)

        val start = page * perPage
        for (i in 0 until perPage) {
            val idx = start + i
            if (idx >= books.size) break
            val b = books[idx]
            val y = 516f + 51f + i * 168f

            // Обложка 73×106: дно + «корешок» полосой
            ink.roundRect(c, 95f, y, 73f, 106f, T.R_COVER, T.G12)
            ink.roundRect(c, 95f, y, 11f, 106f, T.R_COVER, b.rim)

            ink.text(c, ink.ellipsize(b.title, 600f, T.TS_LABEL, 700),
                95f + 110f, y + 3f + 40f, T.TS_LABEL, 700, T.G1)
            ink.text(c, ink.ellipsize(b.author, 700f, T.TS_META, 600),
                95f + 110f, y + 61f + 32f, T.TS_META, 600, T.G4)
            ink.text(c, b.status, 95f + 929f, y + 32f + 32f, T.TS_META, 600, T.G4)

            // Разделитель: 1104×2 G11, кроме последней строки
            if (i < perPage - 1 && idx < books.size - 1) {
                ink.rect(c, 95f + 110f, y + 145f, 1104f, 2f, T.G11)
            }
        }
    }

    private fun pager(c: Canvas) {
        val total = Math.max(1, (books.size + perPage - 1) / perPage)
        ink.convex(c, 41f, 1701f, 122f, 127f, 33f)
        icons.draw(c, "back", 41f + 38f, 1701f + 40f, 46f, 48f)
        ink.convex(c, 1240f, 1701f, 122f, 127f, 33f)
        icons.draw(c, "arrowR", 1240f + 39f, 1701f + 41f, 44f, 44f)
        ink.textCenter(c, "${page + 1} / $total", 0f, W, 1738f + 44f, T.TS_PAGE, 600, T.G1)
    }

    // --------------------------------------------------------------- ввод

    override fun onUp(x: Float, y: Float) {
        for (h in hits) {
            if (x >= h.x && x <= h.x + h.w && y >= h.y && y <= h.y + h.h) {
                when (h.id) {
                    HIT_BACK -> act.pop()
                    HIT_PREV -> if (page > 0) { page--; layout(W, H); redraw() }
                    HIT_NEXT -> {
                        val total = Math.max(1, (books.size + perPage - 1) / perPage)
                        if (page < total - 1) { page++; layout(W, H); redraw() }
                    }
                    HIT_VIEW -> { viewMode = if (viewMode == 0) 1 else 0; redraw() }
                    HIT_SORT -> { sortMode = (sortMode + 1) % 4; redraw() }
                    HIT_SEARCH -> { /* экран ввода — следующий этап */ }
                    else -> if (h.id > HIT_BOOK) act.go(EinkActivity.SCR_READER)
                }
                return
            }
        }
    }
}
