package ru.ogrpfsense.dexpm8.ui

import android.content.Intent
import android.graphics.Canvas
import android.net.Uri
import android.provider.Settings
import ru.ogrpfsense.dexpm8.core.LibraryDb
import ru.ogrpfsense.dexpm8.core.Scanner

/**
 * B2 · Библиотека (список) и B11 · Библиотека (сетка). Числа — из design/v5/B02.json и
 * B11.json; базовые линии — по рамкам текста через [T.base] (top + fs·(lh/2 + k)).
 * Переключатель вида ([viewMode]) выбирает [list] или [grid]; шапка, поиск, сортировка и
 * пагинация общие (рамки, текст, круги — те же числа). Иконки в B11 другого размера (центры
 * те же): `back`/`list`/`grid` в шапке 43.9×45.7 против 45.7×47.6 на B2 — рисуем теми же
 * вызовами, что на B2 (разница 1.8 px в допуске ±2 px; `IconSpecs` не трогали, вопрос чату
 * дизайна). Поиск, фильтр (38.4×40.2) и пагинация (`back` 54.8×57.1, `next` 53×53) в режиме
 * сетки рисуются по размерам и позициям B11 ([searchField], [pager]).
 *
 *   статус-бар    [StatusBar]
 *   назад         41.1,112.3     106.9×112.4 (круг 102.4), иконка 72.2,144.3
 *   заголовок     175.5,104      60/700 lh 1.2 ; подзаголовок 175.5,180  32/600 lh 1.3 G4
 *   вид           1155.4,122.3   204.8×87.7 (желоб), сегмент 1159.9 (+95.1 для «сетки») 99.6×83.2
 *   поиск         43.9,248.6     1316.2×102.4 (вдавленное), плейсхолдер 153.6 38/500 G6,
 *                 фильтр 1262.3,256.9 85×90.5
 *   сортировка    43.9,387.6     1316.2×87.7, сегмент 48.4 + i·325.4, ширина 330, подпись 34
 *   панель        41.1,516.4     1320.8×1136.2
 *   строки        95.1, y = 569.2 + i·184; обложка 73.1×106, корешок 11 (G7); текст с x 204.8;
 *                 состояние книги — по правому краю 1309; разделитель 204.8, +145, 1104.2×1.8 G11
 *   выбранная     вдавленная плашка 65.8, y−22, 1272.4×149.9, дно G12; обложка G14, корешок G3
 *   страницы      41.1 / 1240.4, 1708.3, 121.6×127.1 (круг 117); номер 44/600, верх рамки 1745.3
 *
 * Сетка B11 — числа карточек в [LibraryGrid] (без панели, карточки прямо на фоне G12):
 *   карточка      рамка 414.1×558.5 (выпуклая, r 43.875), x 41.1 + i·453.4, y 509.1 / 1094.1;
 *                 лицо = рамка +2.8,+2.8 (409.5×548.4); зоны касания — рамки карточек
 *   выбранная     «Мастер и Маргарита» — вдавленная на месте лица, неподвижно
 *   гнездо        лицо +32.9,+29.2, 343.7×288; обложка 314.4×258.8; названия Literata 34 / 36
 */
class LibraryScreen(act: EinkActivity) : Screen(act) {

    /** [kind]: [ST_PROGRESS] с процентом [pct], [ST_NEW] или [ST_READ]. */
    private class Book(val title: String, val author: String, val kind: Int, val pct: Int)

    // Данные — страница из базы ([refresh]); настоящий список даёт сканер.
    private val db = act.db
    private val scanner = Scanner(act, db)

    /** Строки текущей страницы: [rowIds] — `_id`, [books] — для списка B2, [gridBooks] — для B11. */
    private val rowIds = ArrayList<Long>(6)
    private val books = ArrayList<Book>(6)
    private val gridBooks = ArrayList<GridCard>(6)

    private var total = 0                  // книг в текущей выборке (с учётом «Непрочитанные»)
    private var selectedId = -1L           // последняя открытая книга; -1 — не открывали
    private var subtitle = ""
    private var storageOk = act.hasStorage()

