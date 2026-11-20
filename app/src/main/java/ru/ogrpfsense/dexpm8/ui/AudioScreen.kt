package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import android.graphics.RectF
import ru.ogrpfsense.dexpm8.core.AudioPlayer

/**
 * B7 · «Аудиоплеер». Числа — `design/v5/B07.json`.
 *
 *   шапка     свернуть 41.1,106.9 106.9×112.4 (`down` 45.7×47.6); «Сейчас играет» 0,136.5
 *             1404×45 Manrope/36/700 по центру; главы 1255,106.9 106.9×112.4 (`chapters`)
 *   циферблат 336.4,237.7 731.3×731.3: шкала (24 штриха G7/1.5 + пять жирных G1), диск
 *             443.3,344.6 511.9 (r 256), время 446.1,482.8 511.9×130 Manrope/108/200,
 *             «осталось 45:12» 446.1,616.8 34/600 G4, «Глава 13 из 32» 446.1,661.8
 *   карточка  41.1,1021 1320.8×631.6 (r 65.8125): название 43.9,1070 Literata/60/500,
 *             автор и глава 43.9,1148 Manrope/36/600 G4
 *   кнопки    prev 128.9,1276.9 136.2×141.7; «10 с назад» 348.3; пауза 589.6,1233.1
 *             223.9×229.4 (тёмная, диск 219.4); «10 с вперёд» 918.6; next 1138
 *   параметры скорость 99.6,1492.7 384.8×105.1 (`speed`), таймер 509.1 (`moon`),
 *             закладка 918.6 (`bookmark`), подписи 36/700
 *   низ       «Далее: глава 14 · Слава петуху!» 0,1712.5 34/600 G4 по центру
 *
 * Решения по п. 12:
 *  - «свернуть» — выход на B33 (список), не «свернуть в фон»: второго экрана плеера нет;
 *  - параметры открывают лист B35 (`AudioSheet`), «Закладка» ставит/снимает место в книге:
 *    в макете это кнопка, отдельного списка закладок у аудио нет;
 *  - «Далее: глава …» подставляется из списка глав (встроенные `chpl` или файлы);
 *  - пока книга играет, экран перерисовывается раз в 15 с (тик `AudioPlayer`) — это
 *    частичная перерисовка небольшой области, не полный кадр.
 */
class AudioScreen(act: EinkActivity) : Screen(act) {

    private val db = act.db
    private val status = StatusBar(act, this, ink, icons)
    private val lit = T.literata(ink.res, 500)
    private val sheet = AudioSheet(ink)

    /** Блокировка положения: лист B35 поверх экрана (под листом кадр затемнён). */
    private var sheetOpen = false

    /** Закладка поставлена (подсветка кнопки): сбрасывается при уходе с места. */
    private var marked = false

    private val p get() = act.audio

    override fun render(c: Canvas) {
        val dim = if (sheetOpen) dimBegin(c) else -1
        ink.rect(c, 0f, 0f, W, H, T.BG)
        header(c)
        dial(c)
        card(c)
        controls(c)
        params(c)
        footer(c)
        if (dim >= 0) dimEnd(c, dim)
        status.draw(c)
        if (sheetOpen) sheet.draw(c, p.speed, p.sleepLeft(), p.sleepChapter, p.sleepAt != 0L)
    }

    // ------------------------------------------------------------------ шапка

    private fun header(c: Canvas) {
        ink.convex(c, 41.1f, 106.9f, 106.9f, 112.4f, 51.2f)
        icons.drawRound(c, "down", 41.1f, 106.9f, 106.9f, 112.4f, 45.7f, 47.6f)
        ink.textCenter(c, "Сейчас играет", 0f, W, T.base(136.5f, T.TS_LABEL, 1.25f), T.TS_LABEL, 700, T.G1)
        ink.convex(c, 1255f, 106.9f, 106.9f, 112.4f, 51.2f)
        icons.drawRound(c, "chapters", 1255f, 106.9f, 106.9f, 112.4f, 45.7f, 47.6f)
    }

    // ----------------------------------------------------------------- циферблат

