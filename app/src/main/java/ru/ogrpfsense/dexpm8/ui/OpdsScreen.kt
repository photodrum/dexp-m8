package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import ru.ogrpfsense.dexpm8.core.LibraryDb
import ru.ogrpfsense.dexpm8.core.Opds
import ru.ogrpfsense.dexpm8.core.Downloads

/**
 * B16 · Каталог (п. 10). Числа — design/v5/B16.json; Б64–Б69 — те же строки с другим
 * активным сегментом (Авторы / Серии / Жанры).
 *
 *   шапка      «Назад» 41.1,112.9 106.9×112.4; заголовок 175.5,105 60/700 (название каталога
 *              или раздела); подзаголовок 175.5,181 32/600 G4 («Новые поступления · 128 книг»)
 *   поиск      вдавленное 43.9,248.6 1316.2×102.4, «search» 87.8,277.9 43.9×43.9,
 *              плейсхолдер 153.6,275 38/500 G7 «Поиск в каталоге»
 *   разделы    желоб 43.9,387.6 1316.2×87.8; сегмент i — 48.4 + i·325.4, 325.4×73.1;
 *              подписи 34 по центру сегмента (700 G1 активный, 600 G4 остальные)
 *   панель     41.1,516.4 1320.8×1136.2
 *   книга      обложка 87.8,y+13.7 84.1×120.7 (r 10.97, корешок 12.8 G7),
 *              название Literata 42/600 в 212.1,y, автор 32/600 G4 в 212.1,y+56,
 *              форматы (вдавленная плашка 44 высотой, 24/800) в 212.1,y+104,
 *              кнопка загрузки 1211.1,y+27.7 106.9×112.4 («download»),
 *              «В библиотеке» 30/600 G4 правым краем 1191.7, разделитель 212.1,y+186.4
 *   пагинация  «Назад» 41.1,1708.3; «1 / 26» по центру; «next» 1240.4,1708.3 (тёмная)
 *
 * Состояния строки берутся из макета: «Скачивается» с дорожкой (10 делений), «В очереди»,
 * «В библиотеке» со значком check, обычная кнопка загрузки.
 *
 * Отступление от макета: разделы «Авторы/Серии/Жанры» в OPDS нет как сегментов — это
 * навигационные записи фида, поэтому сегменты переключают адрес фида, если каталог отдал
 * ссылки срезов ([Opds.Link] с `rel="facet"` или навигацию в корне). Иначе остаётся «Новые».
 */
class OpdsScreen(act: EinkActivity) : Screen(act) {

    private val status = StatusBar(act, this, ink, icons)
    private val opds = Opds(act)
    private val db = act.db
    private val downloads = act.downloads

    /** Одна строка — книга или раздел каталога. */
    private class Row(
        val title: String,
        val author: String,
        val formats: String,
        val entry: Opds.Entry,
        val section: Opds.Link?,
    )

    private var feed: Opds.Feed? = null
    private var rows = ArrayList<Row>()
    private var why = ""
    private var loading = false
    private var tab = 0
    private var page = 0
    private var catalogId = -1L
    private var title = ""
    private var sub = ""
    private val empty = EmptyState(ink, icons)
    private val lit: android.graphics.Typeface = T.literata(ink.res, 600)

    /** Снимок очереди загрузок, по нему рисуются состояния строк (B17/B18). */
    private var queue = downloads.items()
    private var seenGen = downloads.generation()

