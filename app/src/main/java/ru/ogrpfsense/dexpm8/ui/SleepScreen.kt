package ru.ogrpfsense.dexpm8.ui

import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.os.BatteryManager
import ru.ogrpfsense.dexpm8.core.LibraryDb

/**
 * B19 · Заставка сна. Числа — design/v5/B19.json.
 *
 * Показывается поверх текущего экрана, когда до системного гашения остаётся
 * [EinkActivity.SLEEP_LEAD_MS]. Своего гашения нет: панель выключает система по своему
 * тайм-ауту, а кадр B19 остаётся на E-Ink и виден на выключенном экране — поэтому заставку
 * рисуем **до** гашения, а не после.
 *
 *   строка сна   0,96 1404×45 Manrope/36/700 G5 по центру («Сон · заряд 78%»)
 *   гнездо       332,190 740×1040 (вдавленное, дно G11)
 *   обложка      362,220 680×980 G14 r30; корешок 362,220 28×980 G3 r30
 *   название на обложке 423,271 586 Literata/84/600 G1, до двух строк
 *   автор на обложке    423,492.6 Manrope/44/600 G4
 *   название     0,1300 1404×79 Literata/66/600 G1 по центру
 *   автор и прогресс 0,1390 Manrope/34/600 G4 по центру («Лев Толстой · прочитано 14%»)
 *   прогресс     352,1470 700×22 (дорожка G11), заливка G3 на 695 от 355,1475
 *   подсказка    152,1640 1100×120 (вдавленная, дно G11): `battery` и 36/600 G1
 *
 * Заряд в верхней строке — только при «Показывать заряд на заставке» (`Prefs.coverCharge`,
 * B40); выключено — просто «Сон». Данные книги — `LibraryDb.lastOpened()`; книги не открывали —
 * карточки нет, остаются строка сна и подсказка (отступление от макета, в нём нет пустого вида).
 *
 * Касание или свайп будят оболочку: заставка снимается, прежний экран остаётся как был.
 */
class SleepScreen(act: EinkActivity) : Screen(act) {

    private companion object {
        const val COVER_X = 362f
        const val COVER_Y = 220f
        const val COVER_W = 680f
        const val COVER_H = 980f
        const val COVER_R = 30f          // радиус обложки
        const val SPINE_W = 28f          // корешок слева
        const val COVER_TEXT_X = 423f
        const val COVER_TEXT_W = 586f
        const val COVER_FS = 84f
        /** Дорожка и подсказка вдавленные — дно G11 на фоне экрана. */
        const val TRACK_X = 352f
        const val TRACK_Y = 1470f
        const val TRACK_W = 700f
        const val TRACK_H = 22f
        const val TRACK_FILL_X = 355f
        const val TRACK_FILL_Y = 1475f
        const val TRACK_FILL_W = 695f
        const val TRACK_FILL_H = 14f
    }

    /** Заряд, % — снимок при показе (липкий `ACTION_BATTERY_CHANGED`, право не нужно). */
    private var charge = 78

    /** Последняя открытая книга; null — ничего не открывали. */
    private var book: LibraryDb.Entry? = null

    /** Название на обложке, до двух строк: первая — всегда, вторая — null, если влезло в одну. */
    private val coverLines = arrayOfNulls<String>(2)

    /** Шторка B5 на заставке не открывается: касание будит оболочку, а не показывает панель. */
    override val shadeZone: Float get() = 0f

    init {
        background = null    // не рисуем за прежним экраном: он остаётся как был до пробуждения
    }

    override fun render(c: Canvas) {
        ink.rect(c, 0f, 0f, W, H, T.BG)
        ink.textCenter(c, sleepLine(), 0f, W, T.base(96f, T.TS_LABEL, 1.25f), T.TS_LABEL, 700, T.G5)

        val e = book
        if (e != null) {
            cover(c, e)
            caption(c, e)
        }
        hint(c)
    }

    /** «Сон · заряд 78%» либо «Сон», если заряд на заставке выключен на B40. */
    private fun sleepLine(): String =
        if (act.prefs.coverCharge) "Сон · заряд $charge%" else "Сон"

    // --------------------------------------------------------------- обложка

