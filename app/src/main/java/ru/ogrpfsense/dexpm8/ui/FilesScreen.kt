package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import android.os.Environment
import java.io.File
import java.text.Collator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ru.ogrpfsense.dexpm8.core.Scanner

/**
 * B25 · Файлы — обзор папок памяти. Числа — из design/v5/B25.json.
 *
 *   шапка      «Назад» 41,113.5 107×112 (иконка 87.9,155.3 12.3×26.6); «Файлы» 176,105 60/700;
 *      «Свободно N из M ГБ» 176,181 32/600 G4; сортировка 1255,113.5 107×112, «sort» 1290.6,153.2
 *   крошки     вдавленные 44,249 1316×102 r44; «вверх» 51,255 89×94, иконка 86,295 20×11.7;
 *      путь 160,276.9 36/600 G4, текущая папка 36/700 G1
 *   панель     41,389 1321×1280 (выпуклая, r 51)
 *   папка      клавиша 88,y 115×120, «files» 125,y+40.3 42×37.6, название 42/700 в 237,y+9.5,
 *      «N файлов» 32/600 G4 в 237,y+62.5, «next» 1305,y+47 11×23.8, разделитель 237,y+152
 *   файл       гнездо 88,y 110×110 (r 26, дно G12), формат 28/800 G3 по центру гнезда в y+37.7,
 *      имя 42/600 в 237,y+5, «1,1 МБ · 12 окт 2026» 32/600 G4 в 237,y+58,
 *      «В библиотеке» 32/700 G4 по правому краю 1264.8 и «check» 1291.5,y+45.2 24.5×18.4,
 *      либо «+» 1225,y+6.5 93×98 с «plus» 1260.3,y+41.8 23.3×25
 *   страницы   41.1 / 1240.4, 1708.3, 121.6×127.1 (круг 117); номер 44/600, верх рамки 1738.3
 *
 * Решения 9.6 (ROADMAP): шаг строк 172 от 426 — в макете 426…1467 с разбросом ±7, поэтому
 * 7 строк на страницу ровным шагом; сначала папки по имени, затем файлы; скрытые (`.*`)
 * пропускаются; корень — «Память» (`Environment.getExternalStorageDirectory()`).
 *
 * Отступления от макета (вопросы в `DESIGN_QUESTIONS.md`): клавиша сортировки переключает
 * «по имени ↔ по дате» без листа — вид выбранного состояния в макете не нарисован; `pdf`/`cbz`
 * показываются с пометкой «не поддерживается» и без «+» (в макете только «В библиотеке» и «+»);
 * пустая папка — карточка B44 «В папке нет книг» (на B25 такого состояния нет).
 *
 * Файлы попадают в библиотеку кнопкой «+» ([Scanner.addFile]) или сканером; нажатие на строку
 * книги из библиотеки открывает «О книге» (B12), на остальные — ничего.
 */
class FilesScreen(act: EinkActivity) : Screen(act) {

    /** Строка списка: папка или файл. [format] — для файла (у папки пустая строка). */
    private class Row(val file: File, val folder: Boolean, val name: String, val format: String)

    private val scanner = Scanner(act, act.db)
    private val status = StatusBar(act, this, ink, icons)
    private val empty = EmptyState(ink, icons)

    private val root: File = Environment.getExternalStorageDirectory()
    private var dir: File = root

    private var rows = ArrayList<Row>(24)
    private var page = 0

    /** Есть ли файл уже в библиотеке; кэш на экран — B12 возвращает сюда же. */
    private val inLib = HashMap<String, Boolean>()

    private val collator = Collator.getInstance(Locale("ru"))
    private val dateFmt = SimpleDateFormat("d MMM yyyy", Locale("ru"))

    /** Касания крошек: папка сегмента и его левый край. */
    private val crumbHits = ArrayList<Pair<File, Float>>(8)

