package ru.ogrpfsense.dexpm8.ui

import android.content.Intent
import android.graphics.Canvas
import android.provider.Settings
import ru.ogrpfsense.dexpm8.chess.Fics

/**
 * B85 · Шахматы · по сети · поиск (B85а — ожидание, B87 — нет подключения). Числа —
 * design/v5/B85.json, B85а.json, B87.json; диалоги — [ChessNetDialogs].
 *
 *   шапка      «По сети» 176,105 60/700; подзаголовок «FICS · freechess.org» 176,181
 *   «КОНТРОЛЬ ВРЕМЕНИ»  59,250; желоб 56,300 1292×95, три сегмента по 428 ([Fics.CONTROLS])
 *   пояснение  59,420 32/600 G4
 *   «ВЫ ИГРАЕТЕ»  59,520; панель 41,570 1321×336: «Гость» 88,611 и «Без рейтинга» 88,763
 *   пояснение  59,946; кнопка «Искать соперника» тёмная 41,1694 1321×142
 *
 * Игра — [ChessNet]: экран только рисует его состояние и ставит `ChessNet.onChange` на время
 * показа. Нашёлся соперник (PLAYING) — переход на B86 ([ChessOnlineScreen]). Секундный тик
 * счётчика «0:42» перерисовывает только его рамку (на E-Ink меняем минимум).
 *
 * «Назад» (кнопка и системная): на B85а — «Отмена» поиска; иначе — выйти из сети и в меню.
 * Выбранный контроль помнится на время процесса (`ChessNet.minutes/increment`).
 */
class ChessNetScreen(act: EinkActivity) : Screen(act) {

    private val status = StatusBar(act, this, ink, icons)
    private val kit = SettingsKit(ink, icons)
    private val dialogs = ChessNetDialogs(ink, icons)
    private var control = 0
    private var shown = D_NONE
    private var lastCounter = ""
    private var waitWifi = false

    private val change: () -> Unit = { onNet() }

