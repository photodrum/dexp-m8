package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import ru.ogrpfsense.dexpm8.core.Sys

/**
 * B5 · Панель управления — шторка сверху поверх текущего экрана (п. 8.1). Числа — design/v5/B05.json.
 *
 * Открывается касанием центральной трети экрана (x 468…936) у верхнего края: в оболочке — полоса
 * статус-бара (y < [ZONE_SHELL]), в читалке — верх ≈27% страницы (y < [ZONE_READER]); проверяет
 * `Screen.onTouchEvent`, открывает `EinkActivity.openShade`. Закрывается касанием ниже панели и
 * кнопкой «Назад». Экран под шторкой затемнён (`Screen.dimmed`, замечание 15).
 *
 * View на весь экран без фона: рисует только панель, ниже виден затемнённый экран; касания
 * забирает все.
 *
 *   панель      0,−73.1 1404×1126.1 r 65.8: тень G10 +1.8,+7.3; блик G15 −2.7,−2.8; поверхность G13
 *   яркость     метка 51.2,125.1; «62%» правый край 1352.8; трек 138.9,212.1 1096.9×36.6; ручка Ø87.7
 *   контраст    центр шкалы 307.1,581.4; 16 делений r 171.8…192, 150° + 16°·i; регулятор Ø241.3
 *   режим       клавиши 354.7×175.5 r 43.9: (621.6 | 1005.5) × (372.9 | 577.7)
 *   быстро      круглые Ø138.9 в 196.3 / 630.2 / 1064.1, y 816.3; подпись 32/600 G4 y 970.8
 *   захват      643.5,1020.1 117×11 G11
 *
 * «Чёткий / Быстрый» — `Prefs.refreshMode` (общий с B37) → `EinkActivity.applyRefreshMode`
 * («Быстрый» — A2-режим на мелкие изменения; открытие и закрытие шторки — полной перерисовкой,
 * `EinkActivity.bigFrame`); «Очистка» закрывает шторку и полностью перерисовывает
 * экран одним кадром (`EinkActivity.cleanScreen`) — п. 8.2.
 */
class ShadeScreen(act: EinkActivity) : Screen(act) {

    companion object {
        /** Центральная треть экрана — зона открытия шторок (схема пользователя 2026-11-12). */
        const val ZONE_X0 = 468f
        const val ZONE_X1 = 936f
        /** Оболочка: только полоса статус-бара — ниже на всех экранах уже начинаются шапки. */
        const val ZONE_SHELL = 100f
        /** Читалка: верх ≈27% страницы (513 из 1872, границы на глаз — подтвердить на книге). */
        const val ZONE_READER = 513f

        /** Низ панели вместе с тенью: касание ниже закрывает шторку. */
        private const val PANEL_BOTTOM = 1060.3f

        // Шкала контраста: центр — пересечение линий делений в макете.
        private const val DIAL_CX = 307.13f
        private const val DIAL_CY = 581.37f
        private const val TICK_R0 = 171.8f
        private const val TICK_R1 = 191.95f
        private const val ARC_START = 150f
        private const val ARC_STEP = 16f
        /** Деление, которое считаем текущим, если свойство не прочиталось. */
        private const val GAMMA_DEFAULT = 7

        // Яркость: дно трека.
        private const val TRACK_X = 141.7f
        private const val TRACK_W = 1091.4f

        // Клавиши «Режим экрана»: верхний левый угол поверхности (у нажатой — углубления).
        private const val KEY_W = 354.7f
        private const val KEY_H = 175.5f
        private const val KEY_R = 43.875f
        private val KEY_X = floatArrayOf(621.6f, 1005.5f, 621.6f, 1005.5f)
        private val KEY_Y = floatArrayOf(372.9f, 372.9f, 577.7f, 577.7f)
        private val KEY_ICON_DY = floatArrayOf(36.8f, 36.8f, 40f, 34.2f)
        private val KEY_LABEL = arrayOf("Чёткий", "Быстрый", "Очистка", "Сеть")
        private const val K_SHARP = 0
        private const val K_FAST = 1
        private const val K_CLEAN = 2
        private const val K_NET = 3

        // Быстрые действия: левый край подписи (ширина 329.1), клавиша на +92.3.
        private val QUICK_X = floatArrayOf(104f, 537.9f, 971.8f)
        private const val QUICK_W = 329.1f
        private const val QUICK_Y = 816.3f
        private const val ROUND_D = 138.9f
        private const val Q_POWER = 0
        private const val Q_SLEEP = 1
        private const val Q_SHOT = 2

        /** Сколько держать «Снимок сохранён» (как «Очищено» на B42 — 2–3 с). */
        private const val SAVED_MS = 2500L
    }