    private companion object {
        const val ROUND = 51.2f
        const val R_UP = 42f          // «вверх» 89×94: слои 84.4×83.9
        const val R_ROW = 55f         // клавиша папки 115×120: слои 110.4×109.9
        const val R_PLUS = 44f        // «+» 93×98: слои 88.4×87.9
        const val R_PAGE = 58.5f

        const val SORT_NAME = 0
        const val SORT_DATE = 1

        const val ROW_Y0 = 426f
        const val ROW_STEP = 172f
        const val ROW_HIT_H = 162f
        const val PER_PAGE = 7
        const val DIVIDER_DY = 152f
        const val TEXT_X = 237f
        const val TEXT_W = 730f        // до статуса «В библиотеке» (984.8) и «+» (1225)
        const val STATUS_RIGHT = 1264.8f
        const val RIGHT = 1316f
        const val TS_BOOK = 42f

        const val CRUMB_X = 160f
        const val CRUMB_MAX_W = 1170f
        const val SEP = "  ›  "

        const val PAGER_Y = 1708.3f
    }

    // --------------------------------------------------------------- данные

    /** Формат файла для «Файлов»: книги и неподдерживаемые; null — такой файл не показываем. */
    private fun formatOf(f: File): String? = scanner.fileFormat(f.name)

    private fun reload() {
        load()
        redraw()
    }

    private fun load() {
        val list = ArrayList<Row>(32)
        try {
            dir.listFiles()?.forEach { f ->
                if (f.name.startsWith(".")) return@forEach
                if (f.isDirectory) {
                    list.add(Row(f, true, f.name, ""))
                } else {
                    val fmt = formatOf(f) ?: return@forEach
                    list.add(Row(f, false, f.name, fmt))
                }
            }
        } catch (t: Throwable) {
            Trace.step("files load FAIL: ${t.javaClass.simpleName}: ${t.message}")
        }
        val folders = list.filter { it.folder }.sortedWith { a, b -> collator.compare(a.name, b.name) }
        val plain = list.filter { !it.folder }
        val files = if (act.prefs.filesSort == SORT_DATE) {
            // Новые сверху; одинаковое время — по имени, чтобы порядок не прыгал.
            plain.sortedWith { a, b ->
                val d = b.file.lastModified().compareTo(a.file.lastModified())
                if (d != 0) d else collator.compare(a.name, b.name)
            }
        } else {
            plain.sortedWith { a, b -> collator.compare(a.name, b.name) }
        }
        rows = ArrayList<Row>(folders.size + files.size)
        rows.addAll(folders)
        rows.addAll(files)
        if (page >= pages()) page = pages() - 1
    }

    private fun pages() = Math.max(1, (rows.size + PER_PAGE - 1) / PER_PAGE)

    private fun isEmptyFolder() = rows.isEmpty()

    private fun parentOf(f: File): File? {
        if (f.path == root.path) return null
        val p = f.parentFile ?: return root
        // Выше корня не поднимаемся: «Память» — верхняя папка списка.
        return if (p.path.length < root.path.length) root else p
    }

    /** Путь от корня до текущей папки; первый сегмент — «Память». */
    private fun segments(): ArrayList<File> {
        val chain = ArrayList<File>(6)
        var f: File? = dir
        while (f != null && f.path.length >= root.path.length) {
            chain.add(f)
            if (f.path == root.path) break
            f = f.parentFile
        }
        chain.reverse()
        return chain
    }

    private fun label(f: File): String = if (f.path == root.path) "Память" else f.name

    private fun inLibrary(f: File): Boolean = inLib.getOrPut(f.path) { act.db.find(f.path) != null }

    // --------------------------------------------------------------- рисование

    override fun render(c: Canvas) {
        ink.rect(c, 0f, 0f, W, H, T.BG)
        header(c)
        crumb(c)
        panel(c)
        if (!isEmptyFolder()) pager(c)
        status.draw(c)
    }

