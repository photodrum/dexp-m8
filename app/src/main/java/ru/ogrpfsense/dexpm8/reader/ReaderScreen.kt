package ru.ogrpfsense.dexpm8.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import ru.ogrpfsense.dexpm8.core.LibraryDb
import ru.ogrpfsense.dexpm8.ui.D
import ru.ogrpfsense.dexpm8.ui.EinkActivity
import ru.ogrpfsense.dexpm8.ui.Epd
import ru.ogrpfsense.dexpm8.ui.ErrorDialog
import ru.ogrpfsense.dexpm8.ui.Screen
import ru.ogrpfsense.dexpm8.ui.ShadeScreen
import ru.ogrpfsense.dexpm8.ui.T
import ru.ogrpfsense.dexpm8.ui.Trace
import java.nio.ByteBuffer

/**
 * B3 · Чтение (R1.3). Числа — из `design/v5/B03.json`.
 *
 *   книга      колонтитул слева 73.1,37.9, Manrope 30/600 G4
 *   глава      колонтитул справа, правый край 1330.8, Manrope 30/600 G4
 *   страница   область текста 102.4,117 1199.3×1540 — в неё рисуется буфер движка
 *   прогресс   трек 73.1,1764.8 1023.7×21.9 (вдавленный), заливка G3 75.9,1770.3 высотой 13.7
 *   счётчик    «124 / 350» Manrope 36/700 G1, правый край 1330.8, верх 1752.6
 *   подпись    «35% · … до конца главы» Manrope 30/600 G4, 73.1,1803.8
 *
 * Статус-бара на макете нет: на странице книги его роль играют колонтитулы.
 *
 * Листание — трети экрана (левая назад, правая вперёд; центр отдан шторке B5 сверху,
 * шторке B4 и меню B9 — R3, `ShadeScreen.ZONE_READER`), свайп по настройке B39 «Листать
 * свайпом» ([onSwipe]: влево — вперёд, вправо — назад) и физические кнопки книги:
 * `gpio-keys` отдаёт `KEY_PAGEUP` / `KEY_PAGEDOWN`, `adc-keys` — `KEY_VOLUMEUP` /
 * `KEY_VOLUMEDOWN` (разведка на книге 2026-11-13). Клавиши страниц листают всегда, громкость —
 * по настройке B39 «Кнопки громкости» (`Prefs.volumeKeys`, B39.2). Клавиши приходят из
 * `EinkActivity.dispatchKeyEvent` в [key].
 *
 * Память: два `ALPHA_8` (по 1,8 МБ) и один `ByteArray` — страница на экране и заранее
 * отрисованная соседняя (READER_SPEC, раздел 4). Соседняя рисуется в фоне в непоказанный
 * буфер, экран переключает готовый.
 *
 * Состояние страниц (`cur`, `pages`, буферы) меняется только в `Engine.worker` — движок
 * не потокобезопасен. UI-поток читает готовые поля в [render]; [show] и [key] с UI-потока
 * только ставят задачу в очередь движка.
 *
 * Место чтения (R1.4): в базе лежит позиция движка (xpointer, колонка `pos`), а не номер
 * страницы — она переживает смену кегля и повторную разметку. При открытии [Engine.gotoPos]
 * ставит движок на сохранённую строку; при листании, уходе в фон и закрытии экрана позиция
 * пишется обратно вместе с прогрессом ([LibraryDb.setPlace]).
 *
 * Поверх страницы живут: лист B31 (DjVu), меню B9, список B10, лист B29 «Переход на страницу»
 * (R3.3) — он открывается касанием шкалы меню B9 (и подписи «Стр. N из M» под ней) — и лист
 * B4 «Типографика» (R3.5): кнопка «Текст» меню B9 у crengine (у DjVu там «Масштаб» → B31).
 * Настройки B4 хранятся в `Prefs` (общие с экраном B39) и применяются при каждом открытии книги.
 */

class ReaderScreen(act: EinkActivity) : Screen(act) {

    private val pagePaint = Paint().apply {
        color = T.G0          // альфа из буфера: 0 → G15 фона, 255 → G0
        isFilterBitmap = false
        isDither = false
    }
    /**
     * B29: под листом страница «приглушена» — в макете её текст и колонтитул G10, а не G0/G4.
     * Это не фильтр и не прозрачность: цвет точки меняется на одну ступень палитры.
     */
    private val pagePaintDim = Paint().apply {
        color = T.G10
        isFilterBitmap = false
        isDither = false
    }
    private val dst = RectF(TX, TY, TX + TW, TY + TH)

    /**
     * B51: страница под листом заметки приглушена сильнее, чем под B29 и B4 — в макете текст
     * G8 (не G10). Колонтитул там G9, его цвет передаётся в [header] отдельно.
     */
    private val pagePaintDim8 = Paint().apply {
        color = T.G8
        isFilterBitmap = false
        isDither = false
    }

    // --- Данные книги (меняются в worker)
    private var title = ""
    /** Строка базы открытой книги: из неё берётся место чтения и в неё оно пишется. */
    @Volatile private var entry: LibraryDb.Entry? = null
    @Volatile private var pages = 0
    @Volatile private var cur = 0

    // --- Оглавление (`toc()`, R1.2): глава на экране и её последняя страница
    @Volatile private var chapterTitle = ""
    @Volatile private var chapterEnd = -1
    /** Первая страница текущей главы (для клавиши «Начало главы» на B29); −1 — главы нет. */
    @Volatile private var chapterStart = -1

    // --- Буферы: один показывается, второй пишется в фоне; nextPage — что в нём лежит
    private var bmpA: Bitmap? = null
    private var bmpB: Bitmap? = null
    @Volatile private var shownB = false
    private var nextPage = -1
    /** Какая страница лежит в буфере экрана; −1 — экрана ещё нет. */
    @Volatile private var shownPage = -1
    private var buf: ByteArray? = null
    private var pw = 0
    private var ph = 0
    private var toc: List<TocItem> = emptyList()

    private var gen = 0
    /** Меняется при каждом листании: подсчёт кадров в очереди движка по нему отсекается. */
    private var epoch = 0
    /**
     * Страница, которую просят кнопки листания меню B9 (замечание 2026-11-16). Пока движок
     * рисует предыдущую страницу, `cur` ещё старый, поэтому нажатия складываются от последней
     * запрошенной страницы, а не от показанной. −1 — никто не просит.
     */
    @Volatile private var wantPage = -1
    private var attached = false
    /** Страниц перевёрнуто: каждое [FULL_EVERY]-е — полная перерисовка (B37). */
    private var flips = 0

    // --- Время чтения (п. 22, B32 «Статистика»)
    /** С какого момента (uptime, мс) считать время чтения; 0 — не считаем. */
    private var readStart = 0L
    /** Страниц перевёрнуто с прошлой записи времени в базу. */
    @Volatile private var readPages = 0

    private val dialog = ErrorDialog(ink, icons)
    private var dialogOpen = false
    /** B59: какая страница не декодировалась (−1 — диалог B45 или его нет). */
    private var badPage = -1

    // --- B31 «Масштаб и поля» (R2.5): настройки DjVu живут в экране, лист — только рисует
    private val sheet = DjvuViewSheet(ink, icons)
    /** Открыт лист B31 поверх страницы. */
    private var sheetOpen = false
    // --- B9 «Меню чтения» (R3.1): две панели поверх страницы, состояние — здесь
    private val menu = ReaderMenu(ink, icons)
    /** Открыто меню B9. */
    private var menuOpen = false
    /**
     * На текущей странице стоит закладка (R4.1): кнопка «Закладка» в меню B9 рисуется вдавленной.
     * Считается при открытии меню и после каждого нажатия — база, а не догадка.
     *
     * `volatile`: пишется в потоке движка (`refreshMark`), читается в [render] с UI-потока.
     */
    @Volatile private var marked = false
    /** Идёт чтение вслух (R6): кнопка «Вслух» меню B9 вдавлена, листание идёт за голосом. */
    @Volatile private var ttsOn = false
    /**
     * Листание, которое заказало само чтение вслух. Такое листание не останавливает голос —
     * в отличие от ручного (касание, клавиши, меню).
     */
    private var ttsTurn = false
    // --- B10 «Оглавление» (R3.2) и B27 «Закладки» (R4.2): одна панель, две вкладки
    private val tocView = TocScreen(ink, icons)
    /** Открыт список B10/B27. */
    private var tocOpen = false
    /** Открытая вкладка: [TocScreen.TAB_TOC] — оглавление, [TocScreen.TAB_MARKS] — закладки. */
    private var tocTab = TocScreen.TAB_TOC
    /** Страница открытой вкладки: 7 пунктов оглавления или 5 закладок. */
    private var tocPage = 0
    /**
     * Закладки книги и их число для подписи вкладки (R4.2). Читаются из базы при открытии списка
     * и после возврата с вкладки закладок — список мог измениться из меню B9.
     */
    private var marks: List<LibraryDb.Mark> = emptyList()
    private var markCnt = 0
    /** Заметки и цитаты книги — третья вкладка списка B27 и подпись «Заметки · N». */
    private var tocNotes: List<LibraryDb.Mark> = emptyList()
    private var noteCnt = 0
    // --- B29 «Переход на страницу» (R3.3): нижний лист с клавиатурой поверх страницы
    private val goto = GotoSheet(ink, icons)
    /** Открыт лист B29. */
    private var gotoOpen = false
    // --- B4 «Типографика» (R3.5): нижний лист настроек текста поверх приглушённой страницы
    private val typo = TypographySheet(ink, icons)
    /** Открыт лист B4. */
    private var typoOpen = false
    // --- B28 «Поиск по книге» (R4.3): полноэкранный список поверх страницы, как B10
    private val searchView = SearchBookScreen(ink, icons, act)
    /** Открыт поиск по книге. */
    private var searchOpen = false
    /** Оглавление для подписей «Место» на B28: страницы и названия пунктов, считается один раз. */
    private var tocPages: List<Int> = emptyList()
    private var tocNames: List<String> = emptyList()
    // --- B50 «Выделение» (R4.3): плашки G12, маркеры и меню действий поверх страницы
    private val selBar = SelectionBar(ink)
    /** Отрезки выделения в координатах буфера страницы (x, y, w, h по 4 числа); пусто — нет. */
    private var selRects: IntArray = IntArray(0)
    /** Текст выделения — для меню «Копировать», «Найти» и листа B51. */
    private var selText = ""
    /** Начало и конец выделения (xpointer) — для `addQuote`/`addNote`. */
    private var selStart: String? = null
    private var selEnd: String? = null
    /**
     * Якорь ссылки под выделенным словом (`n_1`), если слово — сноска. Берётся тем же
     * касанием, что и слово ([Engine.linkAt] рядом с [Engine.wordAt]): у вкладки «Сноска»
     * нет ни сети, ни второго запроса к движку, а после листания выделение снимается.
     */
    private var selHref: String? = null
    /** Верх панели B50 на кадре: считается один раз при отрисовке, нужен касанию. */
    private var selBarY = 0f
    /**
     * Маркеры выделения на кадре — левый верхний угол каждого (дизайн-px). Считаются при
     * отрисовке ([drawSelectionOver]) и нужны касанию ([markerHit]): геометрия у рисования
     * и касаний одна (READER_SPEC, «одна геометрия для рисования и касаний»).
     * [markShown] = false — маркеров на кадре нет (выделение снято или не отрисовано).
     */
    private var markStartX = 0f
    private var markStartY = 0f
    private var markEndX = 0f
    private var markEndY = 0f
    private var markShown = false
    /** Касание выделения обрабатывается в потоке движка: плашки нужны для меню. */
    @Volatile private var selecting = false
    // --- B51 «Новая заметка» (R4.3): нижний лист с цитатой и клавиатурой
    private val noteView = NoteSheet(ink, act)
    /** Открыт лист B51. */
    private var noteOpen = false
    // --- B30 «Словарь и сноски» (R5): нижний лист со словом под пальцем
    private val dictView = DictSheet(ink, icons)
    /** Открыт лист B30. */
    private var dictOpen = false
    /** Сеть для словаря и перевода. Лениво: экран живёт в кэше, контекст — активити. */
    private val dictOnline by lazy { ru.ogrpfsense.dexpm8.core.DictOnline(act) }
    /**
     * Номер запроса к сети: ответ, пришедший после закрытия листа или смены слова,
     * выбрасывается так же, как кадр движка по [epoch].
     */
    private var dictGen = 0
    /**
     * Метки B52 на показанной странице: подчёркивание цитаты G3 и заливка заметки G13 с номером
     * на полях. Считаются в потоке движка ([rangeRects] читает разметку) при каждой отрисовке
     * страницы и после сохранения цитаты/заметки; [render] только рисует готовые отрезки.
     */
    @Volatile private var pageMarks: List<PageMark> = emptyList()

    /**
     * Метка B52: отрезки текста в координатах буфера страницы ([rects], по 4 числа) и её вид.
     * [number] — номер заметки на полях (у цитаты 0: там знак «“»).
     */
    class PageMark(val kind: Int, val rects: IntArray, val number: Int)

    /**
     * Типографика B4 на листе: значения из `Prefs`, пока лист открыт — его
     * [TypographySheet.result]. Пишется и из потока движка (применение), читается в [render] —
     * поэтому `volatile`, как `pages` и `cur`.
     */
    @Volatile private var typing = TypographySheet.State()
    /** Перевёрстка после смены настроек идёт: касания листа ждут (нельзя менять на ходу). */
    @Volatile private var relayouting = false
    /** Настройки страницы DjVu этой книги; для crengine не используется. */
    @Volatile private var view = DjvuView.DEFAULT
    /** Предел сдвига последнего отрисованного кадра `[maxX, maxY]` — для третей (пишет worker). */
    @Volatile private var panLast: IntArray? = null
    /** Предел сдвига от движка по странице `[maxX, maxY]`: трети сдвигают или листают. */
    private val pan = HashMap<Int, IntArray>(CACHE_MAX)
    /** Промилле авто-обрезки: страница → промилле (пустой массив = полей нет). Только worker. */
    private val auto = HashMap<Int, IntArray>(CACHE_MAX)
    /** Превью для листа: кадр 600×820 без обрезки и его страница. Только worker. */
    private val prevBuf = ByteArray(PREVIEW_W * PREVIEW_H)
    /** Два `Bitmap` превью: worker пишет в непоказанный, экран переключает по `post`. */
    private var prevA: Bitmap? = null
    private var prevB: Bitmap? = null
    @Volatile private var prevShown: Bitmap? = null
    private var prevPage = -1

    // --- B58 «Открываем книгу»: первое открытие длиннее SHOW_AFTER_MS
    private val openDialog = OpenBookDialog(ink, icons)
    /** Диалог на экране: загрузка идёт и порог ожидания уже прошёл. */
    private var opening = false
    /** Процент, который пришёл от движка, когда диалога ещё не было. */
    private var openPct = 0
    /** Открытие идёт (от постановки задачи в `Engine.worker` до первой страницы). */
    private var loading = false
    /** Нажата «Отмена»: результат открытия выбрасывается, документ закрывается. */
    @Volatile private var cancelled = false
    private val showOpen = Runnable {
        if (attached && loading && !cancelled) {
            opening = true
            openDialog.open(openSub(), openPct)
            redraw()
        }
    }

    /** Прогресс открытия с потока движка (переслан на UI). */
    private fun onOpenProgress(p: Int) {
        if (!attached) return
        openPct = p
        if (opening && openDialog.progress(p)) redraw()
    }

    /** Подзаголовок B58: «книга · автор · FB2» (в снимке «Война и мир · Л. Толстой · FB2»). */
    private fun openSub(): String {
        val e = entry ?: return title
        val parts = ArrayList<String>(3)
        if (e.title.isNotEmpty()) parts.add(e.title)
        if (!e.author.isNullOrEmpty()) parts.add(e.author)
        if (e.format.isNotEmpty()) parts.add(e.format.uppercase(java.util.Locale.ROOT))
        return parts.joinToString(" · ")
    }