    private val status = StatusBar(act, this, ink, icons)

    private var brightness = 62
    /** Номер деления контраста 0…15; −1 — свойство не прочитано. */
    private var gamma = -1
    /**
     * Подпись под быстрой клавишей вместо названия на [SAVED_MS]: «Снимок сохранён» / «Не сохранён»,
     * «Нужна настройка» у «Выключения» без службы. Индекс — Q_*.
     */
    private val notes = arrayOfNulls<String>(3)
    private val clearNote = Runnable { notes.fill(null); redraw() }

    override val shadeZone: Float get() = 0f

    init {
        background = null    // ниже панели виден затемнённый экран
    }

    // --------------------------------------------------------------- отрисовка

    override fun render(c: Canvas) {
        // Панель: тень, блик, поверхность G13 (в макете под G13 ещё слой G14 — не виден).
        ink.roundRect(c, 1.8f, -65.8f, 1404f, 1126.1f, T.R_BUTTON, T.G10)
        ink.roundRect(c, -2.7f, -75.9f, 1404f, 1126.1f, T.R_BUTTON, T.G15)
        ink.roundRect(c, 0f, -73.1f, 1404f, 1126.1f, T.R_BUTTON, T.G13)

        status.draw(c, 2.5f, T.G12)
        brightness(c)
        contrast(c)
        ink.text(c, "Режим экрана", 621.6f, T.base(307.9f, T.TS_LABEL_L, 1.25f), T.TS_LABEL_L, 700, T.G1)
        val mode = act.prefs.refreshMode
        key(c, K_SHARP, "modeNormal", mode == 0)
        key(c, K_FAST, "modeFast", mode == 1)
        key(c, K_CLEAN, "refresh@53x55.2", false)
        key(c, K_NET, if (status.wifi) "wifiDark" else "wifi@53x55.2", status.wifi)
        quick(c, Q_POWER, "power", 64f, 66.7f, 858f, notes[Q_POWER] ?: "Выключение")
        quick(c, Q_SLEEP, "moon@64x66.7", 64f, 66.7f, 858f, "Сон")
        quick(c, Q_SHOT, "shot", 62.2f, 64.7f, 857.6f, notes[Q_SHOT] ?: "Снимок")

        ink.roundRect(c, 643.5f, 1020.1f, 117f, 11f, 5.484375f, T.G11)
    }

    private fun brightness(c: Canvas) {
        ink.text(c, "Яркость", 51.2f, T.base(125.1f, T.TS_LABEL_L, 1.25f), T.TS_LABEL_L, 700, T.G1)
        right(c, "$brightness%", 1352.8f, 125f, T.TS_LABEL_L, 700, T.G1)
        icons.draw(c, "sunLow@53x55.2", 50.3f, 204.9f, 53f, 55.2f)
        icons.draw(c, "sunHigh@53x55.2", 1300.7f, 204.9f, 53f, 55.2f)
        ink.inset(c, 138.9f, 212.1f, 1096.9f, 36.6f, 18.28125f, T.G12)
        val fill = TRACK_W * brightness.coerceIn(0, 100) / 100f
        if (fill >= 28.3f) ink.roundRect(c, TRACK_X, 217.5f, fill, 28.3f, 14.16796875f, T.G3)
        convexRound(c, TRACK_X + fill - 46.7f, 183.7f, 87.7f)
    }

