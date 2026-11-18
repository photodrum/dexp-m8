package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import ru.ogrpfsense.dexpm8.core.Prefs
import ru.ogrpfsense.dexpm8.reader.Engine
import ru.ogrpfsense.dexpm8.reader.TypographySheet

/**
 * B62 · Первый запуск · шаг 4 · чтение (п. 13). Числа — из design/v5/B62.json.
 *
 *   раздел «ТЕКСТ»      59,480; панель 41,528 1321×480
 *   «Шрифт и размер»    88,562.5: круглая клавиша (Δ 88,562.5 115×120), название с x 237,
 *                       описание 625, `next` 1305,609.5, разделитель 237,710 1079
 *   «Интервал и поля»   88,761, значение до 1267.6, `next` 1305,777, разделитель 88,864
 *   «Переносы слов»     88,906.5, тумблер 1177,897
 *   раздел «ЗОНЫ КАСАНИЙ» 59,1040; панель 41,1088 1321×570
 *   схема               88,1130 360×480 + варианты 490,1130 / 1292 / 1454 по 140
 *   кнопка «Готово»     41.1,1693.6 1320.8×141.7
 *
 * Пишутся те же `Prefs`, что у B39/B70: значения общие («настройки чтения по умолчанию»).
 * Ввод: «Шрифт и размер» — B70, «Интервал и поля» — B39 (своего листа нет), переносы — тумблер,
 * зоны — три варианта. «Готово» ведёт на B63.
 */
class FirstReadScreen(act: EinkActivity) : Screen(act) {

    private class Zone(val title: String, val desc: String, val y: Float)

    /** Шторку B5 на шаге мастера не открываем: панель управления здесь ни к чему. */
    override val shadeZone: Float get() = 0f

    private val status = StatusBar(act, this, ink, icons)
    private val kit = SettingsKit(ink, icons)
    private val first = FirstRunKit(ink, icons)

    private val zones = arrayOf(
        Zone("Классическая", "Левая треть — назад, остальное — вперёд", 1130f),
        Zone("Две половины", "Левая — назад, правая — вперёд", 1292f),
        Zone("Только вперёд", "Любое касание листает вперёд", 1454f)
    )

    override fun render(c: Canvas) {
        val p = act.prefs
        ink.rect(c, 0f, 0f, W, H, T.BG)
        status.draw(c)
        first.header(c, 4, "Как удобнее читать",
            "Шрифт, поля и зоны касаний для новых книг. В каждой книге их можно поменять отдельно.")

        kit.section(c, "ТЕКСТ", 480f)
        kit.panel(c, 528f, 480f)
        kit.key(c, 88f, 562.5f)
        icons.draw(c, "font@39.8x33.1", 127.2f, 605f, 39.8f, 33.1f)
        kit.title(c, "Шрифт и размер", 572f, 237f)
        kit.desc(c, faceText(p), 625f, 237f)
        icons.draw(c, "next@11x23.8", 1305f, 609.5f, 11f, 23.8f)
        kit.divider(c, 710f, 237f, 1079f)

        kit.title(c, "Интервал и поля", 761f)
        kit.valueRow(c, spacingText(p), 769f)
        kit.next(c, 777f)
        kit.divider(c, 864f)

        kit.title(c, "Переносы слов", 906.5f)
        kit.toggle(c, 897f, p.hyphens)

        kit.section(c, "ЗОНЫ КАСАНИЙ", 1040f)
        kit.panel(c, 1088f, 570f)
        scheme(c)
        for (i in zones.indices) variant(c, zones[i], i == p.tapZones)

        first.buttonDark(c, "Готово")
    }

    /** Подпись «Шрифт и размер»: гарнитура и кегль (в макете «Литерата · 20»), как на B39. */
    private fun faceText(p: Prefs): String {
        val name = when (p.textFace) {
            Engine.FACE_SANS -> "Manrope"
            Engine.FACE_MONO -> "Моно"
            Engine.FACE_PTSERIF -> "PT Serif"
            Engine.FACE_SERIF -> "Литерата"
            else -> "Свой шрифт"
        }
        return "$name · ${p.textSize}"
    }

    /** «Обычный · широкие»: подписи те же, что у листа B4 (`TypographySheet`). */
    private fun spacingText(p: Prefs): String {
        val sp = TypographySheet.SPACING_LABELS.getOrElse(p.textSpacing) { "" }
        val mg = TypographySheet.MARGIN_LABELS.getOrElse(p.textMargins) { "" }
        return "$sp · ${mg.lowercase()}"
    }

    /** Схема страницы 360×480: зона «Назад» и «Меню», вперёд — остальное поле. */
    private fun scheme(c: Canvas) {
        ink.inset(c, 88f, 1130f, 360f, 480f, 28f, T.G12)
        ink.roundRect(c, 102f, 1146f, 104.8f, 450f, 16f, T.G10)
        ink.roundRect(c, 212.8f, 1146f, 110.4f, 144f, 16f, T.G11)
        cap(c, "Назад", 102f, 104.8f, 1352.5f)
        cap(c, "Меню", 212.8f, 110.4f, 1200.5f)
        cap(c, "Вперёд", 206.8f, 227.2f, 1424.5f)
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
        // «Шрифт и размер» — B70: значения те же `Prefs`, что у листа B4.
        if (kit.inRow(x, y, 562.5f, 149.5f)) { act.go(EinkActivity.SCR_SET_FONT); return }
        val p = act.prefs
        if (kit.inRow(x, y, 864f, 144f)) {
            p.hyphens = !p.hyphens
            redraw()
            return
        }
        for (i in zones.indices) {
            val z = zones[i]
            if (x >= 490f && x <= 1314f && y >= z.y && y <= z.y + 140f) {
                if (p.tapZones != i) { p.tapZones = i; redraw() }
                return
            }
        }
        if (first.isDarkFull(x, y)) act.go(EinkActivity.SCR_FIRST_DONE)
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