    private fun header(c: Canvas) {
        ink.convex(c, 41f, 113.5f, 107f, 112f, ROUND)
        icons.draw(c, "back@12.3x26.6", 87.9f, 155.3f, 12.3f, 26.6f)
        ink.text(c, "Файлы", 176f, T.base(105f, T.TS_TITLE, 1.25f), T.TS_TITLE, 700, T.G1)
        ink.text(c, freeText(), 176f, T.base(181f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        ink.convex(c, 1255f, 113.5f, 107f, 112f, ROUND)
        icons.draw(c, "sort", 1290.6f, 153.2f, 36.8f, 30.6f)
    }

    /** «Свободно 6,9 из 16 ГБ» — как на B42: свободно из заявленного объёма накопителя. */
    private fun freeText(): String {
        val st = SettingsData.storage() ?: return "Свободно — ГБ"
        return "Свободно ${SettingsData.gb1(st.second)} из ${SettingsData.capacityGb(st.first)} ГБ"
    }

    private fun crumb(c: Canvas) {
        ink.inset(c, 44f, 249f, 1316f, 102f, 44f)
        ink.convex(c, 51f, 255f, 89f, 94f, R_UP)
        icons.draw(c, "up", 86f, 295f, 20f, 11.7f)

        crumbHits.clear()
        val segs = segments()
        val n = segs.size
        val sepW = ink.measure(SEP, T.TS_BODY, 600)
        val base = T.base(276.9f, T.TS_BODY, 1.25f)

        // Длинный путь: левые сегменты заменяем на «…»; в родительскую папку всегда ведёт «вверх».
        var from = 0
        while (from < n - 1 && widthFrom(segs, from, sepW) > CRUMB_MAX_W) from++

        var x = CRUMB_X
        if (from > 0) {
            ink.text(c, "…", x, base, T.TS_BODY, 600, T.G4)
            x += ink.measure("…", T.TS_BODY, 600) + sepW
        }
        for (i in from until n) {
            val last = i == n - 1
            val s = label(segs[i])
            ink.text(c, s, x, base, T.TS_BODY, if (last) 700 else 600, if (last) T.G1 else T.G4)
            crumbHits.add(Pair(segs[i], x))
            x += ink.measure(s, T.TS_BODY, if (last) 700 else 600)
            if (!last) {
                ink.text(c, SEP, x, base, T.TS_BODY, 600, T.G4)
                x += sepW
            }
        }
    }

    /** Ширина пути от сегмента [from] до последнего (с «…» впереди, если [from] > 0). */
    private fun widthFrom(segs: ArrayList<File>, from: Int, sepW: Float): Float {
        var w = 0f
        if (from > 0) w += ink.measure("…", T.TS_BODY, 600) + sepW
        for (i in from until segs.size) {
            val last = i == segs.size - 1
            w += ink.measure(label(segs[i]), T.TS_BODY, if (last) 700 else 600)
            if (!last) w += sepW
        }
        return w
    }

    private fun panel(c: Canvas) {
        ink.convex(c, 41f, 389f, 1321f, 1280f, T.R_TILE)
        if (isEmptyFolder()) {
            // Состояния пустой папки на B25 нет — карточка B44 (решение 9.6). Панель 389…1669,
            // карточка 425×410 по центру: верх 824, левый край 41 + (1321 − 425) / 2 = 489.5.
            empty.card(c, 489.5f, 824f, "files", 53f, 55.2f, "В папке нет книг",
                "Скопируйте сюда файлы по USB")
            return
        }
        val first = page * PER_PAGE
        val last = Math.min(rows.size, first + PER_PAGE)
        for (i in first until last) {
            val y = ROW_Y0 + (i - first) * ROW_STEP
            val r = rows[i]
            if (r.folder) folderRow(c, r, y) else fileRow(c, r, y)
            if (i < last - 1) ink.rect(c, TEXT_X, y + DIVIDER_DY, 1079f, 2f, T.G11)
        }
    }

    private fun folderRow(c: Canvas, r: Row, y: Float) {
        ink.convex(c, 88f, y, 115f, 120f, R_ROW)
        icons.draw(c, "files@42x37.6", 125f, y + 40.3f, 42f, 37.6f)
        ink.text(c, ink.ellipsize(r.name, TEXT_W, TS_BOOK, 700), TEXT_X,
            T.base(y + 9.5f, TS_BOOK, 1.25f), TS_BOOK, 700, T.G1)
        ink.text(c, folderSub(r.file), TEXT_X, T.base(y + 62.5f, T.TS_BODY_S, 1.25f),
            T.TS_BODY_S, 600, T.G4)
        icons.draw(c, "next@11x23.8", 1305f, y + 47f, 11f, 23.8f)
    }

    private fun fileRow(c: Canvas, r: Row, y: Float) {
        ink.inset(c, 88f, y, 110f, 110f, 26f, T.G12)
        // Формат 28/800 G3 по центру гнезда: рамка текста 91,y+37.7 105×34, lh 1.21.
        ink.textCenter(c, r.format.uppercase(Locale.ROOT), 91f, 105f,
            T.base(y + 37.7f, T.TS_CAPTION, 1.21f), T.TS_CAPTION, 800, T.G3)

        ink.text(c, ink.ellipsize(r.name, TEXT_W, TS_BOOK, 600), TEXT_X,
            T.base(y + 5f, TS_BOOK, 1.25f), TS_BOOK, 600, T.G1)
        ink.text(c, ink.ellipsize(subOf(r), TEXT_W, T.TS_BODY_S, 600), TEXT_X,
            T.base(y + 58f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)

        if (scanner.unsupported(r.format)) {
            // Вида для PDF/CBZ в макете нет (DESIGN_QUESTIONS): пометка вместо «+».
            val s = "не поддерживается"
            ink.text(c, s, RIGHT - ink.measure(s, T.TS_BODY_S, 700),
                T.base(y + 46f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 700, T.G4)
        } else if (inLibrary(r.file)) {
            val s = "В библиотеке"
            ink.text(c, s, STATUS_RIGHT - ink.measure(s, T.TS_BODY_S, 700),
                T.base(y + 32.9f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 700, T.G4)
            icons.draw(c, "check@24.5x18.4", 1291.5f, y + 45.2f, 24.5f, 18.4f)
        } else {
            ink.convex(c, 1225f, y + 6.5f, 93f, 98f, R_PLUS)
            icons.draw(c, "plus", 1260.3f, y + 41.8f, 23.3f, 25f)
        }
    }

    /** «1,1 МБ · 12 окт 2026» / «48 МБ · не в библиотеке» — как в макете. */
    private fun subOf(r: Row): String {
        val name = if (!scanner.unsupported(r.format) && inLibrary(r.file)) dateOf(r.file.lastModified())
        else "не в библиотеке"
        return sizeOf(r.file.length()) + " · " + name
    }

    private fun dateOf(mtime: Long): String = dateFmt.format(Date(mtime)).replace(".", "")

    /** «640 КБ», «1,1 МБ», «48 МБ» (без «,0»), «1,2 ГБ» — размеры из макета. */
    private fun sizeOf(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format(Locale("ru"), "%.1f ГБ", bytes / 1e9)
        bytes >= 1_000_000L -> {
            val s = String.format(Locale("ru"), "%.1f", bytes / 1e6)
            (if (s.endsWith(",0")) s.dropLast(2) else s) + " МБ"
        }
        else -> "${Math.max(1L, Math.round(bytes / 1e3))} КБ"
    }

    /** «4 файла» или «4 файла · 2 папки»: считаем только то, что видно в списке. */
    private fun folderSub(f: File): String {
        var files = 0
        var dirs = 0
        f.listFiles()?.forEach {
            if (it.name.startsWith(".")) return@forEach
            if (it.isDirectory) dirs++ else if (formatOf(it) != null) files++
        }
        return when {
            dirs == 0 -> Words.files(files)
            files == 0 -> Words.folders(dirs)
            else -> Words.files(files) + " · " + Words.folders(dirs)
        }
    }

    private fun pager(c: Canvas) {
        ink.convex(c, 41.1f, PAGER_Y, 121.6f, 127.1f, R_PAGE)
        ink.convexDark(c, 1240.4f, PAGER_Y, 121.6f, 127.1f, R_PAGE)
        icons.draw(c, "back@54.8x57.1", 75f, 1743.2f, 54.8f, 57.1f)
        icons.draw(c, "next@53x53", 1275.1f, 1743f, 53f, 53f)
        ink.textCenter(c, "${page + 1} / ${pages()}", 0f, W,
            T.base(1738.3f, T.TS_PAGE, 1.25f), T.TS_PAGE, 600, T.G1)
    }

    // --------------------------------------------------------------- ввод

    private fun inRect(x: Float, y: Float, rx: Float, ry: Float, rw: Float, rh: Float) =
        x >= rx && x <= rx + rw && y >= ry && y <= ry + rh

    override fun onUp(x: Float, y: Float) {
        if (inRect(x, y, 41f, 113.5f, 107f, 112f)) {
            act.pop()
            return
        }
        if (inRect(x, y, 1255f, 113.5f, 107f, 112f)) {
            // Сортировка без листа: «по имени ↔ по дате» (решение 9.6).
            act.prefs.filesSort = if (act.prefs.filesSort == SORT_DATE) SORT_NAME else SORT_DATE
            page = 0
            reload()
            return
        }
        if (inRect(x, y, 44f, 249f, 1316f, 102f)) {
            crumbUp(x, y)
            return
        }
        if (isEmptyFolder()) return

        if (inRect(x, y, 41.1f, PAGER_Y, 121.6f, 127.1f)) {
            if (page > 0) {
                page--
                reload()
            }
            return
        }
        if (inRect(x, y, 1240.4f, PAGER_Y, 121.6f, 127.1f)) {
            if (page < pages() - 1) {
                page++
                reload()
            }
            return
        }

        val first = page * PER_PAGE
        val last = Math.min(rows.size, first + PER_PAGE)
        for (i in first until last) {
            val ry = ROW_Y0 + (i - first) * ROW_STEP
            if (!inRect(x, y, 88f, ry, 1228f, ROW_HIT_H)) continue
            val r = rows[i]
            if (r.folder) {
                dir = r.file
                page = 0
                reload()
            } else if (scanner.unsupported(r.format)) {
                // Читалка PDF/CBZ не открывает — нажатие ничего не делает (решение 9.6).
            } else if (!inLibrary(r.file) && inRect(x, y, 1225f, ry + 6.5f, 93f, 98f)) {
                add(r.file)
            } else {
                // «В библиотеке» и имя файла ведут в «О книге» (B12) — если книга в базе есть.
                val e = act.db.find(r.file.path)
                if (e != null) {
                    act.bookId = e.id
                    act.go(EinkActivity.SCR_BOOK)
                }
            }
            return
        }
    }

    /** Крошки: «вверх» — родительская папка, сегмент под пальцем — та папка. */
    private fun crumbUp(x: Float, y: Float) {
        if (inRect(x, y, 51f, 255f, 89f, 94f)) {
            val p = parentOf(dir) ?: return
            dir = p
            page = 0
            reload()
            return
        }
        if (y < 249f || y > 351f) return
        val sepW = ink.measure(SEP, T.TS_BODY, 600)
        for (h in crumbHits) {
            val last = h.first.path == dir.path
            val w = ink.measure(label(h.first), T.TS_BODY, if (last) 700 else 600)
            // Половина разделителя справа тоже нажимается — промахов меньше.
            if (x >= h.second && x <= h.second + w + sepW / 2f) {
                if (last) return
                dir = h.first
                page = 0
                reload()
                return
            }
        }
    }

    /** «+»: тот же разбор, что у сканера, без полного прохода по памяти. */
    private fun add(f: File) {
        try {
            if (scanner.addFile(f)) {
                inLib[f.path] = true
                redraw()
            }
        } catch (t: Throwable) {
            Trace.step("files add FAIL ${f.path}: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
        inLib.clear()       // вернулись из B12 или файл добавился — проверяем заново
        if (dir.path.length < root.path.length || !dir.exists()) dir = root
        load()
    }

    override fun onDetachedFromWindow() {
        status.detach()
        super.onDetachedFromWindow()
    }
}
