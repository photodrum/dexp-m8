package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import ru.ogrpfsense.dexpm8.core.LibraryDb

/**
 * B23 · Полки (список) + B48 · Новая полка (лист ввода названия, [NameSheet]).
 * Числа — из design/v5/B23.json и B48.json.
 *
 *   шапка     назад 41,113.5 107×112; «Полки» 60/700 в 176,105; подзаголовок 32/600 G4 в 176,181
 *   панель    41,246 1321×(250 + 186·(строк−1)), r 51
 *   строка    y = 285 + 186·i: клавиша 78,y 115×120; значок по центру клавиши;
 *      название 42/700 в 227,y+9.5; описание 32/600 G4 в 227,y+62.5; «next» 1312,y+47;
 *      разделитель 227,y+152 1096×2 G11
 *   подсказка 59, под панелью +34, 32/600 G4; кнопка «Новая полка» 41,1693 1321×142 (тёмная)
 *
 * Состав: «Читаю сейчас» (автоматическая), свои полки по названию, «Прочитанные»
 * (автоматическая). Отступы от макета: нет «Хочу прочитать» (обычная своя полка) и «Аудиокниг»
 * (в базе их нет); текст подсказки другой — долгого нажатия в [Screen] нет, переименование и
 * удаление — в меню «…» самой полки; больше 6 строк — страницы (пагинация на месте подсказки).
 */
class ShelvesScreen(act: EinkActivity) : Screen(act) {

    private class Row(val id: Long, val name: String, val count: Int, val auto: Boolean, val icon: Int)

    private val db = act.db
    private val status = StatusBar(act, this, ink, icons)
    private val sheet = NameSheet(ink)
    private var sheetOpen = false

    private var rows = ArrayList<Row>()
    private var allBooks = 0
    private var page = 0

    private companion object {
        const val I_MARK = 0
        const val I_LIB = 1
        const val I_CHECK = 2

        const val PER_PAGE = 6
        const val ROW_Y0 = 285f
        const val ROW_STEP = 186f
        const val R_ROUND = 51f
        const val R_PAGE = 58.5f
    }

    // --------------------------------------------------------------- данные

    private fun load() {
        val list = ArrayList<Row>()
        try {
            list.add(Row(LibraryDb.SHELF_READING, LibraryDb.NAME_READING,
                    db.shelfBookCount(LibraryDb.SHELF_READING), true, I_MARK))
            for (s in db.shelves()) list.add(Row(s.id, s.name, s.count, false, I_LIB))
            list.add(Row(LibraryDb.SHELF_READ, LibraryDb.NAME_READ,
                    db.shelfBookCount(LibraryDb.SHELF_READ), true, I_CHECK))
            allBooks = db.count()
        } catch (t: Throwable) {
            Trace.step("shelves load FAIL: ${t.javaClass.simpleName}: ${t.message}")
        }
        rows = list
        if (page >= totalPages()) page = totalPages() - 1
    }

    private fun totalPages() = Math.max(1, (rows.size + PER_PAGE - 1) / PER_PAGE)

    private fun pageCount() = Math.max(0, Math.min(PER_PAGE, rows.size - page * PER_PAGE))

    // --------------------------------------------------------------- рисование

    override fun render(c: Canvas) {
        val dim = if (sheetOpen) dimBegin(c) else -1
        ink.rect(c, 0f, 0f, W, H, T.BG)
        header(c)
        val n = pageCount()
        val panelH = 250f + ROW_STEP * (n - 1)
        ink.convex(c, 41f, 246f, 1321f, panelH, T.R_TILE)
        for (i in 0 until n) shelfRow(c, rows[page * PER_PAGE + i], ROW_Y0 + i * ROW_STEP, i < n - 1)
        if (totalPages() > 1) {
            pager(c, 246f + panelH + 34f)
        } else {
            val hint = "Автоматические полки собираются сами по прогрессу чтения. " +
                "Свои полки можно переименовать или удалить в меню «…» внутри полки."
            ink.paragraph(c, hint, 59f, 246f + panelH + 34f, 1290f, 32f, 600, T.G4, 2f)
        }
        newButton(c)
        if (dim >= 0) dimEnd(c, dim)
        status.draw(c)
        if (sheetOpen) sheet.draw(c)
    }