    /**
     * Циферблат: шкала снаружи, диск и три строки по центру.
     *
     * **Шкала — прогресс по длительности (решение 2026-11-20).** Полный круг из [SCALE_N] рисок
     * равен всей длительности книги: при старте стоит одна риска, по мере проигрывания они
     * добавляются по часовой стрелке от верха, и круг замыкается на конце записи. Пересчёт и
     * перерисовка — на каждом тике плеера (10 с, пока экран включён). Первые [HEAD_W] рисок —
     * с нарастающей толщиной и длиной, как жирные риски макета B07, остальные одинаковые.
     * Раньше было 27 статичных рисок и пять жирных, которые «закрашивались» по 20 %.
     */
    private fun dial(c: Canvas) {
        val frac = if (p.total > 0L) p.pos.toFloat() / p.total else 0f
        val shown = Math.ceil((frac * SCALE_N).toDouble()).toInt().coerceIn(1, SCALE_N)
        for (i in 0 until shown) {
            val o = i * 4
            if (i < HEAD_W.size) {
                ink.line(c, SCALE[o], SCALE[o + 1], SCALE[o + 2], SCALE[o + 3], HEAD_W[i], T.G1)
            } else {
                ink.line(c, SCALE[o], SCALE[o + 1], SCALE[o + 2], SCALE[o + 3], TICK_W, T.G7)
            }
        }
        // Диск 511.9 в 443.3,344.6 — выпуклая пластина радиусом 256.
        ink.convex(c, 443.3f, 344.6f, 521.9f, 521.9f, 256f)
        ink.textCenter(c, AudioKit.clock(p.pos), 446.1f, 511.9f,
            T.base(482.8f, 108f, 1.2f), 108f, 200, T.G1)
        ink.textCenter(c, "осталось " + AudioKit.clock(maxOf(0L, p.total - p.pos)), 446.1f, 511.9f,
            T.base(616.8f, T.TS_BODY, 1.25f), T.TS_BODY, 600, T.G4)
        val n = p.chapters.size
        ink.textCenter(c, if (n > 0) "Глава ${p.chapterNumber()} из $n" else "", 446.1f, 511.9f,
            T.base(661.8f, T.TS_BODY, 1.25f), T.TS_BODY, 600, T.G4)
    }

    // ------------------------------------------------------------------ карточка

    private fun card(c: Canvas) {
        val b = p.book ?: return
        ink.convex(c, 41.1f, 1021f, 1320.8f, 631.6f, T.R_BUTTON)
        ink.textCenter(c, ink.ellipsize(b.title, 1316.3f, 60f, 500, lit), 43.9f, 1316.3f,
            T.base(1070f, 60f, 1.15f, true), 60f, 500, T.G1, lit)
        val chap = p.chapterOf(p.pos)?.title
        val author = b.author ?: "Автор неизвестен"
        val sub = if (chap != null && chap != b.title) "$author · $chap" else author
        ink.textCenter(c, ink.ellipsize(sub, 1316.3f, T.TS_LABEL, 600), 43.9f, 1316.3f,
            T.base(1148f, T.TS_LABEL, 1.25f), T.TS_LABEL, 600, T.G4)
    }

    // ------------------------------------------------------------------- кнопки

    private fun controls(c: Canvas) {
        // prev / next — главы; маленький диск 131.6.
        ink.convex(c, 128.9f, 1276.9f, 136.2f, 141.7f, 65.8f)
        icons.drawRound(c, "prev", 128.9f, 1276.9f, 136.2f, 141.7f, 45.7f, 47.6f)
        ink.convex(c, 1138f, 1276.9f, 136.2f, 141.7f, 65.8f)
        icons.drawRound(c, "nxt", 1138f, 1276.9f, 136.2f, 141.7f, 45.7f, 47.6f)

        // ±10 с: диск 131.6, иконка `back10`/`fwd10` 102.4×106.6 и цифра «10» поверх.
        // Цифра ставится по фактической рамке иконки, а не по абсолютным координатам макета:
        // иконка центрируется по оптическому центру диска (Icons.drawRoundBox), и макетные
        // 382.1/1328.7 давали сдвиг вниз-вправо (замечание 2026-11-20).
        ink.convex(c, 348.3f, 1276.9f, 136.2f, 141.7f, 65.8f)
        ten(c, icons.drawRoundBox(c, "back10", 348.3f, 1276.9f, 136.2f, 141.7f, 102.4f, 106.6f))
        ink.convex(c, 918.6f, 1276.9f, 136.2f, 141.7f, 65.8f)
        ten(c, icons.drawRoundBox(c, "fwd10", 918.6f, 1276.9f, 136.2f, 141.7f, 102.4f, 106.6f))

        // Главная клавиша: 219.4 тёмная (пауза/плей) — 223.9×229.4 в 589.6,1233.1.
        ink.convexDark(c, 589.6f, 1233.1f, 223.9f, 229.4f, 110f)
        val icon = if (p.playing) "pause" else "play"
        icons.drawRound(c, icon, 589.6f, 1233.1f, 223.9f, 229.4f, 87.8f, 87.8f)
    }

