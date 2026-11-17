package ru.ogrpfsense.dexpm8.reader

import android.graphics.Canvas
import ru.ogrpfsense.dexpm8.core.LibraryDb
import ru.ogrpfsense.dexpm8.ui.Icons
import ru.ogrpfsense.dexpm8.ui.Ink
import ru.ogrpfsense.dexpm8.ui.Keyboard
import ru.ogrpfsense.dexpm8.ui.T

/**
 * B28 · «Поиск по книге» (R4.3). Числа — `design/v5/B28.json`.
 *
 *   шапка      «Назад» выпуклая 41,113.5 107×112 (иконка 12.3×26.6); «Поиск по книге» 176,105
 *              60/700 G1; подзаголовок 176,181 32/600 G4 — «Анна Каренина · найдено 38»
 *   поле       вдавленное 44,249 1316×102 (r 44, дно G11); лупа 95.6,284.9 32×33.9; запрос
 *              154,274.6 38/600 G1; «Очистить» — круглая клавиша 1262,256 87×92, `close` 15.8×17.4
 *   вкладки    желоб 44,388 1316×88 (вдавленный, дно G11 r 41); активный сегмент 48,392 436×83
 *              (выпуклый, r 36.5); подписи 34 в 48 / 484 / 920 шириной 431 — активная 700 G1,
 *              остальные 600 G5
 *   панель     выпуклая 41,516 1321×1150 (r 51): тень G10, блик G15, поверхность G14
 *   строка     «Место» 95,556 30/700 G5 («Стр. 3 · Часть 1, глава I»); фрагмент 95,604 1214
 *              Literata 38/400 lh 1.4, не больше двух строк; `next` 11×23.8 в 1298,563;
 *              разделитель G11 1214×2 в 95,734. Шаг строк — 210 (556 → 766 → 976 → 1186)
 *   пагинация  «1 / 8» 44/600 по центру y 1738.3; клавиши блик Ø117 в 41.1 и 1240.4, y 1708.3
 *
 * Отступления от снимка (макет расходится сам с собой):
 *  - пятая строка в снимке стоит на 1420, то есть с шагом 234, а первые четыре — 210. Взят 210:
 *    он задан четырьмя строками и разделителем на 178;
 *  - в панели 5 строк: последняя в снимке обрезана краем панели (её высота 154 вместо 180).
 *
 * Клавиатуры на B28 нет (как и на B21) — рисуем [Keyboard] на листе с y 1385: без неё поле
 * набирать нечем. Пока она открыта, строки кончаются на 1375 (4 строки), пагинация скрыта.
 *
 * Поиск стоит денег: `Engine.search` на 420 страницах — около секунды (замер R4.3), поэтому он
 * запускается по тёмной клавише «Найти» и при смене вкладки, а не после каждой буквы (на B21
 * поиск идёт по базе, там он дешёвый). Набранное, но не найденное, помечается [stale]: старые
 * результаты от другого запроса на экране не висят.
 *
 * Вкладки:
 *  - «Вся книга» — все совпадения движка (до 500);
 *  - «Текущая глава» — те же совпадения, отфильтрованные по страницам главы; нет оглавления —
 *    границ главы нет, вкладка показывает всю книгу;
 *  - «Заметки» — цитаты и заметки книги из таблицы `bookmarks` (вид [LibraryDb.MARK_QUOTE] и
 *    [LibraryDb.MARK_NOTE]).
 *
 * Состояние (запрос, вкладка, страница, результаты) живёт здесь, как у `SearchScreen` B21;
 * экран читалки передаёт данные книги в [draw] и выполняет действия по коду [hit].
 */
class SearchBookScreen(private val ink: Ink, private val icons: Icons) {

    /** Совпадение поиска: страница, фрагмент и позиция движка (xpointer) для перехода. */
    class Hit(val page: Int, val text: String, val pos: String?)

    private val kb = Keyboard(ink)

    /** Набранный запрос; пусто — искать нечего. */
    var query = ""
        private set

