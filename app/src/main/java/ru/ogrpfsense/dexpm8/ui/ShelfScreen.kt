package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import android.graphics.Typeface
import ru.ogrpfsense.dexpm8.core.LibraryDb

/**
 * B24 · Полка (содержимое), B54 · Переименовать полку ([NameSheet]), B55 · Удалить полку.
 * Полка — `act.shelfId`. Числа — из design/v5/B24.json, B54.json, B55.json.
 *
 *   шапка     назад 41,113.5 107×112; название 60/700 в 176,105; подзаголовок 32/600 G4 в 176,181;
 *      «…» 1255,113.5 107×112 (только у своих полок)
 *   порядок   желоб 44,249 1316×88, сегмент 48 + i·436 (431×73), подпись 34 (700 / 600 G5)
 *   панель    41,389 1321×1280; книга на y = 439 + 172·i (7 на странице): обложка 88,y 91×132;
 *      название Literata 42/600 в 219,y+18; «автор · формат» 32/600 G4 в 219,y+74;
 *      дорожка 958,y+57 238×22, «НН%» по правому краю 1316; разделитель 219,y+151
 *   страницы 41.1 / 1240.4, 1708.3
 *   B55       диалог 92,610 1225×662 (r 56); гнездо 152,674 140×140 + «trash» 197,718; заголовок
 *      332,680 60/700; пояснение 38/500 в 152,880; «Отмена» 152,1071 425×112; «Удалить» 612,1056 645×142
 *
 * В макете меню «…» нет: оно — нижний лист «Переименовать / Удалить полку» нарисован теми же
 * выпуклыми кнопками (решение по текущему варианту, DEVLOG 9.3). У автоматических полок
 * кнопки «…» нет, подзаголовок — «Автоматическая полка».
 */
class ShelfScreen(act: EinkActivity) : Screen(act) {

    private class Row(
        val id: Long,
        val title: String,
        val sub: String,
        val kind: Int,
        val pct: Int,
    )

    private val db = act.db
    private val status = StatusBar(act, this, ink, icons)
    private val sheet = NameSheet(ink)
    private val lit600: Typeface = T.literata(ink.res, 600)

    private var shelfId = 0L
    private var name = ""
    private var auto = false
    private var total = 0
    private var order = LibraryDb.SHELF_ORDER_SHELF
    private var page = 0
    private var mode = M_NONE
    private var rows = ArrayList<Row>()

    private val orderLabels = arrayOf("Порядок полки", "По автору", "По прогрессу")

    private companion object {
        const val M_NONE = 0
        const val M_MENU = 1
        const val M_RENAME = 2
        const val M_DELETE = 3

        const val ST_PROGRESS = 0
        const val ST_NEW = 1
        const val ST_READ = 2

        const val PER_PAGE = 7
        const val ROW_Y0 = 439f
        const val ROW_STEP = 172f
        const val RIGHT = 1316f
        const val R_ROUND = 51f
        const val R_PAGE = 58.5f
        const val SEG_STEP = 436f

        const val MENU_Y = 1490f
    }

    // --------------------------------------------------------------- данные

