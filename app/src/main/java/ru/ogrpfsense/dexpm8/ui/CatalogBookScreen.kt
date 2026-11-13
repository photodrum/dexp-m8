package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import ru.ogrpfsense.dexpm8.core.Opds
import ru.ogrpfsense.dexpm8.core.Downloads

/**
 * B17 · Книга в каталоге (п. 10). Числа — design/v5/B17.json.
 *
 *   шапка      «Назад» 41.1,112.9 106.9×112.4; «Книга» 175.5,105 60/700;
 *              название каталога 175.5,181 32/600 G4
 *   обложка    углубление 43.9,248.6 431.4×603.3 (r 40.2); обложка 65.8,270.6 387.6×559.4 (r 21.9);
 *              корешок 65.8,270.6 18.3 G7; «Белая гвардия» Literata 44/600 в 117,321.7 (две строки);
 *              «М. Булгаков» 32/600 G4 в 117,447.7
 *   свойства   название Literata 66/500 в 519.2,253; автор 40/600 G5 в 519.2,340.8;
 *              метка 30/600 G5 и значение 40/700 в двух столбцах (519.2 / 939.7, шаг 106.1)
 *   формат     метка 30/800 G5 в 519.2,655.8; желоб 519.2,705.7 840.9×95.1;
 *              сегменты 413.2×80.4 (подпись 34)
 *   кнопка     41.1,904 1320.8×141.7 тёмная «Скачать FB2 · 0,9 МБ», «download» 1265.1,949
 *   описание   41.1,1094.1 1320.8×638.9: «ОПИСАНИЕ» 32/800 G5, текст Literata 40/400 1213.9,
 *              «ЖАНР» 32/800 G5, жанры 36/600, «Ещё книги автора» 38/800 G2 + «arrowR»
 *
 * Отступление от макета: размер файла (0,9 МБ) в OPDS не обязателен — если сервер его
 * не отдал, в подписи кнопки только формат («Скачать FB2»).
 */
class CatalogBookScreen(act: EinkActivity) : Screen(act) {

    private val status = StatusBar(act, this, ink, icons)
    private val downloads = act.downloads

    private var entry: Opds.Entry? = null
    private val lit = T.literata(ink.res, 600)
    private var formats = ArrayList<Opds.Link>()

    /** Вход каталога: без него защищённый сервер отдаёт 401 на скачивание. */
    private var auth: Opds.Auth? = null

    /** Номер изменения очереди загрузок: кнопка перерисовывается, когда он другой. */
    private var seenGen = downloads.generation()