    private fun contrast(c: Canvas) {
        ink.text(c, "Контраст", 51.2f, T.base(307.9f, T.TS_LABEL_L, 1.25f), T.TS_LABEL_L, 700, T.G1)
        val cur = gamma
        for (i in 0 until 16) {
            if (i != cur) tick(c, i, TICK_R0, TICK_R1, 1.6f, T.G4)
        }
        convexRound(c, 183.7f, 457.9f, 241.3f)
        if (cur >= 0) {
            // Активное деление длиннее и толще (r 164.5…193.8, 3 px, G1); указатель Ø18.3 на r 95.
            tick(c, cur, 164.5f, 193.8f, 3f, T.G1)
            val a = Math.toRadians((ARC_START + ARC_STEP * cur).toDouble())
            val px = DIAL_CX + Math.cos(a).toFloat() * 95f
            val py = DIAL_CY + Math.sin(a).toFloat() * 95f
            ink.roundRect(c, px - 9.15f, py - 9.15f, 18.3f, 18.3f, 9.15f, T.G1)
        }
        val v = if (cur >= 0) "${Math.round(cur * 100f / 15f)}%" else "—"
        ink.textCenter(c, v, 215.7f, 182.8f, T.base(556.4f, T.TS_LABEL_L, 1.25f), T.TS_LABEL_L, 700, T.G1)
        ink.textCenter(c, "Мягкий", 85.9f, 110f, T.base(693.1f, T.TS_META, 1.25f), T.TS_META, 600, T.G4)
        ink.textCenter(c, "Чёткий", 420.3f, 106f, T.base(693.1f, T.TS_META, 1.25f), T.TS_META, 600, T.G4)
    }

    private fun tick(c: Canvas, i: Int, r0: Float, r1: Float, w: Float, color: Int) {
        val a = Math.toRadians((ARC_START + ARC_STEP * i).toDouble())
        val cs = Math.cos(a).toFloat()
        val sn = Math.sin(a).toFloat()
        ink.line(c, DIAL_CX + cs * r0, DIAL_CY + sn * r0, DIAL_CX + cs * r1, DIAL_CY + sn * r1, w, color)
    }

    /** Клавиша «Режима экрана»: нажата — углубление, «Вкл» G1 / 800; отжата — выпуклая, «Выкл» G5 / 600. */
    private fun key(c: Canvas, k: Int, icon: String, on: Boolean) {
        val x = KEY_X[k]
        val y = KEY_Y[k]
        if (on) ink.inset(c, x, y, KEY_W, KEY_H, KEY_R, T.G12)
        else ink.convex(c, x - T.SURFACE_DX, y - T.SURFACE_DY, KEY_W + T.SHADOW_DX, KEY_H + T.SHADOW_DY, KEY_R)
        icons.draw(c, icon, x + 35.6f, y + KEY_ICON_DY[k], 53f, 55.2f)
        ink.text(c, KEY_LABEL[k], x + 36.5f, T.base(y + 99.6f, T.TS_LABEL_L, 1.25f), T.TS_LABEL_L,
            if (on) 800 else 600, if (on) T.G1 else T.G5)
        if (k != K_CLEAN) {
            right(c, if (on) "Вкл" else "Выкл", x + 318f, y + 45.4f, T.TS_META, 700, if (on) T.G1 else T.G5)
        }
    }

