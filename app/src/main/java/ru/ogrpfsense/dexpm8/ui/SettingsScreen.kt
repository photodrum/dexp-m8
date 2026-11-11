package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import android.os.Build

/**
 * B13 · Настройки · разделы. Числа — из design/v5/B13.json.
 *
 *   шапка        «Назад» 41.1,112.9 (106.9×112.4); заголовок 175.5,105 60/700; подзаголовок 175.5,181 32/600 G4
 *   панель       41.1,245.9  1320.8×1443.3 (выпуклая, r 51.1875)
 *   строка       круглая клавиша 77.7,y (114.3×119.7); иконка 108.8 (53×55.2);
 *                название 226.7,y+9.4 42/700; значение 226.7,y+61.7 32/600 G4;
 *                разделитель 226.7, 1096.9×1.8 G11; «next» 1295.2,y+35.6
 *
 * Значение строки рисуется только когда есть данные (как счётчики на B1); остальные разделы
 * получат значения вместе с экранами B37–B43 (шаги 4.5, 4.6).
 */
class SettingsScreen(act: EinkActivity) : Screen(act) {

    /** [iconDy] — смещение иконки от верха строки (у «Сети» в макете 27.1, у остальных 32.1). */
    private class Row(
        val title: String, val icon: String, val y: Float, val iconDy: Float,
        val divY: Float, val target: Int, var value: String = ""
    )

    private val status = StatusBar(act, this, ink, icons)

    private val rows = arrayOf(
        Row("Экран", "contrast", 287.3f, 32.1f, 441.1f, EinkActivity.SCR_SET_SCREEN),
        Row("Сеть", "wifi@53x55.2", 472.2f, 27.1f, 616.6f, EinkActivity.SCR_SET_NET),
        Row("Чтение", "font", 647.7f, 32.1f, 792.1f, EinkActivity.SCR_SET_READ),
        Row("Кнопки и жесты", "more@53x55.2", 823.2f, 32.1f, 967.6f, EinkActivity.SCR_SET_KEYS),
        Row("Сон и питание", "moon", 998.7f, 32.1f, 1143.1f, EinkActivity.SCR_SET_SLEEP),
        Row("Дата, время, язык", "clock", 1174.2f, 32.1f, 1318.6f, EinkActivity.SCR_SET_LANG),
        Row("Память", "files", 1349.7f, 32.1f, 1494.1f, EinkActivity.SCR_SET_STORAGE),
        Row("Об устройстве", "info", 1530.2f, 32.2f, 0f, EinkActivity.SCR_SET_ABOUT)
    )

    private var subtitle = "DEXP M8 Prudentia"

    override fun render(c: Canvas) {
        ink.rect(c, 0f, 0f, W, H, T.BG)
        status.draw(c)

        ink.convex(c, 41.1f, 112.9f, 106.9f, 112.4f, 51.2f)
        icons.draw(c, "back@43.9x45.7", 73.1f, 144.9f, 43.9f, 45.7f)
        ink.text(c, "Настройки", 175.5f, T.base(105f, T.TS_TITLE, 1.2f), T.TS_TITLE, 700, T.G1)
        ink.text(c, subtitle, 175.5f, T.base(181f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)

        ink.convex(c, 41.1f, 245.9f, 1320.8f, 1443.3f, T.R_TILE)
        for (r in rows) row(c, r)
    }

    private fun row(c: Canvas, r: Row) {
        ink.convex(c, 77.7f, r.y, 114.3f, 119.7f, 54.85f)
        icons.draw(c, r.icon, 108.8f, r.y + r.iconDy, 53f, 55.2f)
        ink.text(c, r.title, 226.7f, T.base(r.y + 9.4f, T.TS_ACTION, 1.25f), T.TS_ACTION, 700, T.G1)
        if (r.value.isNotEmpty()) {
            ink.text(c, ink.ellipsize(r.value, VALUE_W, T.TS_BODY_S, 600), 226.7f,
                T.base(r.y + 61.7f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        }
        if (r.divY > 0f) ink.rect(c, 226.7f, r.divY, 1096.9f, 1.8f, T.G11)
        icons.draw(c, "next@43.9x45.7", 1295.2f, r.y + 35.6f, 43.9f, 45.7f)
    }

    // --------------------------------------------------------------- данные

    private fun refresh() {
        val ver = try {
            act.packageManager.getPackageInfo(act.packageName, 0).versionName ?: ""
        } catch (t: Throwable) {
            ""
        }
        subtitle = if (ver.isEmpty()) "DEXP M8 Prudentia" else "DEXP M8 Prudentia · оболочка $ver"
        val p = act.prefs
        val br = act.sys.brightness()
        rows[0].value = if (br >= 0) "Подсветка $br%" else ""
        rows[1].value = "Wi-Fi ${onOff(act.sys.wifiOn())} · Bluetooth ${onOff(act.sys.btOn())}"
        rows[2].value = "Зоны касаний: " + arrayOf("классическая", "две половины", "только вперёд")
            .getOrElse(p.tapZones) { "" }
        rows[3].value = "Листание свайпом ${onOff(p.swipe)}"
        rows[4].value = when (val ms = act.sys.sleepTimeout()) {
            -1 -> ""
            Int.MAX_VALUE -> "Не засыпать"
            else -> "Засыпать через ${ms / 60_000} мин"
        }
        rows[5].value = SettingsData.langValue(act)
        rows[6].value = SettingsData.storage()?.let { (total, free) ->
            "Занято ${SettingsData.gb1(total - free)} из ${SettingsData.capacityGb(total)} ГБ"
        } ?: ""
        rows[7].value = if (ver.isEmpty()) "Android ${Build.VERSION.RELEASE}"
            else "Android ${Build.VERSION.RELEASE} · версия оболочки $ver"
    }

    private fun onOff(on: Boolean) = if (on) "вкл." else "выкл."

    private companion object {
        const val VALUE_W = 987.2f
    }

    // --------------------------------------------------------------- ввод

    override fun onUp(x: Float, y: Float) {
        if (x >= 41.1f && x <= 148f && y >= 112.9f && y <= 225.3f) {
            act.pop()
            return
        }
        for (r in rows) {
            if (x >= 77.7f && x <= 1339.1f && y >= r.y && y <= r.y + 146.3f) {
                act.go(r.target)
                return
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
        refresh()
    }

    override fun onDetachedFromWindow() {
        status.detach()
        super.onDetachedFromWindow()
    }
}
