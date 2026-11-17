package ru.ogrpfsense.dexpm8.reader

import android.os.SystemClock
import ru.ogrpfsense.dexpm8.ui.Trace

/**
 * Самопроверка сеанса R1 без экрана (временная, до B3 в R1.3). Запуск на книге:
 *
 * `adb shell am start -n ru.ogrpfsense.dexpm8/.ui.EinkActivity --es reader "путь к книге"`
 *
 * Прогоняет весь путь сеанса и пишет результат в трассу (тег DexpM8): открытие,
 * число страниц, отрисовка 0/1/последней, позиция (xpointer), поиск страницы по позиции,
 * смена кегля с сохранением места, закрытие и повторное открытие из кэша.
 * Ничего не рисует — проверяется только движок, UI будет в R1.3.
 */
object EngineSelfTest {

    /** Ширина/высота текста под окно 1404×1872 без колонтитулов (как будет в B3). */
    private const val W = 1404
    private const val H = 1600
    private const val BUF = W * H

    fun run(path: String) {
        Engine.worker.execute {
            try {
                step(path)
                Trace.step("selftest: ВСЁ ОК")
            } catch (t: Throwable) {
                Trace.step("selftest: ПАДЕНИЕ ${t.javaClass.simpleName}: ${t.message}")
                val sw = java.io.StringWriter()
                t.printStackTrace(java.io.PrintWriter(sw))
                Trace.step(sw.toString().take(1200))
            }
        }
    }

