package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import android.graphics.Typeface
import java.util.Calendar
import java.util.Locale
import ru.ogrpfsense.dexpm8.core.LibraryDb

/**
 * B12 · О книге. Числа — из design/v5/B12.json.
 *
 * Данные — строка `LibraryDb` (`act.bookId`): название, автор, формат, размер, дата добавления,
 * прогресс. Нет данных до читалки — не рисуются: «Страниц» (на его месте «Добавлена»), строка
 * «Глава N из M · до конца» (вместо неё — статус: «Не начата» / «Читается» / «Прочитана»)
 * и карточка «Аннотация». Нажимаются «Назад» и «Продолжить чтение» (в читалку);
 * «На полку» открывает [ShelfPicker]; «Удалить» даёт диалог B53; «Оглавление» и «…» нарисованы, пока не работают.
 *
 *   обложка      гнездо 43.9,248.6 431.4×603.3; обложка 65.8,270.6 387.6×559.4 (G14), корешок 18.3 (G3);
 *                название Literata 44/600 в 117,321.8 (ширина 320, до 2 строк), автор 32/600 G4
 *   название     Literata 66/500 в 519.2,255.9; автор Manrope 40/600 G4 в 519.2,340.9
 *   свойства     подпись 30/600 G5, значение 40/700 G1; колонки 519.2 / 939.7, строки 428.9 / 548.9
 *   прогресс     шкала 519.2,671.1 694.7×25.6; «NN%» 38/800 по правому краю 1360.1; строка статуса 711.9
 *   основная     41.1,904 1320.8×141.7; три кнопки y 1079.5, 421.4×112.4, x 41.1 / 490.9 / 940.6
 */
class BookScreen(act: EinkActivity) : Screen(act) {

    private val status = StatusBar(act, this, ink, icons)
    private val lit500: Typeface = T.literata(ink.res, 500)
    private val lit600: Typeface = T.literata(ink.res, 600)

    private var entry: LibraryDb.Entry? = null
    private val lines = arrayOfNulls<String>(2)
    private val picker = ShelfPicker(ink, icons)
    private var pickerOpen = false
    private var deleteOpen = false
    private var deleteError = false

    private companion object {
        const val R_BTN = 51.1875f
        const val R_MAIN = 65.8125f

        val MONTHS = arrayOf(
            "января", "февраля", "марта", "апреля", "мая", "июня",
            "июля", "августа", "сентября", "октября", "ноября", "декабря",
        )
    }

    override fun render(c: Canvas) {
        val dim = if (pickerOpen || deleteOpen) dimBegin(c) else -1
        ink.rect(c, 0f, 0f, W, H, T.BG)
        header(c)
        val e = entry
        if (e != null) {
            cover(c, e)
            info(c, e)
            buttons(c)
        }
        if (dim >= 0) dimEnd(c, dim)
        status.draw(c)
        if (pickerOpen) picker.draw(c)
        val del = entry
        if (deleteOpen && del != null) deleteDialog(c, del)
    }

    private fun header(c: Canvas) {
        ink.convex(c, 41.1f, 112.3f, 106.9f, 112.4f, 51.2f)
        icons.draw(c, "back", 73.1f, 144.3f, 43.9f, 45.7f)
        ink.text(c, "О книге", 175.5f, T.base(104f, T.TS_TITLE, 1.2f), T.TS_TITLE, 700, T.G1)
        ink.text(c, "Библиотека", 175f, T.base(180f, T.TS_BODY_S, 1.3f), T.TS_BODY_S, 600, T.G4)
        ink.convex(c, 1255f, 112.3f, 106.9f, 112.4f, 51.2f)
        icons.draw(c, "more", 1284.3f, 142.5f, 49.4f, 51.4f)
    }

