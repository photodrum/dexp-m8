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
 * прогресс. Нет данных до читалки — не рисуются: «Страниц» (на его месте «Добавлена») и строка
 * «Глава N из M · до конца» (вместо неё — статус: «Не начата» / «Читается» / «Прочитана»).
 * Карточка «Аннотация» (п. 27) — только у книги с аннотацией (FB2, `LibraryDb.annotation`, её
 * читает сканер): столько текста, сколько влезает в блок макета (5 строк), хвост — «…» по слову;
 * «Читать полностью» открывает книгу, как «Продолжить чтение». Нажимаются «Назад», «Продолжить
 * чтение» и «Оглавление» (обе — в читалку, вторая сразу с панелью B10), «На полку» открывает
 * [ShelfPicker], «Удалить» даёт диалог B53, «…» в шапке — нижний лист [Sheet] с пунктом
 * «Открыть папку с файлом» (B25 на каталог книги).
 *
 * Лист «…»: в макете его нет. Собран из нижних листов проекта (`ShelfPicker`): затемнение экрана,
 * панель на всю ширину с радиусом 66, захват 117×11, заголовок 60/700, строка 132 высотой,
 * подпись 38/700; «Готово» — тёмная кнопка, как на B35/B48. Вопрос дизайну про остальной
 * состав листа — `docs/DESIGN_QUESTIONS.md`.
 *
 *   обложка      гнездо 43.9,248.6 431.4×603.3; обложка 65.8,270.6 387.6×559.4 (G14), корешок 18.3 (G3);
 *                название Literata 44/600 в 117,321.8 (ширина 320, до 2 строк), автор 32/600 G4
 *   название     Literata 66/500 в 519.2,255.9; автор Manrope 40/600 G4 в 519.2,340.9
 *   свойства     подпись 30/600 G5, значение 40/700 G1; колонки 519.2 / 939.7, строки 428.9 / 548.9
 *   прогресс     шкала 519.2,671.1 694.7×25.6; «NN%» 38/800 по правому краю 1360.1; строка статуса 711.9
 *   основная     41.1,904 1320.8×141.7; три кнопки y 1079.5, 421.4×112.4, x 41.1 / 490.9 / 940.6
 *   аннотация    карточка 41.1,1233.1 1320.8×558.5 (r 51.19); «АННОТАЦИЯ» 95.1,1275 Manrope 32/800 G5;
 *                текст 95.1,1331 шириной 1213.9, Literata 40/400 G1, шаг 64 (lh 1.6), 5 строк в 320;
 *                «Читать полностью» 95,1689 Manrope 38/800 G2 + `arrowR@40.2x42` 465,1691 (вариант
 *                для светлой поверхности, как у «Пропустить» на B8)
 */
class BookScreen(act: EinkActivity) : Screen(act) {

    private val status = StatusBar(act, this, ink, icons)
    private val lit500: Typeface = T.literata(ink.res, 500)
    private val lit600: Typeface = T.literata(ink.res, 600)
    private val lit400: Typeface = T.literata(ink.res, 400)

    private var entry: LibraryDb.Entry? = null
    /** Строки аннотации, уже разбитые по ширине блока; пусто — у книги нет аннотации, карточки нет. */
    private var annLines: List<String> = emptyList()
    /** Название и автор — [CoverText]: название сверху, автор снизу. Обложка 270.6…830, поле 25. */
    private val coverText = CoverText(ink)
    private val picker = ShelfPicker(ink, icons)
    private var pickerOpen = false
    /** Лист «…» из шапки B12. */
    private var sheetOpen = false
    private var deleteOpen = false
    private var deleteError = false
    private val errorDialog = ErrorDialog(ink, icons)
    private var errorOpen = false

    private companion object {
        const val R_BTN = 51.1875f
        const val R_MAIN = 65.8125f

        /** Блок текста аннотации в макете: ширина 1213.9, высота 320 = 5 строк по 64 (40 × 1.6). */
        const val ANN_W = 1213.9f
        const val ANN_SIZE = 40f
        const val ANN_STEP = 64f
        const val ANN_LINES = 5

        val MONTHS = arrayOf(
            "января", "февраля", "марта", "апреля", "мая", "июня",
            "июля", "августа", "сентября", "октября", "ноября", "декабря",
        )
    }