    private fun cover(c: Canvas, e: LibraryDb.Entry) {
        ink.inset(c, 332f, 190f, 740f, 1040f, 56f, T.G11)
        ink.roundRect(c, COVER_X, COVER_Y, COVER_W, COVER_H, COVER_R, T.G14)
        ink.roundRect(c, COVER_X, COVER_Y, SPINE_W, COVER_H, COVER_R, T.G3)

        val lit = T.literata(ink.res, 600)
        coverTitle(e.title)
        val b = T.base(271f, COVER_FS, 1.2f, true)
        ink.text(c, coverLines[0]!!, COVER_TEXT_X, b, COVER_FS, 600, T.G1, lit)
        coverLines[1]?.let {
            ink.text(c, it, COVER_TEXT_X, b + COVER_FS * 1.2f, COVER_FS, 600, T.G1, lit)
        }
        // «Автор неизвестен» на обложку не ставим: в макете на ней только настоящее имя.
        e.author?.let {
            ink.text(c, ink.ellipsize(it, COVER_TEXT_W, T.TS_PAGE, 600), COVER_TEXT_X,
                T.base(492.6f, T.TS_PAGE, 1.25f), T.TS_PAGE, 600, T.G4)
        }
    }

    /**
     * Название на обложке: одна строка, либо перенос по последнему пробелу, который влезает
     * в 586 px; вторая строка обрезается «…». Кегль здесь 84 против 30 на B1, поэтому перенос
     * считается по своему кеглю, а не общим `HomeScreen.coverTitle`.
     */
    private fun coverTitle(s: String) {
        val lit = T.literata(ink.res, 600)
        coverLines[0] = s
        coverLines[1] = null
        if (ink.measure(s, COVER_FS, 600, lit) <= COVER_TEXT_W) return
        var cut = -1
        var sp = s.indexOf(' ')
        while (sp >= 0 && ink.measure(s.substring(0, sp), COVER_FS, 600, lit) <= COVER_TEXT_W) {
            cut = sp
            sp = s.indexOf(' ', sp + 1)
        }
        if (cut < 0) {
            coverLines[0] = ink.ellipsize(s, COVER_TEXT_W, COVER_FS, 600, lit)
            return
        }
        coverLines[0] = s.substring(0, cut)
        coverLines[1] = ink.ellipsize(s.substring(cut + 1), COVER_TEXT_W, COVER_FS, 600, lit)
    }

    // --------------------------------------------------------------- подписи

    /** Название, автор с прогрессом и дорожка — под обложкой, по центру экрана. */
    private fun caption(c: Canvas, e: LibraryDb.Entry) {
        val lit = T.literata(ink.res, 600)
        ink.textCenter(c, ink.ellipsize(e.title, W, 66f, 600, lit), 0f, W,
            T.base(1300f, 66f, 1.2f, true), 66f, 600, T.G1, lit)

        val pct = e.progress.coerceIn(0, 100)
        val line = if (e.author != null) "${e.author} · прочитано $pct%" else "прочитано $pct%"
        ink.textCenter(c, ink.ellipsize(line, W, T.TS_BODY, 600), 0f, W,
            T.base(1390f, T.TS_BODY, 1.25f), T.TS_BODY, 600, T.G4)

        ink.inset(c, TRACK_X, TRACK_Y, TRACK_W, TRACK_H, 11f, T.G11)
        val fill = TRACK_FILL_W * pct / 100f
        if (fill >= 20f) ink.roundRect(c, TRACK_FILL_X, TRACK_FILL_Y, fill, TRACK_FILL_H, 7f, T.G3)
    }

    /** «Нажмите кнопку питания, чтобы продолжить» — вдавленная плашка с иконкой батареи. */
    private fun hint(c: Canvas) {
        ink.inset(c, 152f, 1640f, 1100f, 120f, 60f, T.G11)
        icons.draw(c, "battery", 269f, 1690.4f, 36.4f, 21.1f)
        ink.text(c, "Нажмите кнопку питания, чтобы продолжить", 339f,
            T.base(1676.9f, T.TS_LABEL, 1.25f), T.TS_LABEL, 600, T.G1)
    }

    // --------------------------------------------------------------- данные

    private fun refresh() {
        charge = battery()
        book = try {
            act.db.lastOpened()
        } catch (t: Throwable) {
            Trace.step("sleep: db FAIL ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    private fun battery(): Int {
        val i = act.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return 78
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (level < 0 || scale <= 0) 78 else (level * 100 / scale).coerceIn(0, 100)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refresh()
    }

    // --------------------------------------------------------------- касания
    // Любое касание — пробуждение: заставка уходит, прежний экран остаётся. Удержание тоже
    // будит: палец мог просто пролежать на выключенном экране.

    override fun onUp(x: Float, y: Float) = act.wakeUp()

    override fun onSwipe(x0: Float, y0: Float, x1: Float, y1: Float): Boolean {
        act.wakeUp()
        return true
    }

    override fun onLongPress(x: Float, y: Float): Boolean {
        act.wakeUp()
        return true
    }
}
