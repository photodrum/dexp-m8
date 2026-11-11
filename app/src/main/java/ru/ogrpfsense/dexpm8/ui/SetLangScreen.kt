package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import android.provider.Settings
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * B41 · Настройки · Дата, время, язык. Числа — из design/v5/B41.json.
 *
 * Читается: время и дата, 12/24-часовой формат, язык, часовой пояс, «Время по сети»
 * (`Settings.Global.auto_time`). Не рисуются (данных нет): значения «Раскладки клавиатуры»
 * и «Словари». Время не обновляется само (на E-Ink нет таймеров) — только при открытии экрана
 * и при перерисовке. Пишется (шаг 4.6) только «24-часовой формат» — `Settings.System.TIME_12_24`
 * (WRITE_SETTINGS). «Время по сети» — Settings.Global, обычному приложению закрыт: только чтение.
 * Пояс, язык, раскладки и словари не нажимаются (своих экранов в макете нет).
 */
class SetLangScreen(act: EinkActivity) : Screen(act) {

    private val status = StatusBar(act, this, ink, icons)
    private val kit = SettingsKit(ink, icons)

    private var autoTime = true

    override fun render(c: Canvas) {
        val now = Date()
        val is24 = DateFormat.is24HourFormat(act)
        val l = Locale.getDefault()

        ink.rect(c, 0f, 0f, W, H, T.BG)
        status.draw(c)
        kit.header(c, "Дата, время, язык", SettingsData.langValue(act))

        kit.panel(c, 250f, 225f)
        val clock = SimpleDateFormat(if (is24) "HH:mm" else "h:mm", l).format(now)
        ink.text(c, clock, 88f, T.base(275.3f, T.TS_HUGE, 1.1f), T.TS_HUGE, 700, T.G1)
        val day = SimpleDateFormat("EEEE", l).format(now)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(l) else it.toString() }
        ink.text(c, day, 560f, T.base(307.5f, T.TS_ACTION, 1.25f), T.TS_ACTION, 700, T.G1)
        ink.text(c, SimpleDateFormat("d MMMM yyyy", l).format(now), 560f,
            T.base(363.5f, T.TS_LABEL_L, 1.25f), T.TS_LABEL_L, 600, T.G4)

        kit.section(c, "ВРЕМЯ", 550f)
        kit.panel(c, 598f, 480f)
        kit.title(c, "Время по сети", 642f)
        kit.desc(c, "Сверяется при подключении к Wi-Fi", 695f)
        kit.toggle(c, 654f, autoTime)
        kit.divider(c, 780f)

        kit.title(c, "Часовой пояс", 831f)
        kit.valueRow(c, zoneName(now), 839f)
        kit.next(c, 847f)
        kit.divider(c, 934f)

        kit.title(c, "24-часовой формат", 976.5f)
        kit.toggle(c, 967f, is24)

        kit.section(c, "ЯЗЫК И ВВОД", 1110f)
        kit.panel(c, 1158f, 470f)
        kit.title(c, "Язык оболочки", 1223.5f)
        kit.valueRow(c, SettingsData.langName(), 1231.5f)
        kit.next(c, 1239.5f)
        kit.divider(c, 1340f)

        kit.title(c, "Раскладки клавиатуры", 1386f)
        kit.next(c, 1402f)
        kit.divider(c, 1484f)

        kit.title(c, "Словари", 1526.5f)
        kit.next(c, 1542.5f)
    }

    /** «Москва, UTC+3»; если системного названия нет — только «UTC+3». */
    private fun zoneName(now: Date): String {
        val tz = TimeZone.getDefault()
        val min = tz.getOffset(now.time) / 60_000
        val h = Math.abs(min) / 60
        val m = Math.abs(min) % 60
        val utc = "UTC" + (if (min < 0) "−" else "+") + h + (if (m != 0) ":" + String.format("%02d", m) else "")
        val city = tz.getDisplayName(tz.inDaylightTime(now), TimeZone.LONG, Locale.getDefault())
            .substringBefore(',')
        return if (city.startsWith("GMT") || city.startsWith("UTC") || city.isEmpty()) utc else "$city, $utc"
    }

    private fun refresh() {
        autoTime = Settings.Global.getInt(act.contentResolver, "auto_time", 1) != 0
    }

    override fun onUp(x: Float, y: Float) {
        if (kit.isBack(x, y)) { act.pop(); return }
        if (kit.inRow(x, y, 934f, 144f)) {
            val want = !DateFormat.is24HourFormat(act)
            act.withWriteSettings {
                act.sys.setTime24(want)
                redraw()
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