    private fun quick(c: Canvas, q: Int, icon: String, iw: Float, ih: Float, iy: Float, label: String) {
        val kx = QUICK_X[q] + 92.3f
        convexRound(c, kx, QUICK_Y, ROUND_D)
        icons.draw(c, icon, kx + 40.3f, iy, iw, ih)
        ink.textCenter(c, label, QUICK_X[q], QUICK_W, T.base(970.8f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
    }

    /** Круглая выпуклая деталь Ø[d], ([x], [y]) — блик: тень G10 +4.6,+10.1; поверхность G14 +2.8,+2.8. */
    private fun convexRound(c: Canvas, x: Float, y: Float, d: Float) {
        val r = d / 2f
        ink.roundRect(c, x + T.SHADOW_DX, y + T.SHADOW_DY, d, d, r, T.G10)
        ink.roundRect(c, x, y, d, d, r, T.G15)
        ink.roundRect(c, x + T.SURFACE_DX, y + T.SURFACE_DY, d, d, r, T.G14)
    }

    private fun right(c: Canvas, s: String, rx: Float, top: Float, fs: Float, fw: Int, color: Int) {
        val w = ink.measure(s, fs, fw)
        ink.text(c, s, rx - w, T.base(top, fs, 1.25f), fs, fw, color)
    }

    // --------------------------------------------------------------- касания

    override fun onUp(x: Float, y: Float) {
        if (y > PANEL_BOTTOM) { act.closeShade(); return }

        // Яркость: иконки по краям −10 / +10, трек — значение с шагом 5 (как SettingsKit.sliderHit).
        if (y >= 170f && y <= 295f) {
            val v = when {
                x < 130f -> brightness - 10
                x > 1244f -> brightness + 10
                else -> Math.round((x - TRACK_X) / TRACK_W * 20f) * 5
            }.coerceIn(0, 100)
            act.withWriteSettings {
                if (act.sys.setBrightness(v)) brightness = v
                Trace.step("shade: brightness $v -> $brightness")
                redraw()
            }
            return
        }

        if (x < 560f && y > 300f && y < 790f) { contrastHit(x, y); return }

        for (k in 0 until 4) {
            if (x >= KEY_X[k] - 10f && x <= KEY_X[k] + KEY_W + 10f &&
                y >= KEY_Y[k] - 10f && y <= KEY_Y[k] + KEY_H + 10f) {
                keyHit(k)
                return
            }
        }

        if (y >= 800f && y <= 1015f) {
            for (q in 0 until 3) {
                if (x >= QUICK_X[q] && x <= QUICK_X[q] + QUICK_W) { quickHit(q); return }
            }
        }
    }

    /**
     * Шкала контраста: угол касания → ближайшее деление. Внизу, между концами дуги (там подписи
     * «Мягкий» и «Чёткий»), — шаг −1 слева и +1 справа. Середина регулятора не нажимается.
     */
    private fun contrastHit(x: Float, y: Float) {
        val dx = x - DIAL_CX
        val dy = y - DIAL_CY
        if (dx * dx + dy * dy < 40f * 40f) return
        val cur = if (gamma >= 0) gamma else GAMMA_DEFAULT
        val ang = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
        val t = ((ang - ARC_START) % 360f + 360f) % 360f
        val i = when {
            t <= 15 * ARC_STEP + ARC_STEP / 2f -> Math.round(t / ARC_STEP).coerceIn(0, 15)
            x < DIAL_CX -> (cur - 1).coerceAtLeast(0)
            else -> (cur + 1).coerceAtMost(15)
        }
        val ok = act.sys.setGamma(Sys.GAMMA_STEPS[i])
        val read = act.sys.gamma()
        gamma = if (read >= 0) Sys.gammaIndex(read) else -1
        Trace.step("shade: gamma ${Sys.GAMMA_STEPS[i]} write=${if (ok) "OK" else "FAIL"} read=$read")
        redraw()
    }

    private fun keyHit(k: Int) {
        when (k) {
            K_SHARP, K_FAST -> {
                act.prefs.refreshMode = if (k == K_FAST) 1 else 0
                act.applyRefreshMode()
            }
            K_CLEAN -> {
                // Разовая полная перерисовка: шторку снимаем, экран под ней — одним кадром (8.2).
                act.closeShade()
                act.cleanScreen()
                return
            }
            K_NET -> {
                val on = !status.wifi
                Trace.step("shade: wifi -> $on (${act.sys.setWifi(on)})")
                // Итог придёт в StatusBar событием WIFI_STATE_CHANGED, он и перерисует шторку.
                return
            }
        }
        redraw()
    }

    private fun quickHit(q: Int) {
        when (q) {
            // Меню питания появляется поверх окна асинхронно: шторку снимаем в том же проходе UI.
            Q_POWER -> if (act.powerDialog()) act.closeShade() else note(Q_POWER, "Нужна настройка")
            Q_SLEEP -> { act.closeShade(); act.sleepNow() }
            Q_SHOT -> act.screenshot { ok -> note(Q_SHOT, if (ok) "Снимок сохранён" else "Не сохранён") }
        }
    }

    private fun note(q: Int, s: String) {
        notes[q] = s
        removeCallbacks(clearNote)
        postDelayed(clearNote, SAVED_MS)
        redraw()
    }

    // --------------------------------------------------------------- данные

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
        val b = act.sys.brightness()
        if (b >= 0) brightness = b
        val g = act.sys.gamma()
        gamma = if (g >= 0) Sys.gammaIndex(g) else -1
        notes.fill(null)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(clearNote)
        status.detach()
        super.onDetachedFromWindow()
    }
}