    private fun cover(c: Canvas, e: LibraryDb.Entry) {
        ink.inset(c, 43.9f, 248.6f, 431.4f, 603.3f, 40.21875f)
        ink.roundRect(c, 65.8f, 270.6f, 387.6f, 559.4f, 21.9375f, T.G14)
        ink.roundRect(c, 65.8f, 270.6f, 43.875f, 559.4f, 21.9375f, T.G3)
        ink.rect(c, 65.8f + 18.3f, 270.6f, 43.875f, 559.4f, T.G14)

        val n = wrap(e.title, 320f, 44f, lit600)
        for (k in 0 until n) {
            ink.text(c, lines[k]!!, 117f, T.base(321.8f + k * 44f * 1.2f, 44f, 1.2f, true), 44f, 600, T.G1, lit600)
        }
        val a = e.author
        if (a != null) {
            ink.text(c, ink.ellipsize(a, 320f, T.TS_BODY_S, 600), 117f,
                T.base(321.8f + n * 52.8f + 22.2f, T.TS_BODY_S, 1.3f), T.TS_BODY_S, 600, T.G4)
        }
    }

    private fun info(c: Canvas, e: LibraryDb.Entry) {
        ink.text(c, ink.ellipsize(e.title, 840.9f, 66f, 500, lit500), 519.2f,
            T.base(255.9f, 66f, 1.2f, true), 66f, 500, T.G1, lit500)
        ink.text(c, ink.ellipsize(e.author ?: "Автор неизвестен", 840.9f, 40f, 600), 519.2f,
            T.base(340.9f, 40f, 1.3f), 40f, 600, T.G4)

        prop(c, "Формат", e.format.uppercase(Locale.ROOT), 519.2f, 428.9f)
        prop(c, "Размер", sizeText(e.size), 939.7f, 428.9f)
        prop(c, "Добавлена", dateText(e.added), 519.2f, 548.9f)

        // Прогресс: шкала, «NN%», статус.
        val pct = when (e.status) {
            LibraryDb.STATUS_READ -> 100
            LibraryDb.STATUS_READING -> e.progress.coerceIn(0, 100)
            else -> 0
        }
        ink.inset(c, 519.2f, 671.1f, 694.7f, 25.6f, 12.796875f)
        if (pct > 0) {
            ink.roundRect(c, 521.9f, 676.6f, 689.2f * pct / 100f, 17.4f, 8.68359375f, T.G3)
        }
        val s = "$pct%"
        ink.text(c, s, 1360.1f - ink.measure(s, 38f, 800), T.base(660.9f, 38f, 1.2f), 38f, 800, T.G1)
        val st = when (e.status) {
            LibraryDb.STATUS_READ -> "Прочитана"
            LibraryDb.STATUS_READING -> "Читается"
            else -> "Не начата"
        }
        ink.text(c, st, 519.2f, T.base(711.9f, T.TS_BODY_S, 1.3f), T.TS_BODY_S, 600, T.G4)
    }

    private fun prop(c: Canvas, label: String, value: String, x: Float, y: Float) {
        ink.text(c, label, x, T.base(y, T.TS_META, 1.3f), T.TS_META, 600, T.G5)
        ink.text(c, ink.ellipsize(value, 380f, 40f, 700), x, T.base(y + 41f, 40f, 1.2f), 40f, 700, T.G1)
    }

    private fun buttons(c: Canvas) {
        ink.convexDark(c, 41.1f, 904f, 1320.8f, 141.7f, R_MAIN)
        ink.text(c, "Продолжить чтение", 95.1f, T.base(947.6f, T.TS_ACTION, 1.2f), T.TS_ACTION, 700, T.G15)
        icons.draw(c, "arrowR", 1265.1f, 950.6f, 43.9f, 43.9f)

        button(c, 41.1f, "Оглавление", "chapters", 146.3f, 1112.3f, 83.2f, 1111.5f)
        button(c, 490.9f, "На полку", "bookmark", 596f, 1112.3f, 532.9f, 1111.5f)
        button(c, 940.6f, "Удалить", "trash", 1045.7f, 1110.2f, 982.6f, 1109.3f)
    }

