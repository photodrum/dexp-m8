package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import android.provider.Settings

/**
 * B37 · Настройки · Экран. Числа — из design/v5/B37.json.
 *
 * Читается только яркость (`Settings.System.SCREEN_BRIGHTNESS`, право не нужно). Тёплый свет,
 * режим обновления, очистка, контраст и ориентация в системе не лежат — до шага 4.6 рисуются
 * значения по умолчанию из макета. Ввод не обрабатывается, работает только «Назад».
 */
class SetScreenScreen(act: EinkActivity) : Screen(act) {

    private val status = StatusBar(act, this, ink, icons)
    private val kit = SettingsKit(ink, icons)

    private var brightness = 62
    private val warm = 30
    private val mode = 0
    private val modes = arrayOf("Чёткий", "Быстрый", "Без мерцания")

    override fun render(c: Canvas) {
        ink.rect(c, 0f, 0f, W, H, T.BG)
        status.draw(c)
        kit.header(c, "Экран", "Подсветка, обновление, контраст")

        kit.section(c, "ПОДСВЕТКА", 250f)
        kit.panel(c, 298f, 440f)
        kit.title(c, "Яркость", 336f)
        kit.valueBig(c, "$brightness%", 340f)
        kit.slider(c, 408.5f, brightness, false)
        kit.divider(c, 520f)
        kit.title(c, "Тёплый свет", 548f)
        kit.valueBig(c, "$warm%", 552f)
        kit.slider(c, 624f, warm, true)

        kit.section(c, "ОБНОВЛЕНИЕ ЭКРАНА", 770f)
        kit.panel(c, 818f, 530f)
        kit.title(c, "Режим", 856f)
        kit.segments(c, 920f, modes, mode)
        kit.title(c, "Полная очистка", 1066f)
        kit.desc(c, "Убирает следы прошлых страниц", 1119f)
        kit.valueRow(c, "каждые 6 стр.", 1092.6f)
        kit.next(c, 1103.5f)
        kit.divider(c, 1204f)
        kit.title(c, "Очищать при смене экрана", 1246.5f)
        kit.toggle(c, 1237f, true)

        kit.section(c, "ОТОБРАЖЕНИЕ", 1380f)
        kit.panel(c, 1428f, 340f)
        kit.title(c, "Усиленный контраст", 1469f)
        kit.desc(c, "Темнее серые и тонкие линии", 1522f)
        kit.toggle(c, 1481f, false)
        kit.divider(c, 1604f)
        kit.title(c, "Ориентация", 1656.5f)
        kit.valueRow(c, "Книжная", 1664.5f)
        kit.next(c, 1672.5f)
    }

    private fun refresh() {
        try {
            val v = Settings.System.getInt(act.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            brightness = (v * 100 + 127) / 255
        } catch (t: Throwable) {
            Trace.step("settings brightness FAIL: ${t.javaClass.simpleName}: ${t.message}")
        }
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