    private val cards = LibraryGrid(ink, icons)
    private val empty = EmptyState(ink, icons)
    private val noStorage = NoStorageHint(ink, icons)

    private val sortLabels = arrayOf("По автору", "По дате", "Новые", "Непрочитанные")

    private var page = 0
    private val perPage = 6
    private var viewMode = 0     // 0 = список, 1 = сетка

    // B22: сортировка и фильтры живут в Prefs; полоса B2 — четыре быстрых набора тех же значений.
    private val sheet = FilterSheet(ink, icons)
    private var sheetOpen = false
    private var sheetCount = 0

    /** Номер набора полосы, который совпадает с текущими значениями, или −1 (набор из листа). */
    private fun sortMode(): Int {
        val p = act.prefs
        if (p.libReverse) return -1
        val none = p.libStates == 0
        val onlyNew = p.libStates == 1 shl LibraryDb.STATUS_NEW
        return when {
            p.libSort == LibraryDb.SORT_AUTHOR && none -> 0
            p.libSort == LibraryDb.SORT_RECENT && none -> 1
            p.libSort == LibraryDb.SORT_ADDED && none -> 2
            p.libSort == LibraryDb.SORT_TITLE && onlyNew -> 3
            else -> -1
        }
    }

    /** Нажатие на полосу: набор [i] меняет сортировку и состояние, формат и «скрывать» остаются. */
    private fun applyPreset(i: Int) {
        val p = act.prefs
        p.libReverse = false
        p.libStates = if (i == 3) 1 shl LibraryDb.STATUS_NEW else 0
        p.libSort = when (i) {
            0 -> LibraryDb.SORT_AUTHOR
            1 -> LibraryDb.SORT_RECENT
            2 -> LibraryDb.SORT_ADDED
            else -> LibraryDb.SORT_TITLE
        }
    }

    private fun dbFilter() =
        LibraryDb.Filter(act.prefs.libFormats, act.prefs.libStates, act.prefs.libHideRead)

    /** Перерисовать после смены права: B46 уходит, на его месте — содержимое библиотеки. */
    private val storageListener: (Boolean) -> Unit = { ok ->
        storageOk = ok
        if (ok) {
            startScan(true)      // скан сам перерисует по готовности
            layout(W, H)         // зоны касания: B46 → обычный экран
            redraw()
        } else {
            layout(W, H)
            redraw()
        }
    }

    private val litTitle = T.literata(ink.res, 600)

    private val status = StatusBar(act, this, ink, icons)

    private val hits = ArrayList<Hit>(16)
    private class Hit(val id: Int, val x: Float, val y: Float, val w: Float, val h: Float)

    private companion object {
        const val HIT_BACK = 1
        const val HIT_PREV = 2
        const val HIT_NEXT = 3
        const val HIT_SEARCH = 4
        const val HIT_SORT = 5
        const val HIT_VIEW = 6
        const val HIT_FILTER = 8
        const val HIT_SHELVES = 7
        /** Сетевые каталоги (п. 10): клавиша в шапке B2. */
        const val HIT_CATALOGS = 60
        const val HIT_BOOK = 9

        const val ST_PROGRESS = 0
        const val ST_NEW = 1
        const val ST_READ = 2

        const val ROW_Y0 = 569.2f
        const val ROW_STEP = 184f
        const val ROW_H = 146.8f
        const val TEXT_X = 204.8f
        const val TEXT_MAX_W = 730f      // до дорожки прогресса (950.6)
        const val RIGHT = 1309f          // правый край состояния книги (панель 1360 − 51)
        const val TS_BOOK = 42f          // название книги в строке (Literata 600)

        const val TRACK_X = 950.6f
        const val TRACK_W = 237.7f
        const val BAR_X = 953.4f
        const val BAR_W = 232.2f
        const val BAR_MIN = 21.9f        // «3 %» в макете нарисовано шириной 21.9, а не 7
        const val R_BAR = 6.85546875f

        const val SORT_X = 43.9f
        const val SORT_STEP = 325.4f

        const val R_ROUND_BACK = 51.2f   // круглые клавиши: диск 102.4 / 117 / 80.4
        const val R_ROUND_PAGE = 58.5f
        const val R_ROUND_FILTER = 40.2f
    }

