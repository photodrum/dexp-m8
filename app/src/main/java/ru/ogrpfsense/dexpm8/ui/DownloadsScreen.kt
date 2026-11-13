package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import ru.ogrpfsense.dexpm8.core.Downloads

/**
 * B18 · Загрузки (п. 10). Числа — design/v5/B18.json.
 *
 *   шапка      «Назад» 41.1,112.9 106.9×112.4; «Загрузки» 175.5,105 60/700;
 *              подзаголовок 175.5,181 32/600 G4 «1 скачивается · 1 в очереди»
 *   панель     41.1,245.9 1320.8×1136.2
 *   строка     обложка 87.8,y+12 91.4×131.6 (r 10.97, корешок 12.8 G7),
 *              название Literata 42/600 в 219.4,y+7.1, «Ильф и Петров · FB2» 32/600 G4 в 219.4,y+61.4,
 *              дорожка 219.4,y+105.1 804.4×32.9 с десятью делениями 75×21.9,
 *              «620 из 980 КБ» 32/700 правым краем 1316 в y+125.4,
 *              круглая клавиша «close» 1225.8,y 92.3×97.8, разделитель 219.4,y+196.2
 *   «В очереди» 32/700 G4 в 219.4,y+102; ошибка — «info» + «Сервер не ответил» в 219.4,y+103.4,
 *              выпуклая «Повторить» 1021,y+26.7 297.1×105.1
 *
 * Отступление от макета: строк в макете четыре без пагинации; у нас панель листается по
 * [PER_PAGE] строк, а подзаголовок считает идущие и ожидающие загрузки.
 */
class DownloadsScreen(act: EinkActivity) : Screen(act) {

    private val status = StatusBar(act, this, ink, icons)
    private val downloads = act.downloads
    private val lit: android.graphics.Typeface = T.literata(ink.res, 600)
    private val empty = EmptyState(ink, icons)

    private var page = 0
    private var items = ArrayList<Downloads.Item>()
    private var seenGen = downloads.generation()