    private fun step(path: String) {
        val buf = ByteArray(BUF)
        var t0 = SystemClock.uptimeMillis()
        val rc = Engine.open(path, W, H)
        Trace.step("selftest: open rc=$rc ${Engine.codeText(rc)} ${SystemClock.uptimeMillis() - t0}ms")
        if (rc != Engine.OK) return

        val pages = Engine.pageCount()
        Trace.step("selftest: pages=$pages")

        // R2.4: настройки B31 на движке — масштаб, обрезка, сдвиг, контраст.
        if (Engine.djvu) viewStep()

        // Страницы: первая, вторая, последняя. Первая после открытия — самая дорогая.
        for (p in intArrayOf(0, 1, pages - 1)) {
            if (p < 0 || p >= pages) continue
            t0 = SystemClock.uptimeMillis()
            val r = Engine.page(p, W, H, buf)
            val ms = SystemClock.uptimeMillis() - t0
            Trace.step("selftest: page $p rc=$r ${Engine.codeText(r)} ${ms}ms dark=${dark(buf)}")
        }

        // Позиция: xpointer страницы 1 → обратно в страницу. Должна совпасть.
        val pos = Engine.pos(1)
        Trace.step("selftest: pos(1)=${pos ?: "null"}")
        if (pos != null) {
            val back = Engine.pageOf(pos)
            Trace.step("selftest: pageOf(pos)=$back ${if (back == 1) "ок" else "РАСХОЖДЕНИЕ"}")
        }

        // Оглавление (R1.2): число пунктов, первые три с уровнем и страницей, переход
        // по позиции второго пункта — проверка, что xpointer из TOC рабочий.
        run {
            t0 = SystemClock.uptimeMillis()
            val toc = Engine.toc()
            Trace.step("selftest: toc=${toc.size} ${SystemClock.uptimeMillis() - t0}ms")
            for (i in 0 until minOf(toc.size, 3)) {
                val it = toc[i]
                Trace.step("selftest: toc[$i] L${it.level} стр.${it.page} «${cut(it.title)}»")
            }
            val second = toc.getOrNull(1)
            if (second?.pos != null) {
                val jumped = Engine.gotoPos(second.pos)
                Trace.step("selftest: toc[1] gotoPos → стр.$jumped " +
                    if (jumped == second.page) "СТРАНИЦА СОВПАЛА" else "СТРАНИЦА ДРУГАЯ")
                if (jumped >= 0) {
                    Engine.page(jumped, W, H, buf)
                    Trace.step("selftest: toc[1] текст=${cut(Engine.pageText())}")
                }
            }
            // Уровни: в книге должен быть хотя бы один пункт второго уровня, если
            // оглавление вложенное; считаем для трассы, без приговора.
            val maxLevel = toc.maxOfOrNull { it.level } ?: 0
            Trace.step("selftest: toc уровней=$maxLevel")
        }

        // Смена кегля: место чтения держится позицией, число страниц меняется.
        // Критерий приёмки — «та же фраза на странице», а не «страница начинается
        // с фразы»: страница почти всегда начинается раньше абзаца, откуда снята
        // позиция. Поэтому спрашиваем движок, лежит ли позиция на текущей странице
        // (`Engine.posOnPage`), а текст печатаем для глаза.
        val mid = pages / 2
        Engine.page(mid, W, H, buf)
        val textBefore = Engine.pageText()
        Trace.step("selftest: до кегля стр.$mid текст=${cut(textBefore)}")
        val midPos = Engine.pos(mid)     // позиция при обычном кегле — проверяется после reopen
        val before = midPos
        if (before != null) {
            // Позиция должна быть на той же странице ещё до перевёрстки — иначе pos()
            // снимает страницу неточно, и проверять после кегля нечего.
            Trace.step("selftest: pos($mid) pageOf=${Engine.pageOf(before)} " +
                "posOnPage=${Engine.posOnPage(before)}")
        }
        if (before != null) {
            t0 = SystemClock.uptimeMillis()
            val fr = Engine.setFont(56, before)
            val after = Engine.pageOf(before)
            val onPage = Engine.posOnPage(before)
            Trace.step("selftest: setFont(56) rc=$fr ${Engine.codeText(fr)} " +
                "${SystemClock.uptimeMillis() - t0}ms pages=${Engine.pageCount()} " +
                "стр.позиции=$after posOnPage=$onPage " +
                if (onPage == 1) "ТА ЖЕ ФРАЗА" else "ФРАЗА УЕХАЛА")
            if (after >= 0) {
                Engine.page(after, W, H, buf)
                Trace.step("selftest: после кегля стр.$after текст=${cut(Engine.pageText())}")
            }
        }

        // R3.4: типографика B4 — гарнитура, кегль, интервал, поля, переносы.
        // Каждая настройка меняет разметку, поэтому проверяется по числу страниц и по
        // тому, осталась ли та же фраза (xpointer держится позицией в тексте).
        typeStep(midPos, buf)

        // R4.3: поиск по книге и выделение (B28, B50–B52). Нужен кадр с текстом, чтобы
        // `wordAt` попал в слово: берём середину страницы, заведомо занятую текстом.
        findStep(buf)

        // Повторное открытие — должно быть из кэша разметки (быстро).
        Engine.close()
        t0 = SystemClock.uptimeMillis()
        val rc2 = Engine.open(path, W, H)
        Trace.step("selftest: reopen rc=$rc2 ${SystemClock.uptimeMillis() - t0}ms pages=${Engine.pageCount()}")
        // Главное приёмки R1: место чтения из прошлой сессии восстанавливается.
        if (midPos != null) {
            // `pageOf` даёт страницу по позиции, но не двигает движок: важно проверить,
            // что после переоткрытия страница действительно встаёт на своё место.
            val p = Engine.pageOf(midPos)
            Trace.step("selftest: после reopen страница позиции=$p (была $mid) " +
                if (p == mid) "МЕСТО СОХРАНЕНО" else "МЕСТО УЕХАЛО")
            if (p >= 0) {
                val jumped = Engine.gotoPos(midPos)
                Engine.page(jumped.coerceAtLeast(0), W, H, buf)
                Trace.step("selftest: gotoPos → стр.$jumped " +
                    "posOnPage=${Engine.posOnPage(midPos)} " +
                    "текст=${cut(Engine.pageText())}")
            }
        }
        dump(1, "first")
        Engine.close()
    }
    /**
     * R4.3 на книге: поиск B28 и выделение B50–B52 через `Engine`.
     *
     * Что должно сойтись:
     *  - `search` находит известное слово, номера страниц растут, фрагмент не пустой;
     *  - `gotoPos(searchPos(...))` встаёт на ту же страницу, что назвал поиск;
     *  - `wordAt` в середину страницы даёт непустое слово, `selectionPos` — xpointer;
     *  - `rangeRects` по сохранённой позиции даёт 1–3 отрезка в пределах страницы;
     *  - после смены кегля выделение и результаты поиска сбрасываются (xpointer старого DOM).
     */
    private fun findStep(buf: ByteArray) {
        val pages = Engine.pageCount()
        if (pages <= 0) return
        val p = pages / 2
        Engine.page(p, W, H, buf)

        // Слово из текста страницы — чтобы искать заведомо существующее.
        val text = Engine.pageText().orEmpty()
        val words = text.split(' ', '\n', '\r', '\t', '—', ',', '.', '«', '»', '…')
            .filter { it.length in 5..24 && it.all { ch -> ch.isLetter() } }
        val query = words.getOrNull(1) ?: words.firstOrNull()
        Trace.step("selftest: find слово='${query ?: "—"}' из=${cut(text)}")
        if (query != null) {
            var t0 = SystemClock.uptimeMillis()
            val n = Engine.search(query)
            val ms = SystemClock.uptimeMillis() - t0
            Trace.step("selftest: find '$query' n=$n ${Engine.codeText(n)} ${ms}ms")
            if (n > 0) {
                for (i in 0 until Math.min(3, n)) {
                    val page = Engine.searchPage(i)
                    val frag = Engine.searchText(i)
                    Trace.step("selftest: find[$i] стр.$page ${cut(frag)}")
                    if (i == 0) {
                        val pos = Engine.searchPos(0)
                        val jumped = if (pos != null) Engine.gotoPos(pos) else -1
                        Trace.step("selftest: find[0] gotoPos → стр.$jumped " +
                            if (jumped == page) "СТРАНИЦА СОВПАЛА" else "СТРАНИЦА ДРУГАЯ")
                    }
                }
            }
        }

        // Выделение: точка в середине страницы — почти всегда в тексте.
        Engine.page(p, W, H, buf)
        var wx = W / 2
        var wy = H / 2
        var word = Engine.wordAt(wx, wy)
        // Середина могла попасть в пустую строку между абзацами — поднимаемся на строку.
        var tries = 0
        while (word == null && tries < 8) {
            wy -= 60
            word = Engine.wordAt(wx, wy)
            tries++
        }
        Trace.step("selftest: wordAt($wx,$wy) попыток=$tries слово='${word ?: "—"}'")
        if (word != null) {
            val sel = Engine.selectionText()
            val a = Engine.selectionPos(0)
            val b = Engine.selectionPos(1)
            Trace.step("selftest: text выделения='${cut(sel)}' pos=${a != null}…${b != null}")
            var rects = Engine.rangeRects(null, null)
            Trace.step("selftest: rects выделения=${rects?.size?.div(4) ?: -1}")
            // Маркер конца: слово вперёд — выделение должно вырасти.
            val ext = Engine.selectionExtend(1)
            val after = Engine.selectionText()
            rects = Engine.rangeRects(null, null)
            Trace.step("selftest: extend(+1)=$ext '${cut(after)}' " +
                "rects=${rects?.size?.div(4) ?: -1}")
            // Сохранённый диапазон — как B52 рисует уже записанную цитату.
            val sa = Engine.selectionPos(0)
            val sb = Engine.selectionPos(1)
            if (sa != null && sb != null) {
                val saved = Engine.rangeRects(sa, sb)
                Trace.step("selftest: rects saved=${saved?.size?.div(4) ?: -1}")
            }
            Engine.selectionClear()
            Trace.step("selftest: clear → выделение='${Engine.selectionText() ?: "нет"}' " +
                "поиск остался=${Engine.searchCount()}")
            // Смена кегля бросает выделение и найденное: xpointer старого DOM не годится.
            Engine.setFont(52, null)
            Trace.step("selftest: после setFont выделение='${Engine.selectionText() ?: "нет"}' " +
                "поиск=${Engine.searchCount()}")
        }
    }

