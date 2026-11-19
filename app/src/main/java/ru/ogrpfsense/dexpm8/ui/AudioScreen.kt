package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
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
    private val sheet = AudioSheet(ink, icons)

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
     * Циферблат: шкала снаружи (24 штриха и пять жирных по дуге), диск и три строки по центру.
     * Штрихи и дуга — из снимка пути; рисуем их линиями по числам, а не разбором `d`:
     * координаты уже сняты из макета (см. [TICKS]).
     */
    private fun dial(c: Canvas) {
        for (t in TICKS) ink.line(c, t[0], t[1], t[2], t[3], t[4], T.G7)
        // Пять жирных штрихов — течение прослушанного: длина по проценту книги.
        val done = p.percent() * BOLD.size / 100
        for (i in BOLD.indices) {
            val b = BOLD[i]
            if (i < done) ink.line(c, b[0], b[1], b[2], b[3], b[4], T.G1)
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
        ink.convex(c, 348.3f, 1276.9f, 136.2f, 141.7f, 65.8f)
        icons.drawRound(c, "back10", 348.3f, 1276.9f, 136.2f, 141.7f, 102.4f, 106.6f)
        ink.textCenter(c, "10", 382.1f, 73.1f, T.base(1328.7f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 800, T.G2)
        ink.convex(c, 918.6f, 1276.9f, 136.2f, 141.7f, 65.8f)
        icons.drawRound(c, "fwd10", 918.6f, 1276.9f, 136.2f, 141.7f, 102.4f, 106.6f)
        ink.textCenter(c, "10", 950.5f, 73.1f, T.base(1327.7f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 800, T.G2)

        // Главная клавиша: 219.4 тёмная (пауза/плей) — 223.9×229.4 в 589.6,1233.1.
        ink.convexDark(c, 589.6f, 1233.1f, 223.9f, 229.4f, 110f)
        val icon = if (p.playing) "pause" else "play"
        icons.drawRound(c, icon, 589.6f, 1233.1f, 223.9f, 229.4f, 87.8f, 87.8f)
    }

    // ---------------------------------------------------------------- параметры

    private fun params(c: Canvas) {
        param(c, 99.6f, "speed", AudioKit.speed(p.speed))
        param(c, 509.1f, "moon", sleepLabel())
        param(c, 918.6f, "bookmark", if (marked) "Закладка есть" else "Закладка")
    }

    /** Кнопка параметра 384.8×105.1: выпуклая, иконка слева, подпись 36/700. */
    private fun param(c: Canvas, x: Float, icon: String, label: String) {
        ink.convex(c, x, 1492.7f, 384.8f, 105.1f, 47.5f)
        icons.drawRound(c, icon, x, 1492.7f, 384.8f, 105.1f, 47.5f, 49.5f)
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
        p.onTick = { if (parent != null) redraw() }
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
         * Штрихи шкалы B7 (x1, y1, x2, y2, толщина) — сняты из пути `svg-path` снимка B07:
         * 27 тонких G7/1.5 по дуге сверху вниз слева направо.
         */
        val TICKS = arrayOf(
            floatArrayOf(702f, 266.9f, 702f, 237.7f, 1.5f),
            floatArrayOf(745.9f, 269.8f, 749.7f, 240.8f, 1.5f),
            floatArrayOf(789.1f, 278.4f, 796.6f, 250.1f, 1.5f),
            floatArrayOf(830.7f, 292.5f, 841.9f, 265.5f, 1.5f),
            floatArrayOf(870.2f, 312f, 884.8f, 286.6f, 1.5f),
            floatArrayOf(906.8f, 336.4f, 924.6f, 313.2f, 1.5f),
            floatArrayOf(939.9f, 365.4f, 960.5f, 344.8f, 1.5f),
            floatArrayOf(968.9f, 398.5f, 992.1f, 380.7f, 1.5f),
            floatArrayOf(993.3f, 435.1f, 1018.7f, 420.5f, 1.5f),
            floatArrayOf(1012.8f, 474.6f, 1039.8f, 463.4f, 1.5f),
            floatArrayOf(1026.9f, 516.2f, 1055.2f, 508.7f, 1.5f),
            floatArrayOf(1035.5f, 559.4f, 1064.5f, 555.6f, 1.5f),
            floatArrayOf(1038.4f, 603.3f, 1067.6f, 603.3f, 1.5f),
            floatArrayOf(1035.5f, 647.2f, 1064.5f, 651f, 1.5f),
            floatArrayOf(1026.9f, 690.3f, 1055.2f, 697.9f, 1.5f),
            floatArrayOf(1012.8f, 732f, 1039.8f, 743.2f, 1.5f),
            floatArrayOf(993.3f, 771.5f, 1018.7f, 786.1f, 1.5f),
            floatArrayOf(968.9f, 808.1f, 992.1f, 825.9f, 1.5f),
            floatArrayOf(939.9f, 841.1f, 960.5f, 861.8f, 1.5f),
            floatArrayOf(906.8f, 870.2f, 924.6f, 893.4f, 1.5f),
            floatArrayOf(870.2f, 894.6f, 884.8f, 919.9f, 1.5f),
            floatArrayOf(830.7f, 914f, 841.9f, 941.1f, 1.5f),
            floatArrayOf(789.1f, 928.2f, 796.6f, 956.5f, 1.5f),
            floatArrayOf(745.9f, 936.8f, 749.7f, 965.8f, 1.5f),
            floatArrayOf(702f, 939.7f, 702f, 968.9f, 1.5f),
            floatArrayOf(658.1f, 936.8f, 654.3f, 965.8f, 1.5f),
            floatArrayOf(614.9f, 928.2f, 607.4f, 956.5f, 1.5f),
        )

        /** Пять жирных штрихов левой дуги (G1, толщина растёт): прослушанная часть книги. */
        val BOLD = arrayOf(
            floatArrayOf(576.1f, 907.3f, 562.1f, 941.1f, 2f),
            floatArrayOf(539.3f, 885.1f, 519.2f, 919.9f, 2.6f),
            floatArrayOf(508.4f, 855.6f, 479.4f, 893.4f, 3.5f),
            floatArrayOf(482.2f, 823f, 443.5f, 861.8f, 4.5f),
            floatArrayOf(461.2f, 788f, 411.9f, 825.9f, 5.5f),
        )
    }
}
