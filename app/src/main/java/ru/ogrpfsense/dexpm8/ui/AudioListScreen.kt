package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import ru.ogrpfsense.dexpm8.core.LibraryDb

/**
 * B33 · «Аудиокниги» (список). Числа — `design/v5/B33.json`.
 *
 *   шапка      назад 41,113.5 107×112 (`back` 12.3×26.6); «Аудиокниги» 176,105 Manrope/60/700;
 *              подзаголовок 176,181 32/600 G4 («5 книг · 41 ч»); поиск 1255,113.5 107×112
 *   карточка   41,246 1321×340 (r 51): гнездо обложки 88,296 236×236 (r 36, дно G12),
 *              обложка 106,308 200×200 (G14, корешок 16 G3), метка «СЕЙЧАС СЛУШАЕТЕ» 364,302,
 *              название 364,346 Literata/42/600, «Глава 4 из 32 · осталось 11 ч 20 мин» 364,404,
 *              дорожка 364,476 720×22, play 1173,336 140×140 (тёмная)
 *   раздел     «ВСЕ АУДИОКНИГИ» 59,620 30/700 G5
 *   панель     41,668 1321×1000 (r 51)
 *   строка     миниатюра 88,y 132×132 (G12, корешок G7); название 260,y+11 Literata/42/600;
 *              «Автор · 14 ч 50 мин» 260,y+67 32/600 G4; дорожка 958,y+50 238×22 и процент
 *              1214,y+41 34/700 по правому краю; разделитель 260,y+165 1056×2 G11
 *   пагинация  клавиши 41.1 / 1240.4, 1708.3 121.6×127.1; «1 / 5» 44/600
 *
 * Решения по п. 12:
 *  - клавиша «поиск» в шапке ведёт в общий B21 (свой поиск по аудио):
 *    `EinkActivity.audioQuery` переключает список результатов на аудиокниги;
 *  - обложки — заглушка из начала названия, как в макете: картинку из ID3 не достаём
 *    (лишняя память на E-Ink, вопрос дизайну в `docs/DESIGN_QUESTIONS.md`);
 *  - строка новой книги показывает справа «Новая», прослушанной — «Прослушана»
 *    с галочкой, начатой — дорожку и процент: так нарисовано в макете;
 *  - на странице пять строк (панель 1000 при шаге 160), дальше пагинация: в макете её
 *    нет, но книг бывает больше пяти.
 */
class AudioListScreen(act: EinkActivity) : Screen(act) {

    private val db = act.db
    private val status = StatusBar(act, this, ink, icons)
    private val lit = T.literata(ink.res, 600)
    private val empty = EmptyState(ink, icons)
    private val kit = SettingsKit(ink, icons)

    private var books = ArrayList<LibraryDb.Audio>()
    private var current: LibraryDb.Audio? = null
    private var page = 0

    /** Идёт разбор папок — подзаголовок «Идёт поиск аудиокниг…». */
    private var scanning = false

    override fun render(c: Canvas) {
        ink.rect(c, 0f, 0f, W, H, T.BG)
        header(c)
        if (books.isEmpty()) {
            emptyState(c)
        } else {
            nowCard(c)
            sectionLabel(c)
            panel(c)
            pager(c)
        }
        status.draw(c)
    }

    // ------------------------------------------------------------------ шапка

    private fun header(c: Canvas) {
        kit.header(c, "Аудиокниги", subtitle())
        // Поиск: клавиша 107×112 в 1255,113.5, иконка `search` 34.1×36.2.
        ink.convex(c, 1255f, 113.5f, 107f, 112f, 51.2f)
        icons.drawRoundRing(c, "search", 1255f, 113.5f, 107f, 112f, 34.1f, 36.2f)
    }

    private fun subtitle(): String {
        if (scanning) return "Идёт поиск аудиокниг…"
        if (books.isEmpty()) return "пусто"
        val total = db.audioTotalDuration()
        return if (total > 0) "${AudioKit.books(books.size)} · ${AudioKit.hours(total)}"
        else AudioKit.books(books.size)
    }

    // ------------------------------------------------------------ «сейчас слушаете»