    /**
     * R3.4 на книге: типографика B4 через `Engine.setTypography`. Для каждого варианта —
     * число страниц, время перевёрстки и «та же фраза» (xpointer места чтения держится
     * позицией в тексте, а не номером страницы). Кадр — в PNG, посмотреть глазами.
     *
     * Проверяется то, что у движка реально есть: гарнитура (свои Literata/Manrope против
     * системного), кегль, интервал, поля и переносы. Тумблера «Выравнивание по ширине»
     * здесь нет — см. вопрос в `docs/DESIGN_QUESTIONS.md`.
     */
    private fun typeStep(pos: String?, buf: ByteArray) {
        var t0: Long

        /** Один вариант настроек: `−1` — «не менять». */
        fun run(name: String, face: Int = -1, size: Int = -1, inter: Int = -1,
                margin: Int = -1, hyph: Int = -1) {
            t0 = SystemClock.uptimeMillis()
            val rc = Engine.setTypography(face, size, inter, margin, hyph, pos)
            val ms = SystemClock.uptimeMillis() - t0
            val onPage = if (pos != null) Engine.posOnPage(pos) else -1
            Trace.step("selftest: type $name rc=$rc ${Engine.codeText(rc)} ${ms}ms " +
                "pages=${Engine.pageCount()} posOnPage=$onPage " +
                if (onPage == 1) "ТА ЖЕ ФРАЗА" else if (onPage == 0) "ФРАЗА УЕХАЛА" else "—")
            val cur = Engine.typography()
            if (cur != null) {
                Trace.step("selftest: type $name → face=${cur[0]} size=${cur[1]} " +
                    "inter=${cur[2]} margin=${cur[3]} hyph=${cur[4]}")
            }
            if (rc >= 0) {
                Engine.page(Engine.pageCount() / 2, W, H, buf)
                dumpBuf(buf, "type-$name")
            }
        }

        val base = Engine.pageCount()
        Trace.step("selftest: type база pages=$base")
        run("гротеск", face = Engine.FACE_SANS)
        run("моно", face = Engine.FACE_MONO)
        run("антиква", face = Engine.FACE_SERIF)
        run("кегль-28", size = 28)
        run("кегль-60", size = 60)
        run("интервал-90", inter = 90)
        run("интервал-115", inter = 115)
        run("поля-60", margin = 60)
        run("поля-120", margin = 120)
        // Переносы: выключить и включить обратно — разметка должна измениться.
        run("без-переносов", hyph = 0)
        run("с-переносами", hyph = 1)
        // Вернуть макетные значения: кегль 44 (B3/B4), антиква, интервал 100, поля 0.
        run("сброс", face = Engine.FACE_SERIF, size = 44, inter = 100, margin = 0, hyph = 1)
        Trace.step("selftest: type итог pages=${Engine.pageCount()} (база $base)")
    }