    private fun stopOpenDialog() {
        loading = false
        removeCallbacks(showOpen)
        if (opening) {
            opening = false
            redraw()
        }
    }

    override fun layout(w: Float, h: Float) {
        super.layout(w, h)
        dst.set(TX, TY, TX + TW, TY + TH)
    }

    /** Читалка: шторка B5 открывается касанием центральной трети в верхней части страницы. */
    override val shadeZone: Float
        get() = if (sheetOpen || menuOpen || tocOpen || gotoOpen || typoOpen || searchOpen) 0f
        else ShadeScreen.ZONE_READER

    /**
     * Замечание 20 (2026-11-17): зона шторки B5 накрывает верхнюю треть страницы, и меню
     * выделения B50 при выделении у верха страницы встаёт ровно в неё — отпускание по «Заметке»
     * или «Словарю» открывало шторку, а не то, что нажали. Поэтому где стоит панель B50, шторка
     * уступает касание экрану: `onUp` отдаёт его `selectionTouch` и меню работает как нарисовано.
     *
     * Остальная зона шторки на месте, и удержание её по-прежнему не пробивает (`Screen.longPress`),
     * так что новое выделение в верхней трети не начинается — но уже поставленное продолжается.
     */
    override fun shadeAllowed(x: Float, y: Float): Boolean {
        // Поверх страницы лист B51/B30, диалог или открытие книги: меню B50 не нарисовано,
        // и уступать нечему — зона шторки работает как у остальных экранов.
        if (noteOpen || dictOpen || dialogOpen || opening) return true
        if (selRects.isEmpty()) return true
        // Меню B50 или маркер выделения (2026-11-20): по ним касание расширяет выделение,
        // а не открывает шторку.
        return !selBar.contains(x, y, selBarY) && markerHit(x, y) == MARK_NONE
    }

    // ------------------------------------------------------------------ кадр

    override fun render(c: Canvas) {
        // B31: на макете под листом только фон и превью — страницу и колонтитулы книги не рисуем
        // (иначе они просвечивают за превью и колонтитул листа). Затемнения нет, как решено.
        if (sheetOpen) {
            ink.rect(c, 0f, 0f, W, H, T.BG_READ)
            sheet.draw(c, view)
            if (dialogOpen) dialog.draw(c)
            return
        }
        // B10 «Оглавление» и B27 «Закладки»: полноэкранный список поверх страницы, страница под ним
        // не рисуется. Заголовок и подписи вкладок — по открытой вкладке.
        if (tocOpen) {
            ink.rect(c, 0f, 0f, W, H, T.BG)
            tocView.draw(c, menuSub(), toc, tocPage, hereOf(), tocTab, marks, markCnt,
                tocNotes, noteCnt)
            return
        }
        // B28 «Поиск по книге» (R4.3): тоже полный экран поверх страницы, фон экрана G12.
        if (searchOpen) {
            ink.rect(c, 0f, 0f, W, H, T.BG)
            searchView.draw(c, title, chapterStart, chapterEnd)
            return
        }
        // B29 «Переход на страницу»: та же страница, но приглушённая (макет: текст G10),
        // сверху нижний лист с клавиатурой. Затемнения нет — как B31.
        if (gotoOpen) {
            ink.rect(c, 0f, 0f, W, H, T.BG_READ)
            header(c, T.G10)
            val gb = if (shownB) bmpB else bmpA
            if (gb != null) c.drawBitmap(gb, null, dst, pagePaintDim)
            val target = goto.page(pages)
            goto.draw(c, pages, target, cur + 1, chapterOf(target), pctOf(target))
            return
        }
        // B4 «Типографика» (R3.5): страница тоже приглушена (в макете текст и колонтитул G10),
        // сверху нижний лист с настройками. Затемнения нет — как B29 и B31.
        if (typoOpen) {
            ink.rect(c, 0f, 0f, W, H, T.BG_READ)
            header(c, T.G10)
            val tb = if (shownB) bmpB else bmpA
            if (tb != null) c.drawBitmap(tb, null, dst, pagePaintDim)
            typo.draw(c, typing)
            return
        }
        // B51 «Новая заметка» (R4.3): страница под листом приглушена сильнее — в макете её
        // текст G8, колонтитул G9; сверху лист с цитатой и клавиатурой.
        if (noteOpen) {
            ink.rect(c, 0f, 0f, W, H, T.BG_READ)
            header(c, T.G9)
            drawSelectionUnder(c)         // выделение видно сквозь страницу: оно же цитата заметки
            val nb = if (shownB) bmpB else bmpA
            if (nb != null) c.drawBitmap(nb, null, dst, pagePaintDim8)
            drawSelectionOver(c, bar = false)  // маркеры поверх; меню закрыто листом
            noteView.draw(c, notePlace(), selText)
            return
        }
        // B30 «Словарь и сноски» (R5): страница как есть (в макете её текст G1), слово под
        // вдавленной плашкой, сверху нижний лист. Меню B50 закрыто листом, маркеры остаются.
        if (dictOpen) {
            ink.rect(c, 0f, 0f, W, H, T.BG_READ)
            header(c)
            drawSelectionUnder(c)
            val db = if (shownB) bmpB else bmpA
            if (db != null) c.drawBitmap(db, null, dst, pagePaint)
            if (pages > 0) footer(c)
            drawSelectionOver(c, bar = false)
            dictView.draw(c)
            return
        }

        val dim = if (opening) dimBegin(c) else -1
        ink.rect(c, 0f, 0f, W, H, T.BG_READ)
        header(c)
        // B52 и B50 под текстом: заливки и подчёркивания видны сквозь прозрачный фон буфера,
        // а буквы остаются поверх них.
        if (pageMarks.isNotEmpty() || selRects.isNotEmpty()) drawSelectionUnder(c)
        val b = if (shownB) bmpB else bmpA
        if (b != null) c.drawBitmap(b, null, dst, pagePaint)
        if (pages > 0) footer(c)
        // B52: метки на полях; B50: маркеры выделения и меню действий — поверх страницы.
        if (pageMarks.isNotEmpty()) drawPageMarks(c)
        if (selRects.isNotEmpty()) drawSelectionOver(c)
        if (menuOpen) menu.draw(c, title, menuSub(), menuPlace(), pages, cur, Engine.djvu, marked, ttsOn)
        if (dim >= 0) dimEnd(c, dim)
        if (opening) openDialog.draw(c)
        if (dialogOpen) dialog.draw(c)
    }

    /**
     * Подзаголовок меню B9: «Лев Толстой · Часть 1, глава I» в макете. У нас это автор и
     * текущая глава из оглавления; чего нет — не выводим.
     */
    private fun menuSub(): String {
        val a = entry?.author.orEmpty()
        val ch = chapterTitle
        return when {
            a.isNotEmpty() && ch.isNotEmpty() -> "$a · $ch"
            a.isNotEmpty() -> a
            else -> ch
        }
    }

    /** Положение в книге для нижней панели B9: «Стр. 124 из 350 · Глава I · 35%». */
    private fun menuPlace(): String {
        val pct = pctOf(cur)
        val base = "Стр. ${cur + 1} из $pages"
        return if (chapterTitle.isEmpty()) "$base · $pct%" else "$base · $chapterTitle · $pct%"
    }

    /** Открыть меню B9 (касание середины центральной колонки, решение 2026-11-12). */
    private fun openMenu() {
        if (menuOpen || loading || pages <= 0) return
        menuOpen = true
        // Кнопки листания отсчитывают от последней запрошенной страницы: за время, пока меню
        // было закрыто, могли листать третями — начинаем от той, что на экране.
        wantPage = -1
        // Состояние «Закладка» узнаём у базы, но позицию спрашивает движок — только в его
        // потоке (`Engine.pos` читает разметку). До ответа клавиша выпуклая.
        marked = false
        redraw()
        Trace.step("b3: menu open")
        Engine.worker.execute {
            refreshMark()
            post { if (attached && menuOpen) redraw() }
        }
    }

