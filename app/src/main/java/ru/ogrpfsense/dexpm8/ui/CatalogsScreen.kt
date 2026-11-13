package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import ru.ogrpfsense.dexpm8.core.LibraryDb
import ru.ogrpfsense.dexpm8.core.Downloads

/**
 * B14 · Сетевые каталоги (п. 10). Числа — design/v5/B14.json.
 *
 *   шапка     «Назад» 41.1,112.9 106.9×112.4; заголовок 175.5,105 60/700;
 *             подзаголовок 175.5,181 32/600 G4 «3 каталога · OPDS»
 *   панель    41.1,245.9 1320.8×653.6 — строки каталогов, шаг 207.9:
 *             круглая клавиша 77.7,y 121.6×127.1 (иконка 113.3,y+45.4 51.2×53.3),
 *             название 234,y 42/700, адрес 234,y+56 32/600 G4,
 *             «Обновлён …» 234,y+100 30/600 G5, «next» 1295.2,y+49.1 43.9×43.9,
 *             разделитель 234,y+181.3 1089.6×1.8 G11
 *   загрузки  41.1,947.9 1320.8×185.6: «download» 113.3,1009.7 51.2×53.3,
 *             «Загрузки» 234,988.4 42/700, подпись 234,1044.4 32/600,
 *             счётчик 1188.3,1006.1 58.5×58.5 (диск G3, число 34/800 G15)
 *   подсказка 43.9,1184.6 1316.2×318.2 (вдавленная): «info» 95.1,1247.8 47.5×49.5,
 *             заголовок 168.2,1247.6 40/800, текст 95.1,1313.6 1206.6 34/600 G4
 *   кнопка    41.1,1693.6 1320.8×141.7 тёмная «Добавить каталог», «plus» 1265.1,1740.2
 *
 * Отступление от макета: в B14 три каталога нарисованы без пагинации; у нас панель листается
 * по [PER_PAGE] строк — так же, как библиотека B2 (в макете на панель приходится ровно три
 * строки по 207.9, четвёртая уже вышла бы за панель).
 */
class CatalogsScreen(act: EinkActivity) : Screen(act) {

    private val status = StatusBar(act, this, ink, icons)
    private val db = act.db
    private val emptyState = EmptyState(ink, icons)

    private class Row(val id: Long, val title: String, val sub: String, val seen: String, val icon: String)

    private var rows = ArrayList<Row>()
    private var page = 0

    override fun render(c: Canvas) {
        ink.rect(c, 0f, 0f, W, H, T.BG)
        status.draw(c)
        header(c)
        if (rows.isEmpty()) empty(c) else list(c)
        downloads(c)
        hint(c)
        addButton(c)
        paging(c)
    }

