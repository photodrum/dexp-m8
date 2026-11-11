package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import java.text.SimpleDateFormat
import java.util.Date
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

    private val status = StatusBar(act, this, ink, icons)

    private val hits = ArrayList<Hit>(8)

    private class Hit(val id: Int, val x: Float, val y: Float, val w: Float, val h: Float)

    init {
        refreshDate()
    }

    private fun refreshDate() {
        val now = Date()
        val d = dateFmt.format(now)
        date = d.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("ru")) else it.toString() }
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
        // Строка «Сегодня N мин · …» из макета не рисуется: статистики чтения нет до читалки.
    }

    private fun header(c: Canvas) {
        ink.text(c, date, 51.2f, T.base(111f, T.TS_BODY, 1.25f), T.TS_BODY, 600, T.G4)
        ink.text(c, "Добрый день", 51.2f, T.base(149.6f, T.TS_GREETING, 1.2f),
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

        // Название на обложке — Literata 30/600, lh 1.15, до двух строк.
        val lit600 = T.literata(ink.res, 600)
        val b1 = T.base(376.6f, 30f, 1.15f, true)
        coverTitle(e.title)
        ink.text(c, coverLines[0]!!, 131.6f, b1, 30f, 600, T.G1, lit600)
        if (coverLines[1] != null) {
            ink.text(c, coverLines[1]!!, 131.6f, b1 + 30f * 1.15f, 30f, 600, T.G1, lit600)
        }
        val author = e.author ?: "Автор неизвестен"
        // На обложке только настоящий автор: «Автор неизвестен» не влезает в 200 px.
        if (e.author != null) {
            ink.text(c, ink.ellipsize(author, COVER_TEXT_W, T.TS_CAPTION, 600), 131.6f,
                T.base(458.6f, T.TS_CAPTION, 1.25f), T.TS_CAPTION, 600, T.G4)
        }

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

    /** Название на обложке: одна строка, либо перенос по последнему пробелу, который влезает. */
    private fun coverTitle(s: String) {
        val lit = T.literata(ink.res, 600)
        coverLines[0] = s
        coverLines[1] = null
        if (ink.measure(s, 30f, 600, lit) <= COVER_TEXT_W) return
        var cut = -1
        var sp = s.indexOf(' ')
        while (sp >= 0 && ink.measure(s.substring(0, sp), 30f, 600, lit) <= COVER_TEXT_W) {
            cut = sp
            sp = s.indexOf(' ', sp + 1)
        }
        if (cut < 0) {
            coverLines[0] = ink.ellipsize(s, COVER_TEXT_W, 30f, 600, lit)
        } else {
            coverLines[0] = s.substring(0, cut)
            coverLines[1] = ink.ellipsize(s.substring(cut + 1), COVER_TEXT_W, 30f, 600, lit)
        }
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
        Tile("Аудиокниги", "", "audio", 721.2f, 1072.2f, EinkActivity.SCR_LIBRARY),
        Tile("Приложения", "", "apps", 41.1f, 1437.8f, EinkActivity.SCR_APPS),
        Tile("Настройки", "Экран, сеть, сон", "settings", 721.2f, 1437.8f, EinkActivity.SCR_SETTINGS)
    )

    /** Данные из базы: последняя открытая книга и число книг. Только UI-поток. */
    private var book: LibraryDb.Entry? = null
    private val coverLines = arrayOfNulls<String>(2)

    private fun refresh() {
        try {
            book = act.db.lastOpened()
            tiles[0].count = booksWord(act.db.count())
            tiles[2].count = appsWord(AppsScreen.count(act))
            refreshPinned()
        } catch (t: Throwable) {
            Trace.step("home refresh FAIL: ${t.javaClass.simpleName}: ${t.message}")
        }
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
            icons.draw(c, t.icon, t.x + 73.1f, t.y + 74.6f, 73.1f, 76.2f)
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
 const val TITLE_MAX_W = 884.8f    // от 431.4 до края дорожки
        /** Иконки плиток B1 нарисованы под 73.1×76.2 — только они годятся для закреплённого приложения. */
        val PINNED_ICONS = setOf("apps", "settings", "library", "audio")
    }

    // --------------------------------------------------------------- ввод

    override fun onUp(x: Float, y: Float) {
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
                act.go(if (h.id == EinkActivity.SCR_READER && book == null) EinkActivity.SCR_LIBRARY else h.id)
                return
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
        refreshDate()
        refresh()
    }

    override fun onDetachedFromWindow() {
        status.detach()
        super.onDetachedFromWindow()
    }
}
