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
 * Не рисуются (данных нет): «Хватит примерно на…» и «Последняя зарядка — …» — отступ от макета,
 * место справа от «78 %» остаётся пустым. Остальное — значения по умолчанию из макета.
 * Ввод не обрабатывается, работает только «Назад» (запись — шаг 4.6).
 */
class SetSleepScreen(act: EinkActivity) : Screen(act) {

    private val status = StatusBar(act, this, ink, icons)
    private val kit = SettingsKit(ink, icons)

    private val timeouts = arrayOf("5 мин", "10 мин", "30 мин", "Никогда")
    private var active = 1
    private var saverAt = 15
    private val saverOn = true
    private val coverCharge = true

    override fun render(c: Canvas) {
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
        kit.toggle(c, 618f, saverOn, 1170f)

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
        kit.toggle(c, 1318.5f, coverCharge)
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
        try {
            val ms = Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT)
            active = when {
                ms < 300_000 -> -1          // короче 5 минут: такого сегмента в макете нет
                ms <= 300_000 -> 0
                ms <= 600_000 -> 1
                ms <= 1_800_000 -> 2
                else -> 3
            }
        } catch (t: Throwable) {
            Trace.step("settings sleep FAIL: ${t.javaClass.simpleName}: ${t.message}")
        }
        val lvl = Settings.Global.getInt(cr, "low_power_trigger_level", 0)
        if (lvl in 1..99) saverAt = lvl
    }

    override fun onUp(x: Float, y: Float) {
        if (kit.isBack(x, y)) act.pop()
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