    /**
     * Пересчитать [marked] для текущей страницы (R4.1). Позиция — `Engine.pos(cur)`, то есть
     * та же xpointer, что лежит в базе у закладки: сравнение идёт по тексту, а не по номеру
     * страницы, поэтому после смены кегля закладка остаётся «на месте».
     *
     * Только в `Engine.worker`: `Engine.pos` читает разметку движка.
     */
    private fun refreshMark() {
        val e = entry
        if (e == null || e.id <= 0) { marked = false; return }
        val pos = try { Engine.pos(cur) } catch (t: Throwable) { null }
        marked = try { !pos.isNullOrEmpty() && act.db.hasMark(e.id, pos) }
        catch (t: Throwable) {
            Trace.step("b3: hasMark FAIL ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /**
     * Поставить или снять закладку на текущей странице (кнопка «Закладка» меню B9).
     *
     * Решение пользователя 2026-11-21 (отменяет решение 2026-11-16 «только ставит»): кнопка —
     * переключатель. Первое нажатие ставит закладку, и клавиша становится вдавленной; повторное
     * на той же странице снимает её, и клавиша снова выпуклая. Так видно, что нажатие сработало
     * (в обоих случаях меняется вид клавиши), а в списке B27 закладку по-прежнему можно удалить
     * корзиной строки.
     *
     * Позицию берём у движка в его потоке (`Engine.pos`) — это тот же вызов, которым читалка
     * сохраняет место чтения, значит закладка и `books.pos` всегда об одном тексте.
     * Строка в базе — снимок «страница, глава, первые слова»: список B27 покажет её,
     * не открывая книгу.
     *
     * Меню остаётся открытым: человек видит, что клавиша стала вдавленной.
     * Поэтому `refreshMark` вызывается здесь же, в [Engine.worker], и только потом `post` —
     * раньше он стоял внутри `post`, то есть на UI-потоке: `Engine.pos` там возвращал null,
     * клавиша оставалась выпуклой, и нажатие выглядело как «не работает» (замечание 2026-11-16).
     */
    private fun toggleBookmark() {
        val e = entry ?: return
        if (e.id <= 0 || pages <= 0) return
        val page = cur
        val chapter = chapterTitle.ifEmpty { null }
        Engine.worker.execute {
            val pos = try { Engine.pos(page) } catch (t: Throwable) { null }
            if (pos.isNullOrEmpty()) {
                Trace.step("b3: mark — позиции нет")
                return@execute
            }
            val already = try { act.db.hasMark(e.id, pos) } catch (t: Throwable) { false }
            if (already) {
                // Повторное нажатие — снять закладку (решение 2026-11-21).
                try {
                    act.db.removeMark(e.id, pos)
                    Trace.step("b3: mark снята стр.${page + 1}")
                } catch (t: Throwable) {
                    Trace.step("b3: removeMark FAIL ${t.javaClass.simpleName}: ${t.message}")
                    return@execute
                }
            } else {
                // `pageText` читает разметку движка — только в его потоке (правило ARCHITECTURE).
                val txt = try { Engine.pageText() } catch (t: Throwable) { null }
                val line = txt?.replace('\u00A0', ' ')?.trim()?.take(MARK_TEXT_MAX)
                try {
                    act.db.addMark(e.id, pos, page, chapter, line)
                    Trace.step("b3: mark поставлена стр.${page + 1}")
                } catch (t: Throwable) {
                    Trace.step("b3: addMark FAIL ${t.javaClass.simpleName}: ${t.message}")
                    return@execute
                }
            }
            // Состояние клавиши — здесь: `refreshMark` спрашивает позицию у движка.
            refreshMark()
            post {
                if (!attached) return@post
                redraw()
            }
        }
    }

    private fun closeMenu() {
        if (!menuOpen) return
        menuOpen = false
        redraw()
        Trace.step("b3: menu close")
    }

    /**
     * Открыть список B10/B27: оглавление уже в [toc] (`Engine.toc()` при открытии книги), закладки
     * читаются из базы здесь же — их список меняется от кнопки «Закладка» меню B9.
     *
     * Открытая вкладка — «Оглавление», а если оглавления нет (часть DjVu без навигации),
     * то «Закладки»: иначе дверь в список вела бы в пустой экран.
     */
    private fun openToc() {
        if (tocOpen) return
        reloadMarks()
        tocTab = if (toc.isEmpty() && markCnt > 0) TocScreen.TAB_MARKS else TocScreen.TAB_TOC
        if (toc.isEmpty() && markCnt == 0 && noteCnt == 0) {
            // Ни оглавления, ни закладок, ни заметок — открывать нечего, меню просто закрылось.
            Trace.step("b3: toc пусто")
            return
        }
        tocOpen = true
        // Открываем на странице с текущей главой: иначе искать своё место в длинной книге.
        val here = hereOf()
        tocPage = if (tocTab == TocScreen.TAB_TOC) {
            if (here >= 0) here / TocScreen.ROWS else 0
        } else 0
        redraw()
        Trace.step("b3: toc open items=${toc.size} marks=$markCnt notes=$noteCnt tab=$tocTab page=$tocPage")
    }

    private fun closeToc() {
        if (!tocOpen) return
        tocOpen = false
        redraw()
        Trace.step("b3: toc close")
    }

    /**
     * Перечитать закладки и заметки книги из базы (UI-поток: `LibraryScreen` читает так же).
     * У книги без строки в базе записей быть не может — оба списка пустые.
     *
     * `marks()` отдаёт только закладки (фильтр по виду в запросе), заметки и цитаты — `notes()`:
     * раньше список закладок брал все строки подряд, и сохранённая заметка появлялась
     * в «Закладках» (замечание пользователя 2026-11-17).
     */
    private fun reloadMarks() {
        val e = entry
        if (e == null || e.id <= 0) {
            marks = emptyList()
            markCnt = 0
            tocNotes = emptyList()
            noteCnt = 0
            return
        }
        marks = try { act.db.marks(e.id) } catch (t: Throwable) {
            Trace.step("b3: marks FAIL ${t.javaClass.simpleName}: ${t.message}")
            emptyList()
        }
        markCnt = marks.size
        tocNotes = try { act.db.notes(e.id) } catch (t: Throwable) {
            Trace.step("b3: notes FAIL ${t.javaClass.simpleName}: ${t.message}")
            emptyList()
        }
        noteCnt = tocNotes.size
    }

    /**
     * Смена вкладки списка. Заголовок шапки — имя вкладки, поэтому страница каждого списка
     * не переносится: на «Оглавлении» открывается место текущей главы, на «Закладках»
     * и «Заметках» — первая страница (списки идут свежими сверху).
     *
     * Оба списка перечитываются при каждом переходе на вкладку: пока список был закрыт,
     * человек мог поставить закладку в меню B9 или сохранить цитату/заметку в B50–B52.
     */
    private fun switchTocTab(tab: Int) {
        if (!tocOpen || tab == tocTab) return
        tocTab = tab
        if (tab == TocScreen.TAB_TOC) {
            val here = hereOf()
            tocPage = if (here >= 0) here / TocScreen.ROWS else 0
        } else {
            reloadMarks()
            tocPage = 0
        }
        redraw()
        Trace.step("b3: toc tab=$tab page=$tocPage marks=$markCnt notes=$noteCnt")
    }

    // ------------------------------------------------------------------ B28 «Поиск по книге» (R4.3)

    /**
     * Открыть B28 (кнопка «Поиск» в шапке меню B9). Книга должна быть открыта; у DjVu текстового
     * слоя нет — поиска и заметок там не будет (READER_SPEC, R4), экран не открывается вовсе.
     *
     * Заметки читаются из базы сразу: они и так есть, а поиск по книге — только по «Найти».
     */
    private fun openSearch() {
        if (searchOpen || loading || pages <= 0) return
        openSearch("")
    }

    /**
     * Открыть B28 с готовым запросом (пусто — как раньше, с клавиатурой и пустым полем).
     * Запрос приходит из меню выделения B50 («Найти»): слово уже набрано, клавиатура не нужна,
     * а поиск запускается сразу — от человека ждать ещё одного нажатия незачем.
     */
    private fun openSearch(query: String) {
        if (searchOpen || loading || pages <= 0) return
        if (Engine.djvu) {
            Trace.step("b28: DjVu — поиска нет")
            return
        }
        searchView.open()
        // Оглавление для подписей «Место»: страницы и названия пунктов, один раз на книгу.
        if (tocPages.size != toc.size) {
            tocPages = toc.map { it.page }
            tocNames = toc.map { it.title }
        }
        searchView.setChapters(tocPages, tocNames)
        reloadNotes()
        searchOpen = true
        redraw()
        if (query.isNotEmpty()) {
            searchView.setQuery(query)
            runSearch()
            Trace.step("b28: open из выделения '$query'")
        } else {
            Trace.step("b28: open pages=$pages главы=${toc.size} заметок=${searchView.notesCount()}")
        }
    }

    private fun closeSearch() {
        if (!searchOpen) return
        searchOpen = false
        redraw()
        Trace.step("b28: close")
    }

    /** Заметки и цитаты книги из базы — вкладка «Заметки» (B28) и метки B52. */
    private fun reloadNotes() {
        val e = entry
        if (e == null || e.id <= 0) {
            searchView.setNotes(emptyList())
            return
        }
        val list = try { act.db.notes(e.id) } catch (t: Throwable) {
            Trace.step("b28: notes FAIL ${t.javaClass.simpleName}: ${t.message}")
            emptyList()
        }
        searchView.setNotes(list)
    }

    /**
     * Запустить поиск по набранному запросу. `Engine.search` читает разметку движка и на книге
     * в 420 страниц идёт около секунды (замер R4.3) — только в `Engine.worker`.
     *
     * Результат кладётся в экран одним куском ([SearchBookScreen.setHits]): фрагмент и позиция
     * каждого совпадения спрашиваются у движка здесь же, чтобы [render] не ходил в движок.
     */
    private fun runSearch() {
        val q = searchView.searchQuery()
        if (q.isBlank()) return
        searchView.markSearching()
        redraw()
        val my = ++epoch
        Engine.worker.execute {
            val t0 = SystemClock.uptimeMillis()
            val n = Engine.search(q)
            if (my != epoch) return@execute
            if (n < 0) {
                Trace.step("b28: search '$q' rc=$n ${Engine.codeText(n)}")
                post {
                    if (!attached) return@post
                    searchView.failSearch()
                    redraw()
                }
                return@execute
            }
            val list = ArrayList<SearchBookScreen.Hit>(n)
            for (i in 0 until n) {
                val page = Engine.searchPage(i)
                if (page < 0) continue
                list.add(SearchBookScreen.Hit(
                    page = page,
                    text = Engine.searchText(i).orEmpty(),
                    pos = Engine.searchPos(i),
                ))
            }
            val ms = SystemClock.uptimeMillis() - t0
            Trace.step("b28: search '$q' n=$n ${ms}ms")
            post {
                if (!attached || !searchOpen) return@post
                searchView.setHits(list, q)
                redraw()
            }
        }
    }

    /** Перейти на найденное: позиция движка (xpointer) надёжнее номера страницы. */
    private fun gotoSearchHit(h: SearchBookScreen.Hit) {
        closeSearch()
        gotoMarkPos(h.pos, h.page, "поиск")
    }

    /**
     * Перейти на позицию [pos]; [page] — запасной путь, если позиция не принята (книга
     * переверстана, другой файл). Общий для заметок B28 и закладок B27.
     */
    private fun gotoMarkPos(pos: String?, page: Int, what: String) {
        val my = ++epoch
        Engine.worker.execute {
            var target = if (pos.isNullOrEmpty()) -1 else try { Engine.gotoPos(pos) } catch (t: Throwable) { -1 }
            if (target < 0) target = page
            if (my != epoch) return@execute
            if (target < 0 || target >= pages) return@execute
            post {
                if (!attached) return@post
                show(target, 0)
            }
        }
        Trace.step("b28: $what goto стр.${page + 1}")
    }

    /** Открыть B29 «Переход на страницу» (шкала меню B9): поле сразу с номером текущей страницы. */
    private fun openGoto() {
        if (gotoOpen || loading || pages <= 0) return
        gotoOpen = true
        goto.open(cur + 1)
        redraw()
        Trace.step("b3: goto open page=${cur + 1}/$pages chapter=$chapterStart")
    }

    private fun closeGoto() {
        if (!gotoOpen) return
        gotoOpen = false
        redraw()
        Trace.step("b3: goto close")
    }

    /**
     * «Перейти» на B29: страница берётся из поля листа и листается как обычная ([show]).
     * Номер в книге ограничивает сам лист (`GotoSheet.page`); пустое поле — просто закрыть.
     */
    private fun gotoPage() {
        val target = goto.page(pages)
        closeGoto()
        if (target < 0) return
        Trace.step("b3: goto page ${target + 1}/$pages")
        show(target, if (target > cur) 1 else if (target < cur) -1 else 0)
    }

    // ------------------------------------------------------------------ B50 «Выделение» (R4.3)

    /**
     * Удержание на слове (B50). Жест объявлен в `Screen.onLongPress`: `ACTION_UP` после него
     * не придёт, поэтому обычное нажатие по той же точке меню B9 не откроет.
     *
     * `true` — слово найдено и выделение поставлено (жест наш), `false` — точка не на тексте:
     * тогда отпускание отработает как обычное нажатие (трети, меню).
     *
     * Всё, что читает разметку (`wordAt`, `selectionText`, `selectionPos`, `rangeRects`), —
     * только в `Engine.worker`; отрисовка — в `post`. Пока идёт запрос, второе удержание
     * игнорируется ([selecting]).
     */
    override fun onLongPress(x: Float, y: Float): Boolean {
        if (selecting) return true
        if (!attached || loading || pages <= 0) return false
        if (Engine.djvu) return false                 // у скана нет текстового слоя (READER_SPEC, R4)
        if (dialogOpen || sheetOpen || menuOpen || tocOpen || searchOpen || gotoOpen ||
            typoOpen || noteOpen || dictOpen) return false
        // Идёт перевёрстка после смены кегля: номера страниц ещё старые, координаты относятся
        // к прежнему кадру — удержание сейчас дало бы слово из чужого места.
        if (relayouting) {
            Trace.step("b50: удержание во время перевёрстки — пропуск")
            return false
        }
        // `word_at` ждёт точку в координатах БУФЕРА страницы, а буфер рисуется со смещением
        // области текста (TX,TY): без вычета получалось выделение на 117 px ниже — на две строки.
        // Всё за пределами области текста (колонтитул, поля, нижняя панель) — промах.
        val bx = x - TX
        val by = y - TY
        if (bx < 0f || by < 0f || bx >= TW || by >= TH) {
            Trace.step("b50: удержание вне текста ${x.toInt()},${y.toInt()}")
            return false
        }
        val px = D.px(bx)
        val py = D.px(by)
        // Страница, которая на экране: движок мог уехать вперёд фоновой отрисовкой соседней.
        val page0 = cur
        selecting = true
        Engine.worker.execute {
            // Пока задача ждала очередь, могла закончиться перевёрстка ([applyTypo]) и
            // сменить номер страницы: берём актуальный, снимок с UI-потока уже устарел.
            val page = if (relayouting) page0 else cur
            val word = try { Engine.wordAt(page, px, py) } catch (t: Throwable) { null }
            // Якорь сноски — тем же касанием: у значка сноски слово есть, и лист B30
            // на вкладке «Сноска» покажет её текст без второго запроса.
            val href = try { Engine.linkAt(page, px, py) } catch (t: Throwable) { null }
            if (word.isNullOrEmpty()) {
                selecting = false
                // Промах по пустому месту: удержание не состоялось — отпускание должно
                // отработать как обычное нажатие (трети, меню B9), иначе экран «не отвечает».
                post {
                    if (attached) {
                        Trace.step("b50: удержание мимо текста")
                        onUp(x, y)
                    }
                }
                return@execute
            }
            val text = Engine.selectionText().orEmpty()
            val a = Engine.selectionPos(0)
            val b = Engine.selectionPos(1)
            val rects = Engine.rangeRects(null, null) ?: IntArray(0)
            selecting = false
            if (rects.isEmpty()) return@execute
            post {
                if (!attached) return@post
                selText = text
                selStart = a
                selEnd = b
                selHref = href
                selRects = rects
                Trace.step("b50: выделено '${text.take(40)}' отрезков=${rects.size / 4} " +
                    "первый=${rects[0]},${rects[1]},${rects[2]},${rects[3]}")
                redraw()
            }
        }
        return true
    }

    /** Снять выделение: плашки и меню уходят, движку тоже сказано (`sel_clear`). */
    private fun clearSelection() {
        selRects = IntArray(0)
        selText = ""
        selStart = null
        selEnd = null
        selHref = null
        markShown = false            // маркеров на кадре больше нет — касание по ним не считается
        Engine.worker.execute { Engine.selectionClear() }
        redraw()
        Trace.step("b50: выделение снято")
    }

    /**
     * Плашки G12 по отрезкам движка (B50) и заливки/подчёркивания меток B52 — **под** страницей:
     * буфер движка приходит как маска (`ALPHA_8`, фон прозрачный), поэтому текст страницы
     * ложится на плашку сверху и остаётся читаемым. Маркеры и меню — [drawSelectionOver].
     *
     * Отрезки приходят в координатах буфера страницы, отрисовка — в дизайн-px со смещением
     * области текста ([TX]/[TY]): на книге `D.u = 1`, поэтому числа совпадают с макетом
     * (плашка 96,330.2).
     */
    private fun drawSelectionUnder(c: Canvas) {
        for (m in pageMarks) {
            val r = m.rects
            var i = 0
            while (i + 3 < r.size) {
                val rx = TX + r[i] / D.u
                val ry = TY + r[i + 1] / D.u
                val rw = r[i + 2] / D.u
                val rh = r[i + 3] / D.u
                if (m.kind == LibraryDb.MARK_NOTE) {
                    // Заметка: заливка строки. В макете B52 стоит G13, но на E-Ink от фона
                    // страницы G14 она отличается на одну ступень и вблизи не читается —
                    // замечание пользователя 2026-11-17: «выделенное заметкой слово почти
                    // не видно». Берём G12, как плашка выделения B50: та же «подсветка»,
                    // текст поверх неё остаётся G0/G1 и виден.
                    ink.roundRect(c, rx - MARK_PLATE_PAD, ry, rw + 2 * MARK_PLATE_PAD, rh,
                        R_SEL, T.G12)
                } else {
                    // Цитата: подчёркивание по низу строки (B52: 102,390.2 1199×4).
                    ink.rect(c, rx, ry + rh - QUOTE_UNDER_H, rw, QUOTE_UNDER_H, T.G3)
                }
                i += 4
            }
        }
        var i = 0
        while (i + 3 < selRects.size) {
            ink.roundRect(c, TX + selRects[i] / D.u, TY + selRects[i + 1] / D.u,
                selRects[i + 2] / D.u, selRects[i + 3] / D.u, R_SEL, T.G12)
            i += 4
        }
    }

    /**
     * Верх выделения: маркеры 6×72.4 у первой и последней строки и меню B50.
     * Считается по тем же отрезкам, что рисовала [drawSelectionUnder].
     * [bar] = false — только маркеры (под листом B51 меню всё равно закрыто листом).
     */
    private fun drawSelectionOver(c: Canvas, bar: Boolean = true) {
        markShown = false
        var top = Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        var firstX = 0f
        var lastRight = 0f
        /** Верх последней строки выделения — от него ставится маркер конца. */
        var lastTop = 0f
        var i = 0
        while (i + 3 < selRects.size) {
            val rx = TX + selRects[i] / D.u
            val ry = TY + selRects[i + 1] / D.u
            val rw = selRects[i + 2] / D.u
            val rh = selRects[i + 3] / D.u
            if (ry < top) {
                top = ry
                firstX = rx
            }
            if (ry + rh > bottom) {
                bottom = ry + rh
                lastTop = ry
                lastRight = rx + rw
            } else if (rx + rw > lastRight) lastRight = rx + rw
            i += 4
        }
        if (top > bottom) return
        // Маркеры 6×72.4 — по строке, у которой стоят: начало у верхней строки выделения
        // (макет: 148,257.8 при плашке 150,261.8, то есть на 4 выше), конец — у последней
        // строки (макет: 1305,326.2 при строке 330.2…394.6, тоже на 4 выше). Считать от низа
        // всего выделения нельзя: на многострочном выделении маркер уезжал бы на высоту
        // последней строки вверх — на меню B50.
        markStartX = firstX - MARK_OFF_X
        markStartY = top - MARK_OFF_Y
        markEndX = lastRight + MARK_GAP_X
        markEndY = lastTop - MARK_OFF_Y
        markShown = true
        ink.rect(c, markStartX, markStartY, MARK_W, MARK_H, T.G1)
        ink.rect(c, markEndX, markEndY, MARK_W, MARK_H, T.G1)
        if (!bar) return
        selBarY = selBar.top(top, bottom)
        selBar.draw(c, selBarY)
    }

    /**
     * Касание меню выделения (B50). `true` — касание наше: меню, маркер, само выделение или
     * «мимо» — всё это не должно доходить до третей и меню B9.
     *
     * Порядок: клавиши меню, затем маркеры, затем «мимо» — снять выделение. Маркеры
     * проверяются после меню, потому что панель может стоять над выделением и накрывать их
     * (у последних абзацев страницы): что нарисовано сверху, то и нажимается.
     */
    private fun selectionTouch(x: Float, y: Float): Boolean {
        if (selRects.isEmpty()) return false
        when (selBar.hit(x, y, selBarY)) {
            SelectionBar.ACT_QUOTE -> addQuote()
            SelectionBar.ACT_NOTE -> openNote()
            SelectionBar.ACT_DICT -> openDict()
            SelectionBar.ACT_FIND -> {
                val w = selText.trim()
                clearSelection()
                openSearch(w)
            }
            SelectionBar.ACT_COPY -> copySelection()
            else -> {
                // Касание по маркеру двигает границу на слово: левый — начало, правый — конец.
                // Правила E-Ink запрещают перетаскивание, поэтому расширение идёт касаниями:
                // сколько раз нажал, на столько слов и растянулось (решение 2026-11-20).
                when (markerHit(x, y)) {
                    MARK_START -> extendSelection(-1)
                    MARK_END -> extendSelection(1)
                    else -> clearSelection()      // мимо меню и маркеров — снять выделение
                }
            }
        }
        return true
    }

    /**
     * Какой маркер под точкой: [MARK_START], [MARK_END] или [MARK_NONE]. Геометрия — та же,
     * что нарисована в [drawSelectionOver]. Сам маркер 6 дизайн-px шириной пальцем не поймать,
     * поэтому попадание считается с запасом [MARK_HIT_PAD] по бокам и снизу-сверху: зона
     * выходит 6 + 2×45 = 96 дизайн-px — минимум тач-зоны по правилам (`ARCHITECTURE.md`).
     * Запас ограничен так, чтобы зоны начала и конца не смыкались на коротком выделении.
     */
    private fun markerHit(x: Float, y: Float): Int {
        if (!markShown) return MARK_NONE
        val padY = MARK_HIT_PAD_Y
        // Половина расстояния между маркерами — предел запаса, иначе на слове в 3 буквы
        // оба маркера ловили бы одно и то же касание.
        val room = ((markEndX - markStartX) / 2f - MARK_W / 2f).coerceAtLeast(0f)
        val padX = MARK_HIT_PAD.coerceAtMost(room)
        if (y >= markStartY - padY && y <= markStartY + MARK_H + padY &&
            x >= markStartX - padX && x <= markStartX + MARK_W + padX
        ) return MARK_START
        if (y >= markEndY - padY && y <= markEndY + MARK_H + padY &&
            x >= markEndX - padX && x <= markEndX + MARK_W + padX
        ) return MARK_END
        return MARK_NONE
    }

    /**
     * Расширить выделение на слово: [dir] < 0 — начало, > 0 — конец. Движок двигает границу
     * сам ([Engine.selectionExtend]: идёт по видимым словам, поэтому курсив и сноски не рвут
     * слово), нам остаётся перечитать текст, позиции и отрезки — ровно как после удержания.
     *
     * Перед запросом движок возвращается на показанную страницу ([Engine.syncPage]): фоновая
     * отрисовка соседней страницы оставляет его на ней, и отрезки пришли бы не с этого кадра
     * (та же грабля, что у [Engine.wordAt], 2026-11-17).
     *
     * Выделение при этом остаётся на месте — меняются плашки, маркеры и меню.
     */
    private fun extendSelection(dir: Int) {
        if (selecting || selRects.isEmpty()) return
        selecting = true
        val page = cur
        Engine.worker.execute {
            val r = try { Engine.selectionExtend(dir) } catch (t: Throwable) {
                Trace.step("b50: extend FAIL ${t.javaClass.simpleName}: ${t.message}")
                -1
            }
            // 0 — край текста: расширять некуда, выделение не трогаем.
            if (r <= 0) {
                selecting = false
                Trace.step("b50: расширение $dir — ${if (r == 0) "край текста" else "нет выделения"}")
                return@execute
            }
            if (r > 0) Engine.syncPage(page)
            val text = Engine.selectionText().orEmpty()
            val a = Engine.selectionPos(0)
            val b = Engine.selectionPos(1)
            val rects = Engine.rangeRects(null, null) ?: IntArray(0)
            // Слово за краем страницы: плашек на кадре нет, и выделение «пропало» бы с экрана,
            // оставаясь в движке. Откатываем — так же, как у края текста.
            if (r > 0 && rects.isEmpty()) {
                try { Engine.selectionExtend(-dir) } catch (_: Throwable) {
                }
                selecting = false
                Trace.step("b50: расширение $dir — за краем страницы, откат")
                return@execute
            }
            selecting = false
            if (rects.isEmpty()) return@execute
            post {
                if (!attached) return@post
                selText = text
                selStart = a
                selEnd = b
                selRects = rects
                Trace.step("b50: расширено '${text.take(40)}' отрезков=${rects.size / 4}")
                redraw()
            }
        }
    }

    /** «Копировать»: выделенный текст в системный буфер Android. */
    private fun copySelection() {
        val s = selText
        if (s.isEmpty()) return
        try {
            val cm = act.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Цитата", s))
            Trace.step("b50: скопировано ${s.length} символов")
        } catch (t: Throwable) {
            Trace.step("b50: буфер обмена FAIL ${t.javaClass.simpleName}: ${t.message}")
        }
        clearSelection()
    }

    /**
     * «Цитата»: выделение (xpointer начала и конца) уходит в базу видом
     * [LibraryDb.MARK_QUOTE]. Страница и глава — на момент сохранения: по ним список B27
     * и B28 показывают «Стр. N», если книгу нельзя открыть прямо сейчас.
     */
    private fun addQuote() {
        val e = entry
        val a = selStart
        val b = selEnd
        val text = selText
        val page = cur
        val chapter = chapterTitle.ifEmpty { null }
        clearSelection()
        if (e == null || e.id <= 0 || a.isNullOrEmpty() || b.isNullOrEmpty()) return
        Engine.worker.execute {
            val line = text.replace('\u00A0', ' ').trim().take(MARK_TEXT_MAX).ifEmpty { null }
            val id = try { act.db.addQuote(e.id, a, b, page, chapter, line) }
                catch (t: Throwable) {
                    Trace.step("b50: addQuote FAIL ${t.javaClass.simpleName}: ${t.message}")
                    -1L
                }
            Trace.step("b50: цитата id=$id стр.${page + 1} '${line.orEmpty().take(30)}'")
            if (id > 0) {
                reloadNotes()
                refreshPageMarks()
                post { if (attached) redraw() }
            }
        }
    }

    // ------------------------------------------------------------------ B51 «Новая заметка» (R4.3)

    /** «Заметка»: лист B51 поверх страницы; выделение остаётся — это и есть цитата заметки. */
    private fun openNote() {
        if (noteOpen) return
        noteView.open()
        noteOpen = true
        redraw()
        Trace.step("b51: open стр.${cur + 1} '${selText.take(30)}'")
    }

    private fun closeNote() {
        if (!noteOpen) return
        noteOpen = false
        redraw()
        Trace.step("b51: close")
    }

    /** Подпись места на B51: «Часть 1 · Глава I · стр. 124» — глава и страница. */
    private fun notePlace(): String {
        val page = "стр. ${cur + 1}"
        return if (chapterTitle.isEmpty()) page else "$chapterTitle · $page"
    }

    /**
     * «Сохранить» на B51: заметка (выделение + набранный текст) уходит в базу видом
     * [LibraryDb.MARK_NOTE]. Пустое поле лист не отдаёт: [NoteSheet.hit] в этом случае
     * возвращает `ACT_NONE`, и сюда дело не доходит.
     */
    private fun saveNote() {
        val e = entry
        val a = selStart
        val b = selEnd
        val text = selText
        val note = noteView.note()
        val page = cur
        val chapter = chapterTitle.ifEmpty { null }
        closeNote()
        if (e == null || e.id <= 0 || a.isNullOrEmpty() || b.isNullOrEmpty()) {
            clearSelection()
            return
        }
        clearSelection()
        Engine.worker.execute {
            val line = text.replace('\u00A0', ' ').trim().take(MARK_TEXT_MAX).ifEmpty { null }
            val id = try { act.db.addNote(e.id, a, b, page, chapter, line, note) }
                catch (t: Throwable) {
                    Trace.step("b51: addNote FAIL ${t.javaClass.simpleName}: ${t.message}")
                    -1L
                }
            Trace.step("b51: заметка id=$id стр.${page + 1} знаков=${note.length}")
            if (id > 0) {
                reloadNotes()
                refreshPageMarks()
                post { if (attached) redraw() }
            }
        }
    }

    // ------------------------------------------------------------------ B30 «Словарь и сноски» (R5)

    /**
     * «Словарь» в меню выделения B50: нижний лист B30 поверх страницы.
     *
     * Выделение остаётся — в макете слово под листом лежит во вдавленной плашке, её рисует
     * [drawSelectionUnder]. Слово для запроса берётся у движка (`selText`), а не из меню:
     * выделение может быть фразой, тогда словарь спросит её целиком и не найдёт — начало
     * формы отдаст поиск по Викисловарю.
     */
    private fun openDict() {
        if (dictOpen) return
        val w = selText.trim()
        if (w.isEmpty()) return
        dictView.open(w)
        dictOpen = true
        redraw()
        Trace.step("b30: open '$w'")
        askDict(w)
    }

    private fun closeDict() {
        if (!dictOpen) return
        dictOpen = false
        dictGen++            // ответ, который уже в пути, экрану не нужен
        redraw()
        Trace.step("b30: close")
    }

    /**
     * Запрос статьи в Викисловарь — только в фоне: `HttpURLConnection` на UI-потоке даёт
     * `NetworkOnMainThreadException`. Ответ приходит в `post`; устаревший (закрыли лист или
     * сменили вкладку) отбрасывается по [dictGen].
     */
    private fun askDict(word: String) {
        val my = ++dictGen
        Engine.worker.execute {
            val d = try { dictOnline.define(word) } catch (t: Throwable) {
                Trace.step("b30: define FAIL ${t.javaClass.simpleName}: ${t.message}")
                null
            }
            val why = if (d == null) dictOnline.why else null
            Trace.step("b30: '$word' -> ${d?.word ?: "нет"} ${why ?: ""}")
            post {
                if (!attached || my != dictGen || !dictOpen) return@post
                if (d == null) dictView.show(word, "", null, "", why)
                else dictView.show(d.word, d.grammar, d.definition, d.source, null)
                redraw()
            }
        }
    }

    /**
     * Вкладка «Перевод»: слово уходит в MyMemory. Направление — [ru.ogrpfsense.dexpm8.core.TrDir]:
     * русское слово → en, любое другое → ru; язык «откуда» — язык книги (`entry.lang`) или авто.
     */
    private fun askTranslate() {
        val w = dictView.word()
        if (w.isEmpty()) return
        val lang = entry?.lang
        val guess = ru.ogrpfsense.dexpm8.core.TrDir.pair(w, lang).label()
        dictView.waitTranslate(guess)
        redraw()
        val my = ++dictGen
        Engine.worker.execute {
            val t = try { dictOnline.translate(w, lang) } catch (e: Throwable) {
                Trace.step("b30: translate FAIL ${e.javaClass.simpleName}: ${e.message}")
                null
            }
            val why = if (t == null) dictOnline.why else null
            Trace.step("b30: перевод '$w' [$guess, язык книги ${lang ?: "—"}] -> ${t?.text ?: "нет"} ${why ?: ""}")
            post {
                if (!attached || my != dictGen || !dictOpen) return@post
                val dir = if (t != null) "${t.from} → ${t.to}" else guess
                dictView.showTranslate(dir, t?.text, why)
                redraw()
            }
        }
    }

    /**
     * Вкладка «Сноска» (R5.1): текст берётся из самой книги по якорю, который нашёлся
     * под словом ещё при удержании ([selHref]). Сеть не нужна, ответ приходит сразу —
     * поэтому ожидания в листе нет, а [dictGen] всё равно двигаем: ответ сети, идущий
     * с вкладки «Словарь», не должен перерисовать чужую вкладку.
     *
     * Текст сноски читает движок, поэтому вызов — в [Engine.worker]; до ответа лист
     * показывает [DictSheet.WAIT] (анимаций на E-Ink нет, кадр один).
     */
    private fun askNote() {
        val href = selHref
        if (href == null) {
            dictView.showNote(null, null)
            redraw()
            Trace.step("b30: сноска — слово не ссылка")
            return
        }
        dictView.waitNote()
        redraw()
        val my = ++dictGen
        Engine.worker.execute {
            val text = try { Engine.linkText(href) } catch (t: Throwable) {
                Trace.step("b30: linkText FAIL ${t.javaClass.simpleName}: ${t.message}")
                null
            }
            Trace.step("b30: сноска #$href -> ${text?.take(40) ?: "нет"}")
            post {
                if (!attached || my != dictGen || !dictOpen) return@post
                dictView.showNote(href, text)
                redraw()
            }
        }
    }

    /**
     * Касание листа B30: вкладки (у «Сноски» — текст из книги, у «Перевода» — сеть),
     * четыре действия внизу и «Закрыть».
     */
    private fun dictTouch(x: Float, y: Float) {
        val a = dictView.hit(x, y)
        when {
            a == DictSheet.ACT_CLOSE -> closeDict()
            a >= DictSheet.ACT_HIGHLIGHT -> dictAction(a - DictSheet.ACT_HIGHLIGHT)
            a >= DictSheet.ACT_TAB -> {
                val t = a - DictSheet.ACT_TAB
                if (!dictView.setTab(t)) return
                redraw()
                when (t) {
                    DictSheet.TAB_TRANSLATE -> askTranslate()
                    DictSheet.TAB_NOTE -> askNote()
                }
            }
        }
    }

    /**
     * Действия внизу листа B30 — те же, что в меню выделения B50: лист их дублирует
     * (макет B30), поэтому закрываем лист и зовём тот же код.
     */
    private fun dictAction(i: Int) {
        when (i) {
            0 -> { closeDict(); addQuote() }
            1 -> { closeDict(); openNote() }
            2 -> { closeDict(); copySelection() }
            else -> {
                val w = selText.trim()
                closeDict()
                clearSelection()
                openSearch(w)
            }
        }
    }


    /**
     * Пересчитать метки показанной страницы (B52): цитаты и заметки книги из базы, у каждой
     * свои отрезки через `Engine.rangeRects(start, end)`; не попавшие на эту страницу дают
     * пустой массив и отбрасываются. Только в `Engine.worker`.
     *
     * Номер заметки — её порядок от начала книги: его считает [LibraryDb.noteNumbers], то же
     * правило берёт вкладка «Заметки» B28. Поэтому номер на полях и в списке один и тот же:
     * в списке свежие сверху, и номер растёт снизу вверх.
     */
    private fun refreshPageMarks() {
        val e = entry
        if (e == null || e.id <= 0 || Engine.djvu) {
            pageMarks = emptyList()
            return
        }
        val list = try { act.db.notes(e.id) } catch (t: Throwable) { emptyList() }
        if (list.isEmpty()) {
            pageMarks = emptyList()
            return
        }
        val numbers = LibraryDb.noteNumbers(list)
        val out = ArrayList<PageMark>(4)
        for (m in list) {
            if (m.page != cur) continue
            val end = m.posEnd
            if (end.isNullOrEmpty()) continue
            val rects = try { Engine.rangeRects(m.pos, end) } catch (t: Throwable) { null }
            if (rects == null || rects.isEmpty()) continue
            out.add(PageMark(m.kind, rects, numbers[m.id] ?: 0))
        }
        pageMarks = out
    }

    /**
     * Метки B52 поверх страницы: Ø44 G3 со знаком «“» (цитата) или с номером (заметка)
     * на полях. Заливка заметки и подчёркивание цитаты рисуются под текстом —
     * [drawSelectionUnder]: на `ALPHA_8`-буфере они видны сквозь прозрачный фон.
     *
     * Метка стоит на одном и том же x = 1330, как в макете (текст кончается на 1301.7),
     * и не зависит от того, где кончилась строка: иначе она наезжала бы на слова.
     * По вертикали: у цитаты — верх её первой строки, у заметки — середина плашки
     * (макет: 269.8 при строке с 261.8 и 621.8 при плашке с 611.8).
     */
    private fun drawPageMarks(c: Canvas) {
        for (m in pageMarks) {
            val r = m.rects
            if (r.size < 4) continue
            var top = Float.MAX_VALUE
            var i = 0
            while (i + 3 < r.size) {
                val y = TY + r[i + 1] / D.u
                if (y < top) top = y
                i += 4
            }
            if (top == Float.MAX_VALUE) continue
            // Заметка: метка по середине плашки (611.8 → 621.8 при Ø44); цитата — по верху строки.
            val h = r[3] / D.u
            val badgeY = if (m.kind == LibraryDb.MARK_NOTE) top + (h - BADGE_D) / 2f else top
            markBadge(c, BADGE_X, badgeY, m)
        }
    }

    /**
     * Метка Ø44 на полях. У цитаты — знак «“» 44/800 G15, у заметки — её номер 26/800 G15.
     * В макете и то и другое: круг 1330, номер выровнен по центру круга, знак поднят на 8.
     */
    private fun markBadge(c: Canvas, x: Float, y: Float, m: PageMark) {
        ink.roundRect(c, x, y, BADGE_D, BADGE_D, BADGE_D / 2f, T.G3)
        if (m.number > 0) {
            ink.textCenter(c, m.number.toString(), x, BADGE_D,
                ink.baselineIn(y, BADGE_D, TS_BADGE, 800), TS_BADGE, 800, T.G15)
        } else {
            ink.textCenter(c, "“", x, BADGE_D,
                ink.baselineIn(y - BADGE_QUOTE_DY, BADGE_D, TS_QUOTE, 800),
                TS_QUOTE, 800, T.G15)
        }
    }

    // ------------------------------------------------------------------ B4 «Типографика» (R3.5)

    /**
     * Открыть лист B4 «Типографика» (кнопка «Текст» меню B9). Значения — из `Prefs`:
     * лист и экран настроек B39 пишут в одни и те же ключи (READER_SPEC, «жесты касания»).
     * У DjVu листа нет: у скана нет кегля — там «Масштаб» (B31, решение 2026-11-15).
     */
    private fun openTypo() {
        if (typoOpen || Engine.djvu || loading || pages <= 0) return
        val p = act.prefs
        typing = TypographySheet.State(
            face = p.textFace, size = p.textSize, spacing = p.textSpacing,
            margins = p.textMargins, hyphens = p.hyphens,
        )
        typo.open(typing)
        typoOpen = true
        redraw()
        Trace.step("b3: B4 open ${typing}")
    }

    private fun closeTypo() {
        if (!typoOpen) return
        typoOpen = false
        redraw()
        Trace.step("b3: B4 close")
    }

    /**
     * Значения листа изменились: записать в `Prefs` и переверстать книгу с удержанием места.
     * Перевёрстка на 5-МБ книге — 4,2–4,4 с (R3.4), поэтому идёт в `Engine.worker`, а лист
     * на это время не принимает касаний ([relayouting]) и показывает новые числа сразу.
     */
    private fun applyTypo(s: TypographySheet.State) {
        val before = typing
        typing = s
        val p = act.prefs
        p.textFace = s.face
        p.textSize = s.size
        p.textSpacing = s.spacing
        p.textMargins = s.margins
        p.hyphens = s.hyphens
        redraw()
        if (relayouting) return                  // применяется — по завершении возьмёт [typing]
        relayouting = true
        val my = ++epoch
        Engine.worker.execute {
            val pos = Engine.pos(cur)
            val np = Engine.setTypography(s.face, s.size, TypographySheet.INTERLINE[s.spacing],
                TypographySheet.MARGIN[s.margins], if (s.hyphens) 1 else 0, pos)
            Trace.step("b3: setTypography ${before} → $s pages=$np " +
                "${if (pos == null) "позиции нет" else "позиция есть"}")
            if (np <= 0) {
                relayouting = false
                post { if (attached) fail(if (np == 0) Engine.EPAGE else np, Engine.codeText(np)) }
                return@execute
            }
            pages = np
            // Место чтения — по xpointer: номер страницы после перевёрстки другой.
            val page = if (pos != null) Engine.pageOf(pos) else -1
            val target = if (page >= 0) page else cur.coerceIn(0, np - 1)
            cur = target
            nextPage = -1                    // заготовка нарисована старым кеглем
            shownPage = -1                   // кадр на экране тоже
            toc = Engine.toc()
            chapter(target)
            relayouting = false
            post {
                if (!attached || my != epoch) return@post
                // Пока верстали, на листе могли поменять ещё раз — сначала доводим до
                // последнего значения, иначе страница осталась бы от промежуточного.
                if (typing != s) {
                    applyTypo(typing)
                    return@post
                }
                Trace.step("b3: после типографики стр.${target + 1}/$np")
                show(target, 0)
                redraw()
            }
        }
    }

    /** Индекс пункта оглавления для текущей страницы — тот же, что даёт главу в колонтитуле. */
    private fun hereOf(): Int = tocIndex(cur)

    /** Индекс пункта оглавления, который начинается на странице [page] или раньше её. */
    private fun tocIndex(page: Int): Int {
        if (toc.isEmpty()) return -1
        var idx = -1
        for (i in toc.indices) {
            val p = toc[i].page
            if (p in 0..page) idx = i else if (p > page) break
        }
        return idx
    }

    /**
     * Название главы для страницы [page] — строка справа от поля B29 («Часть 2, глава V»).
     * У листа она про **целевую** страницу, а не про текущую: человек видит, куда попадает.
     */
    private fun chapterOf(page: Int): String {
        val i = tocIndex(page)
        return if (i >= 0) toc[i].title else ""
    }

    /** Перейти по пункту оглавления: позиция движка, а не номер страницы (переживает смену кегля). */
    private fun gotoItem(i: Int) {
        if (i < 0 || i >= toc.size) return
        val pos = toc[i].pos
        val page = toc[i].page
        if (pos == null && page < 0) return
        closeToc()
        val my = ++epoch
        Engine.worker.execute {
            val target = if (pos != null) Engine.gotoPos(pos) else page
            if (my != epoch) return@execute
            if (target < 0 || target >= pages) return@execute
            post {
                if (!attached) return@post
                show(target, 0)
            }
        }
        Trace.step("b3: toc goto $i «${toc[i].title}» pos=${if (pos == null) "нет" else "ok"} page=$page")
    }

    /**
     * Удалить закладку из списка B27 (корзина строки). Список остаётся открытым: человек видит,
     * что строка исчезла, а подпись вкладки стала «Закладки · N−1».
     *
     * Удаляем по `pos` строки — тому же xpointer, по которому идёт переход; номер страницы для
     * этого не нужен, и удаляется именно та закладка, на которую нажали.
     *
     * Если удалили закладку текущей страницы, [marked] пересчитываем — иначе кнопка «Закладка»
     * в меню B9 осталась бы вдавленной до следующего открытия меню.
     */
    private fun deleteMark(i: Int) {
        val list = if (tocTab == TocScreen.TAB_NOTE) tocNotes else marks
        if (i < 0 || i >= list.size) return
        val e = entry ?: return
        val m = list[i]
        val ok = try { act.db.removeMark(e.id, m.pos, m.kind) } catch (t: Throwable) {
            Trace.step("b3: removeMark FAIL ${t.javaClass.simpleName}: ${t.message}")
            false
        }
        Trace.step("b3: mark del $i kind=${m.kind} стр.${m.page + 1} ok=$ok")
        reloadMarks()
        // Строка была последней на странице — показали бы пустоту вместо предыдущих закладок.
        val pages = TocScreen.pageCount(tocTab, 0, markCnt, noteCnt)
        if (tocPage >= pages) tocPage = pages - 1
        redraw()
        if (!ok) return
        // Состояние после удаления пересчитываем в потоке движка (позиции и прямоугольники
        // спрашиваются у него): клавиша «Закладка» меню B9 и метка на поле B52. Без
        // `refreshPageMarks` значок цитаты/заметки оставался на странице до следующей
        // перерисовки с пересчётом (замечание 2026-11-21).
        Engine.worker.execute {
            refreshMark()
            refreshPageMarks()
            post {
                if (!attached) return@post
                reloadNotes()
                redraw()
            }
        }
    }

    /**
     * Перейти по закладке (R4.2) или по заметке/цитате (R4.3): сперва по её `pos` — позиции
     * движка, той же, что у места чтения, поэтому после смены кегля запись остаётся на своём
     * тексте. `pos` не принят (другая раскладка книги) — запасным путём служит `page`.
     */
    private fun gotoMark(i: Int) {
        val list = if (tocTab == TocScreen.TAB_NOTE) tocNotes else marks
        if (i < 0 || i >= list.size) return
        val m = list[i]
        closeToc()
        val my = ++epoch
        Engine.worker.execute {
            var target = try { Engine.gotoPos(m.pos) } catch (t: Throwable) { -1 }
            if (target < 0) target = m.page
            if (my != epoch) return@execute
            if (target < 0 || target >= pages) return@execute
            post {
                if (!attached) return@post
                show(target, 0)
            }
        }
        Trace.step("b3: mark goto $i стр.${m.page + 1} «${m.chapter ?: "—"}»")
    }

    /**
     * Колонтитулы B3: книга 73.1,37.9 и глава справа (правый край 1330.8), Manrope 30/600.
     * [color] — G4 обычно и G10 под листом B29, где в макете страница приглушена.
     * Полосы не пересекаются: у каждой половина ширины строки (73.1…1330.8) минус зазор,
     * иначе длинная глава налезает на название книги.
     */
    private fun header(c: Canvas, color: Int = T.G4) {
        if (title.isNotEmpty()) {
            val name = ink.ellipsize(title, HEAD_W, T.TS_META, 600)
            ink.text(c, name, 73.1f, T.base(37.9f, T.TS_META, 1.25f), T.TS_META, 600, color)
        }
        val ch = chapterTitle
        if (ch.isEmpty()) return
        val s = ink.ellipsize(ch, HEAD_W, T.TS_META, 600)
        ink.text(c, s, 1330.8f - ink.measure(s, T.TS_META, 600),
            T.base(37.9f, T.TS_META, 1.25f), T.TS_META, 600, color)
    }

    private fun footer(c: Canvas) {
        val pct = pctOf(cur)
        // Трек вдавленный: G15 / G10 / G12, дно 1018.3×13.7 в 75.9,1770.3 (B03.json).
        ink.inset(c, TRACK_X, TRACK_Y, TRACK_W, TRACK_H, T.R_COVER)
        if (pct > 0) {
            ink.roundRect(c, TRACK_X + T.INSET_DX, TRACK_Y + T.INSET_DY,
                TRACK_INNER_W * pct / 100f, TRACK_INNER_H, 6.85546875f, T.G3)
        }
        val page = "${cur + 1} / $pages"
        ink.text(c, page, 1330.8f - ink.measure(page, T.TS_LABEL, 700),
            T.base(1752.6f, T.TS_LABEL, 1.25f), T.TS_LABEL, 700, T.G1)
        ink.text(c, caption(pct), 73.1f, T.base(1803.8f, T.TS_META, 1.25f), T.TS_META, 600, T.G4)
    }

    private fun pctOf(page: Int): Int =
        if (pages <= 0) 0 else ((page + 1).toLong() * 100 / pages).toInt().coerceIn(0, 100)

    /**
     * Подпись под треком. В макете «35% · 4 мин до конца главы»; скорость чтения не
     * измеряется, поэтому вместо минут — страницы (записано в `docs/DESIGN_QUESTIONS.md`).
     * Главы нет (в книге нет оглавления) — только проценты. При чтении вслух (R6) подпись
     * говорит об этом: панель B9 при старте закрыта, и другого признака на экране нет.
     */
    private fun caption(pct: Int): String {
        if (ttsOn) return "Читаю вслух · $pct%"
        val end = chapterEnd
        if (end <= cur) return "$pct%"
        return "$pct% · ${end - cur} стр. до конца главы"
    }

    // ------------------------------------------------------------------ чтение вслух (R6)

    /**
     * «Вслух» меню B9 (п. 19, R6): начать чтение с текущей страницы или остановить.
     *
     * Меню закрывается при старте — читают с экрана, а не через панель; открыв меню снова,
     * человек увидит «Вслух» вдавленной и остановит чтение тем же нажатием. Остановка
     * без меню — любое ручное листание (см. [show]) и уход с экрана.
     *
     * Текст берётся в потоке движка: `Engine.pageText` читает разметку, и позиция вида
     * возвращается на страницу экрана ([Engine.syncPage]) — иначе фоновая отрисовка соседней
     * страницы подсунула бы текст следующей.
     *
     * У DjVu текстового слоя нет — кнопка мертва, как и выделение с поиском (READER_SPEC, R4).
     */
    private fun toggleSpeech() {
        if (Engine.djvu) {
            closeMenu()
            Trace.step("b3: вслух — у DjVu текста нет")
            return
        }
        if (ttsOn) {
            stopSpeech()
            redraw()
            return
        }
        val page = cur
        closeMenu()
        ttsTurn = false
        // Движок поднимается при первом обращении; готовность приходит колбэком.
        val sp = act.speech
        if (sp.ready == false || (sp.ready == true && !sp.hasRu)) return speechUnavailable()
        if (sp.ready == null) {
            // `init` на книге — 1–2 с; начинаем от готовности.
            act.speech.init()
        }
        ttsOn = true                                         // подпись «Читаю вслух» сразу
        setStayAwake(true)
        redraw()
        Engine.worker.execute { readPageAloud(page, 0) }
    }

    /**
     * Отдать страницу [page] голосу. Только в `Engine.worker`: текст берётся у разметки
     * движка.
     *
     * Страница без текста (обложка, иллюстрация, пустая страница перед главой) — не повод
     * останавливать чтение: листаем дальше и читаем следующую. [skipped] считает такие
     * страницы подряд — на конце книги голос не должен крутиться по кругу.
     */
    private fun readPageAloud(page: Int, skipped: Int) {
        if (!ttsOn || page < 0 || page >= pages) return
        Engine.syncPage(page)
        val text = try { Engine.pageText() } catch (t: Throwable) {
            Trace.step("tts: pageText FAIL ${t.javaClass.simpleName}: ${t.message}")
            null
        }
        if (text.isNullOrBlank()) {
            if (page >= pages - 1 || skipped >= TTS_SKIP_MAX) {
                Trace.step("tts: текста нет — стоп стр.$page пропущено=$skipped")
                post { stopSpeech(); redraw() }
                return
            }
            Trace.step("tts: стр.$page без текста — дальше")
            post {
                if (attached && ttsOn) {
                    ttsTurn = true
                    show(page + 1, 1)
                    Engine.worker.execute { readPageAloud(page + 1, skipped + 1) }
                }
            }
            return
        }
        post { if (attached && ttsOn) act.speech.readPage(page, text) }
    }

    /**
     * Голос дочитал страницу: листаем вперёд и читаем следующую — это и есть приёмка R6
     * «листание идёт за чтением». На последней странице чтение заканчивается.
     */
    private fun onSpeechPageDone() {
        if (!attached || !ttsOn) return
        if (cur >= pages - 1) {
            Trace.step("tts: конец книги")
            stopSpeech()
            redraw()
            return
        }
        val target = cur + 1
        ttsTurn = true
        show(target, 1)
        Engine.worker.execute { readPageAloud(target, 0) }
        Trace.step("tts: листание -> стр.${target + 1}")
        redraw()
    }

    /** Остановить чтение (второе нажатие «Вслух», ручное листание, уход с экрана). */
    private fun stopSpeech() {
        setStayAwake(false)
        if (!ttsOn) {
            act.speech.stop()
            return
        }
        ttsOn = false
        ttsTurn = false
        act.speech.stop()
    }

    /**
     * Чтение вслух — это прослушивание, а не касания: без этого флага система погасила бы
     * экран по тайм-ауту сна (`SCREEN_OFF_TIMEOUT`) посреди главы, и книга замолчала бы.
     * Флаг у окна, а не `WakeLock`: ни права на него, ни утечки.
     */
    private fun setStayAwake(on: Boolean) {
        try {
            keepScreenOn = on
        } catch (t: Throwable) {
            Trace.step("tts: keepScreenOn FAIL ${t.javaClass.simpleName}")
        }
    }

    /**
     * Читать нечем: на прошивке нет движка TTS или у него нет русского голоса (у запасного
     * `com.svox.pico` русского нет вовсе; на книге стоит `com.google.android.tts`
     * с голосом `ru-ru-x-dfc`, но проверка обязательна — на другой прошивке его может не быть).
     */
    private fun speechUnavailable() {
        Trace.step("act: вслух — голоса нет (ready=${act.speech.ready} ru=${act.speech.hasRu})")
        dialog.open(
            title = "Чтение вслух недоступно",
            sub = if (title.isEmpty()) "Книга" else title,
            note = "На устройстве нет движка синтеза речи с русским голосом.",
            label = "ЧТО СДЕЛАТЬ",
            code = "Настройки → Язык и ввод → Синтез речи",
            canDelete = false,
        )
        badPage = -1
        dialogOpen = true
        redraw()
    }

    // ------------------------------------------------------------------ открытие

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        // Экран живёт в кэше и отвязывается: подписка на прогресс — на каждый показ.
        Engine.setProgressListener { p -> post { onOpenProgress(p) } }
        // Чтение вслух (R6): голос дочитал страницу — листать дальше; отказ движка — стоп.
        val sp = act.speech
        sp.onPageDone = { post { onSpeechPageDone() } }
        sp.onFail = { why -> post { Trace.step("tts: $why"); stopSpeech(); redraw() } }
        sp.speed(act.prefs.ttsSpeed)
        start()
        // Время чтения (п. 22, B32): экран показан — начинаем или продолжаем отсчёт.
        resumeReading()
    }

    override fun onDetachedFromWindow() {
        attached = false
        // Время чтения (п. 22): экран уходит — сначала записываем набежавшее, потом всё остальное.
        pauseReading()
        gen++                 // результат уже идущей отрисовки выбрасываем
        stopOpenDialog()
        // Чтение вслух уходит вместе с экраном: голос без страницы на экране — это уже
        // не «чтение книги», а брошенный звук.
        stopSpeech()
        act.speech.onPageDone = null
        act.speech.onFail = null
        sheetOpen = false
        // Оверлеи R4.3 снимаем вместе с экраном: при возврате книга откроется на месте чтения,
        // а выделение и лист заметки к ушедшей странице уже не относятся.
        noteOpen = false
        dictOpen = false
        dictGen++
        selRects = IntArray(0)
        selText = ""
        selStart = null
        selEnd = null
        selHref = null
        pageMarks = emptyList()
        prevShown = null
        prevA = null
        prevB = null
        Engine.setProgressListener(null)
        // Очередь движка: сначала сохранить место чтения (позиция берётся у движка),
        // потом закрыть документ — он держит DOM и разметку (PSS).
        Engine.worker.execute {
            savePlace()
            Engine.close()
        }
        super.onDetachedFromWindow()
    }

    private fun start() {
        // B12 «Оглавление»: книгу открыли из оболочки — флаг читаем здесь и снимаем сразу,
        // чтобы он не сработал ещё раз (экран живёт в кэше и отвязывается).
        val wantToc = act.bookToc
        act.bookToc = false
        // Debug (R0): `--es probe <путь>` открывает файл напрямую, без базы.
        val dbg = ProbeScreen.debugPath
        ProbeScreen.debugPath = null
        if (dbg != null) {
            open(ru.ogrpfsense.dexpm8.core.LibraryDb.Entry(
                id = -1L, path = dbg, size = 0L, mtime = 0L,
                format = dbg.substringAfterLast('.', "?").lowercase(java.util.Locale.ROOT),
                title = dbg.substringAfterLast('/'), author = null, series = null, seriesNum = 0, lang = null,
                coverId = null, progress = 0, status = 0, added = 0L, opened = 0L,
            ), wantToc)
            return
        }
        val e = try {
            // Книга задана явно (B12, B1, отладка) — берём её; не нашли строку — ошибка, а не
            // «последняя прочитанная»: иначе откроется не та книга (R1.4).
            if (act.bookId > 0) act.db.get(act.bookId) else act.db.lastOpened()
        } catch (t: Throwable) {
            Trace.step("b3: db FAIL ${t.javaClass.simpleName}: ${t.message}")
            null
        }
        if (e == null) {
            fail(Engine.EOPEN, "Книга не найдена")
            return
        }
        // Книга открыта сейчас: B1 «Сейчас читаю» берёт последнюю по `opened`. Пишем здесь,
        // а не на кнопке «Читать» — вход в читалку есть и с главного экрана.
        try {
            act.db.markOpened(e.id)
        } catch (t: Throwable) {
            Trace.step("b3: markOpened FAIL ${t.javaClass.simpleName}: ${t.message}")
        }
        title = e.title
        open(e, wantToc)
    }

    /**
     * Открыть книгу. [wantToc] — после первой страницы показать оглавление B10: так на B12
     * работает кнопка «Оглавление» (самих пунктов до открытия документа в движке нет).
     */
    private fun open(e: LibraryDb.Entry, wantToc: Boolean = false) {
        val w = D.px(TW)
        val h = D.px(TH)
        if (w <= 0 || h <= 0) return
        // Типографика B4 из `Prefs` (R3.5): читаем здесь, на UI-потоке, — `SharedPreferences`
        // в поток движка не тащим. Движок держит свои настройки на процесс, но `Prefs` могли
        // сбросить или изменить на экране B39 — тогда перевёрстка (см. `Engine.applyTypography`).
        val pr = act.prefs
        val tp = TypographySheet.State(pr.textFace, pr.textSize, pr.textSpacing,
            pr.textMargins, pr.hyphens)
        typing = tp
        // Экран один на всё приложение и живёт в кэше: при новой книге старые поля
        // (номер страницы, буферы, глава) не должны попасть в кадр до первой отрисовки.
        reset()
        title = e.title
        entry = e
        // B31: настройки страницы DjVu — на книгу, база v9. Для crengine не используются.
        view = DjvuView.parse(e.view)
        pw = w
        ph = h
        bmpA = Engine.newPageBitmap(w, h)
        bmpB = Engine.newPageBitmap(w, h)
        prevA = Bitmap.createBitmap(PREVIEW_W, PREVIEW_H, Bitmap.Config.ALPHA_8)
        prevB = Bitmap.createBitmap(PREVIEW_W, PREVIEW_H, Bitmap.Config.ALPHA_8)
        prevShown = null
        buf = ByteArray(w * h)
        val my = ++gen
        cancelled = false
        openPct = 0
        loading = true
        removeCallbacks(showOpen)
        postDelayed(showOpen, OpenBookDialog.SHOW_AFTER_MS)
        Engine.worker.execute {
            val t0 = SystemClock.uptimeMillis()
            val rc = Engine.open(e.path, w, h)
            if (rc != Engine.OK) {
                Trace.step("b3: open rc=$rc ${Engine.codeText(rc)} ${SystemClock.uptimeMillis() - t0}ms")
                post {
                    if (!attached || my != gen) return@post
                    stopOpenDialog()
                    fail(rc, Engine.codeText(rc))
                }
                return@execute
            }
            // «Отмена» на B58: разметка в crengine не прерывается (см. `cre_session.cpp`),
            // поэтому открытие доводится до конца и тут же закрывается — экран уже ушёл.
            if (cancelled || my != gen) {
                Trace.step("b3: open отменён ${SystemClock.uptimeMillis() - t0}ms")
                Engine.close()
                return@execute
            }
            // Типографика B4 — сразу после открытия: движок применил свои настройки на
            // `LoadDocument`, а [tp] могла измениться, пока книга открывалась. Место чтения
            // передаётся позицией: номер страницы после перевёрстки другой.
            val trc = Engine.applyTypography(tp.face, tp.size,
                TypographySheet.INTERLINE[tp.spacing],
                TypographySheet.MARGIN[tp.margins], tp.hyphens, e.pos)
            if (trc < 0) {
                // Книга открыта — читать можно: типографика не применилась, страницы как есть.
                Trace.step("b3: applyTypography FAIL $trc ${Engine.codeText(trc)}")
            }
            pages = Engine.pageCount()
            toc = Engine.toc()

            // Место чтения — позицией движка, а не номером страницы (READER_SPEC, раздел 4).
            // Не нашлась (книга переверстана, файл заменён) — начинаем с начала.
            val saved = Engine.gotoPos(e.pos)
            cur = if (saved in 0 until pages) saved else 0
            val ms = SystemClock.uptimeMillis() - t0
            Trace.step("b3: open ok ${ms}ms pages=$pages toc=${toc.size} " +
                "pos=${if (e.pos.isNullOrEmpty()) "нет" else "есть -> $saved"}")
            post {
                if (!attached || my != gen) return@post
                stopOpenDialog()
                chapter(cur)
                show(cur, 0)
                // B12 «Оглавление»: страница под панелью уже нарисована — открываем список.
                // Пустое оглавление (часть DjVu) панель не откроет: остаётся страница чтения.
                if (wantToc) openToc()
            }
        }
    }

    /** Забыть прошлую книгу: номер страницы, глава, готовые буферы и открытый диалог. */
    private fun reset() {
        title = ""
        pages = 0
        cur = 0
        chapterTitle = ""
        chapterEnd = -1
        chapterStart = -1
        shownB = false
        nextPage = -1
        shownPage = -1
        flips = 0
        toc = emptyList()
        dialogOpen = false
        badPage = -1
        stopOpenDialog()
        cancelled = false
        // B31: лист и всё, что живёт вокруг него, — от прошлой книги.
        sheetOpen = false
        menuOpen = false
        tocOpen = false
        gotoOpen = false
        typoOpen = false
        searchOpen = false
        relayouting = false

        tocPage = 0
        prevPage = -1
        prevShown = null
        panLast = null
        wantPage = -1
        auto.clear()
        pan.clear()
    }

    /**
     * Сохранить место чтения: позиция текущей страницы и прогресс. Только в `Engine.worker` —
     * берёт `cur` и сам движок. Книга без строки в базе (отладочный `--es probe`) не пишется.
     */
    private fun savePlace() {
        val e = entry ?: return
        if (e.id <= 0 || pages <= 0) return
        val pos = Engine.pos(cur)
        val pct = (cur + 1).toLong().times(100).div(pages).toInt().coerceIn(0, 100)
        try {
            act.db.setPlace(e.id, pos, pct)
            // Дочитана (п. 22): B32 считает «книг закончено» по этой метке.
            if (pct >= 100) act.db.markFinished(e.id)
            Trace.step("b3: save page=${cur + 1}/$pages $pct% pos=${if (pos == null) "нет" else "ok"}")
        } catch (t: Throwable) {
            Trace.step("b3: save FAIL ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * Сохранить место сейчас же — вызов с UI-потока (уход приложения в фон, [EinkActivity.onPause]).
     * Задача ставится в очередь движка: `Engine.pos` требует его потока.
     */
    fun saveNow() {
        if (!attached) return
        Engine.worker.execute { savePlace() }
    }

    /**
     * Приложение вернулось из фона, а читалка на экране: продолжить отсчёт времени чтения (п. 22).
     * Зовётся оболочкой из `onResume` — обратная пара к [pauseNow].
     */
    fun resumeNow() {
        if (!attached) return
        resumeReading()
    }

    /**
     * Приложение ушло в фон: записать набежавшее время чтения (п. 22, B32). Зовётся оболочкой
     * из `onPause` рядом с [saveNow] — процесс могут убить в фоне, и минуты не должны пропасть.
     */
    fun pauseNow() {
        if (!attached) return
        pauseReading()
    }

    /**
     * Начать отсчёт времени чтения (п. 22, B32): книга открыта и видна на экране. Зовётся при
     * показе экрана и при возврате приложения из фона; повторный вызов ничего не сбрасывает.
     *
     * Время идёт по `uptimeMillis` — это счётчик, который **не тикает в глубоком сне**: если
     * экран погас и книга в фоне, время не зачтётся (для статистики это верно — человек не читал).
     */
    private fun resumeReading() {
        if (readStart == 0L) readStart = android.os.SystemClock.uptimeMillis()
    }

    /**
     * Записать набежавшее время чтения в базу и остановить отсчёт. Зовётся при уходе с экрана,
     * в фон и при закрытии книги — так на E-Ink редкие записи, а не таймер.
     *
     * Запись идёт в поток движка: `addReading` пишет в базу, а UI-поток занят кадром. Короткие
     * сеансы (меньше [READ_MIN_SEC]) не пишутся — иначе случайное открытие книги дарило бы
     * минуты статистики.
     */
    private fun pauseReading() {
        val start = readStart
        readStart = 0L
        if (start == 0L) return
        val sec = (android.os.SystemClock.uptimeMillis() - start) / 1000L
        val pages = readPages
        readPages = 0
        flushReading(sec, pages)
    }

    /** Дописать [sec] секунд и [pages] страниц за сегодня по открытой книге. */
    private fun flushReading(sec: Long, pages: Int) {
        val e = entry ?: return
        if (e.id <= 0) return
        if (sec < READ_MIN_SEC && pages <= 0) return
        val day = LibraryDb.day()
        Engine.worker.execute {
            try {
                act.db.addReading(e.id, day, sec, pages)
            } catch (t: Throwable) {
                Trace.step("b32: addReading FAIL ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    /**
     * Остановить чтение вслух снаружи — оболочка зовёт в `onPause`: приложение ушло в фон
     * (кнопка «Домой», выключение экрана), голос из свёрнутой книги мешал бы всему остальному.
     */
    fun stopSpeechNow() {
        if (!ttsOn) return
        stopSpeech()
        redraw()
    }

    /** Ошибка открытия: диалог B45 поверх пустой страницы. */
    private fun fail(rc: Int, text: String) {
        Trace.step("b3: FAIL $rc $text")
        dialog.open(
            title = "Книга не открылась",
            sub = if (title.isEmpty()) "Файл" else title,
            note = "Файл повреждён или формат не поддерживается. Остальные книги открываются как обычно.",
            label = "КОД ОШИБКИ",
            code = text,
            canDelete = false,
        )
        badPage = -1
        dialogOpen = true
        redraw()
    }

    /**
     * B59 «Страница повреждена» (R2.3): книга открыта, не декодируется одна страница
     * (`EPAGE`, например обрезанный DjVu). Остальные страницы листаются; «Следующая стр.»
     * сразу просит [page] + 1. Тексты предложены чату дизайна — `docs/DESIGN_QUESTIONS.md`.
     */
    private fun pageFail(page: Int) {
        Trace.step("b3: B59 page $page")
        badPage = page
        dialog.open(
            title = "Страница не открылась",
            sub = if (title.isEmpty()) "Файл" else title,
            note = "Эта страница в файле повреждена. Остальные страницы открываются — можно листать дальше.",
            label = "КОД ОШИБКИ",
            code = "Стр. ${page + 1} из $pages · ${Engine.codeText(Engine.EPAGE)}",
            canDelete = false,
            next = page + 1 < pages,
        )
        dialogOpen = true
        redraw()
    }

    // ------------------------------------------------------------------ листание

    /**
     * Показать страницу [page]: если соседняя уже отрисована в фоне — мгновенное
     * переключение буфера, иначе отрисовка в потоке движка. [dir] — куда листали
     * (+1 вперёд, −1 назад, 0 — открытие): соседняя в этом же направлении готовится заранее.
     */
    private fun show(page: Int, dir: Int) {
        if (loading) return             // книга ещё открывается, листать нечего
        if (page < 0 || page >= pages) return
        // Ручное листание прекращает чтение вслух (R6): голос, ушедший от страницы на экране,
        // читал бы не то, что видно. Листание самого чтения помечено [ttsTurn].
        val bySpeech = ttsTurn
        ttsTurn = false
        if (!bySpeech && ttsOn) stopSpeech()
        // Выделение живёт на конкретной странице: при листании его снимаем (пункт 17 ROADMAP —
        // выделение по рамкам слов текущей страницы, ушедшая страница их уже не отрисует).
        if (selRects.isNotEmpty()) {
            selRects = IntArray(0)
            selText = ""
            selStart = null
            selEnd = null
            selHref = null
            markShown = false
            Engine.worker.execute { Engine.selectionClear() }
        }
        if (page == shownPage && nextPage == -1) {    // уже на экране
            if (wantPage == page) wantPage = -1
            return
        }
        val my = ++epoch
        Engine.worker.execute { showLocked(page, dir, my) }
    }

    /** Всё это — только в `Engine.worker`. */
    private fun showLocked(page: Int, dir: Int, my: Int) {
        if (my != epoch) return          // поверх встало другое листание
        if (page < 0 || page >= pages) return
        if (nextPage == page) {
            // Готовый буфер: переключаем без обращения к движку (≤ 300 мс от касания).
            shownB = !shownB
            nextPage = -1
            shownPage = page
            cur = page
            // Движок обычно уже на этой странице (`prefetch` её и рисовал), но после
            // удержания он возвращён на прежнюю ([Engine.syncPage]) — тогда метки B52
            // считались бы по чужой странице. Проверка дешёвая: совпало — выходит сразу.
            if (!Engine.djvu) Engine.syncPage(page)
            if (wantPage == page) wantPage = -1
            chapter(page)
            flips++
            readPages++               // страница — статистике B32
            refreshPageMarks()        // B52: метки живут на конкретной странице
            if (flips % SAVE_EVERY == 0) savePlace()
            val full = flips % FULL_EVERY == 0
            post {
                if (!attached) return@post
                if (full && Epd.ok) {
                    Epd.request(this, Epd.CLEAN, false)
                    Trace.step("b3: full frame after $flips")
                }
                redraw()
            }
            prefetch(dir)
            return
        }
        val w = pw
        val h = ph
        val out = buf ?: return
        val t0 = SystemClock.uptimeMillis()
        val rc = draw(page, w, h, out)
        val ms = SystemClock.uptimeMillis() - t0
        if (rc != Engine.OK) {
            Trace.step("b3: page $page rc=$rc ${Engine.codeText(rc)}")
            // Страница не декодировалась, а книга открыта — B59; остальное — B45.
            if (rc == Engine.EPAGE && pages > 0) post { if (attached) pageFail(page) }
            else post { if (attached) fail(rc, Engine.codeText(rc)) }
            return
        }
        copyToHidden(page)
        cur = page
        chapter(page)
        flips++
        readPages++                   // страница — статистике B32
        refreshPageMarks()            // B52: метки живут на конкретной странице
        if (flips % SAVE_EVERY == 0) savePlace()
        val full = flips % FULL_EVERY == 0
        Trace.step("b3: page $page ${ms}ms")
        post {
            if (!attached) return@post
            if (full && Epd.ok) {
                Epd.request(this, Epd.CLEAN, false)
                Trace.step("b3: full frame after $flips")
            }
            redraw()
        }
        prefetch(dir)
    }

    /**
     * Страница [page] в буфер [out]: DjVu — с настройками листа B31 (`Engine.pageView`),
     * crengine — обычным `Engine.page`. Только в `Engine.worker`.
     *
     * Обрезка берётся по режиму листа: «Вручную» — промилле из настроек, «Авто» — найденные
     * по превью этой страницы ([autoOf]), «Без обрезки» — никакой. Предел сдвига движок
     * возвращает в `panOut` — он запоминается для страницы, чтобы трети знали, сколько
     * ещё можно сдвинуть.
     */
    private fun draw(page: Int, w: Int, h: Int, out: ByteArray): Int {
        if (!Engine.djvu) return Engine.page(page, w, h, out)
        val v = view
        val crop = when (v.cropMode) {
            DjvuView.CROP_NONE -> null
            DjvuView.CROP_AUTO -> autoOf(page)
            else -> v.crop()
        }
        val ve = if (crop == null) v.copy(cropL = 0, cropT = 0, cropR = 0, cropB = 0)
        else v.copy(cropL = crop[0], cropT = crop[1], cropR = crop[2], cropB = crop[3])
        val panOut = PAN_OUT
        val rc = Engine.pageView(page, w, h, out, ve, panOut)
        if (rc == Engine.OK) {
            val lim = intArrayOf(panOut[2], panOut[3])
            panLast = lim
            if (pan.size >= CACHE_MAX) pan.clear()
            pan[page] = lim
        }
        return rc
    }

    // ------------------------------------------------------------------ авто-обрезка (R2.5)

    /**
     * Промилле авто-обрезки для страницы [page]; null — не режем. Считается по превью
     * 600×820 ([ensurePreview]) и запоминается: страница листается туда-сюда, а рендер
     * превью — лишний кадр движка. Кэш маленький ([AUTO_MAX]) — память 1 ГБ.
     */
    private fun autoOf(page: Int): IntArray? {
        val known = auto[page] ?: computeAuto(page)
        return if (known.isEmpty()) null else known
    }

    /** Посчитать авто-обрезку страницы [page] и запомнить; пустой массив — полей нет. */
    private fun computeAuto(page: Int): IntArray {
        val c = if (ensurePreview(page)) DjvuAuto.measure(prevBuf, PREVIEW_W, PREVIEW_H) else null
        val v = c ?: NO_CROP
        if (auto.size >= CACHE_MAX) auto.clear()     // страниц много, держим только свежие
        auto[page] = v
        return v
    }

    /**
     * Отрисовать превью страницы [page] (600×820, без обрезки и сдвига) в [prevBuf] —
     * тот самый кадр, что видит человек на листе B31. Только в `Engine.worker`.
     * false — движок не отдал кадр (тогда и резать не по чему).
     */
    private fun ensurePreview(page: Int): Boolean {
        if (prevPage == page) return true
        val rc = Engine.pageView(page, PREVIEW_W, PREVIEW_H, prevBuf, DjvuView(), null)
        if (rc != Engine.OK) {
            Trace.step("b3: preview $page rc=$rc ${Engine.codeText(rc)}")
            return false
        }
        prevPage = page
        showPreview()
        return true
    }

    /** Превью из рабочего буфера в `Bitmap` листа — по правилу «писать в worker, показывать по post». */
    private fun showPreview() {
        val shown = prevShown
        val b = if (shown === prevA) prevB else prevA
        if (b == null) return
        b.copyPixelsFromBuffer(ByteBuffer.wrap(prevBuf, 0, PREVIEW_W * PREVIEW_H))
        val page = prevPage
        post {
            if (!attached || prevPage != page) return@post    // уже отрисовано другое
            prevShown = b
            sheet.setPreview(b)
            if (sheetOpen) redraw()
        }
    }

    /** Лист B31 ждёт превью текущей страницы: дверь — касание колонтитула (R2.5). */
    private fun openSheet() {
        if (!Engine.djvu || pages <= 0) return
        sheetOpen = true
        sheet.open(sheetHeader())
        // Кадр текущей страницы уже мог быть отрисован ради авто-обрезки — показываем его,
        // иначе лист сначала пустой и превью приходит из worker.
        sheet.setPreview(if (prevPage == cur) prevShown else null)
        redraw()
        val page = cur
        Engine.worker.execute {
            ensurePreview(page)
            post { if (attached && sheetOpen) redraw() }
        }
    }

    /** Колонтитул листа: «Атлас мира.djvu · стр. 12 из 240» (в макете B31). */
    private fun sheetHeader(): String {
        val name = entry?.path?.substringAfterLast('/') ?: title
        return "$name · стр. ${cur + 1} из $pages"
    }

    private fun closeSheet() {
        if (!sheetOpen) return
        sheetOpen = false
        sheet.setPreview(null)
        redraw()
    }

    /**
     * Настройки листа изменились: страницу перерисовать и записать строку в базу.
     * Перерисовка идёт через [epoch]: ранний выход `page == shownPage && nextPage == -1`
     * в [showLocked] не дал бы нарисовать ту же страницу заново.
     */
    private fun applyView(v: DjvuView) {
        view = v
        sheet.setPreview(sheetPreviewOrNull())
        val e = entry
        if (e != null && e.id > 0) {
            try {
                act.db.setView(e.id, v.encode())
            } catch (t: Throwable) {
                Trace.step("b3: setView FAIL ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        redraw()
        if (!Engine.djvu || pages <= 0) return
        val page = cur
        val my = ++epoch
        Engine.worker.execute {
            nextPage = -1                     // заготовка нарисована старыми настройками
            showLocked(page, 0, my)
        }
        Trace.step("b3: view ${v.encode()}")
    }

    private fun sheetPreviewOrNull(): Bitmap? = if (prevPage >= 0) prevShown else null

    /** Буфер движка → непоказанный `Bitmap`, показ переключается на него. */
    private fun copyToHidden(page: Int) {
        val out = buf ?: return
        val b = if (shownB) bmpA else bmpB
        if (b == null) return
        b.copyPixelsFromBuffer(ByteBuffer.wrap(out, 0, pw * ph))
        shownB = !shownB
        shownPage = page
        nextPage = -1
        // Кнопки листания меню B9 отсчитывают от последней запрошенной страницы — она показана.
        if (wantPage == page) wantPage = -1
    }

    /**
     * Соседняя страница в фоне — листание тогда мгновенное. Считается устаревшей, если
     * [epoch] сменился: значит, поверх встало другое листание (проверка до и после отрисовки).
     */
    private fun prefetch(dir: Int) {
        if (dir == 0) return
        val p = cur + dir
        if (p < 0 || p >= pages) return
        val w = pw
        val h = ph
        val out = buf ?: return
        val my = epoch
        Engine.worker.execute {
            if (my != epoch) return@execute
            val t0 = SystemClock.uptimeMillis()
            val rc = draw(p, w, h, out)      // те же настройки B31, что и у показанной страницы
            if (rc != Engine.OK || my != epoch) return@execute
            // В непоказанный буфер, но экран на него не переключаем: это заготовка.
            val b = (if (shownB) bmpA else bmpB) ?: return@execute
            b.copyPixelsFromBuffer(ByteBuffer.wrap(out, 0, pw * ph))
            nextPage = p
            Trace.step("b3: prefetch $p ${SystemClock.uptimeMillis() - t0}ms")
        }
    }

    override fun onUp(x: Float, y: Float) {
        // B51 «Новая заметка» (R4.3) — раньше всего: лист лежит над страницей и над выделением.
        if (noteOpen) {
            when (noteView.hit(x, y)) {
                NoteSheet.ACT_SAVE -> saveNote()
                NoteSheet.ACT_CHANGED -> redraw()
                NoteSheet.ACT_CLOSE -> { closeNote(); clearSelection() }
            }
            return
        }
        if (dialogOpen) {
            val a = dialog.hit(x, y)
            if (a != ErrorDialog.ACT_NONE) {
                dialogOpen = false
                redraw()
                // B59: «Следующая стр.» — сразу за повреждённой, мимо неё.
                if (a == ErrorDialog.ACT_NEXT && badPage >= 0) show(badPage + 1, 1)
                badPage = -1
            }
            return
        }
        // B31 поверх страницы: касания только его (и «Готово» — закрыть).
        if (sheetOpen) {
            when (sheet.hit(x, y, view)) {
                DjvuViewSheet.ACT_CHANGED -> applyView(sheet.result)
                DjvuViewSheet.ACT_REPAINT -> redraw()
                DjvuViewSheet.ACT_CLOSE -> closeSheet()
            }
            return
        }
        // B28 «Поиск по книге» (R4.3): касания только его (клавиатура, вкладки, строки, «Назад»).
        if (searchOpen) {
            val a = searchView.hit(x, y, chapterStart, chapterEnd)
            when {
                a == SearchBookScreen.ACT_CLOSE -> closeSearch()
                a == SearchBookScreen.ACT_PREV -> { searchView.prev(); redraw() }
                a == SearchBookScreen.ACT_NEXT -> { searchView.next(); redraw() }
                a == SearchBookScreen.ACT_OPEN -> {
                    val hit = searchView.hitAt(chapterStart, chapterEnd)
                    if (hit != null) gotoSearchHit(hit)
                }
                a == SearchBookScreen.ACT_NOTE -> {
                    val m = searchView.noteAt()
                    if (m != null) gotoMarkPos(m.pos, m.page, "заметка")
                }
                a == SearchBookScreen.ACT_CHANGED -> redraw()
                a == SearchBookScreen.ACT_SEARCH -> runSearch()
                // Смена вкладки — коды 10…12; проверяется после ACT_CHANGED/ACT_SEARCH (20/21).
                a >= SearchBookScreen.ACT_TAB -> {
                    redraw()
                    // Новая вкладка могла потребовать поиска: набрано, но не искали.
                    if (searchView.needSearch()) runSearch()
                }
                else -> closeSearch()
            }
            return
        }
        // B10/B27: касания только списка (вкладки, строки, пагинация, «Назад»).
        if (tocOpen) {
            val a = tocView.hit(x, y, tocTab, tocPage, toc, marks, tocNotes)
            when {
                a == TocScreen.ACT_CLOSE -> closeToc()
                a == TocScreen.ACT_PREV -> { tocPage--; redraw() }
                a == TocScreen.ACT_NEXT -> { tocPage++; redraw() }
                a == TocScreen.ACT_OPEN -> gotoItem(tocView.chosen)
                a == TocScreen.ACT_MARK -> gotoMark(tocView.chosenMark)
                a == TocScreen.ACT_DEL -> deleteMark(tocView.chosenMark)
                a >= TocScreen.ACT_TAB -> switchTocTab(a - TocScreen.ACT_TAB)
            }
            return
        }
        // B4 «Типографика»: касания только листа (кегль, гарнитура, интервал, поля, переносы).
        // Пока идёт перевёрстка, лист касаний не принимает — иначе она встанет в очередь
        // движка и человек решит, что клавиша не работает.
        if (typoOpen) {
            if (relayouting) return
            when (typo.hit(x, y, typing)) {
                TypographySheet.ACT_CLOSE -> closeTypo()
                TypographySheet.ACT_CHANGED -> applyTypo(typo.result)
            }
            return
        }
        // B29 «Переход на страницу»: касания только листа (клавиатура, шкала, кнопки).

        if (gotoOpen) {
            when (goto.hit(x, y, pages, chapterStart)) {
                GotoSheet.ACT_CLOSE -> closeGoto()
                GotoSheet.ACT_GO -> gotoPage()
                GotoSheet.ACT_REPAINT -> redraw()
            }
            return
        }
        // B30 «Словарь и сноски» (R5): лист перекрывает страницу целиком — касание только его.
        if (dictOpen) {
            dictTouch(x, y)
            return
        }
        // B50 «Выделение» (R4.3): пока выделено, касание сначала отдаётся меню выделения —
        // «мимо меню» снимает выделение, а не листает страницу и не открывает B9.
        if (selRects.isNotEmpty() && selectionTouch(x, y)) return
        // B9 «Меню чтения»: пока открыто, касания только его.
        if (menuOpen) {
            when (menu.hit(x, y, Engine.djvu)) {
                ReaderMenu.ACT_CLOSE -> closeBook()
                // Листание из меню (замечание 2026-11-16): меню остаётся открытым — человек
                // листает несколько страниц подряд и видит на панели, где он оказался.
                ReaderMenu.ACT_PREV -> step(-1)
                ReaderMenu.ACT_NEXT -> step(1)
                ReaderMenu.ACT_TOC -> { closeMenu(); openToc() }
                ReaderMenu.ACT_SCALE -> { closeMenu(); openSheet() }
                // «Текст» у crengine — лист B4 «Типографика» (R3.5); у DjVu это «Масштаб».
                ReaderMenu.ACT_TEXT -> { closeMenu(); openTypo() }

                // «Поиск» в шапке меню — экран B28 «Поиск по книге» (R4.3).
                ReaderMenu.ACT_SEARCH -> { closeMenu(); openSearch() }

                // Шкала и подпись «Стр. N из M» на нижней панели — дверь в B29 (R3.3).
                ReaderMenu.ACT_GOTO -> { closeMenu(); openGoto() }
                // «Закладка» в шапке (R4.1): поставить или снять на текущей странице. Меню
                // остаётся открытым — видно, что клавиша стала вдавленной (или перестала).
                ReaderMenu.ACT_MARK -> toggleBookmark()
                // «Вслух» (R6): начать с текущей страницы или остановить, если уже читаем.
                // Меню при старте закрывается — иначе вместо текста видна панель.
                ReaderMenu.ACT_TTS -> toggleSpeech()
                // «Ещё» — B32 «Статистика чтения» (п. 22). Меню закрывается: экран полный.
                ReaderMenu.ACT_MORE -> { closeMenu(); act.go(EinkActivity.SCR_STATS) }
                // Мимо меню — закрыть. Мёртвых кнопок в меню нет: «Контраст» из нижнего ряда
                // и «Ещё» (⋮) из шапки убраны решением пользователя 2026-11-21.
                else -> closeMenu()
            }
            return
        }
        // B58: пока книга открывается, касания только на «Отмену» — страниц ещё нет.
        if (opening) {
            if (openDialog.hitCancel(x, y)) cancelOpen()
            return
        }
        // Середина центральной колонки — «Меню чтения» B9 (решение 2026-11-12:
        // верх ≈27% — шторка B5, низ ≈27% — шторка B4, между ними — меню).
        if (x >= ShadeScreen.ZONE_X0 && x <= ShadeScreen.ZONE_X1 &&
            y > ShadeScreen.ZONE_READER && y < MENU_ZONE_Y1) {
            openMenu()
            return
        }
        // Трети: сначала сдвиг увеличенной страницы, листание — когда сдвигать нечего.
        val dir = when {
            x < W / 3f -> -1
            x > W * 2f / 3f -> 1
            else -> 0
        }
        if (dir != 0 && panPage(dir)) return
        if (dir < 0) back() else if (dir > 0) forward()
    }

    /**
     * Сдвиг страницы третями (решение дизайна по B31, `B31б`): касание левой/правой трети
     * сдвигает кадр на треть окна, пока есть что сдвигать. false — сдвигать некуда,
     * экран листает страницу как обычно.
     *
     * Условие — настройки DjVu и запас сдвига у страницы, который вернул движок
     * (`panOut[2]`/`panOut[3]`). Сначала идёт горизонталь, потом вертикаль: у скана
     * обычно крупнее только одна сторона.
     */
    private fun panPage(dir: Int): Boolean {
        if (!Engine.djvu) return false
        val v = view
        // Предел — от последнего кадра: страница на экране, настройки текущие.
        val lim = panLast ?: return false
        val mx = lim[0]
        val my = lim[1]
        if (mx <= 0 && my <= 0) return false
        val stepX = (pw / 3).coerceAtLeast(1)
        val stepY = (ph / 3).coerceAtLeast(1)
        // Крупнее по вертикали (свой масштаб на длинную страницу) — вертикаль первой.
        val vFirst = my > mx
        var dx = 0
        var dy = 0
        if (vFirst) {
            dy = if (dir > 0) Math.min(v.panY + stepY, my) else v.panY - stepY
            if (dy == v.panY) dx = if (dir > 0) Math.min(v.panX + stepX, mx) else v.panX - stepX
        } else {
            dx = if (dir > 0) Math.min(v.panX + stepX, mx) else v.panX - stepX
            if (dx == v.panX) dy = if (dir > 0) Math.min(v.panY + stepY, my) else v.panY - stepY
        }
        val nx = dx.coerceIn(0, mx)
        val ny = dy.coerceIn(0, my)
        if (nx == v.panX && ny == v.panY) return false     // двигать некуда — листаем
        applyView(v.pan(nx, ny))
        Trace.step("b3: pan ${nx},${ny} max ${mx},${my}")
        return true
    }

    /**
     * Свайп листает страницу (настройка B39 «Листать свайпом», `Prefs.swipe`). Правило E-Ink
     * — «касание — это нажал и отпустил» — здесь расширено единственным жестом и только по
     * настройке: палец уезжает по экрану на 120 дизайн-px и больше (≈9 % ширины), при этом
     * горизонталь перевешивает вертикаль вдвое — иначе случайное «уползание» пальца
     * при листании третями стало бы листанием. Влево — вперёд, вправо — назад, как страницу
     * в бумажной книге.
     *
     * Открытые листы, меню и диалоги жест не принимают — как у клавиш в [key]: пока поверх
     * страницы что-то лежит, листать нечего.
     */
    override fun onSwipe(x0: Float, y0: Float, x1: Float, y1: Float): Boolean {
        if (!act.prefs.swipe) return false
        if (dialogOpen || sheetOpen || menuOpen || tocOpen || gotoOpen || typoOpen || searchOpen) return false
        if (opening || loading || pages <= 0) return false
        val dx = x1 - x0
        val dy = y1 - y0
        if (Math.abs(dx) < SWIPE_MIN || Math.abs(dx) < Math.abs(dy) * SWIPE_ASPECT) return false
        // Увеличенный скан (B31) сдвигается третями: жест листает только когда сдвигать нечего.
        val dir = if (dx < 0) 1 else -1
        if (!panPage(dir)) {
            if (dir > 0) forward() else back()
        }
        Trace.step("b3: swipe ${dx.toInt()},${dy.toInt()} dir=$dir")
        return true
    }

    /** «Назад»: сначала B10 и меню B9, листы B4 и B31, диалоги, потом уход из книги. */
    override fun backPressed(): Boolean {
        // B30: «Назад» закрывает лист словаря, выделение под ним снимается вместе с листом.
        if (dictOpen) {
            closeDict()
            return true
        }
        // B51: «Назад» закрывает лист заметки (и снимает выделение) — книга остаётся открытой.
        if (noteOpen) {
            closeNote()
            clearSelection()
            return true
        }
        // B50: пока выделено, «Назад» снимает выделение, а не уходит из книги.
        if (selRects.isNotEmpty()) {
            clearSelection()
            return true
        }
        if (searchOpen) {
            closeSearch()
            return true
        }
        if (typoOpen) {
            closeTypo()
            return true
        }
        if (gotoOpen) {
            closeGoto()
            return true
        }
        if (tocOpen) {
            closeToc()
            return true
        }
        if (menuOpen) {
            closeMenu()
            return true
        }
        if (sheetOpen) {
            closeSheet()
            return true
        }
        if (dialogOpen) {
            dialogOpen = false
            badPage = -1
            redraw()
            return true
        }
        return false
    }

    /**
     * «Отмена» на B58. Разметку crengine не прерывает (флага остановки в движке нет),
     * поэтому: экран уходит в библиотеку сразу, результат открытия выбрасывается,
     * документ закрывается по возврату `Engine.open` (см. `open`).
     */
    private fun cancelOpen() {
        Trace.step("b3: отмена открытия на ${openPct}%")
        cancelled = true
        gen++            // результат открытия уже не нужен
        loading = false
        opening = false
        removeCallbacks(showOpen)
        act.onBackPressed()
    }

    private fun forward() = show(cur + 1, 1)

    private fun back() = show(cur - 1, -1)

    /**
     * Листание кнопками нижней панели меню B9 (замечание 2026-11-16): страница переворачивается,
     * **меню остаётся открытым** — им листают несколько страниц подряд, и на панели видно, где
     * человек оказался.
     *
     * Отсчёт идёт от [wantPage], а не от [cur]: движок рисует страницу в своём потоке, поэтому
     * два быстрых нажатия подряд иначе слились бы в один переворот — второе считалось бы от ещё
     * не обновлённой `cur`. [wantPage] сбрасывается, когда запрошенная страница действительно
     * показана ([showLocked]).
     */
    private fun step(dir: Int) {
        if (loading || pages <= 0) return
        val from = if (wantPage >= 0) wantPage else cur
        val target = (from + dir).coerceIn(0, pages - 1)
        if (target == from) {
            Trace.step("b3: меню листание $dir — край книги стр.${from + 1}/$pages")
            return
        }
        if (target == shownPage && nextPage == -1) {
            // Качнули вперёд и сразу назад, пока движок не успел: страница та же, что на экране.
            wantPage = -1
            Trace.step("b3: меню листание $dir — вернулись на стр.${target + 1}/$pages")
            return
        }
        wantPage = target
        show(target, dir)
        // Пересчёт «Закладки» — только для этой отрисовки: следующее нажатие сменит [epoch],
        // и этот блок выйдет, не тронув клавишу. Лишнего кадра не заказываем — перерисовка
        // нужна, только если состояние клавиши изменилось.
        val my = epoch
        Engine.worker.execute {
            if (my != epoch) return@execute
            val was = marked
            refreshMark()
            if (was != marked) post { if (attached && menuOpen) redraw() }
        }
        Trace.step("b3: меню листание $dir -> стр.${target + 1}/$pages")
    }

    /**
     * «Назад» в шапке меню B9 (замечание 2026-11-16): закрыть книгу и оказаться там, откуда её
     * открыли — главный экран (B1), библиотека, полка, карточка B12: экран читалки снимается
     * со стека, и оболочка показывает предыдущий.
     *
     * Раньше кнопка только закрывала меню, и это выглядело как «вернулся на страницу книги».
     * Клавиша «Назад» системы при этом не меняется: она по-прежнему сначала закрывает листы
     * и меню ([backPressed]) — иначе из книги нельзя было бы выйти без потери меню.
     *
     * Место чтения сохраняет [onDetachedFromWindow] вместе с закрытием документа — здесь
     * сохранять нечего.
     */
    private fun closeBook() {
        if (loading) return      // B58: открытие ещё идёт, у него своя «Отмена»
        // Кнопка меню значит «закрыть книгу», а не «закрыть то, что лежит поверх»: иначе
        // `EinkActivity.onBackPressed` отдал бы нажатие экрану и, например, погасил диалог B45.
        // Все свои оверлеи снимаем сами — экран уходит из книги с чистым состоянием.
        menuOpen = false
        tocOpen = false
        gotoOpen = false
        typoOpen = false
        searchOpen = false
        sheetOpen = false
        dialogOpen = false
        noteOpen = false
        dictOpen = false
        dictGen++
        selRects = IntArray(0)
        selText = ""
        selStart = null
        selEnd = null
        selHref = null
        pageMarks = emptyList()
        badPage = -1
        Trace.step("b3: назад в оболочку со стр.${cur + 1}/$pages")
        act.onBackPressed()
    }

    /**
     * Физические клавиши книги (см. шапку класса). Возвращает true, если клавиша наша.
     *
     * Клавиши страниц (`gpio-keys`) листают всегда. Клавиши громкости (`adc-keys`) листают,
     * только если на B39 выбрано «Листание» (`Prefs.volumeKeys`); при «Громкость» они уходят
     * системе — тогда закрытый лист не должен съедать их, это проверяет [handlesKey].
     */
    fun key(code: Int): Boolean {
        if (dialogOpen) return false
        if (noteOpen) return false     // B51: лист с клавиатурой, страница не листается
        if (dictOpen) return false     // B30: лист словаря поверх страницы
        if (sheetOpen) return false    // B31: пока лист открыт, страница не листается
        if (menuOpen) return false     // B9: меню закрывается касанием или «Назад»
        if (tocOpen) return false      // B10: список листается своими кнопками
        if (searchOpen) return false   // B28: список и клавиатура, страница не листается
        if (gotoOpen) return false     // B29: лист с клавиатурой, страница не листается
        if (opening) return false     // B58: страниц ещё нет, клавиши листания не наши
        val next = code == KEY_PAGEDOWN || (volumePaging() && code == KEY_VOLUME_DOWN)
        val prev = code == KEY_PAGEUP || (volumePaging() && code == KEY_VOLUME_UP)
        if (!next && !prev) return false
        if (next) forward() else back()
        return true
    }

    /**
     * Берёт ли читалка эту клавишу себе (для `ACTION_UP` в `EinkActivity`). false — клавиша
     * наша по типу, но отдана системе: громкость при настройке «Громкость».
     */
    fun handlesKey(code: Int): Boolean = when (code) {
        KEY_PAGEUP, KEY_PAGEDOWN -> true
        KEY_VOLUME_UP, KEY_VOLUME_DOWN -> volumePaging()
        else -> false
    }

    /** Листают ли клавиши громкости (B39). */
    private fun volumePaging() = act.prefs.volumeKeys == ru.ogrpfsense.dexpm8.core.Prefs.VOLUME_PAGING

    /** Глава для страницы [page]: последний пункт оглавления, начавшийся не позже неё. */
    private fun chapter(page: Int) {
        if (toc.isEmpty()) {
            chapterTitle = ""
            chapterEnd = -1
            chapterStart = -1
            return
        }
        var idx = -1
        for (i in toc.indices) {
            val p = toc[i].page
            if (p in 0..page) idx = i else if (p > page) break
        }
        if (idx < 0) {
            chapterTitle = ""
            chapterEnd = -1
            chapterStart = -1
            return
        }
        val level = toc[idx].level
        var end = pages
        for (i in idx + 1 until toc.size) {
            val p = toc[i].page
            if (toc[i].level <= level && p >= 0) {
                end = p
                break
            }
        }
        chapterTitle = toc[idx].title
        chapterEnd = end
        // Клавиша «Начало главы» (B29): если номер пункта не пришёл от движка — берём эту страницу.
        chapterStart = if (toc[idx].page >= 0) toc[idx].page else page
    }

    companion object {
        /** Область текста страницы из B03.json: x 102.4, y 117, 1199.3×1540. */
        private const val TX = 102.4f
        private const val TY = 117f
        private const val TW = 1199.3f
        private const val TH = 1540f

        /** Трек прогресса и заливка внутри него (B03.json). */
        private const val TRACK_X = 73.1f
        private const val TRACK_Y = 1764.8f
        private const val TRACK_W = 1023.7f
        private const val TRACK_H = 21.9f
        private const val TRACK_INNER_W = 1018.3f
        private const val TRACK_INNER_H = 13.7f

        /** Полная перерисовка каждые N страниц (READER_SPEC, раздел 6; в B37 пока нет). */
        private const val FULL_EVERY = 6

        /**
         * Чтение вслух (R6): сколько страниц подряд без текста (иллюстрации, обложка) голос
         * перешагивает, прежде чем остановиться. 40 — заведомо больше любой вклейки картинок.
         */
        private const val TTS_SKIP_MAX = 40
        /**
         * Ширина половины колонтитула B3: строка 73.1…1330.8 (1257.7) делится пополам с зазором 40.
         * Раньше обе половины были по 780 и налезали друг на друга у длинных глав.
         */
        private const val HEAD_W = 600f

        /**
         * Нижняя граница зоны «Меню чтения» B9 (решение 2026-11-12): ниже начинается шторка
         * B4 «Типографика» (y ≈1358). Границы взяты с картинки пользователя на глаз и
         * подтверждаются на книге.
         */
        private const val MENU_ZONE_Y1 = 1358f

        /** Свайп листания (B39 «Листать свайпом»): порог хода и перевес горизонтали. */
        private const val SWIPE_MIN = 120f
        private const val SWIPE_ASPECT = 2f
        /** Сколько символов текста страницы хранить в закладке (R4.1): одна строка списка B27. */
        private const val MARK_TEXT_MAX = 120

        // --- B50 «Выделение» (R4.3)
        /** Скругление плашки выделения: строка текста 64 высотой при радиусе 10. */
        private const val R_SEL = 10f
        /** Маркеры выделения: 6×72.4 (B50.json), вынесены на 4 влево/вверх от плашки. */
        private const val MARK_W = 6f
        private const val MARK_H = 72.4f
        private const val MARK_OFF_X = 2f
        private const val MARK_OFF_Y = 4f
        private const val MARK_GAP_X = 0f
        /**
         * Запас касания по маркеру: сам он 6 дизайн-px шириной, пальцем в него не попасть.
         * По 45 влево-вправо даёт зону 96 — минимум для тач-зон (`ARCHITECTURE.md`); запас
         * урезается, если маркеры стоят близко, иначе их зоны слились бы в одну.
         * По вертикали маркер 72.4 высотой, запас 12 — зона 96.4.
         */
        private const val MARK_HIT_PAD = 45f
        private const val MARK_HIT_PAD_Y = 12f
        /** Что под точкой: маркер начала, маркер конца или ничего. */
        private const val MARK_NONE = 0
        private const val MARK_START = 1
        private const val MARK_END = 2

        // --- B52 «Метки» (R4.3)
        /** Подчёркивание цитаты: полоса G3 в 4 px по низу строки. */
        private const val QUOTE_UNDER_H = 4f
        /** Заливка заметки шире отрезка текста на 2 с каждой стороны (1213 против 1209 строки). */
        private const val MARK_PLATE_PAD = 2f
        /** Метка на полях: Ø44 в 1330 — текст страницы кончается на 1301.7 (B52.json). */
        private const val BADGE_D = 44f
        private const val BADGE_X = 1330f
        /** Номер заметки 26/800, знак цитаты 44/800; знак в макете поднят на 8. */
        private const val TS_BADGE = 26f
        private const val TS_QUOTE = 44f
        private const val BADGE_QUOTE_DY = 8f

        // --- B31 «Масштаб и поля» (R2.5)
        /** Размер превью листа: 600×820 — слой «Страница DjVu» макета, без обрезки. */
        private const val PREVIEW_W = 600
        private const val PREVIEW_H = 820
        /** Сколько страниц помнить в кэше авто-обрезки и по каким настроить трети. */
        private const val CACHE_MAX = 64
        /** Пометка «полей нет» в кэше авто-обрезки (пустой массив короче любого результата). */
        private val NO_CROP = IntArray(0)
        /** Выход движка `[panX, panY, maxX, maxY]` — свой на каждый запрос, чтобы не делиться. */
        private val PAN_OUT = IntArray(4)

        /**
         * Место чтения в базу каждые N перевёрнутых страниц: если книгу выключить на середине,
         * позиция всё равно останется (выход из читалки сохраняет её отдельно).
         */
        private const val SAVE_EVERY = 10

        /**
         * Короче этого сеанс чтения в статистику не попадает (п. 22, B32): открыл книгу, посмотрел
         * обложку, вышел — это не чтение. Страницы считаются всегда: листание — уже действие.
         */
        private const val READ_MIN_SEC = 20L

        private const val KEY_PAGEUP = android.view.KeyEvent.KEYCODE_PAGE_UP
        private const val KEY_PAGEDOWN = android.view.KeyEvent.KEYCODE_PAGE_DOWN
        private const val KEY_VOLUME_UP = android.view.KeyEvent.KEYCODE_VOLUME_UP
        private const val KEY_VOLUME_DOWN = android.view.KeyEvent.KEYCODE_VOLUME_DOWN
    }
}