    private val tick = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || dialog() != D_SEEK) return
            val s = counter()
            if (s != lastCounter) {
                lastCounter = s
                dirtyCounter()
            }
            postDelayed(this, TICK_MS)
        }
    }

    /** Перерисовать только рамку счётчика (`invalidate(l, t, r, b)` — единственный способ на API 27). */
    @Suppress("DEPRECATION")
    private fun dirtyCounter() {
        invalidate(
            D.px(ChessNetDialogs.COUNTER_X), D.px(ChessNetDialogs.COUNTER_Y),
            D.px(ChessNetDialogs.COUNTER_X + ChessNetDialogs.COUNTER_W),
            D.px(ChessNetDialogs.COUNTER_Y + ChessNetDialogs.COUNTER_H),
        )
    }

    override fun render(c: Canvas) {
        val dlg = dialog()
        val dim = if (dlg != D_NONE) dimBegin(c) else -1
        ink.rect(c, 0f, 0f, W, H, T.BG)
        kit.header(c, "По сети", "FICS · freechess.org")

        kit.section(c, "КОНТРОЛЬ ВРЕМЕНИ", 250f)
        trough(c, 300f)
        ink.text(c, "Минуты на партию + секунды за ход. Короче 10 минут на E-Ink неудобно.", 59f,
            T.base(420f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)

        kit.section(c, "ВЫ ИГРАЕТЕ", 520f)
        kit.panel(c, 570f, 336f)
        kit.title(c, "Гость", 611f)
        kit.desc(c, "Имя выдаст сервер: Guest и четыре буквы", 664.5f)
        kit.divider(c, 735f)
        kit.title(c, "Без рейтинга", 763f)
        kit.desc(c, "Гости FICS играют только безрейтинговые партии", 816.5f)
        ink.text(c, "Во время партии с часами книга не засыпает.", 59f,
            T.base(946f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)

        ink.convexDark(c, 41f, 1694f, 1321f, 142f, T.R_BUTTON)
        ink.textCenter(c, "Искать соперника", 44f, 1316f, T.base(1737f, T.TS_ACTION, 1.25f),
            T.TS_ACTION, 700, T.G15)
        if (dim >= 0) dimEnd(c, dim)
        status.draw(c)

        when (dlg) {
            D_SEEK -> {
                lastCounter = counter()
                dialogs.drawSeek(c, label(control), lastCounter)
            }
            D_OFFLINE -> dialogs.drawOffline(c, offlineNote())
        }
    }

    /** Желоб из трёх сегментов (как на B78): активный — выпуклая плашка 428×90. */
    private fun trough(c: Canvas, y: Float) {
        ink.inset(c, 56f, y, 1292f, 95f, 47.5f)
        for (i in Fics.CONTROLS.indices) {
            val x = 60f + 428f * i
            val on = i == control
            if (on) ink.convex(c, x, y + 4f, 428f, 90f, 40f)
            ink.textCenter(c, label(i), x, 423f, T.base(y + (if (on) 27f else 26f), T.TS_BODY_S, 1.25f),
                T.TS_BODY_S, if (on) 700 else 600, if (on) T.G1 else T.G5)
        }
    }

    private fun label(i: Int): String = Fics.CONTROLS[i].let { "${it.first} + ${it.second}" }

    private fun offlineNote(): String {
        val why = ChessNet.error
        val head = if (why.isEmpty()) "Wi-Fi выключен или сервер недоступен." else "$why."
        return "$head С компьютером можно играть и без сети."
    }

    /** Время поиска «0:42»; пока идёт соединение — «0:00». */
    private fun counter(): String {
        val s = if (ChessNet.state == ChessNet.SEEKING)
            ((ChessNet.now() - ChessNet.seekSince) / 1000L).coerceAtLeast(0L).toInt() else 0
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun dialog(): Int = when (ChessNet.state) {
        ChessNet.CONNECTING, ChessNet.SEEKING -> D_SEEK
        ChessNet.OFFLINE -> D_OFFLINE
        else -> D_NONE
    }

    // ------------------------------------------------------------------ состояние сети

    private fun onNet() {
        if (ChessNet.state == ChessNet.PLAYING) {
            act.go(EinkActivity.SCR_CHESS_ONLINE)
            return
        }
        sync()
        redraw()
    }

    /** Диалог открылся или закрылся: большой кадр (как на B79); счётчик — тиком, пока виден. */
    private fun sync() {
        val d = dialog()
        if (d != shown) {
            shown = d
            act.bigFrame("chess net dialog")
        }
        removeCallbacks(tick)
        if (d == D_SEEK && isAttachedToWindow) postDelayed(tick, TICK_MS)
    }

    // ------------------------------------------------------------------ касания

    override fun onUp(x: Float, y: Float) {
        when (dialog()) {
            D_SEEK -> {
                if (dialogs.seekCancelHit(x, y)) ChessNet.cancelSeek()
                return
            }
            D_OFFLINE -> {
                when (dialogs.hit(x, y)) {
                    ChessNetDialogs.LEFT -> search()
                    ChessNetDialogs.RIGHT -> wifiSettings()
                    // Закрыть диалог без сети нечем; «Назад» в шапке оставлен рабочим (как на B57).
                    else -> if (kit.isBack(x, y)) leave()
                }
                return
            }
        }
        if (kit.isBack(x, y)) {
            leave()
            return
        }
        when {
            y in 290f..405f && x in 56f..1348f -> {
                val i = ((x - 60f) / 428f).toInt().coerceIn(0, Fics.CONTROLS.size - 1)
                if (i != control) {
                    control = i
                    redraw()
                }
            }
            y in 1694f..1836f && x in 41f..1362f -> search()
        }
    }

    private fun search() {
        val (min, inc) = Fics.CONTROLS[control]
        ChessNet.seek(min, inc, appVersion())
    }

    private fun appVersion(): String = try {
        act.packageManager.getPackageInfo(act.packageName, 0).versionName ?: ""
    } catch (t: Throwable) {
        ""
    }

    private fun wifiSettings() {
        try {
            waitWifi = true
            act.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        } catch (t: Throwable) {
            waitWifi = false
            Trace.step("chess: wifi settings FAIL: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Вернулись из настроек Wi-Fi: чистим панель после системного экрана (иначе E-Ink оставит след). */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus || parent == null || !waitWifi) return
        waitWifi = false
        act.cleanScreen()
    }

    /** Выйти из сети и вернуться в меню шахмат. */
    private fun leave() {
        ChessNet.leave()
        act.pop()
    }

    override fun backPressed(): Boolean {
        if (dialog() == D_SEEK) ChessNet.cancelSeek() else leave()
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
        // Старая ошибка связи не должна встречать диалогом: вход на экран — чистый лист.
        if (ChessNet.state == ChessNet.OFFLINE) ChessNet.leave()
        ChessNet.onChange = change
        val i = Fics.CONTROLS.indexOf(ChessNet.minutes to ChessNet.increment)
        control = if (i >= 0) i else 0
        shown = dialog()
        if (ChessNet.state == ChessNet.PLAYING) {
            post { act.go(EinkActivity.SCR_CHESS_ONLINE) }
            return
        }
        sync()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        if (ChessNet.onChange === change) ChessNet.onChange = null
        status.detach()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val D_NONE = 0
        const val D_SEEK = 1
        const val D_OFFLINE = 2
        const val TICK_MS = 1000L
    }
}