    /** Открытая вкладка: [TAB_BOOK] / [TAB_CHAPTER] / [TAB_NOTES]. */
    var tab = TAB_BOOK
        private set

    /** Страница списка. */
    var page = 0
        private set

    /** Клавиатура на листе: открыта при входе, как на B21. */
    var kbOpen = true
        private set

    /** Индекс строки, по которой коснулись в последнем [hit] (−1 — никто). */
    var chosen = -1
        private set

    private var hits: List<Hit> = emptyList()
    private var notes: List<LibraryDb.Mark> = emptyList()

    /** Оглавление книги для подписей «Место»: страницы и названия пунктов по порядку. */
    private var chapters: List<Int> = emptyList()
    private var titles: List<String> = emptyList()

    /** Идёт поиск (запрос ушёл в движок, ответа ещё нет). */
    private var searching = false

    /**
     * Запрос изменился после последнего поиска: показанные [hits] — от другого запроса.
     * Тогда список пуст, а шапка просит нажать «Найти».
     */
    private var stale = false

    /** Запрос, по которому искали последний раз. */
    private var searched = ""

    /**
     * Границы текущей главы, как их передал экран читалки в последнем [draw]/[hit]. Нужны
     * счётчику страниц: на вкладке «Текущая глава» список короче общего, и «1 / 100»
     * по всем совпадениям было бы враньём.
     */
    private var chapterFrom = -1
    private var chapterTo = -1

    /** Открыть экран заново: пустое поле, клавиатура, вкладка «Вся книга». */
    fun open() {
        query = ""
        tab = TAB_BOOK
        page = 0
        kbOpen = true
        chosen = -1
        hits = emptyList()
        notes = emptyList()
        searching = false
        stale = false
        searched = ""
        kb.setAction(ACTION_SEARCH)
    }

    /** Пришли результаты: [hits] — совпадения движка в порядке текста, [q] — по какому запросу. */
    fun setHits(hits: List<Hit>, q: String) {
        this.hits = hits
        searched = q
        searching = false
        stale = false
        page = 0
    }

    /** Поиск не удался (код ошибки движка) — список пуст. */
    fun failSearch() {
        hits = emptyList()
        searched = query
        searching = false
        stale = false
        page = 0
    }

    /** Сколько записей на вкладке «Заметки» (для трассы при открытии B28). */
    fun notesCount(): Int = notes.size

    /** Заметки и цитаты книги для третьей вкладки (свежие сверху). */
    fun setNotes(notes: List<LibraryDb.Mark>) {
        this.notes = notes
        page = 0
    }

    /** Нужно ли запустить поиск: запрос есть, а результаты от другого запроса. */
    fun needSearch(): Boolean = query.isNotBlank() && (stale || searching || searched != query)

    /** Запрос для движка (тот, что набран) — читалка передаёт его в `Engine.search`. */
    fun searchQuery(): String = query

    /**
     * Запрос пришёл готовым (B50 «Найти» из меню выделения): поле заполнено, клавиатура
     * не нужна — слово уже набрано, а поиск запускает сама читалка.
     */
    fun setQuery(q: String) {
        query = q.take(MAX_QUERY)
        kbOpen = false
        page = 0
        hits = emptyList()
        stale = true
        searched = ""
    }

    /** Поиск пошёл: до ответа шапка показывает «ищем…» вместо числа. */
    fun markSearching() {
        searching = true
        stale = false
    }

    /** Оглавление книги для подписей «Место»: страницы и названия пунктов по порядку. */
    fun setChapters(pages: List<Int>, names: List<String>) {
        chapters = pages
        titles = names
    }

    // ------------------------------------------------------------------ рисование

    /**
     * Нарисовать экран. [book] — название книги для подзаголовка, [chapterStart]/[chapterEnd] —
     * страницы текущей главы для вкладки «Текущая глава» (−1 — главы нет).
     */
    fun draw(c: Canvas, book: String, chapterStart: Int, chapterEnd: Int) {
        chapterFrom = chapterStart
        chapterTo = chapterEnd
        header(c, book)
        field(c)
        tabs(c)
        panel(c, chapterStart, chapterEnd)
        if (kbOpen) {
            ink.convex(c, -3f, SHEET_Y, 1409f, 520f, T.R_BUTTON)
            kb.draw(c)
        } else {
            pager(c)
        }
    }