    /**
     * Цифра «10» внутри круглой стрелки ±10 с.
     *
     * По макету B07 (`back10` 367.5,1299.4) цифра отстоит от верха рамки иконки на 29.3 и стоит
     * по её центру по x (382.1 + 73.1/2 = 418.65 при центре рамки 418.7). Привязка к рамке,
     * а не к абсолютным числам макета, нужна потому, что иконка центрируется по оптическому
     * центру диска: `back10` уезжает на 4.3 влево и 5.5 вверх, `fwd10` — на 4.5 вверх, и цифра
     * на макетных координатах стояла не по центру стрелки (замечание 2026-11-20).
     */
    private fun ten(c: Canvas, box: RectF) {
        val w = 73.1f
        val x = box.left + (box.width() - w) / 2f
        val base = box.top + 29.3f + T.TS_BODY_S * (1.25f / 2f + T.BASE_K_MANROPE)
        ink.textCenter(c, "10", x, w, base, T.TS_BODY_S, 800, T.G2)
    }


    // ---------------------------------------------------------------- параметры

    private fun params(c: Canvas) {
        param(c, 99.6f, "speed", AudioKit.speed(p.speed))
        param(c, 509.1f, "moon", sleepLabel())
        param(c, 918.6f, "bookmark", if (marked) "Закладка есть" else "Закладка")
    }

    /** Кнопка параметра 384.8×105.1: иконка в +44.8,+25.3 (47.5×49.5), подпись в +112.5. */
    private fun param(c: Canvas, x: Float, icon: String, label: String) {
        ink.convex(c, x, 1492.7f, 384.8f, 105.1f, 47.5f)
        icons.draw(c, icon, x + 44.8f, 1518f, 47.5f, 49.5f)
        ink.text(c, label, x + 112.5f, T.base(1521.6f, T.TS_LABEL, 1.25f), T.TS_LABEL, 700, T.G1)
    }

    /** «30 мин» / «до конца главы» / «таймер» — подпись кнопки таймера. */
    private fun sleepLabel(): String = when {
        p.sleepChapter -> "До главы"
        p.sleepAt != 0L -> {
            val min = Math.max(1L, (p.sleepLeft() + 59_999L) / 60_000L)
            "$min мин"
        }
        else -> "Таймер"
    }

    private fun footer(c: Canvas) {
        val ch = p.chapters
        val line = when {
            p.book == null -> ""
            ch.isEmpty() -> ""
            p.chapterNumber() >= ch.size -> "Конец книги"
            else -> "Далее: " + ch[p.chapterNumber()].title
        }
        ink.textCenter(c, ink.ellipsize(line, 1200f, T.TS_BODY, 600), 0f, W,
            T.base(1712.5f, T.TS_BODY, 1.25f), T.TS_BODY, 600, T.G4)
    }

    // ------------------------------------------------------------------ касания

    override fun onUp(x: Float, y: Float) {
        if (sheetOpen) {
            sheetUp(x, y)
            return
        }
        when {
            inRect(x, y, 41.1f, 106.9f, 106.9f, 112.4f) -> act.pop()          // свернуть → B33
            inRect(x, y, 1255f, 106.9f, 106.9f, 112.4f) -> act.go(EinkActivity.SCR_AUDIO_CHAPTERS)
            inRect(x, y, 128.9f, 1276.9f, 136.2f, 141.7f) -> { p.prevChapter(); redraw() }
            inRect(x, y, 1138f, 1276.9f, 136.2f, 141.7f) -> { p.nextChapter(); redraw() }
            inRect(x, y, 348.3f, 1276.9f, 136.2f, 141.7f) -> { p.skip(-10_000L); redraw() }
            inRect(x, y, 918.6f, 1276.9f, 136.2f, 141.7f) -> { p.skip(10_000L); redraw() }
            inRect(x, y, 589.6f, 1233.1f, 223.9f, 229.4f) -> { p.toggle(); redraw() }
            inRect(x, y, 99.6f, 1492.7f, 384.8f, 105.1f) -> openSheet()
            inRect(x, y, 509.1f, 1492.7f, 384.8f, 105.1f) -> openSheet()
            inRect(x, y, 918.6f, 1492.7f, 384.8f, 105.1f) -> toggleMark()
        }
    }

    /** Закладка: кнопка показывает, поставлено ли место; снятие возвращает прогресс с начала. */
    private fun toggleMark() {
        val b = p.book ?: return
        marked = !marked
        try {
            if (marked) p.save() else db.setAudioProgress(b.id, 0L)
        } catch (t: Throwable) {
            Trace.step("audio mark FAIL: ${t.javaClass.simpleName}: ${t.message}")
        }
        redraw()
    }

    private fun inRect(x: Float, y: Float, rx: Float, ry: Float, rw: Float, rh: Float) =
        x >= rx && x <= rx + rw && y >= ry && y <= ry + rh

    // ------------------------------------------------------------------ лист B35

    private fun openSheet() {
        sheetOpen = true
        sheet.speed = p.speed
        sheet.skipPauses = act.prefs.audioSkipPauses
        sheet.sleep = if (p.sleepChapter) AudioPlayer.SLEEP_CHAPTER
        else ((p.sleepLeft() + 59_999L) / 60_000L).toInt()
        act.bigFrame("audio sheet")
        redraw()
    }