    override fun layout(w: Float, h: Float) {
        super.layout(w, h)
        hits.clear()
        hits.add(Hit(HIT_BACK, 41.1f, 112.3f, 106.9f, 112.4f))
        if (storageOk) {
            hits.add(Hit(HIT_FILTER, 1250f, 248.6f, 110f, 102.4f))
            hits.add(Hit(HIT_SEARCH, 43.9f, 248.6f, 1316.2f, 102.4f))
            hits.add(Hit(HIT_VIEW, 1155.4f, 122.3f, 204.8f, 87.7f))
            hits.add(Hit(HIT_SHELVES, 1020f, 112.3f, 120f, 112.4f))
            hits.add(Hit(HIT_CATALOGS, 915f, 112.3f, 120f, 112.4f))
            hits.add(Hit(HIT_SORT, SORT_X, 387.6f, 1316.2f, 87.7f))
            // Пустой список (B44): пагинации на экране нет, значит и зон у неё нет.
            if (rowIds.isNotEmpty()) {
                hits.add(Hit(HIT_PREV, 41.1f, 1708.3f, 121.6f, 127.1f))
                hits.add(Hit(HIT_NEXT, 1240.4f, 1708.3f, 121.6f, 127.1f))
            }
            for (i in 0 until rowIds.size) {
                if (viewMode == 1) {
                    // Сетка: зона карточки — её рамка в макете (414.1×558.5, зазор между рамками 39.3).
                    hits.add(Hit(HIT_BOOK + i + 1, cards.frameX(i), cards.frameY(i),
                        LibraryGrid.FRAME_W, LibraryGrid.FRAME_H))
                } else {
                    hits.add(Hit(HIT_BOOK + i + 1, 95.1f, ROW_Y0 + i * ROW_STEP, 1213.9f, ROW_H))
                }
            }
        }
        // B46 (нет права): зоны кнопок — в [NoStorageHint.hit], в таблице их нет.
    }

    override fun render(c: Canvas) {
        // Под листом B22 экран затемнён (замечание 15), статус-бар и сам лист — нет.
        val dim = if (sheetOpen) dimBegin(c) else -1
        ink.rect(c, 0f, 0f, W, H, T.BG)
        header(c)
        if (!storageOk) {
            // B46: нет права на память — вместо содержимого библиотеки экран-подсказка.
            noStorage.draw(c)
        } else {
            searchField(c)
            sortBar(c)
            if (rowIds.isEmpty()) {
                emptyState(c)       // B44: пагинации при пустом списке нет
            } else {
                if (viewMode == 1) grid(c) else list(c)
                pager(c)
            }
        }
        if (dim >= 0) dimEnd(c, dim)
        status.draw(c)
        if (sheetOpen) sheet.draw(c, sheetCount)
    }

    private fun count() = total

    // --------------------------------------------------------------- данные

