package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import ru.ogrpfsense.dexpm8.core.LibraryDb

/**
 * B1 · Главный экран. Числа — из design/v5/B01.json; базовые линии — по рамкам текста
 * через [T.base] (top + fs·(lh/2 + k)).
 *
 *   статус-бар   [StatusBar]
 *   дата         51.2,111     34/600 G4
 *   приветствие  51.2,149.6   80/300 G1
 *   карточка     41.1,275.1   1320.8×741.3 (выпуклая, r=58.5), фон G14
 *   гнездо       87.8,321.8   292.5×424.1 (вдавленное, дно G12), обложка 106,340 r=18.28
 *   прогресс     431.4,716.6  884.8×29.2 (дорожка G12), заливка G3
 *   кнопка       85,816.3     1233.1×141.6 (тёмная, r=65.81), стрелка поверх неё
 *   плитки       41.1/721.2 × 1072.2/1437.8, 640.8×331.8; круглая кнопка иконки на +43.9,+43.9
 *   статистика   0,1803.6     30/600 G4 по центру
 */
class HomeScreen(act: EinkActivity) : Screen(act) {

    private val dateFmt = SimpleDateFormat("EEEE, d MMMM", Locale("ru"))

    private var date = ""

    /** Приветствие по времени суток: меняется без действий пользователя, поэтому хранится. */
    private var greet = ""

    private val status = StatusBar(act, this, ink, icons)

    private val hits = ArrayList<Hit>(8)

    private class Hit(val id: Int, val x: Float, val y: Float, val w: Float, val h: Float)

    init {
        refreshHeader()
    }

