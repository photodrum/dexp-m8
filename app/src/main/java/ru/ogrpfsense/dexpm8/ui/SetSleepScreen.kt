package ru.ogrpfsense.dexpm8.ui

import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.os.BatteryManager
import android.provider.Settings

/**
 * B40 · Настройки · Сон и питание. Числа — из design/v5/B40.json.
 *
 * Читается: заряд (липкий `ACTION_BATTERY_CHANGED`, право не нужно), «Засыпать через» —
 * `Settings.System.SCREEN_OFF_TIMEOUT`, порог экономии — `Settings.Global.low_power_trigger_level`.
 * Пишется (шаг 4.6): «Засыпать через» — `act.sys.setSleepTimeout` (WRITE_SETTINGS; «Никогда» —
 * Int.MAX_VALUE); «Экономия заряда» и «Показывать заряд на заставке» — `act.prefs` (исполнятся
 * с B19/B20). Порог экономии не пишется: Settings.Global закрыт для обычных приложений.
 * Не рисуются (данных нет): «Хватит примерно на…» и «Последняя зарядка — …» — отступ от макета,
 * место справа от «78 %» остаётся пустым. «Заставка сна» и «Выключать после сна» не нажимаются.
 */
class SetSleepScreen(act: EinkActivity) : Screen(act) {

    private val status = StatusBar(act, this, ink, icons)
    private val kit = SettingsKit(ink, icons)

    private val timeouts = arrayOf("5 мин", "10 мин", "30 мин", "Никогда")
    private val timeoutMs = intArrayOf(300_000, 600_000, 1_800_000, Int.MAX_VALUE)
    private var active = 1
    private var saverAt = 15

    override fun render(c: Canvas) {
        val p = act.prefs
        ink.rect(c, 0f, 0f, W, H, T.BG)
        status.draw(c)
        kit.header(c, "Сон и питание", "Заставка, засыпание, батарея")

        val pct = battery()
        kit.section(c, "БАТАРЕЯ", 250f)
        kit.panel(c, 298f, 460f)
        ink.text(c, pct.toString(), 88f, T.base(320f, T.TS_HUGE, 1.1f), T.TS_HUGE, 700, T.G1)
        val numW = ink.measure(pct.toString(), T.TS_HUGE, 700)
        ink.text(c, "%", 88f + numW + 6f, T.base(411.1f, 48f, 1.25f), 48f, 700, T.G1)
        ink.inset(c, 88f, 520f, 1221f, 40f, 20f, T.G12)
        val fillW = 1215f * pct / 100f
        if (fillW >= 32f) ink.roundRect(c, 91f, 525f, fillW, 32f, 16f, T.G3)

        kit.title(c, "Экономия заряда", 606f)
        kit.desc(c, "Включится при $saverAt%: без Wi-Fi и подсветки", 659f)
        kit.toggle(c, 618f, p.saver, 1170f)

        kit.section(c, "СОН", 810f)
        kit.panel(c, 858f, 730f)
        kit.title(c, "Засыпать через", 896f)
        kit.segments(c, 962f, timeouts, active)
        kit.divider(c, 1100f)

        kit.key(c, 88f, 1130f)
        icons.draw(c, "moon@33.6x35.8", 129.4f, 1171f, 33.6f, 35.8f)
        kit.title(c, "Заставка сна", 1139.5f, 237f)
        kit.desc(c, "Обложка текущей книги", 1192.5f, 237f)
        kit.next(c, 1177f)
        kit.divider(c, 1274f, 237f, 1079f)

        kit.title(c, "Показывать заряд на заставке", 1328f)
        kit.toggle(c, 1318.5f, p.coverCharge)
        kit.divider(c, 1434f)

        kit.title(c, "Выключать после сна", 1460f)
        kit.desc(c, "Если книгу не открывали", 1513f)
        kit.valueRow(c, "через 3 дня", 1486.6f)
        kit.next(c, 1497.5f)
    }

    private fun battery(): Int {
        val i = act.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return 78
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (level < 0 || scale <= 0) 78 else (level * 100 / scale).coerceIn(0, 100)
    }

    private fun refresh() {
        val cr = act.contentResolver
        val ms = act.sys.sleepTimeout()
        if (ms >= 0) active = when {
            ms < 300_000 -> -1          // короче 5 минут: такого сегмента в макете нет
            ms <= 300_000 -> 0
            ms <= 600_000 -> 1
            ms <= 1_800_000 -> 2
            else -> 3
        }
        val lvl = Settings.Global.getInt(cr, "low_power_trigger_level", 0)
        if (lvl in 1..99) saverAt = lvl
    }

    override fun onUp(x: Float, y: Float) {
        if (kit.isBack(x, y)) { act.pop(); return }
        val p = act.prefs
        val s = kit.segmentHit(x, y, 962f, timeouts.size)
        if (s >= 0) {
            if (s != active) act.withWriteSettings {
                if (act.sys.setSleepTimeout(timeoutMs[s])) active = s
                redraw()
            }
            return
        }
        if (kit.inRow(x, y, 580f, 160f)) { p.saver = !p.saver; redraw(); return }
        if (kit.inRow(x, y, 1274f, 160f)) { p.coverCharge = !p.coverCharge; redraw() }
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