    private fun closeSheet() {
        sheetOpen = false
        act.prefs.audioSpeed = p.speed
        act.bigFrame("audio sheet close")
        redraw()
    }

    private fun sheetUp(x: Float, y: Float) {
        val what = sheet.hit(x, y)
        if (what == AudioSheet.ACT_CLOSE) {
            closeSheet()
            return
        }
        if (what == AudioSheet.ACT_CHANGED) {
            p.setSpeed(sheet.speed)
            p.sleepIn(sheet.sleep)
            act.prefs.audioSpeed = p.speed
            act.prefs.audioSleep = sheet.sleep
            act.prefs.audioSkipPauses = sheet.skipPauses
            p.setSkipPauses(sheet.skipPauses)
            redraw()
        }
    }

    // --------------------------------------------------------------- жизненный цикл

    /** Экран книги включён: на погашенном шкалу не перерисовываем (экономия 1 ГБ и батареи). */
    private fun screenOn(): Boolean {
        val pm = act.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
        return pm?.isInteractive ?: true
    }

    override fun backPressed(): Boolean {
        if (sheetOpen) {
            closeSheet()
            return true
        }
        return false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
        val id = act.audioId
        // Книга уже открыта (ушли на B33 и вернулись) — не переоткрываем: звук идёт.
        if (!p.hasBook || p.book?.id != id) {
            if (id > 0L) p.open(id, act.prefs.audioSpeed) else db.lastAudio()?.let { p.open(it.id, act.prefs.audioSpeed) }
            // Таймер сна не переживает перезапуск плеера: он одноразовый.
            val wantSleep = act.prefs.audioSleep
            if (wantSleep != 0) {
                p.sleepIn(wantSleep)
                act.prefs.audioSleep = 0
            }
        }
        // Шкала обновляется раз в тик (10 с), но только пока экран включён: на погашенном
        // экране перерисовка бессмысленна и греет процессор. Место и таймер сна живут в тике
        // плеера и от экрана не зависят.
        p.onTick = { if (parent != null && screenOn()) redraw() }
        p.onEnd = { if (parent != null) redraw() }
        p.setSkipPauses(act.prefs.audioSkipPauses)
        marked = false
        redraw()
    }

    override fun onDetachedFromWindow() {
        p.onTick = null
        p.onEnd = null
        p.save()
        status.detach()
        super.onDetachedFromWindow()
    }

    private companion object {
        /**
         * Штрих шкалы B7. В снимке 1.5, но на книге линия выходила почти волосяной (2 px с учётом
         * сглаживания) и шкала «сыпалась» — просьба человека 2026-11-20 сделать чуть толще: 2.5.
         */
        const val TICK_W = 2.5f

        /** Рисок на полный круг: шаг 7.5° — как у рисок макета B07 (там они стоят по 7.5°). */
        const val SCALE_N = 48

        /** Центр группы «Шкала прогресса» B07 (336.4,237.7 731.3×731.3) и радиусы рисок. */
        private const val SCALE_CX = 702.05f
        private const val SCALE_CY = 603.35f
        private const val R_OUT = 365.65f
        private const val R_IN = 336.4f

        /**
         * Первые пять рисок — с нарастающей толщиной и длиной (G1): числа жирных рисок макета
         * (2 / 2.6 / 3.5 / 4.5 / 5.5 при тонком 1.5), подняты до 3 / 3.6 / 4.2 / 4.9 / 5.6, чтобы
         * остаться толще обычных после утолщения тонких до 2.5. [HEAD_IN] — внутренний радиус.
         */
        val HEAD_W = floatArrayOf(3f, 3.6f, 4.2f, 4.9f, 5.6f)
        val HEAD_IN = floatArrayOf(329f, 325.4f, 318f, 310.8f, 303.5f)

        /**
         * Концы всех рисок (x1, y1, x2, y2 подряд): риска k стоит на k·7.5° от верха по часовой
         * стрелке. Считается один раз — в кадре тригонометрии нет.
         */
        val SCALE = FloatArray(SCALE_N * 4).also {
            for (k in 0 until SCALE_N) {
                val a = Math.toRadians(k * 360.0 / SCALE_N)
                val s = Math.sin(a).toFloat()
                val c = Math.cos(a).toFloat()
                val rin = if (k < HEAD_IN.size) HEAD_IN[k] else R_IN
                it[k * 4] = SCALE_CX + rin * s
                it[k * 4 + 1] = SCALE_CY - rin * c
                it[k * 4 + 2] = SCALE_CX + R_OUT * s
                it[k * 4 + 3] = SCALE_CY - R_OUT * c
            }
        }
    }
}
