package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import android.graphics.Typeface
import ru.ogrpfsense.dexpm8.core.LibraryDb

/**
 * B21 · Поиск по библиотеке. Числа — из design/v5/B21.json (клавиатура — B48.json, [Keyboard]).
 *
 *   шапка     назад 41,113.5 107×112, «Поиск» 60/700 в 176,105, подзаголовок 32/600 G4 в 176,181
 *   поле      вдавленное 44,249 1316×102; лупа 95.6,284.9 32×33.9; запрос 38/600 в 154,274.6;
 *      «×» — круглая клавиша 1262,256 87×92, значок 1298.1,292.1 15.8×17.4
 *   вкладки   желоб 44,388 1316×88, сегмент 48 + i·327 (327×83), подпись 34 (700 / 600 G5)
 *   панель    41,516 1321×1146; раздел 30/700 G5 в 95,556
 *   автор     клавиша 88,602.5 115×120 + «library» 127.2,642.8; имя 42/700 в 237,612;
 *      описание 32/600 G4 в 237,665; «next» 1305,649.5; разделитель 237,750 1079×2 G11
 *   книга     обложка 88,y 91×132 (корешок 13 G7); название Literata 42/600 в 219,y+18;
 *      «автор · формат» в 219,y+74; дорожка 965,y+57 238×22; «NN%» по правому краю 1316
 *   страницы  как на B2: 41.1 / 1240.4, 1708.3
 *
 * Клавиатуры на макете B21 нет: рисуем B48 ([Keyboard]) на листе с y 1385 (решение 2026-11-12).
 * Пока она открыта, строки кончаются на y 1375, страницы скрыты. Запрос ищется после каждой
 * буквы (`LibraryDb.searchBooks/Authors/Series`). Нажатие на автора или серию — их книги.
 */
class SearchScreen(act: EinkActivity) : Screen(act) {

    private class Row(
        val type: Int,
        val a: String,
        val b: String,
        val id: Long = 0L,
        val kind: Int = 0,
        val pct: Int = 0,
    )

    private val db = act.db
    private val status = StatusBar(act, this, ink, icons)
    private val kb = Keyboard(ink)
    private val lit600: Typeface = T.literata(ink.res, 600)

    private var query = ""
    private var tab = 0
    private var kbOpen = true
    private var page = 0
    private var found = 0

    private var rows = ArrayList<Row>()
    private var ry = FloatArray(0)
    private val pageStarts = ArrayList<Int>()
    private var curStart = 0
    private var curEnd = 0

    private val tabLabels = arrayOf("Всё", "Книги", "Авторы", "Серии")

    private companion object {
        const val R_HEAD = 0
        const val R_AUTHOR = 1
        const val R_SERIES = 2
        const val R_BOOK = 3

        const val ST_PROGRESS = 0
        const val ST_NEW = 1
        const val ST_READ = 2

        const val RIGHT = 1316f
        const val SHEET_Y = 1385f
        const val LIMIT_KB = 1375f
        const val LIMIT_FULL = 1655f

        const val TAB_X = 48f
        const val TAB_STEP = 327f

        const val AUTHOR_H = 149.5f
        const val AUTHOR_PITCH = 173.5f
        const val BOOK_H = 132f
        const val BOOK_PITCH = 182f

        const val R_ROUND = 51f
        const val R_PAGE = 58.5f
    }

    // --------------------------------------------------------------- данные

    private fun plural(n: Int, one: String, few: String, many: String): String {
        val m100 = n % 100
        val m10 = n % 10
        val w = if (m100 in 11..14) many else when (m10) {
            1 -> one
            2, 3, 4 -> few
            else -> many
        }
        return "$n $w"
    }

    private fun booksWord(n: Int) = plural(n, "книга", "книги", "книг")

    private fun startedWord(n: Int) = plural(n, "начата", "начаты", "начато")

    private fun bookRow(e: LibraryDb.Entry): Row {
        val kind = when (e.status) {
            LibraryDb.STATUS_READING -> ST_PROGRESS
            LibraryDb.STATUS_READ -> ST_READ
            else -> ST_NEW
        }
        val pct = if (kind == ST_PROGRESS) e.progress else if (kind == ST_READ) 100 else 0
        val author = e.author ?: "Автор неизвестен"
        return Row(R_BOOK, e.title, "$author · ${e.format.uppercase()}", e.id, kind, pct)
    }