    private fun button(c: Canvas, x: Float, label: String, icon: String,
                       tx: Float, ty: Float, ix: Float, iy: Float) {
        ink.convex(c, x, 1079.5f, 421.4f, 112.4f, R_BTN)
        ink.text(c, label, tx, T.base(ty, T.TS_LABEL_L, 1.2f), T.TS_LABEL_L, 700, T.G1)
        icons.draw(c, icon, ix, iy, 45.7f, 47.6f)
    }

    /** Название в рамку [maxW]: до двух строк, перенос по последнему пробелу, который влезает. */
    private fun wrap(s: String, maxW: Float, size: Float, font: Typeface): Int {
        if (ink.measure(s, size, 600, font) <= maxW) {
            lines[0] = s
            return 1
        }
        var cut = -1
        var sp = s.indexOf(' ')
        while (sp >= 0 && ink.measure(s.substring(0, sp), size, 600, font) <= maxW) {
            cut = sp
            sp = s.indexOf(' ', sp + 1)
        }
        if (cut < 0) {
            lines[0] = ink.ellipsize(s, maxW, size, 600, font)
            return 1
        }
        lines[0] = s.substring(0, cut)
        lines[1] = ink.ellipsize(s.substring(cut + 1), maxW, size, 600, font)
        return 2
    }

    private fun sizeText(bytes: Long): String {
        val ru = Locale("ru")
        return when {
            bytes < 1_000_000L -> "${Math.max(1L, Math.round(bytes / 1e3))} КБ"
            bytes < 100_000_000L -> String.format(ru, "%.1f МБ", bytes / 1e6)
            else -> SettingsData.size(bytes)
        }
    }

    /** «12 октября»; если год не текущий — «12 октября 2025». */
    private fun dateText(ms: Long): String {
        if (ms <= 0L) return "—"
        val cal = Calendar.getInstance()
        val now = cal.get(Calendar.YEAR)
        cal.timeInMillis = ms
        val s = "${cal.get(Calendar.DAY_OF_MONTH)} ${MONTHS[cal.get(Calendar.MONTH)]}"
        val y = cal.get(Calendar.YEAR)
        return if (y == now) s else "$s $y"
    }

