package ru.ogrpfsense.dexpm8.ui

import android.content.Intent
import android.graphics.Canvas
import android.provider.Settings
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
 * Срезы (10.11, B64–B69). Сегмент «Авторы/Серии/Жанры» открывает фид-список среза: адрес
 * берётся из корневого фида ([Opds.findSlice]) — BookLore объявляет `/authors` и `/series`
 * ссылками `rel="subsection"` и записями-навигациями. Список среза — B64/B66/B68 (круг с
 * инициалами у авторов, `list` у серий, `library` у жанров, шаг 184, шесть строк,
 * разделитель 237,y+147.5 1076×2). Выбор строки — фид книг среза (B65/B67/B69): те же строки,
 * что в B16, шапка — имя среза, подзаголовок «Авторы · 12 книг». «Назад» в шапке там
 * возвращает к списку среза (фид держится в памяти), а не в B14. Среза каталог не отдал
 * (у BookLore жанров нет) — карточка B44 «Нет раздела», а не пустой список.
 *
 * Поиск (10.9). На B16 поле поиска нарисовано, но поведения у него нет: клавиатура — B48
 * листом с y 1385, как в B21, запрос — по тёмной клавише «Найти» (каждая буква была бы
 * запросом к серверу; B21 ищет в базе, там это дёшево). «×» 1262,256 87×92 из B21 очищает
 * запрос и возвращает корневой фид. Пока клавиатура открыта, строк четыре, пагинация скрыта.
 * Шаблон поиска берётся из `rel="search"` текущего фида ([Opds.searchTemplate]).
 */
class OpdsScreen(act: EinkActivity) : Screen(act) {

    private val status = StatusBar(act, this, ink, icons)
    private val opds = Opds(act)
    private val db = act.db
    private val downloads = act.downloads
    private val kb = Keyboard(ink)

    /** Одна строка — книга или раздел каталога. */
    private class Row(
        val title: String,
        val author: String,
        val formats: String,
        val entry: Opds.Entry,
        val section: Opds.Link?,
    )

    private var feed: Opds.Feed? = null
    /** Корневой фид каталога: из него берётся `rel="search"`, даже когда открыт раздел. */
    private var base: Opds.Feed? = null