    private fun load() {
        try {
            shelfId = act.shelfId
            auto = shelfId < 0
            if (auto) {
                name = if (shelfId == LibraryDb.SHELF_READING) LibraryDb.NAME_READING else LibraryDb.NAME_READ
            } else {
                val s = db.shelf(shelfId)
                if (s == null) {
                    act.pop()
                    return
                }
                name = s.name
            }
            total = db.shelfBookCount(shelfId)
            val pages = Math.max(1, (total + PER_PAGE - 1) / PER_PAGE)
            if (page >= pages) page = pages - 1
            val list = ArrayList<Row>()
            for (e in db.shelfBooks(shelfId, order, page * PER_PAGE, PER_PAGE)) {
                val kind = when (e.status) {
                    LibraryDb.STATUS_READING -> ST_PROGRESS
                    LibraryDb.STATUS_READ -> ST_READ
                    else -> ST_NEW
                }
                val pct = if (kind == ST_PROGRESS) e.progress else if (kind == ST_READ) 100 else 0
                val author = e.author ?: "Автор неизвестен"
                list.add(Row(e.id, e.title, "$author · ${e.format.uppercase()}", kind, pct))
            }
            rows = list
        } catch (t: Throwable) {
            Trace.step("shelf load FAIL: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun totalPages() = Math.max(1, (total + PER_PAGE - 1) / PER_PAGE)

    // --------------------------------------------------------------- рисование

    override fun render(c: Canvas) {
        val dim = if (mode != M_NONE) dimBegin(c) else -1
        ink.rect(c, 0f, 0f, W, H, T.BG)
        header(c)
        order(c)
        panel(c)
        pager(c)
        if (dim >= 0) dimEnd(c, dim)
        status.draw(c)
        when (mode) {
            M_MENU -> menu(c)
            M_RENAME -> sheet.draw(c)
            M_DELETE -> deleteDialog(c)
        }
    }

    private fun header(c: Canvas) {
        ink.convex(c, 41f, 113.5f, 107f, 112f, R_ROUND)
        icons.draw(c, "back@12.3x26.6", 87.9f, 155.3f, 12.3f, 26.6f)
        ink.text(c, ink.ellipsize(name, 1000f, T.TS_TITLE, 700), 176f,
            T.base(105f, T.TS_TITLE, 1.25f), T.TS_TITLE, 700, T.G1)
        val sub = (if (auto) "Автоматическая полка · " else "Своя полка · ") + Words.books(total)
        ink.text(c, sub, 176f, T.base(181f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        if (!auto) {
            ink.convex(c, 1255f, 113.5f, 107f, 112f, R_ROUND)
            icons.draw(c, "more@53x55.2", 1282f, 142f, 53f, 55.2f)
        }
    }

    private fun order(c: Canvas) {
        ink.inset(c, 44f, 249f, 1316f, 88f, T.R_GROOVE)
        ink.convex(c, 48f + order * SEG_STEP, 253f, 436f, 83f, T.R_SEGMENT)
        val base = T.base(271f, T.TS_BODY, 1.25f)
        for (i in orderLabels.indices) {
            val on = i == order
            ink.textCenter(c, orderLabels[i], 48f + i * SEG_STEP, 431f, base, T.TS_BODY,
                if (on) 700 else 600, if (on) T.G1 else T.G5)
        }
    }

    private fun panel(c: Canvas) {
        ink.convex(c, 41f, 389f, 1321f, 1280f, T.R_TILE)
        if (rows.isEmpty()) {
            val hint = if (auto) "Книги появятся сами по прогрессу чтения"
            else "Добавьте книги кнопкой «На полку» в «О книге»"
            ink.textCenter(c, "На полке пока нет книг", 0f, W, T.base(960f, T.TS_LABEL, 1.25f),
                T.TS_LABEL, 700, T.G1)
            ink.textCenter(c, hint, 0f, W, T.base(1020f, T.TS_BODY_S, 1.3f), T.TS_BODY_S, 600, T.G4)
            return
        }
        for (i in rows.indices) bookRow(c, rows[i], ROW_Y0 + i * ROW_STEP, i < rows.size - 1)
    }

    private fun bookRow(c: Canvas, r: Row, y: Float, divider: Boolean) {
        ink.roundRect(c, 88f, y, 91f, 132f, T.R_COVER, T.G12)
        ink.roundRect(c, 88f, y, 26f, 132f, T.R_COVER, T.G7)
        ink.rect(c, 101f, y, 13f, 132f, T.G12)
        ink.text(c, ink.ellipsize(r.title, 725f, 42f, 600, lit600), 219f,
            T.base(y + 18f, 42f, 1.25f, true), 42f, 600, T.G1, lit600)
        ink.text(c, ink.ellipsize(r.sub, 725f, T.TS_BODY_S, 600), 219f,
            T.base(y + 74f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        when (r.kind) {
            ST_PROGRESS -> {
                ink.inset(c, 958f, y + 57f, 238f, 22f, T.R_COVER, T.G12)
                val fw = Math.max(21.9f, 233f * r.pct / 100f)
                ink.roundRect(c, 961f, y + 62f, fw, 14f, 7f, T.G3)
                right(c, "${r.pct}%", y + 48.6f, T.TS_BODY, 700, 1.2f, T.G1)
            }
            ST_NEW -> right(c, "Новая", y + 49f, T.TS_BODY_S, 700, 1.25f, T.G4)
            else -> {
                val w = ink.measure("Прочитана", T.TS_BODY_S, 700)
                icons.draw(c, "check", RIGHT - w - 56f, y + 48f, 42f, 43.9f)
                right(c, "Прочитана", y + 49f, T.TS_BODY_S, 700, 1.25f, T.G4)
            }
        }
        if (divider) ink.rect(c, 219f, y + 151f, 1097f, 2f, T.G11)
    }

    private fun right(c: Canvas, s: String, top: Float, size: Float, weight: Int, lh: Float, color: Int) {
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

    /** Меню «…»: лист с двумя выпуклыми кнопками (в макете нет). */
    private fun menu(c: Canvas) {
        ink.convex(c, -3f, 1380f, 1409f, 492f, 66f)
        ink.roundRect(c, 644f, 1405f, 117f, 11f, 5f, T.G11)
        ink.convex(c, 56f, MENU_Y - 40f, 1292f, 112f, T.R_TILE)
        ink.textCenter(c, "Переименовать полку", 56f, 1292f, T.base(MENU_Y - 40f + 31f, 38f, 1.2f), 38f, 700, T.G1)
        ink.convex(c, 56f, MENU_Y + 100f, 1292f, 112f, T.R_TILE)
        ink.textCenter(c, "Удалить полку", 56f, 1292f, T.base(MENU_Y + 131f, 38f, 1.2f), 38f, 700, T.G1)
    }

    private fun deleteDialog(c: Canvas) {
        ink.convex(c, 92f, 610f, 1225f, 662f, 56f)
        ink.inset(c, 152f, 674f, 140f, 140f, 70f, T.G12)
        icons.draw(c, "trash", 197f, 718f, 49.9f, 52f)
        ink.text(c, "Удалить полку?", 332f, T.base(680f, T.TS_TITLE, 1.25f), T.TS_TITLE, 700, T.G1)
        ink.text(c, ink.ellipsize(name, 840f, T.TS_BODY_S, 600) + " · " + Words.books(total), 332f,
            T.base(762f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        ink.paragraph(c, "Удалится только полка. Книги останутся в библиотеке и на других полках.",
            152f, 880f, 1100f, 38f, 500, T.G1, 6f)
        ink.convex(c, 152f, 1071f, 425f, 112f, 51f)
        ink.textCenter(c, "Отмена", 155f, 420f, T.base(1101.3f, 38f, 1.26f), 38f, 700, T.G1)
        ink.convexDark(c, 612f, 1056f, 645f, 142f, T.R_BUTTON)
        ink.textCenter(c, "Удалить полку", 615f, 640f, T.base(1098.7f, T.TS_ACTION, 1.24f), T.TS_ACTION, 700, T.G15)
    }

    // --------------------------------------------------------------- ввод

    private fun inRect(x: Float, y: Float, rx: Float, ry: Float, rw: Float, rh: Float) =
        x >= rx && x <= rx + rw && y >= ry && y <= ry + rh

    private fun setMode(m: Int) {
        mode = m
        act.bigFrame("shelf mode $m")
        redraw()
    }

    private fun rename() {
        val n = sheet.text.trim()
        try {
            if (n.isEmpty()) {
                sheet.error = "Введите название"
            } else if (db.shelfExists(n, shelfId)) {
                sheet.error = "Такая полка уже есть"
            } else {
                db.renameShelf(shelfId, n)
                name = n
                setMode(M_NONE)
                return
            }
        } catch (t: Throwable) {
            Trace.step("shelf rename FAIL: ${t.javaClass.simpleName}: ${t.message}")
            sheet.error = "Не удалось сохранить"
        }
        redraw()
    }

    private fun delete() {
        try {
            db.deleteShelf(shelfId)
        } catch (t: Throwable) {
            Trace.step("shelf delete FAIL: ${t.javaClass.simpleName}: ${t.message}")
        }
        mode = M_NONE
        act.pop()
    }

    private fun modeUp(x: Float, y: Float) {
        when (mode) {
            M_MENU -> when {
                y < 1380f -> setMode(M_NONE)
                inRect(x, y, 56f, MENU_Y - 40f, 1292f, 112f) -> {
                    sheet.open("Переименовать полку", "Сохранить", name,
                        Words.books(total) + " · своя полка")
                    setMode(M_RENAME)
                }
                inRect(x, y, 56f, MENU_Y + 100f, 1292f, 112f) -> setMode(M_DELETE)
            }
            M_RENAME -> when (sheet.hit(x, y)) {
                NameSheet.ACT_CHANGED -> redraw()
                NameSheet.ACT_CANCEL -> setMode(M_NONE)
                NameSheet.ACT_OK -> rename()
            }
            M_DELETE -> when {
                inRect(x, y, 152f, 1056f, 425f, 142f) -> setMode(M_NONE)
                inRect(x, y, 612f, 1056f, 645f, 142f) -> delete()
                y < 610f || y > 1272f -> setMode(M_NONE)
            }
        }
    }

    private fun reload() {
        load()
        redraw()
    }

    override fun onUp(x: Float, y: Float) {
        if (mode != M_NONE) {
            modeUp(x, y)
            return
        }
        if (inRect(x, y, 41f, 113.5f, 107f, 112f)) {
            act.pop()
        } else if (!auto && inRect(x, y, 1255f, 113.5f, 107f, 112f)) {
            setMode(M_MENU)
        } else if (inRect(x, y, 44f, 249f, 1316f, 88f)) {
            val i = ((x - 48f) / SEG_STEP).toInt().coerceIn(0, orderLabels.size - 1)
            if (i != order) {
                order = i
                page = 0
                reload()
            }
        } else if (inRect(x, y, 41.1f, 1708.3f, 121.6f, 127.1f)) {
            if (page > 0) {
                page--
                reload()
            }
        } else if (inRect(x, y, 1240.4f, 1708.3f, 121.6f, 127.1f)) {
            if (page < totalPages() - 1) {
                page++
                    reload()
            }
        } else {
            for (i in rows.indices) {
                if (!inRect(x, y, 88f, ROW_Y0 + i * ROW_STEP, 1228f, 152f)) continue
                act.bookId = rows[i].id
                act.go(EinkActivity.SCR_BOOK)
                return
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
        mode = M_NONE
        page = 0
        order = LibraryDb.SHELF_ORDER_SHELF
        load()
    }

    override fun onDetachedFromWindow() {
        status.detach()
        super.onDetachedFromWindow()
    }
}
