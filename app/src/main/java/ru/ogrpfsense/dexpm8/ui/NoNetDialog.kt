package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas

/**
 * B57 · Нет сети · каталог. Числа — design/v5/B57.json. Диалог поверх затемнённого B16:
 * под ним последний удачный фид из кэша (`Opds.fetch` отдаёт его при `cached = true`),
 * поэтому скачанные книги видны и без сети — так и написано в пояснении.
 *
 *   диалог      92,610 1225×662 (выпуклая, r 56)
 *   значок      гнездо 152,674 140×140 (дно G12) + «wifi» 197.2,714.9 49.6×52
 *   заголовок   332,680 534×75 60/700 lh 1.25 G1
 *   подзаголовок 332,762 543×40 32/600 lh 1.25 G4 — «Каталог «…»»
 *   пояснение   152,880 1100 38/500 lh 1.4 G1, перенос по словам
 *   кнопки      «Повторить» — выпуклая 152,1071 425×112, подпись 38/700 G1 по центру 155,1101.3;
 *               «Настройки Wi-Fi» — тёмная 612,1056 645×142, подпись 42/700 G15 по центру 615,1098.7
 *
 * Мимо диалога касание ничего не делает: сети нет, закрыть его можно только выбором кнопки
 * («Повторить» — если сеть всё же появилась) или возвратом из настроек Wi-Fi.
 */
internal class NoNetDialog(private val ink: Ink, private val icons: Icons) {

    /** Название каталога для подзаголовка; пусто — подзаголовок не рисуется. */
    private var catalog = ""

    fun open(catalog: String) {
        this.catalog = catalog
    }

    fun draw(c: Canvas) {
        ink.convex(c, DX, DY, DW, DH, R_DIALOG)
        ink.inset(c, 152f, 674f, 140f, 140f, 70f, T.G12)
        icons.draw(c, "wifi@49.6x52", 197.2f, 714.9f, 49.6f, 52f)

        ink.text(c, "Нет подключения", 332f, T.base(680f, T.TS_TITLE, 1.25f),
            T.TS_TITLE, 700, T.G1)
        if (catalog.isNotEmpty()) {
            ink.text(c, ink.ellipsize("Каталог «" + catalog + "»", 543f, T.TS_BODY_S, 600),
                332f, T.base(762f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        }
        ink.paragraph(c, "Wi-Fi выключен или сеть недоступна. " +
            "Скачанные книги читаются и без сети.", 152f, 880f, 1100f, T.TS_LABEL_L, 500, T.G1, 1.4f)

        ink.convex(c, 152f, 1071f, 425f, 112f, 51f)
        ink.textCenter(c, "Повторить", 155f, 420f, T.base(1101.3f, T.TS_LABEL_L, 1.25f),
            T.TS_LABEL_L, 700, T.G1)

        ink.convexDark(c, 612f, 1056f, 645f, 142f, T.R_BUTTON)
        ink.textCenter(c, "Настройки Wi-Fi", 615f, 640f, T.base(1098.7f, T.TS_ACTION, 1.25f),
            T.TS_ACTION, 700, T.G15)
    }

    /** Касание: [ACT_RETRY] («Повторить»), [ACT_WIFI] («Настройки Wi-Fi») или [ACT_NONE]. */
    fun hit(x: Float, y: Float): Int {
        if (inRect(x, y, 152f, 1071f, 425f, 112f)) return ACT_RETRY
        if (inRect(x, y, 612f, 1056f, 645f, 142f)) return ACT_WIFI
        return ACT_NONE
    }

    private fun inRect(x: Float, y: Float, rx: Float, ry: Float, rw: Float, rh: Float) =
        x >= rx && x <= rx + rw && y >= ry && y <= ry + rh

    companion object {
        const val ACT_NONE = 0
        const val ACT_RETRY = 1
        const val ACT_WIFI = 2

        private const val DX = 92f
        private const val DY = 610f
        private const val DW = 1225f
        private const val DH = 662f
        private const val R_DIALOG = 56f
    }
}