    private fun header(c: Canvas) {
        ink.convex(c, 41.1f, 112.9f, 106.9f, 112.4f, R_BACK)
        icons.draw(c, "back", 71.3f, 143f, 47.5f, 49.5f)
        ink.text(c, "Сетевые каталоги", 175.5f, T.base(105f, T.TS_TITLE, 1.2f), T.TS_TITLE, 700, T.G1)
        val n = rows.size
        val sub = if (n == 0) "OPDS" else Words.plural(n, "каталог", "каталога", "каталогов") + " · OPDS"
        ink.text(c, sub, 175.5f, T.base(181f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
    }

    /** Три строки каталогов: панель 245.9…899.5, шаг 207.9. */
    private fun list(c: Canvas) {
        ink.convex(c, 41.1f, 245.9f, 1320.8f, 653.6f, T.R_TILE)
        val from = page * PER_PAGE
        for (i in 0 until PER_PAGE) {
            val idx = from + i
            if (idx >= rows.size) break
            val r = rows[idx]
            val y = ROW_Y0 + i * ROW_STEP
            ink.convex(c, 77.7f, y, 121.6f, 127.1f, 60.8f)
            icons.draw(c, r.icon, 113.3f, y + 45.4f, 51.2f, 53.3f)
            ink.text(c, ink.ellipsize(r.title, 900f, T.TS_ACTION, 700), 234f,
                T.base(y, T.TS_ACTION, 1.25f), T.TS_ACTION, 700, T.G1)
            ink.text(c, ink.ellipsize(r.sub, 1000f, T.TS_BODY_S, 600), 234f,
                T.base(y + 56f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
            ink.text(c, r.seen, 234f, T.base(y + 100f, T.TS_META, 1.27f), T.TS_META, 600, T.G5)
            icons.draw(c, "next", 1295.2f, y + 49.1f, 43.9f, 43.9f)
            if (idx < rows.size - 1) ink.rect(c, 234f, y + 181.3f, 1089.6f, 1.8f, T.G11)
        }
    }

    /** Пусто: карточка B44 по центру панели (пустого состояния каталогов в макете нет). */
    private fun empty(c: Canvas) {
        ink.convex(c, 41.1f, 245.9f, 1320.8f, 653.6f, T.R_TILE)
        emptyState.card(c, (W - EmptyState.CARD_W) / 2f, 380f, "download", 34.7f, 40.1f,
            "Нет каталогов", "Добавьте адрес OPDS-каталога")
    }

    private fun downloads(c: Canvas) {
        ink.convex(c, 41.1f, 947.9f, 1320.8f, 185.6f, T.R_TILE)
        ink.convex(c, 77.7f, 977.1f, 121.6f, 127.1f, 60.8f)
        icons.draw(c, "download", 113.3f, 1009.7f, 51.2f, 53.3f)
        ink.text(c, "Загрузки", 234f, T.base(988.4f, T.TS_ACTION, 1.25f), T.TS_ACTION, 700, T.G1)
        ink.text(c, "Очередь и ошибки загрузок", 234f, T.base(1044.4f, T.TS_BODY_S, 1.25f),
            T.TS_BODY_S, 600, T.G4)
        val n = act.downloads.active()
        if (n > 0) {
            ink.flat(c, 1188.3f, 1006.1f, 58.5f, 58.5f, 29.2f, T.G3)
            ink.textCenter(c, n.toString(), 1188.3f, 58.5f, T.base(1014.1f, 34f, 1.27f), 34f, 800, T.G15)
        }
        icons.draw(c, "next", 1287f, 1016.4f, 43.9f, 43.9f)
    }

    private fun hint(c: Canvas) {
        ink.inset(c, 43.9f, 1184.6f, 1316.2f, 318.2f, T.R_TILE)
        icons.draw(c, "info", 95.1f, 1247.8f, 47.5f, 49.5f)
        ink.text(c, "Что такое сетевой каталог", 168.2f, T.base(1247.6f, 40f, 1.25f), 40f, 800, T.G1)
        ink.paragraph(c, HINT, 95.1f, 1313.6f, 1206.6f, T.TS_BODY, 600, T.G4, 8f)
    }

    private fun addButton(c: Canvas) {
        ink.convexDark(c, 41.1f, 1693.6f, 1320.8f, 141.7f, T.R_BUTTON)
        ink.text(c, "Добавить каталог", 95.1f, T.base(1736.2f, T.TS_ACTION, 1.25f), T.TS_ACTION, 700, T.G15)
        icons.draw(c, "plus", 1265.1f, 1740.2f, 43.9f, 43.9f)
    }

    /** Пагинация — только когда строк больше страницы (в макете её нет). */
    private fun paging(c: Canvas) {
        if (rows.size <= PER_PAGE) return
        ink.convex(c, 41.1f, 1708.3f, 121.6f, 127.1f, T.R_BUTTON)
        icons.draw(c, "back", 75.9f, 1743f, 53f, 55.2f)
        ink.textCenter(c, "${page + 1} / ${pages()}", 0f, W, T.base(1738.3f, T.TS_PAGE, 1.25f),
            T.TS_PAGE, 600, T.G1)
        ink.convexDark(c, 1240.4f, 1708.3f, 121.6f, 127.1f, T.R_BUTTON)
        icons.draw(c, "next@53x53", 1275.1f, 1743f, 53f, 53f)
    }

    private fun pages(): Int = (rows.size + PER_PAGE - 1) / PER_PAGE

    private fun reload() {
        rows = ArrayList()
        for (c0 in db.catalogs()) {
            rows.add(Row(c0.id, c0.label(), hostOf(c0.url), seenOf(c0), iconOf(c0.url)))
        }
        page = page.coerceIn(0, (pages() - 1).coerceAtLeast(0))
        redraw()
    }

    override fun onUp(x: Float, y: Float) {
        if (inRect(x, y, 41.1f, 112.9f, 106.9f, 112.4f)) {
            act.pop()
            return
        }
        if (inRect(x, y, 41.1f, 1693.6f, 1320.8f, 141.7f)) {
            act.catalogId = -1L
            act.go(EinkActivity.SCR_CATALOG_ADD)
            return
        }
        if (inRect(x, y, 41.1f, 947.9f, 1320.8f, 185.6f)) {
            act.go(EinkActivity.SCR_DOWNLOADS)
            return
        }
        if (rows.size > PER_PAGE) {
            if (inRect(x, y, 41.1f, 1708.3f, 121.6f, 127.1f)) {
                if (page > 0) {
                    page--
                    reload()
                }
                return
            }
            if (inRect(x, y, 1240.4f, 1708.3f, 121.6f, 127.1f)) {
                if (page < pages() - 1) {
                    page++
                    reload()
                }
                return
            }
        }
        val from = page * PER_PAGE
        for (i in 0 until PER_PAGE) {
            val idx = from + i
            if (idx >= rows.size) break
            if (!inRect(x, y, 77.7f, ROW_Y0 + i * ROW_STEP, 1213.9f, 183f)) continue
            val row = rows[idx]
            db.touchCatalog(row.id)
            act.catalogId = row.id
            act.go(EinkActivity.SCR_CATALOG)
            return
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
        reload()
    }

    override fun onDetachedFromWindow() {
        status.detach()
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------ данные строки

    /** Адрес без схемы — в макете вторая строка это «192.168.1.10:8080 · нужен вход». */
    private fun hostOf(url: String): String {
        val s = url.removePrefix("http://").removePrefix("https://")
        return s.trimEnd('/')
    }

    /** «Обновлён сегодня» / «Обновлён вчера» / «Обновлён 3 дня назад» — как в макете. */
    private fun seenOf(c0: LibraryDb.Catalog): String {
        if (c0.seen == 0L) return "Не открывался"
        val days = ((System.currentTimeMillis() - c0.seen) / DAY_MS).toInt()
        return when {
            days <= 0 -> "Обновлён сегодня"
            days == 1 -> "Обновлён вчера"
            days < 30 -> "Обновлён " + Words.plural(days, "день", "дня", "дней") + " назад"
            else -> "Обновлён давно"
        }
    }

    /** Иконка строки по виду адреса: Calibre и открытые каталоги — как в макете B14. */
    private fun iconOf(url: String): String {
        val h = url.lowercase()
        return when {
            h.contains("gutenberg") || h.contains("standardebooks") || h.contains("wikisource") ->
                "browser"
            h.contains("calibre") || h.contains(":8080") -> "home"
            else -> "library"
        }
    }

    private fun inRect(x: Float, y: Float, rx: Float, ry: Float, rw: Float, rh: Float) =
        x >= rx && x <= rx + rw && y >= ry && y <= ry + rh

    private companion object {
        const val ROW_Y0 = 287.9f
        const val ROW_STEP = 207.9f
        const val PER_PAGE = 3
        const val R_BACK = 51.2f
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val HINT = "Каталог OPDS — это библиотека в сети: её можно листать, искать в ней " +
            "и скачивать книги прямо на устройство. Добавьте адрес публичного каталога или " +
            "своего сервера Calibre."
    }
}