    override fun render(c: Canvas) {
        val dim = if (pickerOpen || sheetOpen || deleteOpen || errorOpen) dimBegin(c) else -1
        ink.rect(c, 0f, 0f, W, H, T.BG)
        header(c)
        val e = entry
        if (e != null) {
            cover(c, e)
            info(c, e)
            buttons(c)
            if (annLines.isNotEmpty()) annotationCard(c)
        }
        if (dim >= 0) dimEnd(c, dim)
        status.draw(c)
        if (pickerOpen) picker.draw(c)
        if (sheetOpen) sheet(c)
        val del = entry
        if (deleteOpen && del != null) deleteDialog(c, del)
        if (errorOpen) errorDialog.draw(c)
    }

    private fun header(c: Canvas) {
        ink.convex(c, 41.1f, 112.3f, 106.9f, 112.4f, 51.2f)
        icons.drawRound(c, "back", 41.1f, 112.3f, 106.9f, 112.4f, 43.9f, 45.7f)
        ink.text(c, "О книге", 175.5f, T.base(104f, T.TS_TITLE, 1.2f), T.TS_TITLE, 700, T.G1)
        ink.text(c, "Библиотека", 175f, T.base(180f, T.TS_BODY_S, 1.3f), T.TS_BODY_S, 600, T.G4)
        ink.convex(c, 1255f, 112.3f, 106.9f, 112.4f, 51.2f)
        icons.drawRound(c, "more", 1255f, 112.3f, 106.9f, 112.4f, 49.4f, 51.4f)
    }