    /** Перечитать результаты по [query] и [tab]; страница — первая. */
    private fun search() {
        val list = ArrayList<Row>()
        var n = 0
        if (query.isNotBlank()) {
            try {
                val authors = db.searchAuthors(query, 30)
                val series = db.searchSeries(query, 30)
                val books = db.searchBooks(query, 100)
                n = authors.size + series.size + books.size
                val all = tab == 0
                if ((all || tab == 2) && authors.size > 0) {
                    if (all) list.add(Row(R_HEAD, "АВТОРЫ", ""))
                    for (i in 0 until (if (all) Math.min(authors.size, 3) else authors.size)) {
                        val h = authors[i]
                        var d = booksWord(h.books)
                        if (h.started > 0) d += " · " + startedWord(h.started)
                        list.add(Row(R_AUTHOR, h.name, d))
                    }
                }
                if ((all || tab == 3) && series.size > 0) {
                    if (all) list.add(Row(R_HEAD, "СЕРИИ", ""))
                    for (i in 0 until (if (all) Math.min(series.size, 3) else series.size)) {
                        list.add(Row(R_SERIES, series[i].name, booksWord(series[i].books)))
                    }
                }
                if ((all || tab == 1) && books.size > 0) {
                    if (all) list.add(Row(R_HEAD, "КНИГИ", ""))
                    for (e in books) list.add(bookRow(e))
                }
            } catch (t: Throwable) {
                Trace.step("search FAIL: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        rows = list
        found = n
        ry = FloatArray(rows.size)
        page = 0
        repage()
    }

    // ------------------------------------------------------- страницы

    private fun limit() = if (kbOpen) LIMIT_KB else LIMIT_FULL

    private fun itemH(r: Row) = if (r.type == R_BOOK) BOOK_H else AUTHOR_H

    private fun pitch(r: Row) = if (r.type == R_BOOK) BOOK_PITCH else AUTHOR_PITCH

    /** Раскладывает строки с [start] по [ry]; возвращает индекс первой строки следующей страницы. */
    private fun place(start: Int): Int {
        var y = if (rows[start].type == R_HEAD) 556f else 575f
        var i = start
        while (i < rows.size) {
            val r = rows[i]
            if (r.type == R_HEAD) {
                val next = rows[i + 1]
                val iy = y + (if (next.type == R_BOOK) 62f else 46.5f)
                if (i > start && iy + itemH(next) > limit()) break
                ry[i] = y
                y = iy
                i++
                    continue
            }
            if (i > start && y + itemH(r) > limit()) break
            ry[i] = y
            y += pitch(r)
            i++
                if (i < rows.size && rows[i].type == R_HEAD) y += 24f
        }
        return i
    }

    private fun repage() {
        pageStarts.clear()
        var i = 0
        while (i < rows.size) {
            pageStarts.add(i)
            i = place(i)
        }
        if (page >= pageStarts.size) page = Math.max(0, pageStarts.size - 1)
        if (pageStarts.isEmpty()) {
            curStart = 0
            curEnd = 0
        } else {
            curStart = pageStarts[page]
            curEnd = place(curStart)
        }
    }

    private fun totalPages() = Math.max(1, pageStarts.size)

    // --------------------------------------------------------------- рисование

    override fun render(c: Canvas) {
        ink.rect(c, 0f, 0f, W, H, T.BG)
        status.draw(c)
        header(c)
        field(c)
        tabs(c)
        panel(c)
        if (kbOpen) {
            ink.convex(c, -3f, SHEET_Y, 1409f, 520f, 66f)
            kb.draw(c)
        } else {
            pager(c)
        }
    }

    private fun header(c: Canvas) {
        ink.convex(c, 41f, 113.5f, 107f, 112f, R_ROUND)
        icons.draw(c, "back@12.3x26.6", 87.9f, 155.3f, 12.3f, 26.6f)
        ink.text(c, "Поиск", 176f, T.base(105f, T.TS_TITLE, 1.25f), T.TS_TITLE, 700, T.G1)
        val sub = when {
            query.isBlank() -> "Название, автор или серия"
            found == 0 -> "Совпадений нет"
            else -> plural(found, "совпадение", "совпадения", "совпадений") + " в библиотеке"
        }
        ink.text(c, sub, 176f, T.base(181f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
    }

    private fun field(c: Canvas) {
        ink.inset(c, 44f, 249f, 1316f, 102f, T.R_GROOVE)
        icons.draw(c, "search", 95.6f, 284.9f, 32f, 33.9f)
        val base = T.base(274.6f, T.TS_LABEL_L, 1.3f)
        if (query.isEmpty()) {
            ink.text(c, "Название, автор или серия", 154f, base, T.TS_LABEL_L, 500, T.G6)
            if (kbOpen) ink.rect(c, 154f, 272f, 4f, 56f, T.G1)
            return
        }
        val s = ink.ellipsize(query, 1000f, T.TS_LABEL_L, 600)
        ink.text(c, s, 154f, base, T.TS_LABEL_L, 600, T.G1)
        if (kbOpen) ink.rect(c, 158f + ink.measure(s, T.TS_LABEL_L, 600), 272f, 4f, 56f, T.G1)
        ink.convex(c, 1262f, 256f, 87f, 92f, 41f)
        icons.draw(c, "close", 1298.1f, 292.1f, 15.8f, 17.4f)
    }

    private fun tabs(c: Canvas) {
        ink.inset(c, 44f, 388f, 1316f, 88f, T.R_GROOVE)
        ink.convex(c, TAB_X + tab * TAB_STEP, 392f, 327f, 83f, T.R_SEGMENT)
        val base = T.base(410f, T.TS_BODY, 1.25f)
        for (i in tabLabels.indices) {
            val on = i == tab
            ink.textCenter(c, tabLabels[i], TAB_X + i * TAB_STEP, 322f, base, T.TS_BODY,
                if (on) 700 else 600, if (on) T.G1 else T.G5)
        }
    }


    private fun panel(c: Canvas) {
        ink.convex(c, 41f, 516f, 1321f, 1146f, T.R_TILE)
        if (rows.isEmpty()) {
            val blank = query.isBlank()
            val title = if (blank) "Что ищем?" else "Ничего не найдено"
            val hint = if (blank) "Введите часть названия, имя автора или серию"
                else "Проверьте написание или ищите по автору"
            ink.textCenter(c, title, 0f, W, T.base(760f, T.TS_LABEL, 1.25f), T.TS_LABEL, 700, T.G1)
            ink.textCenter(c, hint, 0f, W, T.base(820f, T.TS_META, 1.3f), T.TS_META, 600, T.G4)
            return
        }
        for (i in curStart until curEnd) {
            val r = rows[i]
            val last = i == curEnd - 1
            when (r.type) {
                R_HEAD -> ink.text(c, r.a, 95f, T.base(ry[i], T.TS_META, 1.25f), T.TS_META, 700, T.G5)
                R_BOOK -> bookRowDraw(c, r, ry[i], !last)
                else -> authorRowDraw(c, r, ry[i], !last)
            }
        }
    }

    private fun authorRowDraw(c: Canvas, r: Row, y: Float, divider: Boolean) {
        ink.convex(c, 88f, y, 115f, 120f, R_ROUND + 4f)
        icons.draw(c, "library", 127.2f, y + 40.3f, 37.5f, 37.6f)
        ink.text(c, ink.ellipsize(r.a, 1000f, 42f, 700), 237f, T.base(y + 9.5f, 42f, 1.25f),
            42f, 700, T.G1)
        ink.text(c, r.b, 237f, T.base(y + 62.5f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        icons.draw(c, "next@11x23.8", 1305f, y + 47f, 11f, 23.8f)
        if (divider) ink.rect(c, 237f, y + 147.5f, 1079f, 2f, T.G11)
    }

    private fun bookRowDraw(c: Canvas, r: Row, y: Float, divider: Boolean) {
        ink.roundRect(c, 88f, y, 91f, 132f, T.R_COVER, T.G12)
        ink.roundRect(c, 88f, y, 26f, 132f, T.R_COVER, T.G7)
        ink.rect(c, 101f, y, 13f, 132f, T.G12)
        ink.text(c, ink.ellipsize(r.a, 725f, 42f, 600, lit600), 219f,
            T.base(y + 18f, 42f, 1.25f, true), 42f, 600, T.G1, lit600)
        ink.text(c, ink.ellipsize(r.b, 725f, T.TS_BODY_S, 600), 219f,
            T.base(y + 74f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        when (r.kind) {
            ST_PROGRESS -> {
                ink.inset(c, 965f, y + 57f, 238f, 22f, T.R_COVER, T.G12)
                val fw = Math.max(21.9f, 233f * r.pct / 100f)
                ink.roundRect(c, 968f, y + 62f, fw, 14f, 7f, T.G3)
                rightText(c, "${r.pct}%", y + 48.6f, T.TS_BODY, 700, 1.2f, T.G1)
            }
            ST_NEW -> rightText(c, "Новая", y + 49f, T.TS_BODY_S, 700, 1.25f, T.G4)
            else -> {
                val w = ink.measure("Прочитана", T.TS_BODY_S, 700)
                icons.draw(c, "check", RIGHT - w - 56f, y + 48f, 42f, 43.9f)
                rightText(c, "Прочитана", y + 49f, T.TS_BODY_S, 700, 1.25f, T.G4)
            }
        }
        if (divider) ink.rect(c, 219f, y + 156f, 1097f, 2f, T.G11)
    }

    private fun rightText(c: Canvas, s: String, top: Float, size: Float, weight: Int,
        lh: Float, color: Int) {
        ink.text(c, s, RIGHT - ink.measure(s, size, weight), T.base(top, size, lh), size, weight, color)
    }

    private fun pager(c: Canvas) {
        ink.convex(c, 41.1f, 1708.3f, 121.6f, 127.1f, R_PAGE)
        ink.convexDark(c, 1240.4f, 1708.3f, 121.6f, 127.1f, R_PAGE)
        icons.draw(c, "back@54.8x57.1", 75f, 1743.2f, 54.8f, 57.1f)
        icons.draw(c, "next@53x53", 1275.1f, 1743f, 53f, 53f)
        ink.textCenter(c, "${page + 1} / ${totalPages()}", 0f, W,
            T.base(1738.3f, T.TS_PAGE, 1.25f), T.TS_PAGE, 600, T.G1)
    }


    // --------------------------------------------------------------- ввод

    private fun inRect(x: Float, y: Float, rx: Float, ry: Float, rw: Float, rh: Float) =
        x >= rx && x <= rx + rw && y >= ry && y <= ry + rh

    private fun onKey(x: Float, y: Float) {
        val k = kb.hit(x, y) ?: return
        when (k.kind) {
            Keyboard.K_DONE -> {
                kbOpen = false
                repage()
            }
            Keyboard.K_DEL -> {
                if (query.isEmpty()) return
                query = query.dropLast(1)
                search()
            }
            else -> {
                val s = kb.press(k)
                if (s != null && query.length < 60) {
                    query += s
                    search()
                }
            }
        }
        redraw()
    }

    private fun onRow(x: Float, y: Float) {
        for (i in curStart until curEnd) {
            val r = rows[i]
            if (r.type == R_HEAD) continue
            val h = if (r.type == R_BOOK) BOOK_PITCH else AUTHOR_PITCH
            if (!inRect(x, y, 88f, ry[i], 1228f, h - 20f)) continue
            if (r.type == R_BOOK) {
                act.bookId = r.id
                act.go(EinkActivity.SCR_BOOK)
            } else {
                // Автор или серия: показать их книги.
                query = r.a
                tab = 1
                kbOpen = false
                search()
                redraw()
            }
            return
        }
    }

    override fun onUp(x: Float, y: Float) {
        if (kbOpen && y >= SHEET_Y) {
            onKey(x, y)
            return
        }
        if (inRect(x, y, 41f, 113.5f, 107f, 112f)) {
            act.pop()
        } else if (inRect(x, y, 1262f, 256f, 87f, 92f) && query.isNotEmpty()) {
            query = ""
            search()
            redraw()
        } else if (inRect(x, y, 44f, 249f, 1316f, 102f)) {
            if (!kbOpen) {
                kbOpen = true
                repage()
                redraw()
            }
        } else if (inRect(x, y, 44f, 388f, 1316f, 88f)) {
            val i = ((x - TAB_X) / TAB_STEP).toInt().coerceIn(0, tabLabels.size - 1)
            if (i != tab) {
                tab = i
                search()
                redraw()
            }
        } else if (!kbOpen && inRect(x, y, 41.1f, 1708.3f, 121.6f, 127.1f)) {
            if (page > 0) {
                page--
                    repage()
                redraw()
            }
        } else if (!kbOpen && inRect(x, y, 1240.4f, 1708.3f, 121.6f, 127.1f)) {
            if (page < totalPages() - 1) {
                page++
                    repage()
                redraw()
            }
        } else {
            onRow(x, y)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
        if (act.searchFresh) {
            act.searchFresh = false
            query = ""
            tab = 0
            kbOpen = true
        }
        search()
    }

    override fun onDetachedFromWindow() {
        status.detach()
        super.onDetachedFromWindow()
    }
}

