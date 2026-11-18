package ru.ogrpfsense.dexpm8.ui

import android.content.Context
import android.graphics.Canvas
import android.net.ConnectivityManager
import android.net.wifi.WifiManager

/**
 * B61 · Первый запуск · шаг 2 · Wi-Fi (п. 13). Числа — из design/v5/B61.json.
 *
 *   раздел «WI-FI»   59,480; панель 41,528 1321×870
 *   «Wi-Fi»          88,562.5: круглая клавиша, заголовок с x 237, описание 625, тумблер 1174,584;
 *                    разделитель 237,710 1076
 *   сеть             строка 66,752.9 1266×150 (вдавленная, «Подключена»), ниже соседи
 *   кнопки          «Пропустить» 41.1,1693.6 553; «Далее» 633.4 728.5
 *
 * **Отступление от макета (2026-11-18).** Список соседних сетей в макете — четыре строки, но на
 * Android 8.1 `WifiManager.getScanResults()` без геолокации (`ACCESS_COARSE_LOCATION` + включённое
 * определение местоположения) возвращает пустой список. Права просить ещё раз на шаге Wi-Fi не
 * хотим: рисуем тумблер Wi-Fi и **текущую** сеть (`getConnectionInfo().wifiInfo`, на 8.1 SSID
 * доступен без геолокации), а касание по строке открывает системный экран Wi-Fi, где список есть.
 * Список соседей вернётся, если решим просить `ACCESS_COARSE_LOCATION` (вопрос в ROADMAP).
 * Тумблер переключается сразу, через [SETTLE_MS] экран один раз сверяется с системой.
 */
class FirstWifiScreen(act: EinkActivity) : Screen(act) {

    private companion object {
        const val SETTLE_MS = 2500L
    }

    private val status = StatusBar(act, this, ink, icons)
    private val kit = SettingsKit(ink, icons)
    private val first = FirstRunKit(ink, icons)

    private var wifiOn = false
    private var ssid = ""
    private var linked = false
    private val settle = Runnable { if (parent != null) { refresh(); redraw() } }

    /** Шторку B5 на шаге мастера не открываем: панель управления здесь ни к чему. */
    override val shadeZone: Float get() = 0f

    override fun render(c: Canvas) {
        ink.rect(c, 0f, 0f, W, H, T.BG)
        status.draw(c)
        first.header(c, 2, "Подключим Wi-Fi",
            "Нужен для каталогов, загрузки книг и точного времени. Можно пропустить и подключить позже.")

        kit.section(c, "WI-FI", 480f)
        kit.panel(c, 528f, 870f)
        kit.key(c, 88f, 562.5f)
        icons.draw(c, "wifi@33.1x30.6", 129.4f, 606.3f, 33.1f, 30.6f)
        kit.title(c, "Wi-Fi", 572f, 237f)
        kit.desc(c, wifiText(), 625f, 237f)
        kit.toggle(c, 584f, wifiOn, 1174f)
        kit.divider(c, 710f, 237f, 1076f)

        // Сеть 66,752.9 1266×150. Подключённая — вдавленная (как «нажато» в макете), иначе выпуклая.
        val name = ssid.ifEmpty { "Сети рядом" }
        val note = when {
            !wifiOn -> "Wi-Fi выключен"
            linked -> "Подключено"
            else -> "Не подключено · открыть настройки системы"
        }
        if (linked) ink.inset(c, 66f, 752.9f, 1266f, 150f, 40f, T.G12)
        else ink.convex(c, 66f, 752.9f, 1266f, 150f, 40f)
        icons.draw(c, "wifi@28.8x26.6", 113.6f, 815.6f, 28.8f, 26.6f)
        ink.text(c, ink.ellipsize(name, 1070f, T.TS_ACTION, 700), 190f,
            T.base(777.9f, T.TS_ACTION, 1.25f), T.TS_ACTION, 700, T.G1)
        ink.text(c, note, 190f, T.base(833.9f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        if (linked) icons.draw(c, "check@25.7x19.3", 1287.3f, 819.2f, 25.7f, 19.3f)

        first.buttonLight(c, "Пропустить")
        first.buttonDarkRight(c, "Далее")
    }

    private fun wifiText() = when {
        !wifiOn -> "Выключено"
        linked -> "Подключено к «${ssid.ifEmpty { "сети" }}»"
        else -> "Не подключено"
    }

    @Suppress("DEPRECATION")
    private fun refresh() {
        wifiOn = act.sys.wifiOn()
        linked = false
        ssid = ""
        try {
            val wm = act.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wm.connectionInfo
            val s = info?.ssid ?: ""
            // Android отдаёт SSID в кавычках; <unknown ssid> — сети нет.
            if (s.isNotEmpty() && s != "<unknown ssid>") ssid = s.trim('"')
            val cm = act.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val ni = cm.activeNetworkInfo
            linked = ni != null && ni.isConnected && ni.type == ConnectivityManager.TYPE_WIFI
        } catch (t: Throwable) {
            Trace.step("first wifi FAIL: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Системный экран Wi-Fi: выбрать сеть и ввести пароль (B47 — системный ввод, свой не нужен). */
    private fun openSystemWifi() {
        val intents = arrayOf(
            android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS),
            android.content.Intent("android.settings.WIFI_SETTINGS"),
        )
        for (i in intents) {
            try {
                act.startActivity(i)
                act.markCleanOnFocus()
                return
            } catch (t: Throwable) {
                Trace.step("first wifi: no screen ${t.javaClass.simpleName}")
            }
        }
    }

    override fun onUp(x: Float, y: Float) {
        when {
            kit.inRow(x, y, 528f, 182f) -> {
                if (act.sys.setWifi(!wifiOn)) { wifiOn = !wifiOn; linked = false; settleLater() }
                redraw()
            }
            // Строка сети: текущая — тоже открывает системный экран, другого действия у неё нет.
            kit.inRow(x, y, 752.9f, 150f) -> openSystemWifi()
            first.isLightButton(x, y) -> act.go(EinkActivity.SCR_FIRST_PERM)
            first.isDarkRight(x, y) -> act.go(EinkActivity.SCR_FIRST_PERM)
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
