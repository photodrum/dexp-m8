package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas

/**
 * Общие детали мастера первого запуска B60–B63 и B8 (п. 13). Числа — из design/v5/B60.json:
 * на всех четырёх шагах шапка и полоса прогресса стоят одинаково, кнопки внизу — из B60/B61.
 *
 *   шаг        «Шаг 1 из 4» 51.2,110  32/700 G4
 *   заголовок  51,152       80/300 G1 (одна строка, на B62 длиннее — 732 px)
 *   пояснение  51.2,258     38/500 G5, рамка 1279.7×114 (две строки)
 *   прогресс   51.2,392     1301.6×29.2 вдавленный, радиус 14.625, дно G11;
 *              заливка G3 53.9,397.5 высотой 19.2, радиус 9.598, длина — доля от 1296.1
 *   кнопка тёмная  41.1,1693.6 1320.8×141.7 r=65.8125, подпись 42/700 G15 от x 95.1, `arrowR` 1265.1
 *   кнопка светлая 41.1,1693.6 553×141.7, подпись 42/700 G1 по центру
 *
 * Полоса прогресса для шага [step] из [STEPS] (1…4).
 */
class FirstRunKit(private val ink: Ink, private val icons: Icons) {

    /**
     * Шапка шага. [subtitle] — пояснение под заголовком: на всех бордах это две строки 38/500 G5
     * в рамке 1279.7×114, шаг строки 57 (lh 1.5). `Ink.paragraph` берёт метрики шрифта
     * (≈51.9 на кегле 38), поэтому зазор — 5, а не 19 из множителя.
     */
    fun header(c: Canvas, step: Int, title: String, subtitle: String) {
        ink.text(c, stepLabel(step), 51.2f, T.base(110f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 700, T.G4)
        ink.text(c, title, 51f, T.base(152f, T.TS_GREETING, 1.2f), T.TS_GREETING, 300, T.G1)
        ink.paragraph(c, subtitle, 51.2f, 259.5f, 1279.7f, T.TS_LABEL_L, 500, T.G5, 5f)
        progress(c, step)
    }

    /** «Шаг 3 из 4»; на B63 — «Готово» (пятый борд мастера, шага у него нет). */
    fun stepLabel(step: Int): String = if (step in 1..STEPS) "Шаг $step из $STEPS" else "Готово"

    /**
     * Полоса прогресса 51.2,392. Заливка — 1296.1·step/4: на B60 (1/4) в макете 324, на B61 и B8
     * (2/4) 648.1. На B8 макет оставил половину, хотя это шаг 3 — рисуем 3/4 (вопрос дизайну).
     */
    fun progress(c: Canvas, step: Int) {
        ink.inset(c, 51.2f, 392f, 1301.6f, 29.2f, 14.625f, T.G11)
        val w = 1296.1f * step.coerceIn(0, STEPS) / STEPS
        if (w >= 19.2f) ink.roundRect(c, 53.9f, 397.5f, w, 19.2f, 9.59766f, T.G3)
    }

    // ---------------------------------------------------------------- нижний ряд

    /** Тёмная кнопка 1320.8 (B60, B62): подпись и стрелка. */
    fun buttonDark(c: Canvas, label: String) {
        ink.convexDark(c, 41.1f, 1693.6f, 1320.8f, 141.7f, T.R_BUTTON)
        ink.text(c, label, 95.1f, T.base(1736.2f, T.TS_ACTION, 1.25f), T.TS_ACTION, 700, T.G15)
        icons.draw(c, "arrowR", 1265.1f, 1740.2f, 43.9f, 43.9f)
    }

    /** Светлая кнопка 553 (B61, B63): подпись по центру. */
    fun buttonLight(c: Canvas, label: String) {
        ink.convex(c, 41.1f, 1693.6f, 553f, 141.7f, T.R_BUTTON)
        ink.textCenter(c, label, 43.9f, 548.4f, T.base(1736.2f, T.TS_ACTION, 1.25f), T.TS_ACTION, 700, T.G1)
    }

    /** Тёмная кнопка в паре (B61, B63): 633.4, ширина 728.5. */
    fun buttonDarkRight(c: Canvas, label: String) {
        ink.convexDark(c, 633.4f, 1693.6f, 728.5f, 141.7f, T.R_BUTTON)
        ink.text(c, label, 687.4f, T.base(1736.2f, T.TS_ACTION, 1.25f), T.TS_ACTION, 700, T.G15)
        icons.draw(c, "arrowR", 1265.1f, 1740.2f, 43.9f, 43.9f)
    }

    /** Попадание в светлую кнопку 553 (x 41.1…594.1). */
    fun isLightButton(x: Float, y: Float) = x >= 41.1f && x <= 594.1f && y >= 1693.6f && y <= 1835.3f

    /** Попадание в правую тёмную кнопку 728.5 (x 633.4…1361.9). */
    fun isDarkRight(x: Float, y: Float) = x >= 633.4f && x <= 1361.9f && y >= 1693.6f && y <= 1835.3f

    /** Попадание в кнопку во всю ширину 1320.8. */
    fun isDarkFull(x: Float, y: Float) = x >= 41.1f && x <= 1361.9f && y >= 1693.6f && y <= 1835.3f

    companion object {
        /** Шагов в мастере: B60 язык, B61 Wi-Fi, B8 разрешения, B62 чтение. */
        const val STEPS = 4
    }
}
