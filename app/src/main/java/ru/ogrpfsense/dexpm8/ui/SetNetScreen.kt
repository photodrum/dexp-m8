package ru.ogrpfsense.dexpm8.ui

import android.content.Context
import android.graphics.Canvas
import android.net.ConnectivityManager

/**
 * B38 · Настройки · Сеть. Числа — из design/v5/B38.json.
 *
 * Wi-Fi и Bluetooth — `act.sys` (WifiManager / BluetoothAdapter, обычные права из манифеста).
 * Переключение асинхронное: тумблер меняется сразу, через [SETTLE_MS] экран один раз сверяется
 * с системой (без таймеров по кругу). Подключение — через `ConnectivityManager`.
 * Не рисуются: список сетей и название сети (нужна геолокация), строка «Сопряжённые устройства».
 * Вместо них панели сжаты до одной строки, всё ниже сдвинуто вверх — относительно макета:
 * Wi-Fi 870 → 190, Bluetooth 340 → 190. «Выключать Wi-Fi во сне» хранится в `act.prefs`
 * (исполнится вместе со сном, п. 8).
 */
class SetNetScreen(act: EinkActivity) : Screen(act) {

    private companion object {
        const val SETTLE_MS = 2500L
    }

    private val status = StatusBar(act, this, ink, icons)
    private val kit = SettingsKit(ink, icons)

    private var wifiOn = false
    private var wifiLinked = false
    private var btOn = false
    private val settle = Runnable { if (parent != null) { refresh(); redraw() } }

    override fun render(c: Canvas) {
        ink.rect(c, 0f, 0f, W, H, T.BG)
        status.draw(c)
        kit.header(c, "Сеть", "Wi-Fi и Bluetooth")

        kit.section(c, "WI-FI", 250f)
        kit.panel(c, 298f, 190f)
        kit.key(c, 88f, 332.5f)
        icons.draw(c, "wifi@33.1x30.6", 129.4f, 376.3f, 33.1f, 30.6f)
        kit.title(c, "Wi-Fi", 342f, 237f)
        kit.desc(c, wifiText(), 395f, 237f)
        kit.toggle(c, 354f, wifiOn, 1174f)

        kit.section(c, "BLUETOOTH", 520f)
        kit.panel(c, 568f, 190f)
        kit.key(c, 88f, 602.5f)
        icons.draw(c, "bluetooth", 135f, 640.6f, 22.1f, 42f)
        kit.title(c, "Bluetooth", 612f, 237f)
        kit.desc(c, "Для наушников и колонок", 665f, 237f)
        kit.toggle(c, 624f, btOn, 1177f)

        kit.title(c, "Выключать Wi-Fi во сне", 816f, 59f)
        kit.desc(c, "Бережёт заряд, при пробуждении подключится снова", 869f, 59f)
        kit.toggle(c, 828f, act.prefs.wifiSleepOff, 1206f)
    }

    private fun wifiText() = when {
        !wifiOn -> "Выключено"
        wifiLinked -> "Подключено"
        else -> "Не подключено"
    }

    private fun refresh() {
        wifiOn = act.sys.wifiOn()
        btOn = act.sys.btOn()
        wifiLinked = try {
            val cm = act.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val ni = cm.activeNetworkInfo
            ni != null && ni.isConnected && ni.type == ConnectivityManager.TYPE_WIFI
        } catch (t: Throwable) {
            Trace.step("settings net FAIL: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    override fun onUp(x: Float, y: Float) {
        if (kit.isBack(x, y)) { act.pop(); return }
        when {
            kit.inRow(x, y, 298f, 190f) -> {
                if (act.sys.setWifi(!wifiOn)) { wifiOn = !wifiOn; wifiLinked = false; settleLater() }
                redraw()
            }
            kit.inRow(x, y, 568f, 190f) -> {
                if (act.sys.setBt(!btOn)) { btOn = !btOn; settleLater() }
                redraw()
            }
            kit.inRow(x, y, 790f, 150f) -> {
                act.prefs.wifiSleepOff = !act.prefs.wifiSleepOff
                redraw()
            }
        }
    }

    private fun settleLater() {
        removeCallbacks(settle)
        postDelayed(settle, SETTLE_MS)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
        refresh()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(settle)
        status.detach()
        super.onDetachedFromWindow()
    }
}