    private fun header(c: Canvas) {
        ink.convex(c, 41f, 113.5f, 107f, 112f, R_ROUND)
        icons.draw(c, "back@12.3x26.6", 87.9f, 155.3f, 12.3f, 26.6f)
        ink.text(c, "Полки", 176f, T.base(105f, T.TS_TITLE, 1.25f), T.TS_TITLE, 700, T.G1)
        val sub = Words.shelves(rows.size) + " · " + Words.books(allBooks)
        ink.text(c, sub, 176f, T.base(181f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
    }

    private fun shelfRow(c: Canvas, r: Row, y: Float, divider: Boolean) {
        ink.convex(c, 78f, y, 115f, 120f, R_ROUND + 4f)
        when (r.icon) {
            I_MARK -> bookmark(c, 125f, y + 37f)
            I_CHECK -> icons.draw(c, "check@25.7x19.3", 120.5f, y + 44.1f, 30.9f, 23.2f)
            else -> icons.draw(c, "library", 117.2f, y + 40.3f, 37.5f, 37.6f)
        }
        ink.text(c, ink.ellipsize(r.name, 1000f, 42f, 700), 227f, T.base(y + 9.5f, 42f, 1.25f),
            42f, 700, T.G1)
        val d = if (r.auto) Words.books(r.count) + " · автоматически" else Words.books(r.count)
        ink.text(c, d, 227f, T.base(y + 62.5f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        icons.draw(c, "next@11x23.8", 1312f, y + 47f, 11f, 23.8f)
        if (divider) ink.rect(c, 227f, y + 152f, 1096f, 2f, T.G11)
    }

    /** Закладка 22.1×44.2 (B23: rect 22.1×42 r 4, обводка 3, точка 11): блик со сдвигом +2.2, затем G4. */
    private fun bookmark(c: Canvas, x: Float, y: Float) {
        ink.ring(c, x + 1.5f, y + 2.2f + 1.5f, 19.1f, 39f, 4f, T.G15, 3f)
        ink.roundRect(c, x + 5.5f, y + 8.9f, 11f, 11f, 5.5f, T.G15)
        ink.ring(c, x + 1.5f, y + 1.5f, 19.1f, 39f, 4f, T.G4, 3f)
        ink.roundRect(c, x + 5.5f, y + 6.6f, 11f, 11f, 5.5f, T.G4)
    }

    private fun pager(c: Canvas, top: Float) {
        ink.convex(c, 41.1f, top, 121.6f, 127.1f, R_PAGE)
        ink.convexDark(c, 1240.4f, top, 121.6f, 127.1f, R_PAGE)
        icons.draw(c, "back@54.8x57.1", 75f, top + 34.9f, 54.8f, 57.1f)
        icons.draw(c, "next@53x53", 1275.1f, top + 34.7f, 53f, 53f)
        ink.textCenter(c, "${page + 1} / ${totalPages()}", 0f, W,
            T.base(top + 30f, T.TS_PAGE, 1.25f), T.TS_PAGE, 600, T.G1)
    }

    private fun newButton(c: Canvas) {
        ink.convexDark(c, 41f, 1693f, 1321f, 142f, T.R_BUTTON)
        // «plus» 25.7×25.7 в 107.2,1749.2: два штриха 2.5 G15 с круглыми концами.
        ink.line(c, 120.03f, 1749.2f, 120.03f, 1774.9f, 2.5f, T.G15)
        ink.line(c, 107.2f, 1762.03f, 132.9f, 1762.03f, 2.5f, T.G15)
        ink.text(c, "Новая полка", 170f, T.base(1735.7f, T.TS_ACTION, 1.24f), T.TS_ACTION, 700, T.G15)
    }

    // --------------------------------------------------------------- ввод

    private fun inRect(x: Float, y: Float, rx: Float, ry: Float, rw: Float, rh: Float) =
        x >= rx && x <= rx + rw && y >= ry && y <= ry + rh

    private fun openSheet() {
        sheet.open("Новая полка", "Создать", "",
            "Книги добавляются кнопкой «На полку» в «О книге»")
        sheetOpen = true
        act.bigFrame("shelf name open")
        redraw()
    }

    private fun closeSheet() {
        sheetOpen = false
        act.bigFrame("shelf name close")
        redraw()
    }

    private fun sheetUp(x: Float, y: Float) {
        when (sheet.hit(x, y)) {
            NameSheet.ACT_CHANGED -> redraw()
            NameSheet.ACT_CANCEL -> closeSheet()
            NameSheet.ACT_OK -> create()
        }
    }

    private fun create() {
        val name = sheet.text.trim()
        try {
            if (name.isEmpty()) {
                sheet.error = "Введите название"
            } else if (db.shelfExists(name)) {
                sheet.error = "Такая полка уже есть"
            } else {
                db.createShelf(name)
                load()
                page = totalPages() - 1
                closeSheet()
                return
            }
        } catch (t: Throwable) {
            Trace.step("shelf create FAIL: ${t.javaClass.simpleName}: ${t.message}")
            sheet.error = "Не удалось создать полку"
        }
        redraw()
    }

    override fun onUp(x: Float, y: Float) {
        if (sheetOpen) {
            sheetUp(x, y)
            return
        }
        if (inRect(x, y, 41f, 113.5f, 107f, 112f)) {
            act.pop()
        } else if (inRect(x, y, 41f, 1693f, 1321f, 142f)) {
            openSheet()
        } else if (totalPages() > 1 && y >= 1440f && y <= 1650f) {
            if (x <= 200f && page > 0) {
                page--
                redraw()
            } else if (x >= 1200f && page < totalPages() - 1) {
                page++
                    redraw()
            }
        } else {
            for (i in 0 until pageCount()) {
                if (!inRect(x, y, 78f, ROW_Y0 + i * ROW_STEP, 1285f, 152f)) continue
                act.shelfId = rows[page * PER_PAGE + i].id
                act.go(EinkActivity.SCR_SHELF)
                return
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
        sheetOpen = false
        load()
    }

    override fun onDetachedFromWindow() {
        status.detach()
        super.onDetachedFromWindow()
    }
}