    private fun header(c: Canvas, book: String) {
        ink.convex(c, BACK_X, BACK_Y, BACK_W, BACK_H, T.R_TILE)
        icons.drawRound(c, "back@12.3x26.6", BACK_X, BACK_Y, BACK_W, BACK_H, 12.3f, 26.6f)
        ink.text(c, "Поиск по книге", TITLE_X, T.base(105f, T.TS_TITLE, 1.25f),
            T.TS_TITLE, 700, T.G1)
        ink.text(c, ink.ellipsize(sub(book), SUB_W, T.TS_BODY_S, 600), TITLE_X,
            T.base(181f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
    }

    /** Подзаголовок шапки, как «Анна Каренина · найдено 38» в снимке. */
    private fun sub(book: String): String {
        val n = if (tab == TAB_CHAPTER) shown(chapterFrom, chapterTo).size else hits.size
        return when {
            searching -> "$book · ищем…"
            stale -> "$book · «Найти» — поиск"
            query.isBlank() -> book
            n == 0 -> "$book · совпадений нет"
            else -> "$book · найдено $n"
        }
    }

    private fun field(c: Canvas) {
        ink.inset(c, FIELD_X, FIELD_Y, FIELD_W, FIELD_H, T.R_GROOVE)
        icons.draw(c, "search", 95.6f, 284.9f, 32f, 33.9f)
        val base = T.base(274.6f, T.TS_LABEL_L, 1.3f)
        if (query.isEmpty()) {
            ink.text(c, PLACEHOLDER, 154f, base, T.TS_LABEL_L, 500, T.G6)
            if (kbOpen) ink.rect(c, 154f, 272f, 4f, 56f, T.G1)
            return
        }
        val s = ink.ellipsize(query, QUERY_W, T.TS_LABEL_L, 600)
        ink.text(c, s, 154f, base, T.TS_LABEL_L, 600, T.G1)
        if (kbOpen) ink.rect(c, 158f + ink.measure(s, T.TS_LABEL_L, 600), 272f, 4f, 56f, T.G1)
        ink.convex(c, CLEAR_X, CLEAR_Y, CLEAR_W, CLEAR_H, T.R_GROOVE)
        icons.drawRound(c, "close", CLEAR_X, CLEAR_Y, CLEAR_W, CLEAR_H, 15.8f, 17.4f)
    }

    private fun tabs(c: Canvas) {
        ink.inset(c, TAB_GROOVE_X, TAB_GROOVE_Y, TAB_GROOVE_W, TAB_GROOVE_H, T.R_GROOVE)
        ink.convex(c, SEG_X + TAB_W * tab, SEG_Y, SEG_W, SEG_H, T.R_SEGMENT)
        val base = T.base(410f, T.TS_BODY, 1.25f)
        for (i in TAB_LABELS.indices) {
            val on = i == tab
            ink.textCenter(c, TAB_LABELS[i], TAB_X + TAB_W * i, TAB_LABEL_W, base, T.TS_BODY,
                if (on) 700 else 600, if (on) T.G1 else T.G5)
        }
    }

    private fun panel(c: Canvas, chapterStart: Int, chapterEnd: Int) {
        ink.convex(c, PANEL_X, PANEL_Y, PANEL_W, PANEL_H, T.R_TILE)
        if (tab == TAB_NOTES) {
            // Номера заметок — от начала книги, тем же правилом, что метки на полях B52:
            // свежие сверху, поэтому номер растёт снизу вверх.
            val numbers = LibraryDb.noteNumbers(notes)
            val first = page * rows()
            val last = minOf(notes.size, first + rows())
            for (i in first until last) {
                noteRow(c, notes[i], numbers[notes[i].id] ?: 0, ROW_TOP + (i - first) * ROW_STEP)
            }
            if (notes.isEmpty()) empty(c, EMPTY_NOTES, EMPTY_NOTES_HINT)
            return
        }
        val list = shown(chapterStart, chapterEnd)
        val first = page * rows()
        val last = minOf(list.size, first + rows())
        for (i in first until last) hitRow(c, list[i], ROW_TOP + (i - first) * ROW_STEP)
        if (list.isEmpty()) {
            when {
                query.isBlank() -> empty(c, EMPTY_QUERY, EMPTY_QUERY_HINT)
                stale -> empty(c, EMPTY_STALE, EMPTY_STALE_HINT)
                searching -> empty(c, EMPTY_SEARCHING, "")
                else -> empty(c, EMPTY_NONE, EMPTY_NONE_HINT)
            }
        }
    }

    /** Строка совпадения: «Место», фрагмент в две строки Literata и шеврон. */
    private fun hitRow(c: Canvas, h: Hit, top: Float) {
        ink.text(c, ink.ellipsize(place(h.page), PLACE_W, T.TS_META, 700), ROW_X,
            T.base(top, T.TS_META, 1.25f), T.TS_META, 700, T.G5)
        icons.draw(c, "next@11x23.8", NEXT_X, top + NEXT_DY, 11f, 23.8f)
        val quote = h.text.replace('\u00A0', ' ').replace(WS, " ").trim()
        val lit = T.literata(ink.res, 400)
        ink.paragraph(c, quote, ROW_X, top + QUOTE_DY, FRAG_W, T.TS_LABEL_L, 400, T.G1,
            0f, lit, QUOTE_LINES)
        ink.rect(c, ROW_X, top + SEP_DY, SEP_W, 2f, T.G11)
    }

    /**
     * Строка заметки: то же место, а под ним текст заметки (нет заметки — выделение).
     *
     * [number] — номер заметки от начала книги, тот же, что стоит на полях B52 (`0` — цитата,
     * у неё номера нет). В снимке B28 номера нет: без него «Заметка 2» на полях не с чем
     * сверить, а метка времени в строке ничего не говорит (решение 2026-11-17).
     */
    private fun noteRow(c: Canvas, m: LibraryDb.Mark, number: Int, top: Float) {
        val head = if (number > 0) "№ $number · ${place(m.page)}" else place(m.page)
        ink.text(c, ink.ellipsize(head, PLACE_W, T.TS_META, 700), ROW_X,
            T.base(top, T.TS_META, 1.25f), T.TS_META, 700, T.G5)
        icons.draw(c, "next@11x23.8", NEXT_X, top + NEXT_DY, 11f, 23.8f)
        val body = (m.note ?: m.text.orEmpty()).replace('\u00A0', ' ').replace(WS, " ").trim()
        val lit = T.literata(ink.res, 400)
        ink.paragraph(c, body, ROW_X, top + QUOTE_DY, FRAG_W, T.TS_LABEL_L, 400, T.G1,
            0f, lit, QUOTE_LINES)
        ink.rect(c, ROW_X, top + SEP_DY, SEP_W, 2f, T.G11)
    }

    /** «Стр. 3 · Часть 1, глава I» — номер с начала книги, как в снимке. Без главы — только номер. */
    private fun place(page: Int): String {
        val ch = chapterOf(page)
        return if (ch.isEmpty()) "Стр. ${page + 1}" else "Стр. ${page + 1} · $ch"
    }

    /** Название главы для страницы — та же логика, что у колонтитула B3. */
    private fun chapterOf(page: Int): String {
        var idx = -1
        for (i in chapters.indices) {
            val p = chapters[i]
            if (p in 0..page) idx = i else if (p > page) break
        }
        return if (idx < 0) "" else titles[idx]
    }

    /** Пустой список: в снимке B28 такого вида нет — заголовок и подсказка по центру панели. */
    private fun empty(c: Canvas, title: String, hint: String) {
        ink.textCenter(c, title, 0f, W, T.base(EMPTY_Y, T.TS_PAGE, 1.25f), T.TS_PAGE, 700, T.G4)
        if (hint.isEmpty()) return
        ink.textCenter(c, hint, 0f, W, T.base(EMPTY_Y + 70f, T.TS_BODY_S, 1.25f),
            T.TS_BODY_S, 600, T.G5)
    }

    private fun pager(c: Canvas) {
        circle(c, 41.1f, 1708.3f, T.G14, T.G15, T.G10, 117f)
        icons.drawRound(c, "back@54.8x57.1", 41.1f, 1708.3f, 117f + T.SHADOW_DX,
            117f + T.SHADOW_DY, 54.8f, 57.1f)
        circle(c, 1240.4f, 1708.3f, T.G3, T.G6, T.G8, 117f)
        icons.drawRound(c, "next@53x53", 1240.4f, 1708.3f, 117f + T.SHADOW_DX,
            117f + T.SHADOW_DY, 53f, 53f)
        ink.textCenter(c, "${page + 1} / ${pageCount()}", 0f, W,
            T.base(1738.3f, T.TS_PAGE, 1.25f), T.TS_PAGE, 600, T.G1)
    }

    /** Круглая клавиша: блик Ø[d] в ([x], [y]); у тёмной — G3/G6/G8, как `Ink.convexDark`. */
    private fun circle(c: Canvas, x: Float, y: Float, surface: Int, hi: Int, shadow: Int, d: Float) =
        ink.convex(c, x, y, d + T.SHADOW_DX, d + T.SHADOW_DY, d / 2f, surface, hi, shadow)

    // ------------------------------------------------------------------ список

    /** Строк на страницу: с клавиатурой панель кончается на 1375 — влезает 4, без неё 5. */
    private fun rows(): Int = if (kbOpen) ROWS_KB else ROWS

    /** Список открытой вкладки: совпадения, отфильтрованные по главе. */
    private fun shown(chapterStart: Int, chapterEnd: Int): List<Hit> {
        if (tab != TAB_CHAPTER) return hits
        // Оглавления нет — границ главы нет: вкладка показывает всю книгу.
        if (chapterStart < 0) return hits
        val end = if (chapterEnd < 0) chapterStart else chapterEnd
        return hits.filter { it.page in chapterStart..end }
    }

    /** Сколько страниц у списка открытой вкладки. */
    private fun pageCount(): Int {
        val n = if (tab == TAB_NOTES) notes.size else shown(chapterFrom, chapterTo).size
        val r = rows()
        return if (n <= 0) 1 else (n + r - 1) / r
    }

    // ------------------------------------------------------------------ касания

    /**
     * Касание в дизайн-px. Возвращает `ACT_*`; [ACT_NONE] — мимо (и тогда экран читалки
     * закрывает поиск, как меню B9).
     *
     * [chapterStart]/[chapterEnd] нужны, чтобы попасть в строку на вкладке «Текущая глава»:
     * список там короче общего.
     */
    fun hit(x: Float, y: Float, chapterStart: Int, chapterEnd: Int): Int {
        chosen = -1
        if (kbOpen && y >= Keyboard.TOP) return type(x, y)
        if (y >= BACK_Y && y <= BACK_Y + BACK_H &&
            x >= BACK_X && x <= BACK_X + BACK_W + T.SHADOW_DX) {
            return ACT_CLOSE
        }
        // «Очистить» — крестик в поле: он лежит внутри поля, поэтому проверяется первым.
        if (query.isNotEmpty() && y >= CLEAR_Y && y <= CLEAR_Y + CLEAR_H &&
            x >= CLEAR_X && x <= CLEAR_X + CLEAR_W + T.SHADOW_DX) {
            query = ""
            hits = emptyList()
            stale = false
            searched = ""
            page = 0
            return ACT_CHANGED
        }
        // Поле поиска — открыть клавиатуру (в снимке поле нажимается целиком).
        if (y >= FIELD_Y && y <= FIELD_Y + FIELD_H && x >= FIELD_X && x <= FIELD_X + FIELD_W) {
            if (kbOpen) return ACT_NONE
            kbOpen = true
            return ACT_CHANGED
        }
        if (y in TAB_GROOVE_Y..(TAB_GROOVE_Y + TAB_GROOVE_H)) {
            val i = ((x - TAB_X) / TAB_W).toInt()
            if (x < TAB_X || i !in TAB_LABELS.indices) return ACT_NONE
            if (i == tab) return ACT_NONE
            tab = i
            page = 0
            // Смена вкладки — тоже повод искать: человек мог набрать и не нажать «Найти».
            return ACT_TAB + i
        }
        if (!kbOpen && y in 1708.3f..1825.4f) {
            if (x <= 170f) return if (page > 0) ACT_PREV else ACT_NONE
            if (x >= 1240.4f) return if (page + 1 < pageCount()) ACT_NEXT else ACT_NONE
            return ACT_NONE
        }
        val top = ROW_TOP - ROW_STEP / 2f
        if (y < top || y > ROW_TOP + ROW_STEP * rows()) return ACT_NONE
        val i = page * rows() + ((y - top) / ROW_STEP).toInt()
        if (tab == TAB_NOTES) {
            if (i < 0 || i >= notes.size) return ACT_NONE
            chosen = i
            return ACT_NOTE
        }
        val list = shown(chapterStart, chapterEnd)
        if (i < 0 || i >= list.size) return ACT_NONE
        chosen = i
        return ACT_OPEN
    }

    /**
     * Клавиша [Keyboard]: буквы и пробел дописывают запрос (поиск — по «Найти»), «←» стирает
     * последний символ. Тёмная клавиша закрывает клавиатуру и просит запустить поиск.
     */
    private fun type(x: Float, y: Float): Int {
        val k = kb.hit(x, y) ?: return ACT_NONE
        when (k.kind) {
            Keyboard.K_DONE -> {
                kbOpen = false
                if (query.isBlank()) {
                    hits = emptyList()
                    stale = false
                    return ACT_CHANGED
                }
                return ACT_SEARCH
            }
            Keyboard.K_DEL -> {
                if (query.isEmpty()) return ACT_NONE
                query = query.dropLast(1)
                stale = query != searched
                if (query.isEmpty()) hits = emptyList()
            }
            else -> {
                val s = kb.press(k)
                if (s != null && query.length < MAX_QUERY) {
                    query += s
                    stale = query != searched
                }
            }
        }
        return ACT_CHANGED
    }

    /** Листнуть список вкладки на страницу назад (кнопка пагинации). */
    fun prev() {
        if (page > 0) page--
    }

    /** Листнуть список вкладки на страницу вперёд. */
    fun next() {
        if (page + 1 < pageCount()) page++
    }

    /** Строка открытой вкладки, по которой коснулись: совпадение или заметка. */
    fun hitAt(chapterStart: Int, chapterEnd: Int): Hit? =
        if (tab == TAB_NOTES) null else shown(chapterStart, chapterEnd).getOrNull(chosen)

    /** Заметка открытой вкладки, по которой коснулись. */
    fun noteAt(): LibraryDb.Mark? = if (tab == TAB_NOTES) notes.getOrNull(chosen) else null

    companion object {
        const val ACT_NONE = 0
        /** «Назад» в шапке — закрыть поиск и вернуться на страницу. */
        const val ACT_CLOSE = 1
        const val ACT_PREV = 2
        const val ACT_NEXT = 3
        /** Выбрано совпадение: читалка переходит на его позицию и закрывает поиск. */
        const val ACT_OPEN = 4
        /** Выбрана заметка: читалка переходит на её позицию и закрывает поиск. */
        const val ACT_NOTE = 5
        /** Смена вкладки: к коду прибавляется её номер. */
        const val ACT_TAB = 10
        /** Перерисовать (набор, «Очистить», открытие клавиатуры). */
        const val ACT_CHANGED = 20
        /** Нажать «Найти»: запустить поиск по набранному запросу. */
        const val ACT_SEARCH = 21

        const val TAB_BOOK = 0
        const val TAB_CHAPTER = 1
        const val TAB_NOTES = 2

        private val TAB_LABELS = arrayOf("Вся книга", "Текущая глава", "Заметки")

        /** «Найти» на тёмной клавише: она и запускает поиск. */
        private const val ACTION_SEARCH = "Найти"
        private const val PLACEHOLDER = "Слово или фразу из книги"
        private const val MAX_QUERY = 60
        private const val W = 1404f

        // Шапка: «Назад» 41,113.5 107×112, заголовок 176,105, подзаголовок 176,181.
        private const val BACK_X = 41f
        private const val BACK_Y = 113.5f
        private const val BACK_W = 107f
        private const val BACK_H = 112f
        private const val TITLE_X = 176f
        /** Подзаголовок не заходит за правый край контента 1360. */
        private const val SUB_W = 1160f

        // Поле: 44,249 1316×102; лупа 95.6,284.9; запрос 154,274.6; крестик 1262,256 87×92.
        private const val FIELD_X = 44f
        private const val FIELD_Y = 249f
        private const val FIELD_W = 1316f
        private const val FIELD_H = 102f
        private const val CLEAR_X = 1262f
        private const val CLEAR_Y = 256f
        private const val CLEAR_W = 87f
        private const val CLEAR_H = 92f
        /** Запрос не залезает под крестик (1262 − 154 − 40). */
        private const val QUERY_W = 1068f

        // Вкладки: желоб 44,388 1316×88; сегмент 48,392 436×83; подписи 34 в 48 / 484 / 920.
        private const val TAB_GROOVE_X = 44f
        private const val TAB_GROOVE_Y = 388f
        private const val TAB_GROOVE_W = 1316f
        private const val TAB_GROOVE_H = 88f
        private const val TAB_X = 48f
        private const val TAB_W = 436f
        private const val TAB_LABEL_W = 431f
        private const val SEG_X = 48f
        private const val SEG_Y = 392f
        private const val SEG_W = 436f
        private const val SEG_H = 83f

        // Панель: 41,516 1321×1150.
        private const val PANEL_X = 41f
        private const val PANEL_Y = 516f
        private const val PANEL_W = 1321f
        private const val PANEL_H = 1150f

        // Строка: 95,556 1214×180, шаг 210 (556 → 766 → 976 → 1186).
        private const val ROW_X = 95f
        private const val ROW_TOP = 556f
        private const val ROW_STEP = 210f
        /** «Место» 95,556 30/700; фрагмент 95,604 = +48; литера 38/400, две строки. */
        private const val QUOTE_DY = 48f
        /** Разделитель 95,734 = +178 от верха строки. */
        private const val SEP_DY = 178f
        private const val SEP_W = 1214f
        private const val QUOTE_LINES = 2
        /** Фрагмент не заходит под шеврон (1298 − 95 − 20). */
        private const val FRAG_W = 1183f
        /** «Место» не заходит под шеврон. */
        private const val PLACE_W = 1180f
        private const val NEXT_X = 1298f
        private const val NEXT_DY = 7f

        /** Строк в панели: без клавиатуры 5, с клавиатурой (панель до 1375) — 4. */
        private const val ROWS = 5
        private const val ROWS_KB = 4

        /** Лист клавиатуры — как на B21: он начинается на 1385 и уходит за нижний край. */
        private const val SHEET_Y = 1385f

        // Пустые состояния: в снимке B28 их нет — по центру панели, как на B10/B27.
        private const val EMPTY_Y = 1060f
        private const val EMPTY_QUERY = "Поиск по книге"
        private const val EMPTY_QUERY_HINT = "Наберите слово или фразу — и «Найти»"
        private const val EMPTY_STALE = "Запрос изменился"
        private const val EMPTY_STALE_HINT = "Нажмите «Найти» на клавиатуре"
        private const val EMPTY_SEARCHING = "Ищем по книге…"
        private const val EMPTY_NONE = "Совпадений нет"
        private const val EMPTY_NONE_HINT = "Попробуйте другое слово или другую вкладку"
        private const val EMPTY_NOTES = "Заметок пока нет"
        private const val EMPTY_NOTES_HINT = "Заметка ставится долгим нажатием на слово"

        private val WS = Regex("\\s+")
    }
}