    /**
     * R2.4 на книге: прогон настроек B31 через `Engine.pageView` — «по странице»,
     * обрезка, «по ширине», свой масштаб, сдвиг до предела, контраст. Для каждого варианта
     * в трассу идут время, доля чернил и `panOut`, а кадр — в PNG (посмотреть глазами).
     * Приёмка движка до экрана R2.5: экранных кнопок ещё нет.
     */
    private fun viewStep() {
        val page = if (Engine.pageCount() > 2) 2 else 0
        val buf = ByteArray(BUF)
        val pan = IntArray(4)
        var t0: Long

        fun run(name: String, view: DjvuView) {
            t0 = SystemClock.uptimeMillis()
            val rc = Engine.pageView(page, W, H, buf, view, pan)
            val ms = SystemClock.uptimeMillis() - t0
            Trace.step("selftest: view $name rc=$rc ${Engine.codeText(rc)} ${ms}ms " +
                "dark=${dark(buf)} pan=${pan[0]},${pan[1]} max=${pan[2]},${pan[3]}")
            if (rc == Engine.OK) dumpBuf(buf, "view-$name")
            else Trace.step("selftest: view $name КАДР НЕ СНЯТ")
        }

        run("page", DjvuView())                                            // «По странице»
        run("crop", DjvuView(cropL = 100, cropT = 100, cropR = 100, cropB = 100))
        run("width", DjvuView(zoom = DjvuView.ZOOM_WIDTH))                  // «По ширине»
        run("zoom140", DjvuView(zoom = 140))                                // «Свой · 140 %»
        run("contrast", DjvuView(contrast = true))

        // Сдвиг: сначала предел при 140 %, потом два положения внутри него.
        Engine.pageView(page, W, H, buf, DjvuView(zoom = 140), pan)
        val maxX = pan[2]
        val maxY = pan[3]
        Trace.step("selftest: view пределы при 140 % max=$maxX,$maxY")
        if (maxY > 0) {
            run("panY0", DjvuView(zoom = 140, panY = 0))
            run("panYmax", DjvuView(zoom = 140, panY = maxY))
            run("panYclamp", DjvuView(zoom = 140, panY = maxY + 500))       // должен стать maxY
        }
        if (maxX > 0) run("panXmax", DjvuView(zoom = 140, panX = maxX))
        // «По ширине» на узком окне: maxX = 0, сдвиг только вниз — так и должно быть.
        Engine.pageView(page, W, H, buf, DjvuView(zoom = DjvuView.ZOOM_WIDTH), pan)
        Trace.step("selftest: view по ширине max=${pan[2]},${pan[3]} " +
            if (pan[2] == 0) "X НЕ СДВИГАЕТСЯ (верно)" else "X СДВИГАЕТСЯ (странно)")
    }