    /** Опрос очереди: после «Скачать» кнопка должна вернуться в исходный вид, а не висеть. */
    private val tick = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            if (seenGen != downloads.generation()) {
                seenGen = downloads.generation()
                redraw()
            }
            if (downloads.running()) postDelayed(this, TICK_MS)
        }
    }
    private var sel = 0

    override fun render(c: Canvas) {
        ink.rect(c, 0f, 0f, W, H, T.BG)
        status.draw(c)
        val e = entry
        header(c, e)
        if (e == null) {
            empty(c)
            return
        }
        cover(c, e)
        props(c, e)
        formatBox(c, e)
        button(c)
        description(c, e)
    }

    private fun header(c: Canvas, e: Opds.Entry?) {
        ink.convex(c, 41.1f, 112.9f, 106.9f, 112.4f, R_BACK)
        icons.draw(c, "back", 71.3f, 143f, 47.5f, 49.5f)
        ink.text(c, "Книга", 175.5f, T.base(105f, T.TS_TITLE, 1.2f), T.TS_TITLE, 700, T.G1)
        val src = act.opdsSource.removePrefix("http://").removePrefix("https://").substringBefore('/')
        ink.text(c, ink.ellipsize(src, 900f, T.TS_BODY_S, 600), 175.5f,
            T.base(181f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        if (e == null) return
    }

    /** Обложка-заглушка 431.4×603.3 с названием и автором на ней — как в макете. */
    private fun cover(c: Canvas, e: Opds.Entry) {
        ink.inset(c, 43.9f, 248.6f, 431.4f, 603.3f, 40.2f)
        ink.flat(c, 65.8f, 270.6f, 387.6f, 559.4f, 21.9f, T.G14)
        ink.roundRect(c, 65.8f, 270.6f, 18.3f, 559.4f, 21.9f, T.G7)
        val title = ink.ellipsize2(e.title, 320f, 44f, 600, lit)
        var y = 321.7f
        for (line in title) {
            ink.text(c, line, 117f, T.base(y, 44f, 1.25f), 44f, 600, T.G1, lit)
            y += 55f
        }
        val a = shortAuthor(e.author)
        if (a.isNotEmpty()) {
            ink.text(c, ink.ellipsize(a, 300f, T.TS_BODY_S, 600), 117f,
                T.base(447.7f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        }
    }

    /** Название, автор и свойства: год, язык, серия, страниц (чего сервер не дал — «—»). */
    private fun props(c: Canvas, e: Opds.Entry) {
        ink.text(c, ink.ellipsize(e.title, 840f, 66f, 500, lit), 519.2f,
            T.base(253f, 66f, 1.2f), 66f, 500, T.G1, lit)
        ink.text(c, ink.ellipsize(e.author, 700f, 40f, 600), 519.2f,
            T.base(340.8f, 40f, 1.25f), 40f, 600, T.G5)
        val year = yearOf(e)
        prop(c, "Год", year, 519.2f, 425.4f)
        prop(c, "Язык", langName(e.lang), 939.7f, 425.4f)
        prop(c, "Серия", seriesOf(e), 519.2f, 531.5f)
        prop(c, "Страниц", "—", 939.7f, 531.5f)
    }

    private fun prop(c: Canvas, label: String, value: String, x: Float, top: Float) {
        ink.text(c, label, x, T.base(top, T.TS_META, 1.27f), T.TS_META, 600, T.G5)
        ink.text(c, ink.ellipsize(value, 360f, 40f, 700), x,
            T.base(top + 32.4f, 40f, 1.25f), 40f, 700, T.G1)
    }

    /** Желоб форматов 840.9×95.1 и сегменты 413.2×80.4; подпись — формат и размер. */
    private fun formatBox(c: Canvas, e: Opds.Entry) {
        ink.text(c, "ФОРМАТ", 519.2f, T.base(655.8f, T.TS_META, 1.27f), T.TS_META, 800, T.G5)
        ink.inset(c, 519.2f, 705.7f, 840.9f, 95.1f, 47.5f)
        val n = formats.size.coerceAtLeast(1)
        val w = (840.9f - 8f) / n
        for (i in 0 until n) {
            val x = 523.8f + i * w
            val on = i == sel
            if (on) ink.convex(c, x, 710.2f, w - 4f, 90.5f, 40.2f)
            val label = if (formats.isEmpty()) "—" else formatLabel(formats[i])
            ink.textCenter(c, label, x, w - 4f, T.base(731.3f, T.TS_BODY, 1.27f), T.TS_BODY,
                if (on) 700 else 600, if (on) T.G1 else T.G4)
        }
    }

    /** Главная кнопка: «Скачать FB2 · 0,9 МБ» (размер — если сервер его отдал). */
    private fun button(c: Canvas) {
        val l = formats.getOrNull(sel)
        val fmt = if (l == null) "книгу" else (mime(l.type) ?: "файл")
        // Три разных исхода: файл уже в библиотеке, качается/в очереди, можно качать.
        val st = if (l == null) -1 else downloads.items()
            .firstOrNull { it.url == l.href }?.state ?: -1
        ink.convexDark(c, 41.1f, 904f, 1320.8f, 141.7f, T.R_BUTTON)
        val label = when (st) {
            Downloads.S_DONE -> "В библиотеке"
            Downloads.S_RUN, Downloads.S_WAIT -> "Уже скачивается"
            Downloads.S_FAIL -> "Повторить $fmt"
            else -> "Скачать $fmt"
        }
        ink.text(c, label, 95.1f, T.base(944.9f, T.TS_ACTION, 1.25f), T.TS_ACTION, 700, T.G15)
        icons.draw(c, "download@43.9x43.9", 1265.1f, 949f, 43.9f, 43.9f)
    }

    /** Описание и жанры: «ОПИСАНИЕ» 32/800, текст Literata 40/400, «ЖАНР» 32/800. */
    private fun description(c: Canvas, e: Opds.Entry) {
        ink.convex(c, 41.1f, 1094.1f, 1320.8f, 638.9f, T.R_TILE)
        ink.text(c, "ОПИСАНИЕ", 95.1f, T.base(1137f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 800, T.G5)
        val text = if (e.summary.isEmpty()) "Описания в каталоге нет." else e.summary
        // Пять строк по ~56: от 1191 до «ЖАНР» на 1483 — больше в карточку не влезает.
        ink.paragraph(c, text, 95.1f, 1191f, 1213.9f, 40f, 400, T.G1, 8f, lit, DESC_LINES)
        val genres = e.categories.map { genreName(it) }.filter { it.isNotEmpty() }.joinToString(" · ")
        if (genres.isNotEmpty()) {
            ink.text(c, "ЖАНР", 95.1f, T.base(1483f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 800, T.G5)
            ink.text(c, ink.ellipsize(genres, 1213.9f, T.TS_LABEL, 600), 95.1f,
                T.base(1527f, T.TS_LABEL, 1.25f), T.TS_LABEL, 600, T.G1)
        }
    }

    /** Пусто: каталог не передал запись (возврат после перезапуска) — карточка B44. */
    private fun empty(c: Canvas) {
        EmptyState(ink, icons).card(c, (W - EmptyState.CARD_W) / 2f, 700f, "library", 113.3f, 113.4f,
            "Книга не выбрана", "Откройте каталог и выберите книгу")
    }

    // ------------------------------------------------------------------ данные

    private fun formatLabel(l: Opds.Link): String = mime(l.type) ?: "файл"

    private fun mime(type: String): String? {
        val t = type.lowercase()
        return when {
            t.contains("fb2") || t.contains("fictionbook") -> "FB2"
            t.contains("epub") -> "EPUB"
            t.contains("mobipocket") -> "MOBI"
            t.contains("text/plain") -> "TXT"
            t.contains("rtf") -> "RTF"
            t.contains("wordprocessingml") || t == "application/msword" -> "DOC"
            t.contains("djvu") -> "DJVU"
            else -> null
        }
    }

    /** «Михаил Булгаков» → «М. Булгаков» — на обложке макета автор сокращён. */
    private fun shortAuthor(a: String): String {
        val parts = a.trim().split(' ').filter { it.isNotEmpty() }
        if (parts.size < 2) return a
        return parts[0].take(1) + ". " + parts.drop(1).joinToString(" ")
    }

    private fun langName(code: String): String = when (code.lowercase()) {
        "ru" -> "русский"
        "en" -> "английский"
        "de" -> "немецкий"
        "fr" -> "французский"
        "uk" -> "украинский"
        "" -> "—"
        else -> code
    }

    /** Год из `updated` фида (OPDS другого поля для года обычно не даёт). */
    private fun yearOf(e: Opds.Entry): String {
        val u = e.updated
        val i = u.indexOf('-')
        return if (i == 4 && u.take(4).toIntOrNull() != null) u.take(4) else "—"
    }

    /**
     * Код жанра BookLore → подпись. Сервер отдаёт латинские коды (`foreign_prose`,
     * `literature_20`), а читателю нужен жанр словами; незнакомый код оставляем как есть
     * без подчёркиваний — это всё равно понятнее пустоты.
     */
    private fun genreName(code: String): String = when (code) {
        "foreign_prose" -> "Зарубежная проза"
        "prose_classic" -> "Классика"
        "prose_rus_classic" -> "Русская классика"
        "foreign_love" -> "Зарубежный любовный роман"
        "love_history" -> "Исторический любовный роман"
        "love_short" -> "Любовный роман, новеллы"
        "love_contemporary" -> "Современный любовный роман"
        "sf" -> "Фантастика"
        "sf_social" -> "Социальная фантастика"
        "sf_space" -> "Космическая фантастика"
        "sf_humor" -> "Юмористическая фантастика"
        "sf_heroic" -> "Героическая фантастика"
        "sf_fantasy" -> "Фэнтези"
        "sf_fantasy_city" -> "Городское фэнтези"
        "child_sf" -> "Детская фантастика"
        "romance_sf" -> "Любовно-фантастический роман"
        "det_political" -> "Политический детектив"
        "popadanec" -> "Попаданцы"
        else -> when {
            code.startsWith("literature_") -> "Литература " + code.removePrefix("literature_") + " века"
            code.startsWith("det_") -> "Детектив"
            code.startsWith("humor_") -> "Юмор"
            else -> code.replace('_', ' ')
        }
    }

    private fun seriesOf(e: Opds.Entry): String = e.categories.firstOrNull { it.contains("серия", true) } ?: "—"

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
        auth = act.db.catalog(act.catalogId)?.auth()
        entry = act.opdsEntry
        formats = entry?.acquisitions() ?: ArrayList()
        sel = 0
        redraw()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        status.detach()
        super.onDetachedFromWindow()
    }

    override fun onUp(x: Float, y: Float) {
        if (inRect(x, y, 41.1f, 112.9f, 106.9f, 112.4f)) {
            act.pop()
            return
        }
        // Сегменты форматов: 523.8 + i·w, 710.2…800.7.
        val n = formats.size
        if (n > 1) {
            val w = (840.9f - 8f) / n
            for (i in 0 until n) {
                if (inRect(x, y, 523.8f + i * w, 710.2f, w - 4f, 90.5f)) {
                    if (sel != i) {
                        sel = i
                        redraw()
                    }
                    return
                }
            }
        }
        if (inRect(x, y, 41.1f, 904f, 1320.8f, 141.7f)) {
            download()
            return
        }
        // «Ещё книги автора» — ищем по адресу раздела, который отдал каталог.
        if (inRect(x, y, 95.1f, 1608f, 400f, 60f)) return
    }

    /** Поставить выбранный формат в очередь загрузок (B18) и обновить кнопку. */
    private fun download() {
        val e = entry ?: return
        val l = formats.getOrNull(sel) ?: return
        downloads.add(l.href, e.title, formatLabel(l), auth)
        removeCallbacks(tick)
        postDelayed(tick, TICK_MS)
        redraw()
    }

    private fun inRect(x: Float, y: Float, rx: Float, ry: Float, rw: Float, rh: Float) =
        x >= rx && x <= rx + rw && y >= ry && y <= ry + rh

    private companion object {
        const val R_BACK = 51.2f
        const val DESC_LINES = 4
        /** Опрос очереди загрузок — раз в секунду, как на B16 и B18. */
        const val TICK_MS = 1000L
    }
}