    private fun cover(c: Canvas, e: LibraryDb.Entry) {
        ink.inset(c, 43.9f, 248.6f, 431.4f, 603.3f, 40.21875f)
        ink.roundRect(c, 65.8f, 270.6f, 387.6f, 559.4f, 21.9375f, T.G14)
        ink.roundRect(c, 65.8f, 270.6f, 43.875f, 559.4f, 21.9375f, T.G3)
        ink.rect(c, 65.8f + 18.3f, 270.6f, 43.875f, 559.4f, T.G14)

        // Название сверху, автор снизу — единая раскладка обложек ([CoverText]).
        coverText.draw(c, e.title, e.author, 117f, 320f, 321.8f, 805f, 44f, 1.2f,
            T.TS_BODY_S, 1.3f)
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

    /** Карточка «Аннотация»: заголовок, строки [annLines] с шагом 64, «Читать полностью →». */
    private fun annotationCard(c: Canvas) {
        ink.convex(c, 41.1f, 1233.1f, 1320.8f, 558.5f, R_BTN)
        ink.text(c, "АННОТАЦИЯ", 95.1f, T.base(1275f, T.TS_BODY_S, 1.3f), T.TS_BODY_S, 800, T.G5)
        for ((i, line) in annLines.withIndex()) {
            ink.text(c, line, 95.1f, T.base(1331f + i * ANN_STEP, ANN_SIZE, 1.6f, true),
                ANN_SIZE, 400, T.G1, lit400)
        }
        ink.text(c, "Читать полностью", 95f, T.base(1689f, 38f, 1.2f), 38f, 800, T.G2)
        icons.draw(c, "arrowR@40.2x42", 465f, 1691f, 40.2f, 42f)
    }

    /**
     * Аннотацию (абзацы через `\n`) — в строки блока: перенос по словам, каждый абзац с новой
     * строки, не больше [ANN_LINES]. Не влезло — последняя строка укорачивается по слову и
     * получает «…». Слово шире блока (адрес без пробелов) режется по символам, как [Ink.ellipsize].
     */
    private fun wrapAnnotation(text: String): List<String> {
        val all = ArrayList<String>(ANN_LINES + 1)
        val paras = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        for (p in paras) {
            var line = ""
            for (w in p.split(' ')) {
                if (w.isEmpty()) continue
                val cand = if (line.isEmpty()) w else "$line $w"
                if (line.isEmpty() || ink.measure(cand, ANN_SIZE, 400, lit400) <= ANN_W) {
                    line = cand
                } else {
                    all.add(line)
                    line = w
                    if (all.size > ANN_LINES) break
                }
            }
            if (line.isNotEmpty() && all.size <= ANN_LINES) all.add(line)
            if (all.size > ANN_LINES) break
        }
        if (all.size <= ANN_LINES) return all
        val out = ArrayList(all.subList(0, ANN_LINES))
        out[ANN_LINES - 1] = withEllipsis(out[ANN_LINES - 1])
        return out
    }

    /** Строка + «…» в ширину блока: слова с конца отбрасываются, запятая перед «…» не остаётся. */
    private fun withEllipsis(line: String): String {
        var t = line
        while (true) {
            val cand = t.trimEnd(' ', ',', ';', ':', '-', '–', '—') + "…"
            if (ink.measure(cand, ANN_SIZE, 400, lit400) <= ANN_W) return cand
            val sp = t.lastIndexOf(' ')
            if (sp <= 0) return ink.ellipsize(t, ANN_W, ANN_SIZE, 400, lit400)
            t = t.substring(0, sp)
        }
    }

    private fun button(c: Canvas, x: Float, label: String, icon: String,
                       tx: Float, ty: Float, ix: Float, iy: Float) {
        ink.convex(c, x, 1079.5f, 421.4f, 112.4f, R_BTN)
        ink.text(c, label, tx, T.base(ty, T.TS_LABEL_L, 1.2f), T.TS_LABEL_L, 700, T.G1)
        icons.draw(c, icon, ix, iy, 45.7f, 47.6f)
    }

    /**
     * Лист «…» из шапки: «Открыть папку с файлом». Листа в макете нет — собрано из нижних
     * листов проекта (B35/B48): панель на всю ширину, r 66, захват, заголовок 60/700, одна
     * строка 132 высотой и «Готово» тёмной кнопкой. Вопрос дизайну — `DESIGN_QUESTIONS.md`.
     */
    private fun sheet(c: Canvas) {
        ink.convex(c, -3f, 1110f, 1409f, 902f, 66f)
        ink.roundRect(c, 644f, 1135f, 117f, 11f, 5.5f, T.G11)
        ink.text(c, "Ещё", 59f, T.base(1172f, 60f, 1.25f), 60f, 700, T.G1)
        ink.convex(c, 41.1f, 1290f, 1320.8f, 132f, R_BTN)
        ink.text(c, "Открыть папку с файлом", 95.1f, T.base(1333f, T.TS_LABEL_L, 1.2f),
            T.TS_LABEL_L, 700, T.G1)
        icons.draw(c, "arrowR", 1265.1f, 1334f, 43.9f, 43.9f)
        ink.convexDark(c, 41.1f, 1693f, 1320.8f, 142f, T.R_BUTTON)
        ink.textCenter(c, "Готово", 44.1f, 1317.8f, T.base(1735.7f, T.TS_ACTION, 1.24f),
            T.TS_ACTION, 700, T.G15)
    }

    private fun setSheet(on: Boolean) {
        sheetOpen = on
        act.bigFrame("book sheet $on")
        redraw()
    }

    /** Касание в листе «…»: строка «Открыть папку с файлом», «Готово», мимо — закрыть. */
    private fun sheetUp(x: Float, y: Float) {
        if (x >= 41.1f && x <= 1362f && y >= 1290f && y <= 1422f) {
            setSheet(false)
            openFolder()
            return
        }
        setSheet(false)
    }

    /**
     * «…» → «Открыть папку с файлом»: B25 «Файлы» открывается в каталоге книги (`act.filesDir`).
     * Файла нет — папки тоже: тогда остаётся корень памяти (B25 сам это проверит).
     */
    private fun openFolder() {
        val e = entry ?: return
        val f = java.io.File(e.path)
        act.filesDir = (if (f.isDirectory) f else f.parentFile)?.path
        act.go(EinkActivity.SCR_FILES)
    }

    /** Название в рамку [maxW]: до двух строк, перенос по последнему пробелу, который влезает. */
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
        if (sheetOpen) {
            sheetUp(x, y)
            return
        }
        if (deleteOpen) {
            deleteUp(x, y)
            return
        }
        if (errorOpen) {
            errorUp(x, y)
            return
        }
        if (entry != null && x >= 940.6f && x <= 1362f && y >= 1079.5f && y <= 1192f) {
            setDelete(true)
            return
        }
        // «Читать полностью» в карточке аннотации: кнопка 95,1689 410×46, зона шире — до края карточки.
        if (annLines.isNotEmpty() && entry != null && x >= 41.1f && x <= 700f &&
            y >= 1660f && y <= 1780f) {
            openReader(entry!!)
            return
        }
        if (x >= 1255f && x <= 1361.9f && y >= 112.3f && y <= 224.7f) {
            setSheet(true)
            return
        }
        // «Оглавление» на B12: своих пунктов до открытия книги нет — открываем читалку и сразу
        // панель B10 (`act.bookToc`, проверка формата и файла — та же, что у «Продолжить чтение»).
        if (entry != null && x >= 41.1f && x <= 462.5f && y >= 1079.5f && y <= 1192f) {
            act.bookToc = true
            openReader(entry!!)
            return
        }
        if (entry != null && x >= 490.9f && x <= 912.3f && y >= 1079.5f && y <= 1192f) {
            openPicker()
        } else if (x >= 41.1f && x <= 148f && y >= 112.3f && y <= 224.7f) {
            act.pop()
        } else if (entry != null && x >= 41.1f && x <= 1361.9f && y >= 904f && y <= 1045.7f) {
            openReader(entry!!)
        }
    }

