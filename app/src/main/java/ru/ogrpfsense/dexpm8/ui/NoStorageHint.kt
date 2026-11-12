package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas

/**
 * B46 · Нужен доступ к файлам — экран «нет права на память» вместо содержимого библиотеки.
 * Числа — design/v5/B46.json; шапку и статус-бар рисует [LibraryScreen].
 *
 *   значок    гнездо 582,280 240×240 (r 120, дно G11) + «lock» 670,356.8 70×91
 *   заголовок 0,560 1404×72 60/700 lh 1.2 по центру
 *   пояснение 152,650 1100 38/500 lh 1.4 по центру, перенос по словам
 *   панель    41,820 1321×550 (выпуклая, r 51) — три шага
 *   шаг       круг 100×100 в 88,y (дно G12) + цифра 44/700 lh 1.2 по центру;
 *             y 866 / 1046 / 1226; «Шаг» 42/700 lh 1.25 в 228,y+2;
 *             пояснение 32/600 lh 1.25 G4 в 228,y+58; разделитель 228,y+140 1088×2 G11
 *   кнопки    «Открыть настройки» — тёмная 41,1500 1321×142, «settings» 106.6,1548.7 26.8×31.2,
 *             подпись 42/700 lh 1.25 G15 в 170,1542.8; «Позже» — выпуклая 41,1715 1321×120,
 *             подпись 38/700 lh 1.25 по центру
 *
 * Раньше на месте панели списка была подсказка «Нажмите, чтобы разрешить» (9.2, временно).
 * Касания обрабатывает [LibraryScreen]: кнопки те же, что в макете (1500 и 1715).
 */
internal class NoStorageHint(private val ink: Ink, private val icons: Icons) {

    fun draw(c: Canvas) {
        ink.inset(c, 582f, 280f, 240f, 240f, 120f)
        icons.draw(c, "lock@70x91", 670f, 356.8f, 70f, 91f)

        ink.textCenter(c, "Нужен доступ к файлам", 0f, W, T.base(560f, T.TS_TITLE, 1.2f),
            T.TS_TITLE, 700, T.G1)
        ink.paragraphCenter(c, "Без него оболочка не увидит книги в памяти устройства. " +
            "Разрешение выдаётся один раз.", 152f, 650f, 1100f, T.TS_LABEL_L, 500, T.G1, 1.4f)

        ink.convex(c, 41f, 820f, 1321f, 550f, T.R_TILE)
        step(c, 0, 866f, "Нажмите «Открыть настройки»",
            "Откроется экран оболочки в настройках Android", true)
        step(c, 1, 1046f, "Выберите «Разрешения» → «Память»",
            "Пункт может называться «Хранилище»", true)
        step(c, 2, 1226f, "Включите доступ и вернитесь назад",
            "Библиотека обновится сама", false)

        ink.convexDark(c, 41f, 1500f, 1321f, 142f, T.R_BUTTON)
        icons.draw(c, "settings@26.8x31.2", 106.6f, 1548.7f, 26.8f, 31.2f)
        ink.text(c, "Открыть настройки", 170f, T.base(1542.8f, T.TS_ACTION, 1.25f),
            T.TS_ACTION, 700, T.G15)

        ink.convex(c, 41f, 1715f, 1321f, 120f, 55f)
        ink.textCenter(c, "Позже", 44f, 1316f, T.base(1749.2f, T.TS_LABEL_L, 1.25f),
            T.TS_LABEL_L, 700, T.G1)
    }

    private fun step(c: Canvas, i: Int, y: Float, title: String, hint: String, divider: Boolean) {
        ink.inset(c, 88f, y, 100f, 100f, 50f, T.G12)
        ink.textCenter(c, "${i + 1}", 91f, 95f, T.base(y + 22.8f, T.TS_PAGE, 1.2f),
            T.TS_PAGE, 700, T.G1)
        ink.text(c, ink.ellipsize(title, 1060f, T.TS_ACTION, 700), 228f,
            T.base(y + 2f, T.TS_ACTION, 1.25f), T.TS_ACTION, 700, T.G1)
        ink.text(c, ink.ellipsize(hint, 1060f, T.TS_BODY_S, 600), 228f,
            T.base(y + 58f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        if (divider) ink.rect(c, 228f, y + 140f, 1088f, 2f, T.G11)
    }

    companion object {
        const val ACT_NONE = 0
        const val ACT_SETTINGS = 1
        const val ACT_LATER = 2

        /** Касание в B46: кнопки «Открыть настройки» и «Позже», мимо — ничего. */
        fun hit(x: Float, y: Float): Int = when {
            inRect(x, y, 41f, 1500f, 1321f, 142f) -> ACT_SETTINGS
            inRect(x, y, 41f, 1715f, 1321f, 120f) -> ACT_LATER
            else -> ACT_NONE
        }

        private fun inRect(x: Float, y: Float, rx: Float, ry: Float, rw: Float, rh: Float) =
            x >= rx && x <= rx + rw && y >= ry && y <= ry + rh

        /** Ширина экрана: макет 1404, у B46 тот же. */
        private const val W = 1404f
    }
}