    /**
     * B53: диалог 92,543 1225×617 (в макете 795 со строкой «Сохранить заметки и цитаты» — заметок
     * в проекте нет, строки нет, кнопки сдвинуты на 178 вверх). Гнездо 152,607 140×140 + «trash»
     * 197,651; заголовок 60/700 в 332,613; подзаголовок 32/600 G4 в 332,695; пояснение 38/500 в
     * 152,813; «Отмена» 152,959 425×112; «Удалить» 612,944 645×142 (тёмная).
     */
    private fun deleteDialog(c: Canvas, e: LibraryDb.Entry) {
        ink.convex(c, 92f, 543f, 1225f, 617f, 56f)
        ink.inset(c, 152f, 607f, 140f, 140f, 70f, T.G12)
        icons.draw(c, "trash", 197f, 651f, 49.9f, 52f)
        ink.text(c, "Удалить книгу?", 332f, T.base(613f, T.TS_TITLE, 1.25f), T.TS_TITLE, 700, T.G1)
        val sub = e.title + (e.author?.let { " · $it" } ?: "") + " · " + e.format.uppercase()
        ink.text(c, ink.ellipsize(sub, 880f, T.TS_BODY_S, 600), 332f,
            T.base(695f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        val note = if (deleteError) "Не удалось удалить файл. Проверьте доступ к памяти."
        else "Файл будет удалён из памяти устройства вместе с прогрессом чтения."
        ink.paragraph(c, note, 152f, 813f, 1100f, 38f, 500, T.G1, 6f)
        ink.convex(c, 152f, 959f, 425f, 112f, 51f)
        ink.textCenter(c, "Отмена", 155f, 420f, T.base(989.2f, 38f, 1.26f), 38f, 700, T.G1)
        ink.convexDark(c, 612f, 944f, 645f, 142f, T.R_BUTTON)
        ink.textCenter(c, "Удалить", 615f, 640f, T.base(986.7f, T.TS_ACTION, 1.24f), T.TS_ACTION, 700, T.G15)
    }

    private fun setDelete(on: Boolean) {
        deleteOpen = on
        deleteError = false
        act.bigFrame("book delete $on")
        redraw()
    }

    /** Файл, затем строка базы. Файл не удалился — базу не трогаем, диалог остаётся с ошибкой. */
    private fun deleteBook() {
        val e = entry ?: return
        try {
            val f = java.io.File(e.path)
            if (f.exists() && !f.delete()) {
                deleteError = true
                redraw()
                return
            }
            act.db.deleteBook(e.id)
        } catch (t: Throwable) {
            Trace.step("book delete FAIL: ${t.javaClass.simpleName}: ${t.message}")
            deleteError = true
            redraw()
            return
        }
        deleteOpen = false
        act.pop()
    }

    private fun deleteUp(x: Float, y: Float) {
        if (x >= 152f && x <= 577f && y >= 959f && y <= 1071f) {
            setDelete(false)
        } else if (x >= 612f && x <= 1257f && y >= 944f && y <= 1086f) {
            deleteBook()
        } else if (y < 543f || y > 1160f) {
            setDelete(false)
        }
    }

    private fun openPicker() {
        val e = entry ?: return
        try {
            picker.open(act.db.shelves(), act.db.shelvesOfBook(e.id))
        } catch (t: Throwable) {
            Trace.step("picker open FAIL: ${t.javaClass.simpleName}: ${t.message}")
            return
        }
        pickerOpen = true
        act.bigFrame("shelf picker open")
        redraw()
    }

    private fun pickerUp(x: Float, y: Float) {
        when (picker.hit(x, y)) {
            ShelfPicker.ACT_TOGGLE -> {
                val e = entry ?: return
                val s = picker.shelfAt(picker.row)
                try {
                    picker.set(s.id, act.db.toggleOnShelf(s.id, e.id))
                } catch (t: Throwable) {
                    Trace.step("toggleOnShelf FAIL: ${t.javaClass.simpleName}: ${t.message}")
                }
                redraw()
            }
            ShelfPicker.ACT_CLOSE -> {
                pickerOpen = false
                act.bigFrame("shelf picker close")
                redraw()
            }
        }
    }

    override fun onUp(x: Float, y: Float) {
        if (pickerOpen) {
            pickerUp(x, y)
            return
        }
        if (deleteOpen) {
            deleteUp(x, y)
            return
        }
        if (entry != null && x >= 940.6f && x <= 1362f && y >= 1079.5f && y <= 1192f) {
            setDelete(true)
            return
        }
        if (entry != null && x >= 490.9f && x <= 912.3f && y >= 1079.5f && y <= 1192f) {
            openPicker()
        } else if (x >= 41.1f && x <= 148f && y >= 112.3f && y <= 224.7f) {
            act.pop()
        } else if (entry != null && x >= 41.1f && x <= 1361.9f && y >= 904f && y <= 1045.7f) {
            val e = entry!!
            try {
                act.db.markOpened(e.id)
            } catch (t: Throwable) {
                Trace.step("markOpened FAIL: ${t.javaClass.simpleName}: ${t.message}")
            }
            act.go(EinkActivity.SCR_READER)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
        pickerOpen = false
        deleteOpen = false
        deleteError = false
        entry = try {
            act.db.get(act.bookId)
        } catch (t: Throwable) {
            Trace.step("book get FAIL: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    override fun onDetachedFromWindow() {
        status.detach()
        super.onDetachedFromWindow()
    }
}