    /**
     * «Продолжить чтение»: сначала проверяем, чем открывать. Читалки пока нет (R1) и PDF/CBZ
     * не поддерживаются (решение 2026-11-11) — вместо пустого экрана диалог B45 с причиной.
     * Файла нет на диске — тоже B45, с кнопкой «Удалить файл» (удаляет только строку базы).
     * Та же проверка у «Оглавления»: при ошибке флаг `act.bookToc` снимается здесь же.
     */
    private fun openReader(e: LibraryDb.Entry) {
        val fmt = e.format.lowercase(Locale.ROOT)
        val unsupported = fmt == "pdf" || fmt == "cbz"
        val missing = !java.io.File(e.path).exists()
        if (unsupported || missing) {
            errorDialog.open(
                title = if (missing) "Файл не найден" else "Формат не поддерживается",
                sub = fileName(e.path) + " · " + sizeText(e.size),
                note = if (missing) "Файл убран из памяти устройства или переименован. " +
                    "Строку можно удалить — прогресс чтения тоже уйдёт."
                else "Оболочка читает FB2, EPUB, MOBI, TXT, RTF и DjVu. " +
                    "PDF и CBZ не открываются — нужна книга в другом формате.",
                label = if (missing) "Что случилось" else "Формат",
                code = if (missing) "Файла нет по пути из библиотеки"
                else "${fmt.uppercase(Locale.ROOT)}: читалка не поддерживает этот формат",
                canDelete = missing,
            )
            // «Оглавление» (флаг `act.bookToc`) не должно сработать на следующей книге.
            act.bookToc = false
            setError(true)
            return
        }
        try {
            act.db.markOpened(e.id)
        } catch (t: Throwable) {
            Trace.step("markOpened FAIL: ${t.javaClass.simpleName}: ${t.message}")
        }
        act.go(EinkActivity.SCR_READER)
    }

    private fun fileName(path: String): String = path.substringAfterLast('/')

    private fun setError(on: Boolean) {
        errorOpen = on
        act.bigFrame("book error $on")
        redraw()
    }

    private fun errorUp(x: Float, y: Float) {
        when (errorDialog.hit(x, y)) {
            ErrorDialog.ACT_DELETE -> {
                // Файла нет — удаляем только строку базы (DEVLOG 9.4: файл не трогаем, его нет).
                val e = entry ?: return
                try {
                    act.db.deleteBook(e.id)
                } catch (t: Throwable) {
                    Trace.step("book delete row FAIL: ${t.javaClass.simpleName}: ${t.message}")
                }
                setError(false)
                act.pop()
            }
            ErrorDialog.ACT_CLOSE -> setError(false)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
        pickerOpen = false
        sheetOpen = false
        deleteOpen = false
        deleteError = false
        errorOpen = false
        entry = try {
            act.db.get(act.bookId)
        } catch (t: Throwable) {
            Trace.step("book get FAIL: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
        annLines = try {
            val e = entry
            val text = if (e != null) act.db.annotation(e.id) else null
            if (text != null) wrapAnnotation(text) else emptyList()
        } catch (t: Throwable) {
            Trace.step("book annotation FAIL: ${t.javaClass.simpleName}: ${t.message}")
            emptyList()
        }
        Trace.step("book: аннотация ${annLines.size} строк, последняя «${annLines.lastOrNull() ?: ""}»")
    }

    override fun onDetachedFromWindow() {
        status.detach()
        super.onDetachedFromWindow()
    }
}