    /** Обновлять экран, пока что-то качается: раз в [TICK_MS], только при смене кадра. */
    private val tick = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            refresh()
            if (downloads.running() || items.any { it.state == Downloads.S_WAIT }) {
                postDelayed(this, TICK_MS)
            }
        }
    }

    override fun render(c: Canvas) {
        ink.rect(c, 0f, 0f, W, H, T.BG)
        status.draw(c)
        header(c)
        ink.convex(c, 41.1f, 245.9f, 1320.8f, 1136.2f, T.R_TILE)
        if (items.isEmpty()) {
            empty.card(c, (W - EmptyState.CARD_W) / 2f, 700f, "download", 34.7f, 40.1f,
                "Загрузок нет", "Скачайте книгу из каталога")
        } else {
            list(c)
            paging(c)
        }
    }

    private fun header(c: Canvas) {
        ink.convex(c, 41.1f, 112.9f, 106.9f, 112.4f, R_BACK)
        icons.draw(c, "back", 71.3f, 143f, 47.5f, 49.5f)
        ink.text(c, "Загрузки", 175.5f, T.base(105f, T.TS_TITLE, 1.2f), T.TS_TITLE, 700, T.G1)
        val run = items.count { it.state == Downloads.S_RUN }
        val wait = items.count { it.state == Downloads.S_WAIT }
        val done = items.count { it.state == Downloads.S_DONE }
        val fail = items.count { it.state == Downloads.S_FAIL }
        val parts = ArrayList<String>(4)
        if (run > 0) parts.add("$run скачивается")
        if (wait > 0) parts.add("$wait в очереди")
        if (done > 0) parts.add("$done готово")
        if (fail > 0) parts.add("$fail с ошибкой")
        val sub = if (parts.isEmpty()) "Нет загрузок" else parts.joinToString(" · ")
        ink.text(c, ink.ellipsize(sub, 1000f, T.TS_BODY_S, 600), 175.5f,
            T.base(181f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
    }

    /** Строки: шаг 237.4 (макет B18: 284.6, 520.6, 740, 959.3 — округлён до ровного). */
    private fun list(c: Canvas) {
        val from = page * PER_PAGE
        for (i in 0 until PER_PAGE) {
            val idx = from + i
            if (idx >= items.size) break
            val it = items[idx]
            val y = ROW_Y0 + i * ROW_STEP
            row(c, it, y)
            if (idx < items.size - 1) ink.rect(c, 219.4f, y + 196.2f, 1096.9f, 1.8f, T.G11)
        }
    }

    private fun row(c: Canvas, it: Downloads.Item, y: Float) {
        ink.flat(c, 87.8f, y + 12f, 91.4f, 131.6f, T.R_COVER, T.G12)
        ink.roundRect(c, 87.8f, y + 12f, 12.8f, 131.6f, T.R_COVER, T.G7)
        ink.text(c, ink.ellipsize(it.title, 780f, 42f, 600, lit), 219.4f,
            T.base(y + 7.1f, 42f, 1.25f), 42f, 600, T.G1, lit)
        ink.text(c, it.format, 219.4f, T.base(y + 61.4f, T.TS_BODY_S, 1.25f),
            T.TS_BODY_S, 600, T.G4)
        when (it.state) {
            Downloads.S_RUN -> progress(c, it, y)
            Downloads.S_WAIT -> ink.text(c, "В очереди", 219.4f,
                T.base(y + 102f, T.TS_BODY_S, 1.27f), T.TS_BODY_S, 700, T.G4)
            Downloads.S_DONE -> {
                ink.text(c, "Скачано", 219.4f, T.base(y + 102f, T.TS_BODY_S, 1.27f),
                    T.TS_BODY_S, 700, T.G4)
                closeButton(c, y)
            }
            Downloads.S_FAIL -> {
                icons.draw(c, "info@32.9x32.9", 219.4f, y + 103.4f, 32.9f, 32.9f)
                ink.text(c, ink.ellipsize(it.why ?: "Сервер не ответил", 700f, T.TS_BODY_S, 700),
                    266.9f, T.base(y + 103.4f, T.TS_BODY_S, 1.27f), T.TS_BODY_S, 700, T.G2)
                retryButton(c, y)
            }
        }
    }

    /** «Скачивается»: дорожка 804.4×32.9, десять делений по 75, «620 из 980 КБ». */
    private fun progress(c: Canvas, it: Downloads.Item, y: Float) {
        ink.inset(c, 219.4f, y + 105.1f, 804.4f, 32.9f, 16.5f, T.G12)
        val pct = it.pct
        for (i in 0 until 10) {
            val on = pct >= (i + 1) * 10
            ink.roundRect(c, 224.9f + i * 79.3f, y + 110.6f, 75f, 21.9f, 5.5f,
                if (on) T.G3 else T.G11)
        }
        ink.text(c, sizeText(it), 1082f, T.base(y + 125.4f, T.TS_BODY_S, 1.27f),
            T.TS_BODY_S, 700, T.G1)
        closeButton(c, y)
    }

    /** «620 из 980 КБ» — байты в килобайтах, как в макете. */
    private fun sizeText(it: Downloads.Item): String {
        val d = it.done / 1024
        if (it.total <= 0) return "$d КБ"
        return "$d из ${it.total / 1024} КБ"
    }

    private fun closeButton(c: Canvas, y: Float) {
        ink.convex(c, 1225.8f, y, 92.3f, 97.8f, 44f)
        icons.draw(c, "close@40.2x42", 1252.3f, y + 26.5f, 40.2f, 42f)
    }

    private fun retryButton(c: Canvas, y: Float) {
        ink.convex(c, 1021f, y + 26.7f, 297.1f, 105.1f, 47.5f)
        icons.draw(c, "refresh@43.9x45.7", 1047.1f, y + 57f, 43.9f, 45.7f)
        ink.text(c, "Повторить", 1103f, T.base(y + 55.5f, T.TS_BODY, 1.27f), T.TS_BODY, 700, T.G1)
    }

    private fun paging(c: Canvas) {
        if (items.size <= PER_PAGE) return
        ink.convex(c, 41.1f, 1708.3f, 121.6f, 127.1f, T.R_BUTTON)
        icons.draw(c, "back", 75.9f, 1743f, 53f, 55.2f)
        ink.textCenter(c, "${page + 1} / ${pages()}", 0f, W, T.base(1738.3f, T.TS_PAGE, 1.25f),
            T.TS_PAGE, 600, T.G1)
        ink.convexDark(c, 1240.4f, 1708.3f, 121.6f, 127.1f, T.R_BUTTON)
        icons.draw(c, "next@53x53", 1275.1f, 1743f, 53f, 53f)
    }

    private fun pages(): Int = (items.size + PER_PAGE - 1) / PER_PAGE

    /**
     * Перечитать очередь и перерисовать, только если поменялось хоть что-то видимое:
     * на E-Ink нельзя инвалидировать каждый кадр прогресса, поэтому сравниваем строку
     * состояния по (id, состояние, процент).
     */
    private fun refresh() {
        items = downloads.items()
        page = page.coerceIn(0, (pages() - 1).coerceAtLeast(0))
        if (seenGen != downloads.generation()) {
            seenGen = downloads.generation()
            redraw()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
        items = downloads.items()
        page = 0
        if (downloads.running()) postDelayed(tick, TICK_MS)
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
        if (items.size > PER_PAGE) {
            if (inRect(x, y, 41.1f, 1708.3f, 121.6f, 127.1f)) {
                if (page > 0) {
                    page--
                    redraw()
                }
                return
            }
            if (inRect(x, y, 1240.4f, 1708.3f, 121.6f, 127.1f)) {
                if (page < pages() - 1) {
                    page++
                    redraw()
                }
                return
            }
        }
        val from = page * PER_PAGE
        for (i in 0 until PER_PAGE) {
            val idx = from + i
            if (idx >= items.size) break
            val it = items[idx]
            val rowY = ROW_Y0 + i * ROW_STEP
            // «Повторить» у строки с ошибкой.
            if (it.state == Downloads.S_FAIL && inRect(x, y, 1021f, rowY + 26.7f, 297.1f, 105.1f)) {
                downloads.retry(it.url)
                refresh()
                if (downloads.running()) {
                    removeCallbacks(tick)
                    postDelayed(tick, TICK_MS)
                }
                return
            }
            if (inRect(x, y, 1225.8f, rowY, 97.8f, 97.8f)) {
                downloads.remove(it.url)
                refresh()
                return
            }
        }
    }

    private fun inRect(x: Float, y: Float, rx: Float, ry: Float, rw: Float, rh: Float) =
        x >= rx && x <= rx + rw && y >= ry && y <= ry + rh

    private companion object {
        const val ROW_Y0 = 284.6f
        const val ROW_STEP = 237.4f
        const val PER_PAGE = 4
        const val R_BACK = 51.2f
        /** Прогресс загрузки — раз в секунду: чаще на E-Ink не имеет смысла. */
        const val TICK_MS = 1000L
    }
}