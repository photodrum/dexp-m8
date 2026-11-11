package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas

/**
 * B39 · Настройки · Чтение по умолчанию. Числа — из design/v5/B39.json.
 *
 * Читалки ещё нет: «Переносы слов», вариант зон касаний и «Листать свайпом» хранятся в `act.prefs`
 * (шаг 4.6) и будут прочитаны читалкой. «Шрифт и размер», «Интервал и поля», «Кнопки громкости» —
 * значения по умолчанию из макета, не нажимаются (их экраны — вместе с читалкой).
 * Блок «Кнопки и жесты» внизу — это и есть экран `SCR_SET_KEYS`: он открывает тот же экран.
 */
class SetReadScreen(act: EinkActivity) : Screen(act) {

    private class Zone(val title: String, val desc: String, val y: Float)

    private val status = StatusBar(act, this, ink, icons)
    private val kit = SettingsKit(ink, icons)

    private val zones = arrayOf(
        Zone("Классическая", "Левая треть — назад, остальное — вперёд", 900f),
        Zone("Две половины", "Левая — назад, правая — вперёд", 1062f),
        Zone("Только вперёд", "Любое касание листает вперёд", 1224f)
    )

    override fun render(c: Canvas) {
        val p = act.prefs
        ink.rect(c, 0f, 0f, W, H, T.BG)
        status.draw(c)
        kit.header(c, "Чтение", "Для новых книг · шрифт, зоны, кнопки")

        kit.section(c, "ТЕКСТ", 250f)
        kit.panel(c, 298f, 480f)
        kit.key(c, 88f, 332.5f)
        icons.draw(c, "font@39.8x33.1", 127.2f, 375f, 39.8f, 33.1f)
        kit.title(c, "Шрифт и размер", 342f, 237f)
        kit.desc(c, "Литерата · 20", 395f, 237f)
        kit.next(c, 379.5f)
        kit.divider(c, 480f, 237f, 1079f)
        kit.title(c, "Интервал и поля", 531f)
        kit.valueRow(c, "Обычный · широкие", 539f)
        kit.next(c, 547f)
        kit.divider(c, 634f)
        kit.title(c, "Переносы слов", 676.5f)
        kit.toggle(c, 667f, p.hyphens)

        kit.section(c, "ЗОНЫ КАСАНИЙ", 810f)
        kit.panel(c, 858f, 570f)
        scheme(c)
        for (i in zones.indices) variant(c, zones[i], i == p.tapZones)

        kit.section(c, "КНОПКИ И ЖЕСТЫ", 1460f)
        kit.panel(c, 1508f, 310f)
        kit.title(c, "Кнопки громкости", 1570.5f)
        kit.valueRow(c, "Листание", 1578.5f)
        kit.next(c, 1586.5f)
        kit.divider(c, 1684f)
        kit.title(c, "Листать свайпом", 1721.5f)
        kit.toggle(c, 1712f, p.swipe)
    }

    /** Схема страницы 360×480: зона «Назад» и «Меню» (вперёд — остальное поле). */
    private fun scheme(c: Canvas) {
        ink.inset(c, 88f, 900f, 360f, 480f, 28f, T.G12)
        ink.roundRect(c, 102f, 916f, 104.8f, 450f, 16f, T.G10)
        ink.roundRect(c, 212.8f, 916f, 110.4f, 144f, 16f, T.G11)
        cap(c, "Назад", 102f, 104.8f, 1122.5f)
        cap(c, "Меню", 212.8f, 110.4f, 970.5f)
        cap(c, "Вперёд", 206.8f, 227.2f, 1194.5f)
    }

    private fun cap(c: Canvas, s: String, x: Float, w: Float, top: Float) {
        ink.textCenter(c, s, x, w, T.base(top, T.TS_CAPTION, 1.25f), T.TS_CAPTION, 700, T.G1)
    }

    /** Вариант зон: выбранный — вдавленный желоб 819×140, остальные — выпуклые 824×140. */
    private fun variant(c: Canvas, z: Zone, selected: Boolean) {
        if (selected) ink.inset(c, 490f, z.y, 819f, 140f, 40f, T.G12)
        else ink.convex(c, 490f, z.y, 824f, 140f, 40f)
        ink.text(c, z.title, 526f, T.base(z.y + 24f, T.TS_LABEL_L, 1.25f), T.TS_LABEL_L, 700, T.G1)
        ink.text(c, z.desc, 526f, T.base(z.y + 74f, T.TS_META, 1.25f), T.TS_META, 600, T.G4)
    }

    override fun onUp(x: Float, y: Float) {
        if (kit.isBack(x, y)) { act.pop(); return }
        val p = act.prefs
        if (kit.inRow(x, y, 634f, 144f)) { p.hyphens = !p.hyphens; redraw(); return }
        for (i in zones.indices) {
            val z = zones[i]
            if (x >= 490f && x <= 1314f && y >= z.y && y <= z.y + 140f) {
                if (p.tapZones != i) { p.tapZones = i; redraw() }
                return
            }
        }
        if (kit.inRow(x, y, 1684f, 134f)) { p.swipe = !p.swipe; redraw() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
    }

    override fun onDetachedFromWindow() {
        status.detach()
        super.onDetachedFromWindow()
    }
}
