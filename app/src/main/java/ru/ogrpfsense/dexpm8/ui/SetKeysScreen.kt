package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import ru.ogrpfsense.dexpm8.core.Prefs

/**
 * B13 · «Кнопки и жесты» — отдельный экран раздела «Настройки» вместо дубля B39.
 *
 * Своего полотна в макете v5 нет: до 2026-11-21 раздел `КНОПКИ И ЖЕСТЫ` был нижним блоком
 * B39 «Чтение», и строка B13 открывала тот же экран (B39 целиком, шапка «Чтение»). Решение
 * человека — разделить: здесь только две настройки блока, числа и порядок взяты из того же
 * блока `design/v5/B39.json` (раздел 1460, панель 1508 высотой 310, строки 1570.5 и 1712,
 * разделитель 1684), сдвинутые на верх экрана: панель 298, то есть всё −1210.
 *
 * Значения — `act.prefs`, как и раньше:
 *   - «Кнопки громкости» — «Листание ⇄ Громкость» (`Prefs.volumeKeys`, B39.2). Клавиши страниц
 *     (`KEY_PAGEUP`/`KEY_PAGEDOWN`) листают всегда, их это не касается;
 *   - «Листать свайпом» — `Prefs.swipe`.
 * Зоны касаний сюда не входят: схема одна («Классическая», макет 2026-11-20) и живёт на B39.
 */
class SetKeysScreen(act: EinkActivity) : Screen(act) {

    private val status = StatusBar(act, this, ink, icons)
    private val kit = SettingsKit(ink, icons)

    override fun render(c: Canvas) {
        val p = act.prefs
        ink.rect(c, 0f, 0f, W, H, T.BG)
        status.draw(c)
        kit.header(c, "Кнопки и жесты", "Клавиши громкости, листание касанием")

        kit.section(c, "КНОПКИ И ЖЕСТЫ", 250f)
        kit.panel(c, 298f, 310f)
        kit.title(c, "Кнопки громкости", 360.5f)
        kit.valueRow(c, Prefs.VOLUME_LABELS.getOrElse(p.volumeKeys) { "" }, 368.5f)
        kit.next(c, 376.5f)
        kit.divider(c, 474f)
        kit.title(c, "Листать свайпом", 511.5f)
        kit.toggle(c, 502f, p.swipe)
    }

    override fun onUp(x: Float, y: Float) {
        if (kit.isBack(x, y)) { act.pop(); return }
        val p = act.prefs
        // «Кнопки громкости» — переключение «Листание ⇄ Громкость» (B39.2): отдельного экрана
        // в макете нет, стрелка `next` ведёт в переключение.
        if (kit.inRow(x, y, 360.5f, 113.5f)) {
            p.volumeKeys = if (p.volumeKeys == Prefs.VOLUME_PAGING) Prefs.VOLUME_SOUND
                else Prefs.VOLUME_PAGING
            redraw()
            return
        }
        if (kit.inRow(x, y, 474f, 134f)) { p.swipe = !p.swipe; redraw() }
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
