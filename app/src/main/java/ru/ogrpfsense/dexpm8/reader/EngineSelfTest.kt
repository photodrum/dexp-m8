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