    /**
     * Дата и приветствие из системных часов. Час берём локальный ([Calendar.HOUR_OF_DAY]):
     * 5–11 «Доброе утро», 12–17 «Добрый день», 18–22 «Добрый вечер», 23–4 «Доброй ночи».
     *
     * Возвращает true, если строка шапки изменилась — только тогда экран перерисовывается:
     * на E-Ink лишний кадр это мерцание.
     */
    private fun refreshHeader(): Boolean {
        val cal = Calendar.getInstance()
        val d = dateFmt.format(cal.time)
        val nd = d.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("ru")) else it.toString() }
        val ng = greeting(cal.get(Calendar.HOUR_OF_DAY))
        val changed = nd != date || ng != greet
        date = nd
        greet = ng
        return changed
    }

    private fun greeting(hour: Int): String = when (hour) {
        in 5..11 -> "Доброе утро"
        in 12..17 -> "Добрый день"
        in 18..22 -> "Добрый вечер"
        else -> "Доброй ночи"
    }

    override fun layout(w: Float, h: Float) {
        super.layout(w, h)
        hits.clear()
        for (t in tiles) hits.add(Hit(t.screen, t.x, t.y, TILE_W, TILE_H))
        hits.add(Hit(EinkActivity.SCR_READER, BTN_X, BTN_Y, BTN_W, BTN_H))
    }

    override fun render(c: Canvas) {
        ink.rect(c, 0f, 0f, W, H, T.BG)
        status.draw(c)
        header(c)
        card(c)
        continueButton(c)
        tiles(c)
        todayLine(c)
    }

    /**
     * Строка «Сегодня 42 мин · 31 стр.» из макета (0,1803.6, 30/600 G4 по центру) — дверь
     * в B32 «Статистика чтения» (п. 22). Данных нет — строка не рисуется: пустая дверь
     * на E-Ink только мешает.
     */
    private fun todayLine(c: Canvas) {
        val s = today ?: return
        ink.textCenter(c, s, 0f, W, T.base(1803.6f, T.TS_META, 1.25f), T.TS_META, 600, T.G4)
    }

    private fun header(c: Canvas) {
        ink.text(c, date, 51.2f, T.base(111f, T.TS_BODY, 1.25f), T.TS_BODY, 600, T.G4)
        ink.text(c, greet, 51.2f, T.base(149.6f, T.TS_GREETING, 1.2f),
            T.TS_GREETING, 300, T.G1)
    }

    // --------------------------------------------------------------- карточка

    private fun card(c: Canvas) {
        ink.convex(c, 41.1f, 275.1f, 1320.8f, 741.3f, T.R_CARD, T.G14, T.G15, T.G10)

        // Гнездо обложки — вдавленная форма 292.5×424.1, дно G12.
        ink.inset(c, 87.8f, 321.8f, 292.5f, 424.1f, T.R_COVER_SEAT, T.G12)
        ink.roundRect(c, 106f, 340f, 252.3f, 383.9f, T.R_COVER_L, T.G14)

        val tx = 431.4f
        ink.text(c, "Сейчас читаю", tx, T.base(327.2f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)

        val e = book
        if (e == null) {
            // Ничего не открывали: подсказка вместо данных книги, обложка — пустое гнездо.
            ink.text(c, "Пока ничего не открыто", tx, T.base(364.4f, T.TS_TITLE, 1.2f, true),
                T.TS_TITLE, 500, T.G1, T.literata(ink.res, 500))
            ink.text(c, "Выберите книгу в библиотеке", tx, T.base(440.1f, T.TS_LABEL, 1.25f),
                T.TS_LABEL, 600, T.G4)
            return
        }

        // Название сверху, автор снизу — единая раскладка обложек ([CoverText]).
        val author = e.author ?: "Автор неизвестен"
        coverText.draw(c, e.title, e.author, 131.6f, COVER_TEXT_W, 376.6f, COVER_BOTTOM,
            30f, 1.15f, T.TS_CAPTION, 1.25f)

        ink.text(c, ink.ellipsize(e.title, TITLE_MAX_W, T.TS_TITLE, 500, T.literata(ink.res, 500)),
            tx, T.base(364.4f, T.TS_TITLE, 1.2f, true), T.TS_TITLE, 500, T.G1,
            T.literata(ink.res, 500))
        ink.text(c, ink.ellipsize("$author · ${e.format.uppercase()}", TITLE_MAX_W, T.TS_LABEL, 600),
            tx, T.base(440.1f, T.TS_LABEL, 1.25f), T.TS_LABEL, 600, T.G4)

        // Процент — из базы; «до конца» и главы — данных нет до читалки, не рисуем.
        val pct = e.progress.coerceIn(0, 100)
        ink.text(c, pct.toString(), tx, T.base(517.2f, T.TS_HUGE, 1.2f), T.TS_HUGE, 200, T.G1)
        val pctX = tx + ink.measure(pct.toString(), T.TS_HUGE, 200) + 8f
        ink.text(c, "%", pctX, T.base(542.4f, T.TS_PERCENT, 1.25f), T.TS_PERCENT, 400, T.G1)
        val cap = T.base(577.7f, T.TS_BODY_S, 1.4f)
        ink.text(c, "прочитано", pctX + 80.5f, cap, T.TS_BODY_S, 600, T.G4)

        // Дорожка 431.4,716.6 884.8×29.2 (дно G12), заливка pct % от 879.3 на 434.2,722.1.
        ink.inset(c, tx, 716.6f, 884.8f, 29.2f, T.R_TRACK, T.G12)
        val fill = 879.3f * pct / 100f
        if (fill >= 21f) ink.roundRect(c, 434.2f, 722.1f, fill, 21f, 10.51171875f, T.G3)
    }

    private fun continueButton(c: Canvas) {
        ink.convexDark(c, BTN_X, BTN_Y, BTN_W, BTN_H, T.R_BUTTON)
        ink.text(c, if (book != null) "Продолжить чтение" else "Открыть библиотеку", 146.3f,
            T.base(858.8f, T.TS_ACTION, 1.25f), T.TS_ACTION, 700, T.G15)
        icons.draw(c, "arrowR", 1221.2f, 862.9f, 43.9f, 43.9f)
    }

    // --------------------------------------------------------------- плитки

    /** [count] пустой — счётчик не рисуем (данных нет до п. 9 «Аудиокниги» и п. 5 «Приложения»). */
    private class Tile(
        var label: String, var count: String, var icon: String,
        val x: Float, val y: Float, val screen: Int
    )

    private val tiles = arrayOf(
        Tile("Библиотека", "", "library", 41.1f, 1072.2f, EinkActivity.SCR_LIBRARY),
        Tile("Аудиокниги", "", "audio", 721.2f, 1072.2f, EinkActivity.SCR_AUDIO_LIST),
        Tile("Приложения", "", "apps", 41.1f, 1437.8f, EinkActivity.SCR_APPS),
        Tile("Настройки", "Экран, сеть, сон", "settings", 721.2f, 1437.8f, EinkActivity.SCR_SETTINGS)
    )

    /** Данные из базы: последняя открытая книга и число книг. Только UI-поток. */
    private var book: LibraryDb.Entry? = null
    private val coverText = CoverText(ink)

    /**
     * «Сегодня 42 мин · 31 стр.» — сводка дня для строки-двери в B32 (п. 22); null — сегодня
     * ещё не читали, строка не рисуется.
     */
    private var today: String? = null

    private fun refresh() {
        try {
            book = act.db.lastOpened()
            tiles[0].count = booksWord(act.db.count())
            // «Аудиокниги» — число аудиокниг (п. 12); своего приложения у плитки нет.
            tiles[1].count = audioWord(act.db.audioCount())
            tiles[2].count = appsWord(AppsScreen.count(act))
            refreshPinned()
            refreshToday()
        } catch (t: Throwable) {
            Trace.step("home refresh FAIL: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * Сводка дня для строки «Сегодня …»: минуты и страницы из `reading` (п. 22). Меньше минуты
     * не показываем — «0 мин» на главном выглядит как поломка.
     */
    private fun refreshToday() {
        val d = LibraryDb.day()
        val st = act.db.stats(d, d)
        val min = st.totalSec / 60L
        val pages = st.totalPages
        today = if (min <= 0L && pages <= 0L) null
        else "Сегодня ${Words.plural(min.toInt(), "минута", "минуты", "минут")} · " +
            "$pages стр."
    }

    private fun audioWord(n: Int): String {
        if (n <= 0) return ""
        val m100 = n % 100
        val m10 = n % 10
        val w = if (m100 in 11..14) "аудиокниг" else when (m10) {
            1 -> "аудиокнига"
            2, 3, 4 -> "аудиокниги"
            else -> "аудиокниг"
        }
        return "$n $w"
    }
    /** «Закрепить на главном» (B36): вторая плитка — выбранное приложение вместо «Аудиокниг». */
    private var pinned: AppEntry? = null

    private fun refreshPinned() {
        val a = AppsScreen.pinned(act, act.prefs.pinnedApp)
        pinned = a
        val t = tiles[1]
 if (a == null) {
     t.label = "Аудиокниги"
     t.icon = "audio"
        } else {
            t.label = a.label
     t.icon = if (a.icon in PINNED_ICONS) a.icon else "apps"
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

    private fun appsWord(n: Int): String {
        val m100 = n % 100
        val m10 = n % 10
        val w = if (m100 in 11..14) "приложений" else when (m10) {
            1 -> "приложение"
            2, 3, 4 -> "приложения"
            else -> "приложений"
        }
        return "$n $w"
    }

    private fun tiles(c: Canvas) {
        for (t in tiles) {
            ink.convex(c, t.x, t.y, TILE_W, TILE_H, T.R_TILE)
            // Круглая кнопка иконки 136.2×141.6 (диск 131.6) на +43.9,+43.9.
            ink.convex(c, t.x + 43.9f, t.y + 43.9f, 136.2f, 141.6f, 65.8f)
            // Плитка и поверхность кнопки — один G14, поэтому тела кнопки не видно: круг читается
            // по кольцу (блик слева-сверху + тень справа-снизу). Центруем по кольцу, иначе
            // пиктограмма «висит» вверх-влево на 3.6 px (замер по снимку 2026-11-17).
            icons.drawRoundRing(c, t.icon, t.x + 43.9f, t.y + 43.9f, 136.2f, 141.6f, 73.1f, 76.2f)
            // Подпись и счётчик — под иконкой, x +46.7.
            ink.text(c, t.label, t.x + 46.7f, T.base(t.y + 202f, T.TS_TILE, 1.25f),
                T.TS_TILE, 700, T.G1)
            if (t.count.isNotEmpty()) {
                ink.text(c, t.count, t.x + 46.7f, T.base(t.y + 261f, T.TS_BODY_S, 1.25f),
                    T.TS_BODY_S, 600, T.G4)
            }
        }
    }

    private companion object {
        const val TILE_W = 640.8f
        const val TILE_H = 331.8f
        const val BTN_X = 85f
        const val BTN_Y = 816.3f
        const val BTN_W = 1233.1f
        const val BTN_H = 141.6f
        const val COVER_TEXT_W = 200f     // обложка 252.3 минус поля 25.6 с каждой стороны
        /** Низ текста автора на обложке: обложка 340…723.9 минус поле 25.6. */
        const val COVER_BOTTOM = 698f
 const val TITLE_MAX_W = 884.8f    // от 431.4 до края дорожки
        /** Иконки плиток B1 нарисованы под 73.1×76.2 — только они годятся для закреплённого приложения. */
        val PINNED_ICONS = setOf("apps", "settings", "library", "audio")
    }

    // --------------------------------------------------------------- ввод

    override fun onUp(x: Float, y: Float) {
        // Строка «Сегодня …» — дверь в B32 (п. 22): панель по низу всего экрана.
        if (today != null && y >= 1780f) {
            act.go(EinkActivity.SCR_STATS)
            return
        }
        for (h in hits) {
     if (x >= h.x && x <= h.x + h.w && y >= h.y && y <= h.y + h.h) {
  val p = pinned
         if (p != null && h.x == tiles[1].x && h.y == tiles[1].y) {
      try {
          AppsScreen.launch(act, p)
      } catch (t: Throwable) {
          Trace.step("home pinned FAIL ${p.pkg}: ${t.javaClass.simpleName}: ${t.message}")
      }
      return
  }
                // Нечего продолжать — кнопка ведёт в библиотеку.
                if (h.id == EinkActivity.SCR_READER && book == null) {
                    act.go(EinkActivity.SCR_LIBRARY)
                    return
                }
                // В читалку с главного: книгу задаём здесь. Иначе экран возьмёт `bookId`
                // от прошлого захода в B12 и откроет не ту книгу (R1.4).
                if (h.id == EinkActivity.SCR_READER) act.bookId = book!!.id
                act.go(h.id)
                return
            }
        }
    }

    /**
     * Обновить дату и приветствие, перерисовав только при смене строки. Зовётся из
     * [EinkActivity.onResume]: экран в кеше переживает выключение экрана, и после пробуждения
     * время суток уже другое. Таймера нет намеренно — на E-Ink лишний кадр это мерцание.
     */
    fun refreshTime() {
        if (!refreshHeader()) return
        if (parent != null) redraw()
    }

    /**
     * Перечитать сводку дня при возврате на главный (п. 22): после чтения строка «Сегодня …»
     * должна показать новые минуты. Перерисовка — только при смене строки: на E-Ink лишний
     * кадр это мерцание.
     */
    fun refreshTodayIfChanged() {
        val before = today
        try {
            refreshToday()
        } catch (t: Throwable) {
            Trace.step("home today FAIL ${t.javaClass.simpleName}: ${t.message}")
        }
        if (before != today && parent != null) redraw()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
        // Первый кадр экрана рисуется и так, поэтому здесь без redraw().
        refreshHeader()
        refresh()
    }

    override fun onDetachedFromWindow() {
        status.detach()
        super.onDetachedFromWindow()
    }
}