    private var rows = ArrayList<Row>()
    private var why = ""
    private var loading = false
    private var tab = 0
    private var page = 0
    private var catalogId = -1L
    private var title = ""
    private var sub = ""
    /**
     * Срез каталога (10.11, B64–B69): [tab] 0 — обычный фид (B16), 1/2/3 — «Авторы/Серии/Жанры»
     * (значения совпадают с [Opds.Slice]). [inSlice] — открыты книги среза (B65/B67/B69),
     * [sliceFeed] — его список в памяти: «Назад» возвращает к нему без запроса к серверу.
     */
    private var sliceFeed: Opds.Feed? = null
    /** Строки списка среза на момент ухода в книги: возврат по «Назад» — без запроса к серверу. */
    private var sliceRows = ArrayList<Row>()
    private var sliceTitle = ""
    private var slicePage = 0
    private var inSlice = false
    /** Каталог не отдал этот срез (у BookLore нет жанров) — карточка «Нет раздела». */
    private var noSlice = false
    /** Название каталога из базы: шапка B16 и B64 — оно, а не название фида. */
    private var catTitle = ""
    /** Поле поиска (10.9): введённое и открыта ли клавиатура B48. */
    private var query = ""
    private var kbOpen = false
    /** Запрос, по которому сейчас показан список: по нему же возвращаются «×» и «Назад». */
    private var searchFor = ""
    /**
     * B57 «Нет подключения» (10.10): сети нет совсем (`Opds.online()` == false). Под диалогом
     * остаётся последний удачный фид из кэша — отсюда строка «Скачанные книги читаются и без сети».
     * Адрес, по которому шёл запрос, хранится в [curUrl]: по нему повторяет «Повторить».
     */
    private var offline = false
    private var curUrl = ""
    /** Был ли [curUrl] корневым фидом каталога: повтор должен идти так же. */
    private var curRoot = false
    /** Запрос поиска, который не прошёл из-за сети: «Повторить» повторит его, а не фид. */
    private var curSearch = ""
    private var waitNet = false
    private val noNet = NoNetDialog(ink, icons)
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
        // B57: под диалогом «Нет подключения» остаётся B16 с кэшем фида — экран затемняется,
        // как под листами и диалогами (замечание 15). Статус-бар и диалог не темнеют.
        val dim = if (offline) dimBegin(c) else -1
        ink.rect(c, 0f, 0f, W, H, T.BG)
        header(c)
        search(c)
        tabs(c)
        // Панель списка среза короче (B64/B66/B68: 1099.1 вместо 1136.2) — по шести строкам.
        if (sliceList) ink.convex(c, 41.1f, 516.4f, 1320.8f, 1099.1f, T.R_TILE)
        else ink.convex(c, 41.1f, 516.4f, 1320.8f, 1136.2f, T.R_TILE)
        // B57 сам называет причину: под диалогом карточку «Не удалось открыть» не рисуем,
        // иначе «Нет подключения» стоит дважды (макет B57 показывает под диалогом книги из кэша).
        if (loading) busy(c) else if (rows.isEmpty() && !offline) empty(c) else list(c)
        if (kbOpen) {
            // Лист B48 поверх панели: y 1385 в B21, здесь то же — панель 516.4…1652.6.
            ink.convex(c, -3f, SHEET_Y, 1409f, 520f, 66f)
            kb.draw(c)
        } else if (rows.isNotEmpty()) {
            // Пустой список (в том числе «ничего не найдено») — пагинации нет: в B44 её нет,
            // а «1 / 0» листать нечего.
            paging(c)
        }
        if (dim >= 0) dimEnd(c, dim)
        status.draw(c)
        if (offline) noNet.draw(c)
    }

    /**
     * Открыт список среза (B64/B66/B68), а не книги: шесть строк, шаг 184. Результаты поиска
     * рисуются как книги B16, даже если поиск начат из среза (сервер ищет по всему каталогу).
     */
    private val sliceList: Boolean get() = tab != 0 && !inSlice && searchFor.isEmpty()

    /**
     * Шапка. B16 и B64/B66/B68 называют каталог (из базы), B65/B67/B69 — сам срез
     * («Михаил Булгаков», «Мир Полудня», «Фантастика»); подзаголовок — [sub].
     */
    private fun header(c: Canvas) {
        ink.convex(c, 41.1f, 112.9f, 106.9f, 112.4f, R_BACK)
        icons.draw(c, "back", 71.3f, 143f, 47.5f, 49.5f)
        val head = if (inSlice) title.ifEmpty { catTitle } else catTitle.ifEmpty { title }
        ink.text(c, ink.ellipsize(head.ifEmpty { "Каталог" }, 900f, T.TS_TITLE, 700), 175.5f,
            T.base(105f, T.TS_TITLE, 1.2f), T.TS_TITLE, 700, T.G1)
        ink.text(c, ink.ellipsize(sub, 900f, T.TS_BODY_S, 600), 175.5f,
            T.base(181f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
    }

    private fun search(c: Canvas) {
        ink.inset(c, 43.9f, 248.6f, 1316.2f, 102.4f, T.R_TILE)
        icons.draw(c, "search", 87.8f, 277.9f, 43.9f, 43.9f)
        val base = T.base(275f, T.TS_LABEL_L, 1.25f)
        if (query.isEmpty()) {
            ink.text(c, "Поиск в каталоге", 153.6f, base, T.TS_LABEL_L, 500, T.G7)
            if (kbOpen) ink.rect(c, 153.6f, 272f, 4f, 56f, T.G1)
            return
        }
        val s = ink.ellipsize(query, 1060f, T.TS_LABEL_L, 600)
        ink.text(c, s, 153.6f, base, T.TS_LABEL_L, 600, T.G1)
        if (kbOpen) ink.rect(c, 157.6f + ink.measure(s, T.TS_LABEL_L, 600), 272f, 4f, 56f, T.G1)
        // «×» — круглая клавиша из B21 (1262,256 87×92): очистить запрос и вернуть корень.
        ink.convex(c, 1262f, 256f, 87f, 92f, 41f)
        icons.draw(c, "close", 1298.1f, 292.1f, 15.8f, 17.4f)
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
        val per = perPage()
        val from = page * per
        for (i in 0 until per) {
            val idx = from + i
            if (idx >= rows.size) break
            val r = rows[idx]
            if (sliceList) {
                // B64/B66/B68: строки 548, 732, 916, 1100, 1284, 1468 (шаг 184),
                // разделитель 237,y+147.5 1076×2.
                val y = SLICE_Y0 + i * SLICE_STEP
                sectionRow(c, r, y)
                if (idx < rows.size - 1) ink.rect(c, 237f, y + 147.5f, 1076f, 2f, T.G11)
                continue
            }
            val y = ROW_Y0 + i * ROW_STEP
            if (r.section != null) sectionRow(c, r, y) else bookRow(c, r, y)
            if (idx < rows.size - 1) ink.rect(c, 212.1f, y + 186.4f, 1104.2f, 1.8f, T.G11)
        }
    }

    /**
     * Строк на экране: пять по макету B16, четыре — пока открыта клавиатура B48 (лист с 1385,
     * пятая строка 1400…1588 уходит под него; в B21 по той же причине строки кончаются на 1375),
     * шесть — в списке среза (B64/B66/B68: 548…1468).
     */
    private fun perPage(): Int = when {
        // Список среза при открытой клавиатуре: 548, 732, 916, 1100 — 1100+149.5 = 1249.5,
        // а пятая строка 1284…1433.5 уходит под лист B48 (верх 1385).
        sliceList && kbOpen -> PER_PAGE_SLICE_KB
        sliceList -> PER_PAGE_SLICE
        kbOpen -> PER_PAGE_KB
        else -> PER_PAGE
    }

    /**
     * Раздел каталога (автор, серия, жанр) — строка B64/B66/B68: круг 88,y 115×120,
     * у авторов инициалы 36/700, у серий `list@38.4x40` в 126.8,y+39.1, у жанров
     * `library@38.4x40` там же; имя 42/700 в 237,y, описание 32/600 G4 в 237,y+62.5,
     * «next» 1302,y+47.
     */
    private fun sectionRow(c: Canvas, r: Row, y: Float) {
        ink.convex(c, 88f, y + 4.1f, 115f, 120f, 55f)
        val icon = when (tab) {
            Opds.Slice.SERIES -> "list@38.4x40"
            Opds.Slice.GENRES -> "library@38.4x40"
            else -> null
        }
        if (icon != null) {
            icons.draw(c, icon, 126.8f, y + 39.1f, 38.4f, 40f)
        } else {
            ink.textCenter(c, initials(r.title), 88f, 115f, T.base(y + 44.1f, T.TS_LABEL, 1.25f),
                T.TS_LABEL, 700, T.G1)
        }
        ink.text(c, ink.ellipsize(r.title, 900f, T.TS_ACTION, 700), 237f,
            T.base(y + 10f, T.TS_ACTION, 1.25f), T.TS_ACTION, 700, T.G1)
        if (r.formats.isNotEmpty()) {
            ink.text(c, ink.ellipsize(r.formats, 1040f, T.TS_BODY_S, 600), 237f,
                T.base(y + 62.5f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        }
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
        when (stateOf(url)) {
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
            // Файла нет (BookLore отдаёт такие книги: «нет загрузки») — кнопка не рисуется,
            // иначе она нажимается и молча ничего не делает.
            NO_FILE -> ink.text(c, "Нет файла", 1006.2f, T.base(y + 104f, T.TS_BODY_S, 1.27f),
                T.TS_BODY_S, 700, T.G5)
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
        // После поиска пусто — это «Ничего не найдено» из B21, а не «не удалось открыть».
        val miss = searchFor.isNotEmpty()
        if (noSlice) {
            empty.card(c, (W - EmptyState.CARD_W) / 2f, 700f, "library", 113.3f, 113.4f,
                "Нет раздела", "Каталог не отдал этот раздел")
            return
        }
        empty.card(c, (W - EmptyState.CARD_W) / 2f, 700f,
            if (miss) EmptyState.ICON_SEARCH else "library",
            if (miss) 36.2f else 113.3f, if (miss) 38.4f else 113.4f,
            when {
                why.isNotEmpty() -> "Не удалось открыть"
                miss -> "Ничего не найдено"
                sliceList -> "Каталог пуст"
                else -> "В каталоге пусто"
            },
            when {
                why.isNotEmpty() -> why
                miss -> "Проверьте написание или ищите по автору"
                sliceList -> "В этом разделе пока ничего нет"
                else -> ""
            })
    }

    private fun paging(c: Canvas) {
        ink.convex(c, 41.1f, 1708.3f, 121.6f, 127.1f, T.R_BUTTON)
        icons.draw(c, "back", 75.9f, 1743f, 53f, 55.2f)
        ink.textCenter(c, "${page + 1} / ${pages()}", 0f, W, T.base(1738.3f, T.TS_PAGE, 1.25f),
            T.TS_PAGE, 600, T.G1)
        ink.convexDark(c, 1240.4f, 1708.3f, 121.6f, 127.1f, T.R_BUTTON)
        icons.draw(c, "next@53x53", 1275.1f, 1743f, 53f, 53f)
    }

    private fun pages(): Int = (rows.size + perPage() - 1) / perPage()

    // ------------------------------------------------------------------ данные

    /** Записи фида → строки экрана: с навигационной ссылкой — раздел, иначе книга. */
    private fun rowsOf(f: Opds.Feed?): ArrayList<Row> {
        val list = ArrayList<Row>(32)
        if (f == null) return list
        for (e in f.entries) {
            val sec = e.section()
            val acq = e.acquisitions()
            if (sec != null && acq.isEmpty()) {
                list.add(Row(e.title, e.author, sliceSummary(e), e, sec))
            } else {
                val fmts = acq.mapNotNull { mimeName(it.type) }.distinct().joinToString(" · ")
                list.add(Row(e.title, e.author, fmts, e, null))
            }
        }
        return list
    }

    /**
     * Открыть фид в фоновом потоке: сеть может думать до 20 с, а UI на E-Ink перерисовывается
     * только дважды — «Открываем каталог…» и результат. Каталог берём по `act.catalogId`,
     * адрес — из базы; первый фид без названия записывает своё название в базу (B15).
     */
    private fun load(url: String, clear: Boolean, append: Boolean = false, root: Boolean = false) {
        loading = true
        why = ""
        curUrl = url
        curRoot = root
        curSearch = ""
        if (clear) {
            rows = ArrayList()
            page = 0
            searchFor = ""
        }
        redraw()
        Thread({
            val auth = db.catalog(catalogId)?.auth()
            // Сеть проверяем до запроса: `fetch` при её отсутствии молча отдаёт кэш, а по B57
            // пользователь должен узнать, что каталог открыт по кэшу, а не по сети.
            val on = opds.online()
            val r = opds.fetch(url, auth)
            val f = r.feed
            val list = rowsOf(f)
            post {
                feed = f
                // Корень каталога запоминаем отдельно: из него берётся шаблон поиска, когда
                // открыт раздел или показаны результаты поиска. Ошибка сети корень не сбрасывает.
                if (f != null && (root || base == null)) base = f
                // append: следующая порция фида дописывается к списку — до края 615 книг
                // иначе пришлось бы делать по десять тапов на каждые 50 записей сервера.
                if (append && f != null) {
                    rows = ArrayList(rows).apply { addAll(list) }
                } else {
                    rows = list
                }
                // Следующая страница дописана к списку среза — «Назад» из книг среза вернёт его целиком.
                if (sliceList && f != null) {
                    sliceFeed = f
                    sliceRows = rows
                }
                title = if (f != null && f.title.isNotEmpty()) f.title else title
                // Название каталога берём из корневого фида: у BookLore все фиды называют себя
                // «Grimmory Catalog», а у фида автора заголовок «Books by …» — в базу он не годится.
                if (f != null && root && f.title.isNotEmpty()) catTitle = f.title
                sub = subtitle()
                why = r.why ?: if (f == null) "Сервер не ответил" else ""
                if (f != null && f.title.isNotEmpty() && root && catalogId > 0) {
                    db.catalogTitle(catalogId, f.title)
                }
                loading = false
                setOffline(on)
                redraw()
            }
        }, "opds-feed").start()
    }

    /**
     * Открыть срез ([tab] 1…3, B64/B66/B68). Адрес берётся из корневого фида ([Opds.sliceHref]):
     * BookLore объявляет `/authors` и `/series` ссылками `rel="subsection"`. Среза нет — карточка
     * «Нет раздела» (жанров BookLore не отдаёт), в сеть не идём. Запрос — в фоне: фид авторов
     * у BookLore 25 КБ, а на 1 ГБ ОЗУ и 20-секундном таймауте блокировать UI нельзя.
     */
    private fun openSlice(kind: Int) {
        val root = base
        val url = root?.let { opds.findSlice(it, kind) }
        if (url == null) {
            noSlice = true
            why = ""
            searchFor = ""
            sliceFeed = null
            sliceTitle = ""
            rows = ArrayList()
            page = 0
            title = catTitle
            sub = TABS[kind]
            redraw()
            return
        }
        noSlice = false
        loading = true
        why = ""
        curUrl = url
        curRoot = false
        curSearch = ""
        rows = ArrayList()
        page = 0
        searchFor = ""
        title = catTitle
        sub = TABS[kind]
        redraw()
        Thread({
            val auth = db.catalog(catalogId)?.auth()
            val on = opds.online()
            val r = opds.fetch(url, auth)
            val f = r.feed
            val list = rowsOf(f)
            post {
                loading = false
                // «N книг» в строке среза: у автора это число его книг, у серии — книг серии.
                sliceFeed = f
                sliceRows = list
                feed = f
                rows = list
                page = 0
                why = r.why ?: if (f == null) "Сервер не ответил" else ""
                sub = subtitle()
                setOffline(on)
                redraw()
            }
        }, "opds-slice").start()
    }

    /**
     * Строки среза → подпись строки: «12 книг». У записей-навигаций своего счётчика книг нет
     * (OPDS его не задаёт), поэтому число берётся из `total` записи, если каталог его положил,
     * иначе — пусто: врать «0 книг» нельзя.
     */
    private fun sliceSummary(e: Opds.Entry): String {
        val s = e.summary
        // BookLore: «Books by X» / «Books in the X series» — это не число, а тот же заголовок.
        if (s.isEmpty() || s.startsWith("Books by") || s.startsWith("Books in the")) return ""
        return s
    }


    /**
     * B57 «Нет подключения» (10.10). Показываем, только когда сети нет совсем: отказ сервера
     * (404, 500) — это [why] в карточке B44, а не «Wi-Fi выключен». Подзаголовок диалога —
     * название каталога из базы (оно есть и до первого ответа).
     */
    private fun setOffline(net: Boolean) {
        val v = !net
        if (v == offline) return
        offline = v
        if (v) {
            kbOpen = false
            noNet.open(db.catalog(catalogId)?.label() ?: title)
            act.bigFrame("no net open")    // большой кадр в «Быстром» — полной перерисовкой
        } else {
            act.bigFrame("no net close")
        }
    }

    /**
     * Поиск по каталогу (10.9). Шаблон берётся из `rel="search"` фида ([Opds.searchTemplate]):
     * у Calibre это готовый `…/{searchTerms}`, у BookLore — описание `/search.opds`, откуда
     * ядро достаёт `/catalog?q={searchTerms}`. Ищем от корня каталога ([base]), даже если сейчас
     * открыт раздел: сервер всё равно ищет по всему каталогу.
     */
    private fun runSearch(q: String) {
        val src = base ?: feed
        if (src == null) {
            why = "Сначала откройте каталог"
            redraw()
            return
        }
        loading = true
        why = ""
        curSearch = q
        rows = ArrayList()
        page = 0
        redraw()
        Thread({
            val auth = db.catalog(catalogId)?.auth()
            val on = opds.online()
            val r = opds.search(src, q, auth)
            val f = r.feed
            val list = rowsOf(f)
            post {
                loading = false
                if (f != null) {
                    feed = f
                    rows = list
                    searchFor = q
                    sub = subtitle()
                    why = ""
                } else {
                    rows = ArrayList()
                    searchFor = ""
                    why = r.why ?: "Сервер не ответил"
                }
                setOffline(on)
                redraw()
            }
        }, "opds-search").start()
    }

    /**
     * Подзаголовок шапки. B16 — «Новые · 615 книг»; список среза (B64/B66/B68) — «Авторы · 442»
     * (в макете без «книг»: считается число записей среза); книги среза (B65/B67/B69) —
     * «Авторы · 12 книг», где число — книги, а не записи.
     */
    private fun subtitle(): String {
        if (searchFor.isNotEmpty()) {
            if (rows.isEmpty()) return "Поиск · ничего не найдено"
            val f = feed
            val n = if (f != null && f.total > 0) f.total else rows.size
            return "Поиск · " + Words.books(n)
        }
        val f = feed ?: return TABS[tab]
        if (sliceList) {
            val n = if (f.total > 0) f.total else rows.size
            return if (n == 0) TABS[tab] else TABS[tab] + " · $n"
        }
        val books = rows.count { it.section == null }
        val n = if (f.total > 0) f.total else books
        if (n == 0) return TABS[tab]
        return TABS[tab] + " · " + Words.books(n)
    }

    /** MIME → подпись формата для плашки («application/fb2+zip» → «FB2»). */
    private fun mimeName(type: String): String? {
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

    /** Состояние строки: очередь загрузок важнее «уже в библиотеке», нет ссылки — [NO_FILE]. */
    private fun stateOf(url: String?): Int {
        if (url == null) return NO_FILE
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

    /** Клавиатура B48: «Готово» заменено на «Найти» — по нему и уходит запрос к серверу. */
    private fun onKey(x: Float, y: Float) {
        val k = kb.hit(x, y) ?: return
        when (k.kind) {
            Keyboard.K_DONE -> {
                kbOpen = false
                page = 0
                val q = query.trim()
                if (q.isEmpty()) {
                    redraw()
                } else {
                    runSearch(q)
                }
            }
            Keyboard.K_DEL -> {
                if (query.isEmpty()) return
                query = query.dropLast(1)
                redraw()
            }
            else -> {
                val s = kb.press(k)
                if (s != null && query.length < 60) {
                    query += s
                    redraw()
                } else {
                    redraw()
                }
            }
        }
    }

    /**
     * «×» в поле поиска: пустой запрос и то, что было до поиска. Из среза возвращаемся к списку
     * среза (он в памяти), из корня — к корневому фиду каталога.
     */
    private fun clearSearch() {
        query = ""
        searchFor = ""
        curSearch = ""
        kbOpen = false
        page = 0
        if (tab != 0) {
            noSlice = false
            if (inSlice) {
                backToList()
                return
            }
            if (sliceFeed != null) {
                feed = sliceFeed
                rows = sliceRows
                page = slicePage
                why = ""
                curUrl = sliceFeed?.url ?: ""
                curRoot = false
                sub = subtitle()
                redraw()
                return
            }
        }
        showRoot()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
        catalogId = act.catalogId
        val c0 = db.catalog(catalogId)
        catTitle = c0?.label() ?: ""
        title = catTitle
        sub = ""
        base = null
        query = ""
        searchFor = ""
        kbOpen = false
        offline = false
        curUrl = ""
        curRoot = false
        curSearch = ""
        waitNet = false
        // Срезы (10.11): экран открывается на «Новые», списки срезов — заново с сервера.
        tab = 0
        sliceFeed = null
        sliceTitle = ""
        inSlice = false
        noSlice = false
        page = 0
        kb.setAction("Найти")
        if (c0 == null) {
            why = "Каталог не выбран"
            return
        }
        load(c0.url, clear = true, root = true)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        status.detach()
        super.onDetachedFromWindow()
    }

    /**
     * Касание под B57: «Повторить» — снова тот же фид (или тот же поиск), «Настройки Wi-Fi» —
     * системный экран Wi-Fi; по возврате из него (фокус окна) экран сам проверит сеть и повторит
     * запрос. Мимо диалога — ничего: закрыть его без сети нечем.
     */
    private fun offlineUp(x: Float, y: Float) {
        when (noNet.hit(x, y)) {
            NoNetDialog.ACT_RETRY -> retry()
            NoNetDialog.ACT_WIFI -> {
                try {
                    waitNet = true
                    act.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                } catch (t: Throwable) {
                    waitNet = false
                    Trace.step("wifi settings FAIL: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
            // В B57 закрывать диалог нечем, а без сети в каталоге делать нечего: «Назад» в шапке
            // (мимо диалога, 41.1,112.9) оставлен рабочим, чтобы со экрана можно было уйти.
            else -> if (inRect(x, y, 41.1f, 112.9f, 106.9f, 112.4f)) act.pop()
        }
    }

    /** Повторить запрос, который не прошёл: результаты поиска — поиском, иначе — фидом. */
    private fun retry() {
        if (curSearch.isNotEmpty()) {
            runSearch(curSearch)
            return
        }
        val url = curUrl.ifEmpty { db.catalog(catalogId)?.url ?: "" }
        if (url.isEmpty()) {
            why = "Каталог не выбран"
            redraw()
            return
        }
        load(url, clear = true, root = curRoot)
    }

    /**
     * Вернулись из «Настроек Wi-Fi»: чистим панель после системного экрана (иначе E-Ink
     * оставляет след) и повторяем запрос — даже если сети нет, так пользователь увидит,
     * что приложение её проверило.
     */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus || parent == null) return
        if (!waitNet) return
        waitNet = false
        act.cleanScreen()
        retry()
    }

    private fun inRect(x: Float, y: Float, rx: Float, ry: Float, rw: Float, rh: Float) =
        x >= rx && x <= rx + rw && y >= ry && y <= ry + rh

    override fun onUp(x: Float, y: Float) {
        // B57: пока сети нет, экран под диалогом не нажимается — только две кнопки диалога.
        if (offline) {
            offlineUp(x, y)
            return
        }
        // Клавиатура листом: пока открыта, касания ниже её верха достаются только ей.
        if (kbOpen && y >= SHEET_Y) {
            onKey(x, y)
            return
        }
        if (inRect(x, y, 41.1f, 112.9f, 106.9f, 112.4f)) {
            // B65/B67/B69: «Назад» возвращает к списку среза (он в памяти), а не в B14 —
            // иначе из книг автора выходишь сразу из каталога.
            if (inSlice) {
                backToList()
                return
            }
            act.pop()
            return
        }
        // «×» в поле: снять запрос и вернуть корневой фид каталога.
        if (inRect(x, y, 1262f, 256f, 87f, 92f) && query.isNotEmpty()) {
            clearSearch()
            return
        }
        // Поле поиска: открыть клавиатуру B48 (в макете поле есть, поведения нет — как в B21).
        if (inRect(x, y, 43.9f, 248.6f, 1316.2f, 102.4f)) {
            if (!kbOpen) {
                kbOpen = true
                kb.setAction("Найти")
                page = 0
                redraw()
            }
            return
        }
        // Разделы: 0 — «Новые» (обычный фид B16), 1…3 — срезы каталога (B64/B66/B68).
        for (i in TABS.indices) {
            if (!inRect(x, y, SEG_X0 + i * SEG_STEP, 387.6f, SEG_W, 87.7f)) continue
            if (i == tab) return
            tab = i
            inSlice = false
            page = 0
            kbOpen = false
            if (i == 0) {
                showRoot()
            } else {
                openSlice(i)
            }
            return
        }
        // Пагинация внизу (на пустом списке её нет — как и в разметке).
        if (rows.isNotEmpty() && inRect(x, y, 41.1f, 1708.3f, 121.6f, 127.1f)) {
            if (page > 0) {
                page--
                redraw()
            }
            return
        }
        if (rows.isNotEmpty() && inRect(x, y, 1240.4f, 1708.3f, 121.6f, 127.1f)) {
            val f = feed
            if (page < pages() - 1) {
                page++
                redraw()
            } else if (f?.next != null) {
                // Дописываем следующую порцию и листаем на неё — список не теряется.
                page++
                load(f.next, clear = false, append = true)
            }
            return
        }
        // Строки каталога: слева (иконка, название) — открыть раздел или книгу, справа — скачать.
        val per = perPage()
        val from = page * per
        for (i in 0 until per) {
            val idx = from + i
            if (idx >= rows.size) break
            val row = rows[idx]
            if (sliceList) {
                // B64/B66/B68: строка среза — вся полоса 88,y 1225×149.5 ведёт к книгам среза.
                val rowY = SLICE_Y0 + i * SLICE_STEP
                if (inRect(x, y, 88f, rowY, 1225f, 149.5f)) openSliceBooks(row)
                return
            }
            val rowY = ROW_Y0 + i * ROW_STEP
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

    /** «Новые» (сегмент 0): корневой фид каталога — в памяти или с сервера. */
    private fun showRoot() {
        tab = 0
        noSlice = false
        inSlice = false
        sliceTitle = ""
        val root = base
        if (root != null && !root.books) {
            feed = root
            rows = rowsOf(root)
            page = 0
            why = ""
            curUrl = root.url
            curRoot = true
            sub = subtitle()
            redraw()
            return
        }
        val c0 = db.catalog(catalogId)
        if (c0 != null) load(c0.url, clear = true, root = true)
        else redraw()
    }

    /**
     * Строка среза → книги среза (B65/B67/B69). Фид книг отдельный от [feed]: «Назад» из него
     * возвращает к списку среза ([backToList]), а не перезагружает список с сервера.
     */
    private fun openSliceBooks(row: Row) {
        val link = row.section ?: return
        // Список среза запоминаем целиком: по «Назад» вернёмся к той же странице и той же ленте.
        sliceFeed = feed
        sliceRows = ArrayList(rows)
        slicePage = page
        loading = true
        why = ""
        curUrl = link.href
        curRoot = false
        curSearch = ""
        inSlice = true
        sliceTitle = row.title
        rows = ArrayList()
        page = 0
        searchFor = ""
        title = row.title
        sub = subtitle()
        redraw()
        Thread({
            val auth = db.catalog(catalogId)?.auth()
            val on = opds.online()
            val r = opds.fetch(link.href, auth)
            val f = r.feed
            val list = rowsOf(f)
            post {
                loading = false
                feed = f
                rows = list
                title = row.title
                sub = subtitle()
                why = r.why ?: if (f == null) "Сервер не ответил" else ""
                setOffline(on)
                redraw()
            }
        }, "opds-slice-books").start()
    }

    /** «Назад» из книг среза: список среза остался в памяти — без запроса к серверу. */
    private fun backToList() {
        inSlice = false
        feed = sliceFeed
        rows = sliceRows
        page = slicePage
        why = ""
        title = catTitle
        curUrl = sliceFeed?.url ?: ""
        curRoot = false
        curSearch = ""
        searchFor = ""
        sub = subtitle()
        redraw()
    }

    /**
     * Нажать «скачать»: в очередь идёт первый формат, который читалка умеет открывать
     * (FB2, EPUB…), а не PDF — его в читалке нет (решение 2026-11-11).
     */
    private fun download(row: Row) {
        val links = row.entry.acquisitions()
        val l = links.firstOrNull { mimeName(it.type) != null } ?: links.firstOrNull() ?: return
        val fmt = mimeName(l.type) ?: row.formats.substringBefore(' ').ifEmpty { "FB2" }
        downloads.add(l.href, row.title, fmt, db.catalog(catalogId)?.auth())
        queue = downloads.items()
        removeCallbacks(tick)
        postDelayed(tick, TICK_MS)
        redraw()
    }

    private companion object {
        const val ROW_Y0 = 543.9f
        const val ROW_STEP = 214f
        const val PER_PAGE = 5
        /** Пока открыта клавиатура: строки кончаются на 1236.2, лист B48 начинается с 1385. */
        const val PER_PAGE_KB = 4
        /**
         * Список среза (B64/B66/B68): строки 548, 732, 916, 1100, 1284, 1468 — шаг 184,
         * шесть строк («1 / 14» при 84 авторах, «1 / 7» при 37 сериях, «1 / 2» при 12 жанрах).
         */
        const val SLICE_Y0 = 548f
        const val SLICE_STEP = 184f
        const val PER_PAGE_SLICE = 6
        /** С клавиатурой B48 (лист с 1385): 548, 732, 916, 1100 — пятая строка 1284 уходит под лист. */
        const val PER_PAGE_SLICE_KB = 4
        /** Верх листа клавиатуры в B21 — тот же, что здесь: 1385. */
        const val SHEET_Y = 1385f
        const val R_BACK = 51.2f
        const val SEG_X0 = 48.4f
        const val SEG_STEP = 325.4f
        const val SEG_W = 325.4f
        const val TS_BOOK = 42f
        /** Опрос очереди загрузок — раз в секунду, как на B18. */
        const val TICK_MS = 1000L
        const val NONE = -1
        /** Файла для скачивания нет — у записи нет ссылки получения. */
        const val NO_FILE = 101
        /** Строка уже есть в библиотеке (по адресу мы этого не знаем) — рисуем как «В библиотеке». */
        const val IN_LIB = 100
        val TABS = arrayOf("Новые", "Авторы", "Серии", "Жанры")
    }
}
