package ru.ogrpfsense.dexpm8.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.net.wifi.WifiManager
import android.os.BatteryManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Общий статус-бар B1/B2: время, Wi-Fi, батарея. Числа — из design/v5/B01.json (группа
 * «Статус-бар»); у B2 те же размеры, базовые линии отличаются меньше 1 px (lh 1.2 вместо 1.25).
 *
 * Не View: экран рисует его в своём [render] через [draw] и подключает в
 * onAttachedToWindow / onDetachedFromWindow через [attach] / [detach]. Часы обновляются только
 * при подключении, батарея — по событию системы, и только при смене процента перерисовывает
 * экран (никаких таймеров: на E-Ink каждая перерисовка — мерцание).
 *
 * Состояние «заряжается» в макете не нарисовано, поэтому не хранится.
 */
class StatusBar(
    private val act: EinkActivity,
    private val owner: Screen,
    private val ink: Ink,
    private val icons: Icons
) {

    // Формат — по системной настройке 12/24 (B41), проверяется при каждом attach().
    private var timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var clock = timeFmt.format(Date())
    private var battery = 78
    /** Wi-Fi включён (по последнему событию системы); читает и шторка B5 для клавиши «Сеть». */
    var wifi = true
        private set
    private var attached = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            intent ?: return
            if (intent.action == WifiManager.WIFI_STATE_CHANGED_ACTION) {
                val on = act.sys.wifiOn()
                if (on != wifi) {
                    wifi = on
                    if (owner.parent != null) owner.redraw()
                }
                return
            }
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level < 0 || scale <= 0) return
            val pct = level * 100 / scale
            if (pct != battery) {
                battery = pct
                if (owner.parent != null) owner.redraw()
            }
        }
    }

    /** Подписка на батарею и обновление времени. Вызывать из onAttachedToWindow экрана. */
    fun attach() {
        val pattern = if (android.text.format.DateFormat.is24HourFormat(act)) "HH:mm" else "h:mm"
        timeFmt = SimpleDateFormat(pattern, Locale.getDefault())
        clock = timeFmt.format(Date())
        wifi = act.sys.wifiOn()
        if (attached) return
        try {
            // Батарея — липкое событие: onReceive придёт сразу с текущим зарядом.
            // Wi-Fi — не липкое: начальное состояние взято выше.
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            act.registerReceiver(receiver, filter)
            attached = true
        } catch (ignored: Exception) {
        }
    }

    /** Отписка. Вызывать из onDetachedFromWindow экрана до super. */
    fun detach() {
        if (!attached) return
        attached = false
        try {
            act.unregisterReceiver(receiver)
        } catch (ignored: IllegalArgumentException) {
        }
    }

    /**
     * [batDy], [batBottom] — батарея в шторке B5 (B05.json): на 2.5 ниже, дно G12 вместо G11;
     * время и Wi-Fi там же, где на B1.
     */
    fun draw(c: Canvas, batDy: Float = 0f, batBottom: Int = T.G11) {
        // Время: рамка 51.2,26.9, 36/700, lh 1.25 → baseline 63.2.
        ink.text(c, clock, X_CLOCK, BASE_CLOCK, T.TS_LABEL, 700, T.G1)

        // Wi-Fi (иконка 38.4×40.2). Вид «выключен» в макете не нарисован — пока скрыт.
        if (wifi) icons.draw(c, "wifi", 1165.4f, 29.2f, 38.4f, 40.2f)

        // Корпус: нижний блик 124.3×43.9, внутренняя тень 121.6×41.1, дно 118.8×35.6 (G11).
        ink.inset(c, BAT_X, BAT_Y + batDy, BAT_W, BAT_H, T.R_BATTERY, batBottom)

        // Заряд: 118.8·% × 32.9, r 16.45, G3.
        val pct = battery.coerceIn(0, 100)
        val fillW = FILL_W * pct / 100f
        ink.roundRect(c, FILL_X, FILL_Y + batDy, fillW, FILL_H, R_FILL, T.G3)

        // Процент: 28/800, по центру заливки, baseline 57.3. Белый — по тёмной заливке; если
        // заливка уже текста, цифры уходят на дно G11 тёмным (в макете этот случай не нарисован,
        // вопрос в чат дизайна).
        val label = pct.toString()
        val tw = ink.measure(label, T.TS_CAPTION, 800)
        if (fillW >= tw + 2f * PAD) {
            ink.textCenter(c, label, FILL_X, fillW, BASE_PERCENT + batDy, T.TS_CAPTION, 800, T.G15)
        } else {
            ink.textCenter(c, label, FILL_X + fillW, FILL_W - fillW, BASE_PERCENT + batDy,
                T.TS_CAPTION, 800, T.G1)
        }
    }

    private companion object {
        const val X_CLOCK = 51.2f
        const val BASE_CLOCK = 63.2f
        const val BASE_PERCENT = 57.3f
        const val BAT_X = 1228.5f
        const val BAT_Y = 25.6f
        const val BAT_W = 124.3f
        const val BAT_H = 43.9f
        const val FILL_X = 1231.2f
        const val FILL_Y = 31.1f
        const val FILL_W = 118.8f
        const val FILL_H = 32.9f
        const val R_FILL = 16.453125f
        const val PAD = 6f
    }
}