    private fun nowCard(c: Canvas) {
        val b = current ?: return
        ink.convex(c, 41f, 246f, 1321f, 340f, T.R_TILE)
        ink.inset(c, 88f, 296f, 236f, 236f, 36f, T.G12)
        cover(c, 106f, 308f, 200f, b.title, 23f)
        ink.text(c, "СЕЙЧАС СЛУШАЕТЕ", 364f, T.base(302f, T.TS_META, 1.25f), T.TS_META, 700, T.G5)
        ink.text(c, ink.ellipsize(b.title, 452f, 42f, 600, lit), 364f,
            T.base(346f, 42f, 1.15f, true), 42f, 600, T.G1, lit)
        ink.text(c, ink.ellipsize(positionLine(b), 720f, T.TS_BODY_S, 600), 364f,
            T.base(404f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        AudioKit.track(ink, c, 364f, 476f, 720f, 22f, b.percent)
        ink.convexDark(c, 1173f, 336f, 140f, 140f, 70f)
        icons.drawRound(c, "play", 1173f, 336f, 140f, 140f, 35.8f, 42.8f)
    }

    /** «Глава 4 из 32 · осталось 11 ч 20 мин»; глав нет — только «осталось …». */
    private fun positionLine(b: LibraryDb.Audio): String {
        val left = AudioKit.left(b.duration - b.pos)
        val n = chapterCount(b)
        return if (n > 0) "Глава ${chapterAt(b)} из $n · $left" else left
    }

    private fun chapterCount(b: LibraryDb.Audio): Int = try {
        db.audioChapters(b.id).size.takeIf { it > 0 } ?: db.audioFiles(b.id).size
    } catch (t: Throwable) {
        0
    }

    /** Номер главы на текущем месте книги — для карточки B33. */
    private fun chapterAt(b: LibraryDb.Audio): Int {
        val starts = try {
            val built = db.audioChapters(b.id)
            if (built.isNotEmpty()) built.map { it.start }
            else {
                var acc = 0L
                db.audioFiles(b.id).map { f -> val s = acc; acc += f.dur; s }
            }
        } catch (t: Throwable) {
            return 1
        }
        var n = 1
        for ((i, s) in starts.withIndex()) if (s <= b.pos) n = i + 1
        return n
    }

    // ------------------------------------------------------------------ список

    private fun sectionLabel(c: Canvas) {
        ink.text(c, "ВСЕ АУДИОКНИГИ", 59f, T.base(620f, T.TS_META, 1.25f), T.TS_META, 700, T.G5)
    }

    private fun panel(c: Canvas) {
        ink.convex(c, 41f, 668f, 1321f, 1000f, T.R_TILE)
        val first = page * PER_PAGE
        val n = minOf(PER_PAGE, books.size - first)
        for (i in 0 until n) row(c, books[first + i], ROW_Y0 + ROW_STEP * i, i < n - 1)
    }

    /**
     * Строка книги: миниатюра, название, «Автор · 14 ч 50 мин»; справа — дорожка с процентом
     * (у начатой), статус (у новой) или «Прослушана» с галочкой.
     */
    private fun row(c: Canvas, b: LibraryDb.Audio, y: Float, divider: Boolean) {
        cover(c, 88f, y, 132f, b.title, 0f)
        ink.text(c, ink.ellipsize(b.title, 660f, 42f, 600, lit), 260f,
            T.base(y + 11f, 42f, 1.15f, true), 42f, 600, T.G1, lit)
        ink.text(c, ink.ellipsize(metaLine(b), 674f, T.TS_BODY_S, 600), 260f,
            T.base(y + 67f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        when {
            b.finished -> {
                val label = "Прослушана"
                val w = ink.measure(label, T.TS_BODY_S, 700)
                ink.text(c, label, 1291.5f - 12f - w, T.base(y + 52f, T.TS_BODY_S, 1.25f),
                    T.TS_BODY_S, 700, T.G4)
                icons.draw(c, "check", 1291.5f, y + 60.3f, 24.5f, 18.4f)
            }
            b.opened == 0L -> {
                val label = "Новая"
                ink.text(c, label, 1316f - ink.measure(label, T.TS_BODY_S, 700),
                    T.base(y + 52f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 700, T.G4)
            }
            else -> {
                AudioKit.track(ink, c, 958f, y + 50f, 238f, 22f, b.percent)
                val pct = "${b.percent}%"
                ink.text(c, pct, 1316f - ink.measure(pct, T.TS_BODY, 700),
                    T.base(y + 41f, T.TS_BODY, 1.25f), T.TS_BODY, 700, T.G1)
            }
        }
        if (divider) ink.rect(c, 260f, y + 165f, 1056f, 2f, T.G11)
    }

    private fun metaLine(b: LibraryDb.Audio): String {
        val a = b.author ?: "Автор неизвестен"
        return if (b.duration > 0) "$a · ${AudioKit.hours(b.duration)}" else a
    }

    /** Миниатюра: гнездо G12 + корешок G7 и название-заглушка на «обложке». */
    private fun cover(c: Canvas, x: Float, y: Float, side: Float, title: String, fs: Float) {
        ink.roundRect(c, x, y, side, side, side * 0.0833f, T.G12)
        ink.roundRect(c, x, y, side * 0.0985f, side, side * 0.0833f, T.G7)
        if (fs <= 0f) return
        val lines = ink.ellipsize2(title, side * 0.66f, fs, 600, lit, 2)
        val base = y + side * 0.166f
        for ((i, s) in lines.withIndex()) {
            ink.text(c, s, x + side * 0.21f, base + i * fs * 1.2f, fs, 600, T.G1, lit)
        }
    }

    // ------------------------------------------------------------- пусто и пагинация

    private fun emptyState(c: Canvas) {
        if (scanning) {
            empty.library(c, "Идёт поиск аудиокниг…",
                "Просматриваются папки Audio и Books на памяти книги.", false)
        } else {
            empty.library(c, "Аудиокниг нет",
                "Положите книгу в папку Audio или Books на памяти книги: MP3 по главам " +
                    "или один файл M4B.", false)
        }
    }

    private fun pager(c: Canvas) {
        val pages = totalPages()
        if (pages <= 1) return
        ink.convex(c, 41.1f, 1708.3f, 121.6f, 127.1f, 58.5f)
        icons.drawRound(c, "back@54.8x57.1", 41.1f, 1708.3f, 121.6f, 127.1f, 54.8f, 57.1f)
        ink.convexDark(c, 1240.4f, 1708.3f, 121.6f, 127.1f, 58.5f)
        icons.drawRound(c, "next@53x53", 1240.4f, 1708.3f, 121.6f, 127.1f, 53f, 53f)
        ink.textCenter(c, "${page + 1} / $pages", 0f, W,
            T.base(1738.3f, T.TS_PAGE, 1.25f), T.TS_PAGE, 600, T.G1)
    }

    private fun totalPages() = maxOf(1, (books.size + PER_PAGE - 1) / PER_PAGE)

    // ------------------------------------------------------------------ касания

    override fun onUp(x: Float, y: Float) {
        if (kit.isBack(x, y)) {
            act.pop()
            return
        }
        if (inRect(x, y, 1255f, 113.5f, 107f, 112f)) {
            act.audioQuery = ""
            act.go(EinkActivity.SCR_SEARCH)
            return
        }
        if (totalPages() > 1) {
            if (inRect(x, y, 41.1f, 1708.3f, 121.6f, 127.1f) && page > 0) {
                page--
                redraw()
                return
            }
            if (inRect(x, y, 1240.4f, 1708.3f, 121.6f, 127.1f) && page < totalPages() - 1) {
                page++
                redraw()
                return
            }
        }
        // Вся карточка «сейчас слушаете» открывает B7 с этой книгой.
        val cur = current
        if (cur != null && inRect(x, y, 41f, 246f, 1321f, 340f)) {
            open(cur.id)
            return
        }
        val first = page * PER_PAGE
        val n = minOf(PER_PAGE, books.size - first)
        for (i in 0 until n) {
            if (!inRect(x, y, 88f, ROW_Y0 + ROW_STEP * i, 1228f, 160f)) continue
            open(books[first + i].id)
            return
        }
    }

    private fun open(id: Long) {
        act.audioId = id
        act.go(EinkActivity.SCR_AUDIO)
    }

    private fun inRect(x: Float, y: Float, rx: Float, ry: Float, rw: Float, rh: Float) =
        x >= rx && x <= rx + rw && y >= ry && y <= ry + rh

    // ------------------------------------------------------------------ данные

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
        load()
        if (books.isEmpty() && !scanning) {
            scanning = true
            redraw()
            act.audioScan.start {
                scanning = false
                if (parent != null) {
                    load()
                    redraw()
                    act.bigFrame("audio list")
                }
            }
        } else {
            redraw()
        }
    }

    override fun onDetachedFromWindow() {
        status.detach()
        super.onDetachedFromWindow()
    }

    /** Перечитать список и карточку. UI-поток; строк немного — пагинация здесь. */
    private fun load() {
        books = try {
            db.audioBooks()
        } catch (t: Throwable) {
            Trace.step("audio list FAIL: ${t.javaClass.simpleName}: ${t.message}")
            ArrayList()
        }
        current = books.firstOrNull { it.opened > 0L }
        if (page >= totalPages()) page = totalPages() - 1
    }

    private companion object {
        const val PER_PAGE = 5
        const val ROW_Y0 = 704f
        const val ROW_STEP = 160f
    }
}
