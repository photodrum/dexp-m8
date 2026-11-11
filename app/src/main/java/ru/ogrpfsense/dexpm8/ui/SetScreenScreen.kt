package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas

/**
 * B37 · Настройки · Экран. Числа — из design/v5/B37.json.
 *
 * Яркость — `Settings.System.SCREEN_BRIGHTNESS` через `act.sys` (запись — право WRITE_SETTINGS,
 * при первом касании открывается системный экран). Тёплый свет, режим обновления, «Очищать при
 * смене экрана» и контраст в системе не лежат — хранятся в `act.prefs`; на экран книги они
 * подействуют с драйвером Rockchip (п. 8). «Полная очистка» и «Ориентация» не нажимаются
 * (своих экранов в макете нет).
 */
class SetScreenScreen(act: EinkActivity) : Screen(act) {

    private val status = StatusBar(act, this, ink, icons)
    private val kit = SettingsKit(ink, icons)

    private var brightness = 62
    private val modes = arrayOf("Чёткий", "Быстрый", "Без мерцания")

    override fun render(c: Canvas) {
        val p = act.prefs
        val warm = p.warm
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
        kit.segments(c, 920f, modes, p.refreshMode)
        kit.title(c, "Полная очистка", 1066f)
        kit.desc(c, "Убирает следы прошлых страниц", 1119f)
        kit.valueRow(c, "каждые 6 стр.", 1092.6f)
        kit.next(c, 1103.5f)
        kit.divider(c, 1204f)
        kit.title(c, "Очищать при смене экрана", 1246.5f)
        kit.toggle(c, 1237f, p.cleanOnSwitch)

        kit.section(c, "ОТОБРАЖЕНИЕ", 1380f)
        kit.panel(c, 1428f, 340f)
        kit.title(c, "Усиленный контраст", 1469f)
        kit.desc(c, "Темнее серые и тонкие линии", 1522f)
        kit.toggle(c, 1481f, p.contrast)
        kit.divider(c, 1604f)
        kit.title(c, "Ориентация", 1656.5f)
        kit.valueRow(c, "Книжная", 1664.5f)
        kit.next(c, 1672.5f)
    }

    private var probed = false

    private fun refresh() {
        val v = act.sys.brightness()
        if (v >= 0) brightness = v
        // Замечание 4: проба записи контраста, один раз за процесс; итог — в трассе.
        if (!probed) { probed = true; act.sys.probeGamma() }
    }

    override fun onUp(x: Float, y: Float) {
        if (kit.isBack(x, y)) { act.pop(); return }
        val p = act.prefs
        kit.sliderHit(x, y, 408.5f, brightness)?.let { v ->
            act.withWriteSettings {
                if (act.sys.setBrightness(v)) brightness = v
                redraw()
            }
            return
        }
        kit.sliderHit(x, y, 624f, p.warm)?.let { v -> p.warm = v; redraw(); return }
        val m = kit.segmentHit(x, y, 920f, modes.size)
        if (m >= 0) { p.refreshMode = m; redraw(); return }
        if (kit.inRow(x, y, 1204f, 140f)) { p.cleanOnSwitch = !p.cleanOnSwitch; redraw(); return }
        if (kit.inRow(x, y, 1440f, 164f)) { p.contrast = !p.contrast; redraw() }
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
