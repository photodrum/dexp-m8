package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import ru.ogrpfsense.dexpm8.core.Prefs
import ru.ogrpfsense.dexpm8.reader.Engine
import ru.ogrpfsense.dexpm8.reader.TypographySheet

/**
 * B39 · Настройки · Чтение по умолчанию. Числа — из design/v5/B39.json (снимок 2026-11-20).
 *
 * «Переносы слов» и схема зон хранятся в `act.prefs` (шаг 4.6).
 * Строка «Интервал и поля» показывает значения `Prefs` (те же, что ставит лист B4), но
 * собственного экрана в макете нет — касание по ней пока ничего не открывает.
 *
 * **Блок «Кнопки и жесты» вынесен на свой экран (2026-11-21, `SetKeysScreen`).** В макете блок
 * лежит внизу B39, и строка B13 «Кнопки и жесты» открывала весь этот экран целиком — с шапкой
 * «Чтение», шрифтом и зонами. Решение человека — разделить: кнопки громкости и свайп живут на
 * `SCR_SET_KEYS`, B39 остаётся на текст и зоны касаний.
 *
 * **Зоны касаний — одна схема, без выбора (макет 2026-11-20, решение человека).** В B39 остался
 * единственный вариант «Классическая»: левая треть — назад, правая — вперёд, центр — меню, и
 * плашки выбора из макета убраны. Поэтому экран рисует название с правилом, но не нажимается;
 * `Prefs.tapZones` остаётся ключом только на чтение (им пользуется прошлое значение в базе),
 * переключать его больше нечем — читалка и так листает третями.
 */
class SetReadScreen(act: EinkActivity) : Screen(act) {

    private val status = StatusBar(act, this, ink, icons)
    private val kit = SettingsKit(ink, icons)

    override fun render(c: Canvas) {
        val p = act.prefs
        ink.rect(c, 0f, 0f, W, H, T.BG)
        status.draw(c)
        kit.header(c, "Чтение", "Для новых книг · шрифт и зоны")

        kit.section(c, "ТЕКСТ", 250f)
        kit.panel(c, 298f, 480f)
        kit.key(c, 88f, 332.5f)
        icons.draw(c, "font@39.8x33.1", 127.2f, 375f, 39.8f, 33.1f)
        kit.title(c, "Шрифт и размер", 342f, 237f)
        kit.desc(c, faceText(p), 395f, 237f)
        kit.next(c, 379.5f)
        kit.divider(c, 480f, 237f, 1079f)
        kit.title(c, "Интервал и поля", 531f)
        kit.valueRow(c, spacingText(p), 539f)
        kit.next(c, 547f)
        kit.divider(c, 634f)
        kit.title(c, "Переносы слов", 676.5f)
        kit.toggle(c, 667f, p.hyphens)

        kit.section(c, "ЗОНЫ КАСАНИЙ", 810f)
        kit.panel(c, 858f, 570f)
        scheme(c)
        schemeText(c)
    }

    /** Подпись строки «Шрифт и размер»: гарнитура и кегль, как они выбраны (в макете «Литерата · 20»). */
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

    /**
     * Подпись строки «Интервал и поля»: интервал и поля из `Prefs` (в макете «Обычный · широкие»).
     * Значения те же, что ставит лист B4 «Типографика», — подписи берём оттуда, чтобы не разъезжались.
     */
    private fun spacingText(p: Prefs): String {
        val sp = TypographySheet.SPACING_LABELS.getOrElse(p.textSpacing) { "" }
        val mg = TypographySheet.MARGIN_LABELS.getOrElse(p.textMargins) { "" }
        // В макете первое слово с большой буквы («Обычный · широкие»), поля — строчными.
        return "$sp · ${mg.lowercase()}"
    }

    /**
     * Схема страницы 360×480 (макет 2026-11-20): по бокам узкие полосы «Назад» и «Вперёд»
     * по всей высоте, в центре две плашки меню — сверху «Меню экрана» (шторка B5),
     * ниже «Меню книги» (меню чтения B9).
     */
    private fun scheme(c: Canvas) {
        ink.inset(c, 88f, 900f, 360f, 480f, 28f, T.G12)
        ink.roundRect(c, 102f, 916f, 104.8f, 450f, 16f, T.G10)
        ink.roundRect(c, 212.8f, 916f, 110.4f, 144f, 16f, T.G11)
        ink.roundRect(c, 330.5f, 916f, 104.8f, 450f, 16f, T.G10)
        ink.roundRect(c, 212.8f, 1067.6f, 110.4f, 144f, 16f, T.G11)
        cap(c, "Назад", 102f, 104.8f, 1122.5f)
        cap2(c, "Меню", "экрана", 212.8f, 110.4f, 957.5f)
        cap(c, "Вперёд", 330.5f, 104.8f, 1122.5f)
        cap2(c, "Меню", "книги", 212.8f, 110.4f, 1106.6f)
    }

    /** Подпись зоны: 28/700 G1 по центру полосы, верх рамки [top]. */
    private fun cap(c: Canvas, s: String, x: Float, w: Float, top: Float) {
        ink.textCenter(c, s, x, w, T.base(top, T.TS_CAPTION, 1.25f), T.TS_CAPTION, 700, T.G1)
    }

    /**
     * Подпись в две строки (28/700 G1 по центру): рамка в макете 70 высотой, кегль 28 с
     * межстрочным 1.25 даёт шаг 35 — вторая строка на [top] + 35.
     */
    private fun cap2(c: Canvas, line1: String, line2: String, x: Float, w: Float, top: Float) {
        ink.textCenter(c, line1, x, w, T.base(top, T.TS_CAPTION, 1.25f), T.TS_CAPTION, 700, T.G1)
        ink.textCenter(c, line2, x, w, T.base(top + CAP2_STEP, T.TS_CAPTION, 1.25f),
            T.TS_CAPTION, 700, T.G1)
    }

    /**
     * Единственный вариант зон (макет 2026-11-20): название 38/700 и правило в две строки
     * 30/600 G4. Плашки выбора и желоба больше нет — нажимать нечего, поэтому касания к блоку
     * не привязаны.
     */
    private fun schemeText(c: Canvas) {
        ink.text(c, "Классическая", 526f, T.base(1080.6f, T.TS_LABEL_L, 1.25f),
            T.TS_LABEL_L, 700, T.G1)
        ink.text(c, "Левая треть — назад", 526f, T.base(1130.6f, T.TS_META, 1.25f),
            T.TS_META, 600, T.G4)
        ink.text(c, "Правая треть — вперёд", 526f, T.base(1130.6f + DESC_STEP, T.TS_META, 1.25f),
            T.TS_META, 600, T.G4)
    }

    override fun onUp(x: Float, y: Float) {
        if (kit.isBack(x, y)) { act.pop(); return }
        // «Шрифт и размер» — экран B70 (R3): значения те же `Prefs`, что у листа B4.
        if (kit.inRow(x, y, 298f, 182f)) { act.go(EinkActivity.SCR_SET_FONT); return }
        val p = act.prefs
        if (kit.inRow(x, y, 634f, 144f)) { p.hyphens = !p.hyphens; redraw(); return }
        // Варианта зон больше нет (макет 2026-11-20): блок «Классическая» ничего не нажимает.
        // Кнопки громкости и свайп — на экране `SCR_SET_KEYS` (`SetKeysScreen`).
    }

    private companion object {
        /** Шаг второй строки подписи зоны: рамка 70 при кегле 28 и межстрочном 1.25. */
        const val CAP2_STEP = 35f
        /** Шаг второй строки правила: рамка 76 на две строки, кегль 30. */
        const val DESC_STEP = 38f
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