    /** Перечитать страницу, число книг и «последнюю открытую» из базы. UI-поток, 6 строк. */
    private fun refresh() {
        try {
            val f = dbFilter()
            total = db.count(f)
            val pages = Math.max(1, (total + perPage - 1) / perPage)
            if (page >= pages) page = pages - 1
            val rows = db.list(act.prefs.libSort, act.prefs.libReverse, page * perPage, perPage, f)
            rowIds.clear()
            books.clear()
            gridBooks.clear()
            for (e in rows) {
                val kind = when (e.status) {
                    LibraryDb.STATUS_READING -> ST_PROGRESS
                    LibraryDb.STATUS_READ -> ST_READ
                    else -> ST_NEW
                }
                val pct = if (kind == ST_PROGRESS) e.progress else if (kind == ST_READ) 100 else 0
                val fmt = e.format.uppercase()
                val author = e.author ?: "Автор неизвестен"
                rowIds.add(e.id)
                books.add(Book(e.title, "$author · $fmt", kind, pct))
                gridBooks.add(GridCard(e.title, author, kind, pct))
            }
            selectedId = db.lastOpened()?.id ?: -1L
            val all = db.count()
            subtitle = if (all == 0) "пусто" else "${booksWord(all)} · ${sizeText(db.totalSize())}"
        } catch (t: Throwable) {
            Trace.step("library refresh FAIL: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun booksWord(n: Int): String {
        val m100 = n % 100
        val m10 = n % 10
        val w = if (m100 in 11..14) "книг" else when (m10) {
            1 -> "книга"
            2, 3, 4 -> "книги"
            else -> "книг"
        }
        return "$n $w"
    }

    private fun sizeText(bytes: Long): String {
        val gb = bytes / 1073741824.0
        if (gb >= 1.0) return String.format(java.util.Locale("ru"), "%.1f ГБ", gb)
        val mb = Math.max(1L, Math.round(bytes / 1048576.0))
        return "$mb МБ"
    }

    private var attached = false

    /** [force] — после выдачи права: пауза не действует. */
    private fun startScan(force: Boolean = false) {
        val onDone: (Scanner.Result) -> Unit = {
            post {
                if (attached) {
                    refresh()
                    layout(W, H)
                    redraw()
                }
            }
        }
        val started = if (force) scanner.start(onDone) else scanner.startIfDue(onDone)
        // Пустая база: показать «Идёт поиск книг…» (один redraw в начале прохода).
        if (started && rowIds.isEmpty()) redraw()
    }

    /** Обновить данные и экран после смены страницы, сортировки или вида. */
    private fun reload() {
        refresh()
        layout(W, H)
        redraw()
    }

    /**
     * Пустая библиотека B44. Тексты — отступление от макета: B44 показывает только «нет книг»,
     * а два других состояния (идёт поиск, пусто после фильтра) на нём не нарисованы.
     * Кнопки «Загрузить по Wi-Fi» и «Сетевые каталоги» в макете есть, но появятся с 9.7 и п. 10;
     * пока — без кнопок, о них говорит только пояснение.
     */
    private fun emptyState(c: Canvas) {
        val title: String
        val hint: String
        if (scanner.isRunning) {
            title = "Идёт поиск книг…"
            hint = "Это займёт несколько секунд"
        } else if (!dbFilter().isEmpty) {
            title = "Ничего не найдено"
            hint = "Проверьте фильтры — книг с такими условиями нет в библиотеке"
        } else {
            title = "В библиотеке пока нет книг"
            hint = "Скопируйте файлы в папку Books по USB, загрузите их по Wi-Fi " +
                "или найдите в сетевых каталогах."
        }
        // Кнопки — только в состоянии «книг нет совсем»: при поиске и пустом фильтре
        // загружать нечего (решение 9.7). «Загрузить по Wi-Fi» ведёт на B26.
        empty.library(c, title, hint, bookless())
    }

    /** Библиотека пуста по-настоящему: не идёт поиск и фильтры ничего не отсекают. */
    private fun bookless(): Boolean = !scanner.isRunning && dbFilter().isEmpty

    private fun header(c: Canvas) {
        ink.convex(c, 41.1f, 112.3f, 106.9f, 112.4f, R_ROUND_BACK)
        icons.draw(c, "back", 72.2f, 144.3f, 45.7f, 47.6f)

        ink.text(c, "Библиотека", 175.5f, T.base(104f, T.TS_TITLE, 1.2f), T.TS_TITLE, 700, T.G1)
        // B46: подзаголовок «Нет доступа к памяти» (на макете библиотеки — число книг и размер).
        ink.text(c, if (storageOk) subtitle else "Нет доступа к памяти", 175.5f,
            T.base(180f, T.TS_BODY_S, 1.3f), T.TS_BODY_S, 600, T.G4)
        // Без права нет ни данных, ни «Полок»: круглая клавиша и переключатель вида не рисуются.
        if (!storageOk) return

        // «Полки»: круглая клавиша левее переключателя вида (в макете B2/B11 её нет, решение DEVLOG 9.3).
        ink.convex(c, 1030f, 112.3f, 106.9f, 112.4f, R_ROUND_BACK)
        icons.draw(c, "library", 1057f, 142f, 52f, 54.2f)
        // «Сетевые каталоги» (п. 10): третья клавиша — в макете B2 её нет, вход задуман только
        // с пустой библиотеки B44, но при 530 книгах B44 недостижим. Место выбрано между
        // «Полками» и переключателем вида, шаг 106.9 как у остальных круглых клавиш.
        ink.convex(c, 920.2f, 112.3f, 106.9f, 112.4f, R_ROUND_BACK)
        icons.draw(c, "download", 947f, 141.5f, 52f, 54.2f)
        // Переключатель вида: желоб 204.8×87.7, активный сегмент сдвигается на 95.1.
        ink.inset(c, 1155.4f, 122.3f, 204.8f, 87.7f, T.R_GROOVE)
        ink.convex(c, 1159.9f + viewMode * 95.1f, 126.9f, 99.6f, 83.2f, T.R_SEGMENT)
        icons.draw(c, "list", 1187.4f, 144.3f, 45.7f, 47.6f)
        icons.draw(c, "grid", 1282.4f, 144.3f, 45.7f, 47.6f)
    }

    private fun searchField(c: Canvas) {
        ink.inset(c, 43.9f, 248.6f, 1316.2f, 102.4f, T.R_TILE)
        if (viewMode == 1) {
            // B11: поиск 87.8,277.9 43.9×45.7; плейсхолдер 154,275.3; фильтр 1286.1,281.5 38.4×40.2.
            icons.draw(c, "search", 87.8f, 277.9f, 43.9f, 45.7f)
            ink.text(c, "Название, автор или серия", 154f, T.base(275.3f, T.TS_LABEL_L, 1.3f),
                T.TS_LABEL_L, 500, T.G6)
            ink.convex(c, 1262.3f, 256.9f, 85f, 90.5f, R_ROUND_FILTER)
            icons.draw(c, "filter", 1286.1f, 281.5f, 38.4f, 40.2f)
        } else {
            icons.draw(c, "search", 86.8f, 275.1f, 45.7f, 47.6f)
            ink.text(c, "Название, автор или серия", 153.6f, T.base(274.4f, T.TS_LABEL_L, 1.3f),
                T.TS_LABEL_L, 500, T.G6)
            ink.convex(c, 1262.3f, 256.9f, 85f, 90.5f, R_ROUND_FILTER)
            icons.draw(c, "filter", 1282.4f, 277.9f, 45.7f, 47.6f)
        }
    }

    private fun sortBar(c: Canvas) {
        ink.inset(c, SORT_X, 387.6f, 1316.2f, 87.7f, T.R_GROOVE)
        val mode = sortMode()
        if (mode >= 0) ink.convex(c, 48.4f + mode * SORT_STEP, 392.1f, 330f, 83.2f, T.R_SEGMENT)
        val base = T.base(409.5f, T.TS_BODY, 1.3f)
        for (i in sortLabels.indices) {
            val active = i == mode
            ink.textCenter(c, sortLabels[i], 51.2f + i * SORT_STEP, SORT_STEP, base, T.TS_BODY,
                if (active) 700 else 600, if (active) T.G1 else T.G5)
        }
    }

    private fun list(c: Canvas) {
        ink.convex(c, 41.1f, 516.4f, 1320.8f, 1136.2f, T.R_TILE)

        for (i in 0 until books.size) {
            val b = books[i]
            val y = ROW_Y0 + i * ROW_STEP
            val sel = rowIds[i] == selectedId
            if (sel) ink.inset(c, 65.8f, y - 22f, 1272.4f, 149.9f, T.R_SEGMENT, T.G12)
            cover(c, y, if (sel) T.G14 else T.G12, if (sel) T.G3 else T.G7)

            ink.text(c, ink.ellipsize(b.title, TEXT_MAX_W, TS_BOOK, 600, litTitle),
                TEXT_X, T.base(y + 3f, TS_BOOK, 1.25f, true), TS_BOOK, 600, T.G1, litTitle)
            ink.text(c, ink.ellipsize(b.author, TEXT_MAX_W, T.TS_BODY_S, 600),
                TEXT_X, T.base(y + 61f, T.TS_BODY_S, 1.3f), T.TS_BODY_S, 600, T.G4)

            bookState(c, b, y)

            // Разделитель — на обложка + 145, кроме выбранной и последней строки.
            if (!sel && i < books.size - 1) {
                ink.rect(c, TEXT_X, y + 145f, 1104.2f, 1.8f, T.G11)
            }
        }
    }

    /** Обложка 73.1×106 и корешок 11 со скруглением только слева (радиус 10.97). */
    private fun cover(c: Canvas, y: Float, body: Int, spine: Int) {
        ink.roundRect(c, 95.1f, y, 73.1f, 106f, T.R_COVER, body)
        ink.roundRect(c, 95.1f, y, 22f, 106f, T.R_COVER, spine)
        ink.rect(c, 106.1f, y, 11f, 106f, body)
    }

    /** Состояние книги по правому краю 1309: прогресс, «Новая» или «Прочитана». */
    private fun bookState(c: Canvas, b: Book, y: Float) {
        when (b.kind) {
            ST_PROGRESS -> {
                ink.inset(c, TRACK_X, y + 45.1f, TRACK_W, 21.9f, T.R_COVER, T.G12)
                val fw = Math.max(BAR_MIN, BAR_W * b.pct / 100f)
                ink.roundRect(c, BAR_X, y + 50.6f, fw, 13.7f, R_BAR, T.G3)
                rightText(c, "${b.pct}%", y + 35f, T.TS_BODY, 700, 1.2f, T.G1)
            }
            ST_NEW -> rightText(c, "Новая", y + 34.6f, T.TS_BODY_S, 700, 1.3f, T.G4)
            else -> {
                icons.draw(c, "check", 1084f, y + 34.5f, 42f, 43.9f)
                rightText(c, "Прочитана", y + 34.6f, T.TS_BODY_S, 700, 1.3f, T.G4)
            }
        }
    }

    /** Текст по правому краю [RIGHT]; [top] — верх рамки текста в макете. */
    private fun rightText(c: Canvas, s: String, top: Float, size: Float, weight: Int,
                          lh: Float, color: Int) {
        ink.text(c, s, RIGHT - ink.measure(s, size, weight), T.base(top, size, lh),
            size, weight, color)
    }

    private fun grid(c: Canvas) {
        for (i in 0 until gridBooks.size) {
            cards.draw(c, i, gridBooks[i], rowIds[i] == selectedId)
        }
    }

    private fun totalPages() = Math.max(1, (count() + perPage - 1) / perPage)

    private fun pager(c: Canvas) {
        ink.convex(c, 41.1f, 1708.3f, 121.6f, 127.1f, R_ROUND_PAGE)
        ink.convexDark(c, 1240.4f, 1708.3f, 121.6f, 127.1f, R_ROUND_PAGE)
        if (viewMode == 1) {
            // B11: back 75,1743.2 54.8×57.1; next 1275.1,1743 53×53 — стрелка белая (G15):
            // на тёмной клавише G6-стрелка сливалась с поверхностью G3 (замечание 16).
            icons.draw(c, "back", 75f, 1743.2f, 54.8f, 57.1f)
            icons.draw(c, "next@53x53", 1275.1f, 1743f, 53f, 53f)
        } else {
            icons.draw(c, "back", 79.5f, 1747.6f, 45.7f, 47.6f)
            icons.draw(c, "nextLight", 1279.7f, 1747.6f, 43.9f, 43.9f)
        }
        ink.textCenter(c, "${page + 1} / ${totalPages()}", 0f, W,
            T.base(1745.3f, T.TS_PAGE, 1.2f), T.TS_PAGE, 600, T.G1)
    }

    // --------------------------------------------------------------- ввод

    /** Лист B22 открыт: все касания — ему; изменение пересчитывает «Показать N книг». */
    private fun sheetUp(x: Float, y: Float) {
        when (sheet.hit(x, y)) {
            FilterSheet.ACT_CHANGED -> {
                sheetCount = db.count(sheet.filter())
                redraw()
            }
            FilterSheet.ACT_CANCEL -> closeSheet(false)
            FilterSheet.ACT_APPLY -> closeSheet(true)
        }
    }

    private fun openSheet() {
        sheet.load(act.prefs)
        sheetCount = db.count(sheet.filter())
        sheetOpen = true
        act.bigFrame("filter sheet open")    // «Быстрый»: большой кадр — полной перерисовкой
        redraw()
    }

    private fun closeSheet(apply: Boolean) {
        sheetOpen = false
        if (apply) {
            sheet.save(act.prefs)
            page = 0
            refresh()
            layout(W, H)
        }
        act.bigFrame("filter sheet close")
        redraw()
    }

    override fun onUp(x: Float, y: Float) {
        if (sheetOpen) {
            sheetUp(x, y)
            return
        }
        if (!storageOk) {
            // B46: сначала кнопки подсказки; «Назад» — общая зона в таблице hits ниже.
            when (NoStorageHint.hit(x, y)) {
                NoStorageHint.ACT_SETTINGS -> {
                    askStorage()
                    return
                }
                NoStorageHint.ACT_LATER -> {
                    act.pop()
                    return
                }
            }
        } else if (rowIds.isEmpty() && bookless()) {
            // B44 с кнопками: «Загрузить по Wi-Fi» (B26) и «Сетевые каталоги» (п. 10, пока молчит).
            when (empty.libraryHit(x, y)) {
                EmptyState.ACT_WIFI -> {
                    act.go(EinkActivity.SCR_WIFI)
                    return
                }
                EmptyState.ACT_CATALOG -> {
                    act.catalogId = -1L
                    act.go(EinkActivity.SCR_CATALOGS)
                    return
                }
            }
        }
        for (h in hits) {
            if (x >= h.x && x <= h.x + h.w && y >= h.y && y <= h.y + h.h) {
                when (h.id) {
                    HIT_BACK -> act.pop()
                    HIT_PREV -> if (page > 0) { page--; reload() }
                    HIT_NEXT -> if (page < totalPages() - 1) { page++; reload() }
                    HIT_VIEW -> {
                        viewMode = if (viewMode == 0) 1 else 0
                        layout(W, H)    // зоны касания строк и карточек различаются
                        redraw()
                    }
                    HIT_SORT -> {
                        val i = ((x - SORT_X) / SORT_STEP).toInt().coerceIn(0, sortLabels.size - 1)
                        if (i != sortMode()) {
                            applyPreset(i)
                            page = 0
                            reload()
                        }
                    }
                    HIT_FILTER -> openSheet()
                    HIT_SHELVES -> act.go(EinkActivity.SCR_SHELVES)
                    HIT_CATALOGS -> act.go(EinkActivity.SCR_CATALOGS)
                    HIT_SEARCH -> {
                        act.searchFresh = true
                        act.go(EinkActivity.SCR_SEARCH)
                    }
                    else -> if (h.id > HIT_BOOK) {
                        val i = h.id - HIT_BOOK - 1
                        // Нажатие на книгу — «О книге» (B12); в читалку — кнопкой оттуда.
                        if (i in 0 until rowIds.size) {
                            act.bookId = rowIds[i]
                            act.go(EinkActivity.SCR_BOOK)
                        }
                    }
                }
                return
            }
        }
    }

    /** B46 «Открыть настройки»: экран приложения в настройках Android, где включается «Память». */
    private fun askStorage() {
        try {
            act.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + act.packageName)))
        } catch (t: Throwable) {
            Trace.step("settings FAIL: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        status.attach()
        act.addStorageListener(storageListener)
        storageOk = act.hasStorage()
        // Выбранная строка и страница — по базе; сканер подтянет изменения в фоне.
        refresh()
        layout(W, H)
        // Диалог права здесь не открываем: он только на холодном старте (EinkActivity).
        // Не выдали — экран B46 с кнопкой «Открыть настройки» (проверка права — storageListener).
        if (storageOk) startScan()
    }

    override fun onDetachedFromWindow() {
        attached = false
        act.removeStorageListener(storageListener)
        status.detach()
        super.onDetachedFromWindow()
    }
}