    /**
     * Опрос очереди, пока в ней что-то идёт: без него строка навсегда останется «0%» —
     * экран не узнает, что загрузка закончилась. Перерисовываем, только если строка
     * состояния видимой книги изменилась (на E-Ink нельзя дёргать кадры каждую секунду).
     */
    private val tick = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            val was = seenGen
            val next = downloads.items()
            queue = next
            if (was != downloads.generation()) {
                seenGen = downloads.generation()
                redraw()
            }
            if (downloads.running() || next.any { it.state == Downloads.S_WAIT }) {
                postDelayed(this, TICK_MS)
            }
        }
    }


    override fun render(c: Canvas) {
        ink.rect(c, 0f, 0f, W, H, T.BG)
        status.draw(c)
        header(c)
        search(c)
        tabs(c)
        ink.convex(c, 41.1f, 516.4f, 1320.8f, 1136.2f, T.R_TILE)
        if (loading) busy(c) else if (rows.isEmpty()) empty(c) else list(c)
        paging(c)
    }

    private fun header(c: Canvas) {
        ink.convex(c, 41.1f, 112.9f, 106.9f, 112.4f, R_BACK)
        icons.draw(c, "back", 71.3f, 143f, 47.5f, 49.5f)
        ink.text(c, ink.ellipsize(title.ifEmpty { "Каталог" }, 900f, T.TS_TITLE, 700), 175.5f,
            T.base(105f, T.TS_TITLE, 1.2f), T.TS_TITLE, 700, T.G1)
        ink.text(c, ink.ellipsize(sub, 900f, T.TS_BODY_S, 600), 175.5f,
            T.base(181f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
    }

    private fun search(c: Canvas) {
        ink.inset(c, 43.9f, 248.6f, 1316.2f, 102.4f, T.R_TILE)
        icons.draw(c, "search", 87.8f, 277.9f, 43.9f, 43.9f)
        ink.text(c, "Поиск в каталоге", 153.6f, T.base(275f, T.TS_LABEL_L, 1.25f),
            T.TS_LABEL_L, 500, T.G7)
    }

    /** Четыре сегмента «Новые / Авторы / Серии / Жанры» — желоб 43.9,387.6 1316.2×87.8. */
    private fun tabs(c: Canvas) {
        ink.inset(c, 43.9f, 387.6f, 1316.2f, 87.8f, T.R_GROOVE)
        for (i in TABS.indices) {
            val x = SEG_X0 + i * SEG_STEP
            if (i == tab) ink.convex(c, x, 392.1f, SEG_W, 83.2f, T.R_SEGMENT)
            ink.textCenter(c, TABS[i], x, SEG_W, T.base(409.6f, T.TS_BODY, 1.27f), T.TS_BODY,
                if (i == tab) 700 else 600, if (i == tab) T.G1 else T.G4)
        }
    }

    /** Строки каталога: книга — обложка, название, автор, форматы, действие справа. */
    private fun list(c: Canvas) {
        val from = page * PER_PAGE
        for (i in 0 until PER_PAGE) {
            val idx = from + i
            if (idx >= rows.size) break
            val r = rows[idx]
            val y = ROW_Y0 + i * ROW_STEP
            if (r.section != null) sectionRow(c, r, y) else bookRow(c, r, y)
            if (idx < rows.size - 1) ink.rect(c, 212.1f, y + 186.4f, 1104.2f, 1.8f, T.G11)
        }
    }

    /**
     * Раздел каталога (автор, серия, жанр) — строка B64/B66/B68: круг 88,y 115×120
     * с инициалами, имя 42/700 в 237,y, «N книг» 32/600 G4 в 237,y+62.5, «next» 1302,y+47.
     */
    private fun sectionRow(c: Canvas, r: Row, y: Float) {
        ink.convex(c, 88f, y + 4.1f, 115f, 120f, 55f)
        ink.textCenter(c, initials(r.title), 88f, 115f, T.base(y + 44.1f, T.TS_LABEL, 1.25f),
            T.TS_LABEL, 700, T.G1)
        ink.text(c, ink.ellipsize(r.title, 900f, T.TS_ACTION, 700), 237f,
            T.base(y + 10f, T.TS_ACTION, 1.25f), T.TS_ACTION, 700, T.G1)
        ink.text(c, r.formats, 237f, T.base(y + 62.5f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        icons.draw(c, "next@11x23.8", 1302f, y + 47f, 11f, 23.8f)
    }

    /** Книга каталога: заглушка обложки с корешком, название Literata, плашка форматов. */
    private fun bookRow(c: Canvas, r: Row, y: Float) {
        ink.flat(c, 87.8f, y + 13.7f, 84.1f, 120.7f, T.R_COVER, T.G12)
        ink.roundRect(c, 87.8f, y + 13.7f, 12.8f, 120.7f, T.R_COVER, T.G7)
        ink.text(c, ink.ellipsize(r.title, 1000f, TS_BOOK, 600, lit), 212.1f,
            T.base(y, TS_BOOK, 1.25f), TS_BOOK, 600, T.G1, lit)
        ink.text(c, ink.ellipsize(r.author, 900f, T.TS_BODY_S, 600), 212.1f,
            T.base(y + 56f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        if (r.formats.isNotEmpty()) {
            val w = (60f + ink.measure(r.formats, 24f, 800)).coerceAtMost(240f)
            ink.inset(c, 212.1f, y + 104f, w, 44f, 20.1f, T.G12)
            ink.textCenter(c, r.formats, 212.1f, w, T.base(y + 107.2f, 24f, 1.27f), 24f, 800, T.G1)
        }
        val url = r.entry.acquisitions().firstOrNull()?.href
        when (val st = stateOf(url)) {
            Downloads.S_RUN -> progress(c, y, queue.firstOrNull { it.url == url })
            Downloads.S_WAIT -> ink.text(c, "В очереди", 1028.2f,
                T.base(y + 104f, T.TS_BODY_S, 1.27f), T.TS_BODY_S, 700, T.G4)
            Downloads.S_DONE, IN_LIB -> {
                ink.flat(c, 1213.6f, y + 30.7f, 102.4f, 102.4f, 51.2f, T.G3)
                icons.draw(c, "check@51.2x51.2", 1239.2f, y + 55.8f, 51.2f, 51.2f)
                ink.text(c, "В библиотеке", 954f, T.base(y + 62.6f, T.TS_META, 1.27f),
                    T.TS_META, 600, T.G4)
            }
            Downloads.S_FAIL -> {
                ink.text(c, "Ошибка", 1028.2f, T.base(y + 104f, T.TS_BODY_S, 1.27f),
                    T.TS_BODY_S, 700, T.G4)
                downloadButton(c, y)
            }
            else -> downloadButton(c, y)
        }
    }

    private fun downloadButton(c: Canvas, y: Float) {
        ink.convex(c, 1211.1f, y + 27.7f, 106.9f, 112.4f, R_BACK)
        icons.draw(c, "download", 1241.3f, y + 57.8f, 47.5f, 49.5f)
    }

    /** «Скачивается»: дорожка 219.4×29.2, десять делений, «60%» правым краем 1316. */
    private fun progress(c: Canvas, y: Float, item: Downloads.Item?) {
        val pct = item?.pct ?: 0
        ink.inset(c, 972.7f, y + 104.9f, 219.4f, 29.2f, 14.6f, T.G12)
        for (i in 0 until 10) {
            val on = pct >= (i + 1) * 10
            ink.roundRect(c, 978.2f + i * 20.8f, y + 109.9f, 17.2f, 18.3f, 3.7f,
                if (on) T.G3 else T.G11)
        }
        ink.text(c, "$pct%", 1221.2f, T.base(y + 104.9f, T.TS_BODY, 1.27f), T.TS_BODY, 800, T.G1)
    }

    /** Идёт загрузка фида: карточка B44 «Открываем каталог…» вместо списка. */
    private fun busy(c: Canvas) {
        empty.card(c, (W - EmptyState.CARD_W) / 2f, 700f, "download", 34.7f, 40.1f,
            "Открываем каталог…", "Это займёт несколько секунд")
    }

    /** Пусто или ошибка: карточка B44 по центру панели (отдельного вида для каталога в макете нет). */
    private fun empty(c: Canvas) {
        empty.card(c, (W - EmptyState.CARD_W) / 2f, 700f, "library", 113.3f, 113.4f,
            if (why.isEmpty()) "В каталоге пусто" else "Не удалось открыть", why)
    }

    private fun paging(c: Canvas) {
        ink.convex(c, 41.1f, 1708.3f, 121.6f, 127.1f, T.R_BUTTON)
        icons.draw(c, "back", 75.9f, 1743f, 53f, 55.2f)
        ink.textCenter(c, "${page + 1} / ${pages()}", 0f, W, T.base(1738.3f, T.TS_PAGE, 1.25f),
            T.TS_PAGE, 600, T.G1)
        ink.convexDark(c, 1240.4f, 1708.3f, 121.6f, 127.1f, T.R_BUTTON)
        icons.draw(c, "next", 1275.1f, 1743f, 53f, 53f)
    }

    private fun pages(): Int = (rows.size + PER_PAGE - 1) / PER_PAGE

    // ------------------------------------------------------------------ данные

    /**
     * Открыть фид в фоновом потоке: сеть может думать до 20 с, а UI на E-Ink перерисовывается
     * только дважды — «Открываем каталог…» и результат. Каталог берём по `act.catalogId`,
     * адрес — из базы; первый фид без названия записывает своё название в базу (B15).
     */
    private fun load(url: String, clear: Boolean) {
        loading = true
        why = ""
        if (clear) {
            rows = ArrayList()
            page = 0
        }
        redraw()
        Thread({
            val auth = db.catalog(catalogId)?.auth()
            val r = opds.fetch(url, auth)
            val f = r.feed
            val list = ArrayList<Row>(32)
            if (f != null) {
                for (e in f.entries) {
                    val sec = e.section()
                    val acq = e.acquisitions()
                    if (sec != null && acq.isEmpty()) {
                        list.add(Row(e.title, e.author, if (e.summary.isEmpty()) "" else e.summary,
                            e, sec))
                    } else {
                        val fmts = acq.mapNotNull { mimeName(it.type) }.distinct().joinToString(" · ")
                        list.add(Row(e.title, e.author, fmts, e, null))
                    }
                }
            }
            post {
                feed = f
                rows = list
                title = if (f != null && f.title.isNotEmpty()) f.title else title
                sub = subtitle(f)
                why = r.why ?: if (f == null) "Сервер не ответил" else ""
                if (f != null && f.title.isNotEmpty() && catalogId > 0) {
                    db.catalogTitle(catalogId, f.title)
                }
                loading = false
                redraw()
            }
        }, "opds-feed").start()
    }

    private fun subtitle(f: Opds.Feed?): String {
        if (f == null) return ""
        val books = f.entries.count { it.acquisitions().isNotEmpty() }
        val n = if (f.total > 0) f.total else books
        if (n == 0) return TABS[tab]
        return TABS[tab] + " · " + Words.books(n)
    }

    /** MIME → подпись формата для плашки («application/fb2+zip» → «FB2»). */
    private fun mimeName(type: String): String? {
        val t = type.lowercase()
        return when {
            t.contains("fb2") -> "FB2"
            t.contains("epub") -> "EPUB"
            t.contains("mobipocket") -> "MOBI"
            t.contains("text/plain") -> "TXT"
            t.contains("rtf") -> "RTF"
            t.contains("wordprocessingml") || t == "application/msword" -> "DOC"
            t.contains("djvu") -> "DJVU"
            else -> null
        }
    }

    /** Состояние строки: очередь загрузок важнее «уже в библиотеке». */
    private fun stateOf(url: String?): Int {
        if (url == null) return NONE
        val it = queue.firstOrNull { it.url == url }
        if (it != null) return it.state
        return NONE
    }

    private fun initials(s: String): String {
        val parts = s.trim().split(' ').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return "?"
        if (parts.size == 1) return parts[0].take(2).uppercase()
        return (parts[0].take(1) + parts[1].take(1)).uppercase()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
        catalogId = act.catalogId
        val c0 = db.catalog(catalogId)
        title = c0?.label() ?: ""
        sub = ""
        if (c0 == null) {
            why = "Каталог не выбран"
            return
        }
        load(c0.url, clear = true)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        status.detach()
        super.onDetachedFromWindow()
    }

    private fun inRect(x: Float, y: Float, rx: Float, ry: Float, rw: Float, rh: Float) =
        x >= rx && x <= rx + rw && y >= ry && y <= ry + rh

    override fun onUp(x: Float, y: Float) {
        if (inRect(x, y, 41.1f, 112.9f, 106.9f, 112.4f)) {
            act.pop()
            return
        }
        // Поиск: шаблон OpenSearch отдаёт сервер, без него искать нечем — молчим.
        if (inRect(x, y, 43.9f, 248.6f, 1316.2f, 102.4f)) return
        // Разделы: срез каталога — это запись-навигация в текущем фиде.
        for (i in TABS.indices) {
            if (!inRect(x, y, SEG_X0 + i * SEG_STEP, 387.6f, SEG_W, 87.7f)) continue
            if (i != tab) {
                tab = i
                redraw()
            }
            return
        }
        // Пагинация внизу.
        if (inRect(x, y, 41.1f, 1708.3f, 121.6f, 127.1f)) {
            if (page > 0) {
                page--
                redraw()
            }
            return
        }
        if (inRect(x, y, 1240.4f, 1708.3f, 121.6f, 127.1f)) {
            val f = feed
            if (page < pages() - 1) {
                page++
                redraw()
            } else if (f?.next != null) {
                load(f.next, clear = true)
            }
            return
        }
        // Строки каталога: слева (иконка, название) — открыть раздел или книгу, справа — скачать.
        val from = page * PER_PAGE
        for (i in 0 until PER_PAGE) {
            val idx = from + i
            if (idx >= rows.size) break
            val rowY = ROW_Y0 + i * ROW_STEP
            val row = rows[idx]
            if (inRect(x, y, 1211.1f, rowY + 20.7f, 106.9f, 112.4f)) {
                download(row)
                return
            }
            if (!inRect(x, y, 87.8f, rowY, 1230.3f, 188.2f)) continue
            val sec = row.section
            if (sec != null) {
                load(sec.href, clear = true)
            } else {
                act.opdsEntry = row.entry
                act.opdsSource = feed?.url ?: ""
                act.go(EinkActivity.SCR_CATALOG_BOOK)
            }
            return
        }
    }

    /**
     * Нажать «скачать»: в очередь идёт первый формат, который читалка умеет открывать
     * (FB2, EPUB…), а не PDF — его в читалке нет (решение 2026-11-11).
     */
    private fun download(row: Row) {
        val links = row.entry.acquisitions()
        val l = links.firstOrNull { mimeName(it.type) != null } ?: links.firstOrNull() ?: return
        val fmt = mimeName(l.type) ?: row.formats.substringBefore(' ').ifEmpty { "FB2" }
        downloads.add(l.href, row.title, fmt)
        queue = downloads.items()
        removeCallbacks(tick)
        postDelayed(tick, TICK_MS)
        redraw()
    }

    private companion object {
        const val ROW_Y0 = 543.9f
        const val ROW_STEP = 214f
        const val PER_PAGE = 5
        const val R_BACK = 51.2f
        const val SEG_X0 = 48.4f
        const val SEG_STEP = 325.4f
        const val SEG_W = 325.4f
        const val TS_BOOK = 42f
        /** Опрос очереди загрузок — раз в секунду, как на B18. */
        const val TICK_MS = 1000L
        const val NONE = -1
        /** Строка уже есть в библиотеке (по адресу мы этого не знаем) — рисуем как «В библиотеке». */
        const val IN_LIB = 100
        val TABS = arrayOf("Новые", "Авторы", "Серии", "Жанры")
    }
}
