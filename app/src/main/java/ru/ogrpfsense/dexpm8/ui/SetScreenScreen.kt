package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import ru.ogrpfsense.dexpm8.core.Sys

/**
 * B37 · Настройки · Экран. Числа — из design/v5/B37.json.
 *
 * Яркость — `Settings.System.SCREEN_BRIGHTNESS` через `act.sys` (запись — право WRITE_SETTINGS,
 * при первом касании открывается системный экран). Режим обновления и «Очищать при смене экрана»
 * в системе не лежат — хранятся в `act.prefs`. «Очищать при смене экрана» работает с п. 6.1
 * (`EinkActivity.fullOnSwitch`), «Режим» — с п. 8.2 (`applyRefreshMode`, общий с B5; «Без
 * мерцания» пока как «Чёткий»).
 * «Полная очистка» и «Ориентация» не нажимаются (своих экранов в макете нет).
 *
 * **«Тёплый свет» → «Контраст»** (решение человека 2026-11-18, замечание 4 ROADMAP). Ползунок
 * тёплого света только хранился: подсветка Carta температуру не меняет. Контраст у книги
 * настоящий — системное свойство `sys.gray.gammalevel`, 16 делений `Sys.GAMMA_STEPS`, запись из
 * приложения работает (проверено шторкой B5 2026-11-12). Ползунок тот же, что у яркости, но:
 *   - шкала — деление 0…15, а не 0…100; проценты считаются как в B5: `round(деление · 100 / 15)`;
 *   - дорожка заполняется по делению (при 7 из 15 — 47 %, как в макете B5);
 *   - иконка справа — `contrast`, а не `moon`: тёплого света больше нет.
 * Запись — как в шторке (`ShadeScreen.contrastHit`): ставим свойство, читаем обратно, а если
 * прошивка его не отдала (`sys.*` сбрасываются при загрузке), оставляем выбранное деление в
 * `Prefs.gammaLevel` — иначе шкала «гасла» бы в ноль после касания.
 *
 * «Усиленный контраст» (тумблер в «ОТОБРАЖЕНИИ») убран: он только хранился в `Prefs`, и теперь
 * у контраста есть настоящий орган. «Ориентация» переехала с 1656.5 на 1469, подпись «Книжная» —
 * под названием (в макете это было значение справа), а панель сжалась 340 → 216: на две строки
 * она была нужна только вместе с тумблером.
 */
class SetScreenScreen(act: EinkActivity) : Screen(act) {

    private val status = StatusBar(act, this, ink, icons)
    private val kit = SettingsKit(ink, icons)

    private var brightness = 62
    private val modes = arrayOf("Чёткий", "Быстрый", "Без мерцания")

    /** Деление контраста 0…15; читается в [refresh] (система → `Prefs.gammaLevel`). */
    private var gamma = 7

    override fun render(c: Canvas) {
        val p = act.prefs
        ink.rect(c, 0f, 0f, W, H, T.BG)
        status.draw(c)
        kit.header(c, "Экран", "Подсветка, обновление, контраст")

        kit.section(c, "ПОДСВЕТКА", 250f)
        kit.panel(c, 298f, 440f)
        kit.title(c, "Яркость", 336f)
        kit.valueBig(c, "$brightness%", 340f)
        kit.slider(c, 408.5f, brightness)
        kit.divider(c, 520f)
        kit.title(c, "Контраст", 548f)
        kit.valueBig(c, percent(gamma), 552f)
        kit.sliderContrast(c, 624f, gamma)

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
        // Панель 340 из макета была на две строки («Усиленный контраст» + «Ориентация»); тумблер
        // убран (контраст теперь ползунком в «ПОДСВЕТКЕ»), поэтому высота — одна строка с
        // описанием: 41 (отступ макета) + 175 (шаг строк B37).
        kit.panel(c, 1428f, 216f)
        kit.title(c, "Ориентация", 1469f)
        kit.desc(c, "Книжная", 1522f)
        kit.next(c, 1503f)
    }

    /** Проценты по делению — как в шторке B5: 15 делений, 100 %. */
    private fun percent(i: Int) = "${Math.round(i * 100f / 15f)}%"

    private fun refresh() {
        val v = act.sys.brightness()
        if (v >= 0) brightness = v
        gamma = readGamma()
    }

    /**
     * Деление контраста: система → `Prefs.gammaLevel`. Прошивка `sys.gray.gammalevel` до первой
     * записи не задаёт, а `sys.*` сбрасываются при каждой загрузке (проверено на книге
     * 2026-11-16), поэтому «пусто» — это не деление 0, а последнее выставленное.
     */
    private fun readGamma(): Int {
        val v = act.sys.gamma()
        if (v < 0) return act.prefs.gammaLevel
        val i = Sys.gammaIndex(v)
        act.prefs.gammaLevel = i
        return i
    }

    /**
     * Запись деления в систему; возвращает то, что показывать: прочитанное значение, а если его
     * нет — выбранное. Итог — в трассу, как в шторке B5.
     */
    private fun setGamma(i: Int): Int {
        val ok = act.sys.setGamma(Sys.GAMMA_STEPS[i])
        val read = act.sys.gamma()
        val shown = if (read >= 0) Sys.gammaIndex(read) else i
        act.prefs.gammaLevel = shown
        Trace.step("B37: gamma ${Sys.GAMMA_STEPS[i]} write=${if (ok) "OK" else "FAIL"} read=$read")
        return shown
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
        // Контраст: ползунок работает в процентах (0…100, шаг 5), делений 16 — берём ближайшее.
        val pct = Math.round(gamma * 100f / 15f).toInt()
        kit.sliderHit(x, y, 624f, pct)?.let { v ->
            gamma = setGamma(Math.round(v * 15f / 100f).coerceIn(0, 15))
            redraw()
            return
        }
        val m = kit.segmentHit(x, y, 920f, modes.size)
        if (m >= 0) { p.refreshMode = m; act.applyRefreshMode(); redraw(); return }
        if (kit.inRow(x, y, 1204f, 140f)) { p.cleanOnSwitch = !p.cleanOnSwitch; redraw() }
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