    /**
     * Сохранить готовый буфер (уже 16 уровней, инвертирован под `ALPHA_8`) в PNG —
     * посмотреть глазами. Путь тот же, что у [dump].
     */
    private fun dumpBuf(buf: ByteArray, name: String) {
        try {
            val px = IntArray(W * H)
            for (i in px.indices) {
                val g = 255 - (buf[i].toInt() and 0xFF)
                px[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
            val bmp = android.graphics.Bitmap.createBitmap(W, H, android.graphics.Bitmap.Config.ARGB_8888)
            bmp.setPixels(px, 0, W, 0, 0, W, H)
            val dir = java.io.File("/sdcard/Android/data/ru.ogrpfsense.dexpm8/files")
            dir.mkdirs()
            val f = java.io.File(dir, "page-$name.png")
            java.io.FileOutputStream(f).use {
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bmp.recycle()
        } catch (t: Throwable) {
            Trace.step("selftest: dump $name FAIL ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Доля чёрных точек (альфа 255) в буфере — грубая проверка, что текст нарисован. */
    private fun dark(buf: ByteArray): String {
        var n = 0
        for (b in buf) if (b == 255.toByte()) n++
        return String.format(java.util.Locale.ROOT, "%.3f", n.toDouble() / buf.size)
    }

    private fun darkOf(page: Int): String {
        if (page < 0) return "—"
        val buf = ByteArray(BUF)
        val rc = Engine.page(page, W, H, buf)
        return if (rc == Engine.OK) dark(buf) else "rc=$rc"
    }

    /**
     * Сохранить страницу в PNG во внешний каталог приложения — посмотреть глазами.
     * `ALPHA_8` не посмотреть напрямую: альфа переводится в серый (0 = G15, 255 = G0),
     * как это сделает экран B3.
     */
    private fun dump(page: Int, name: String) {
        val buf = ByteArray(BUF)
        val rc = Engine.page(page, W, H, buf)
        if (rc != Engine.OK) {
            Trace.step("selftest: dump $name rc=$rc")
            return
        }
        try {
            val px = IntArray(W * H)
            for (i in px.indices) {
                val g = 255 - (buf[i].toInt() and 0xFF)
                px[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
            val bmp = android.graphics.Bitmap.createBitmap(W, H, android.graphics.Bitmap.Config.ARGB_8888)
            bmp.setPixels(px, 0, W, 0, 0, W, H)
            val dir = java.io.File("/sdcard/Android/data/ru.ogrpfsense.dexpm8/files")
            dir.mkdirs()
            val f = java.io.File(dir, "page-$name.png")
            java.io.FileOutputStream(f).use {
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bmp.recycle()
            Trace.step("selftest: dump $name → ${f.absolutePath} ${f.length()} б")
        } catch (t: Throwable) {
            Trace.step("selftest: dump $name FAIL ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Первые 60 символов одним пробелом — для строки трассы. */
    private fun cut(s: String?): String {
        if (s == null) return "null"
        return s.replace('\n', ' ').replace('\r', ' ').trim().take(60)
    }
}
