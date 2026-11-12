package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas

/**
 * B44 · Пустые состояния. Числа — design/v5/B44.json. Один рисунок на все пустые списки:
 * библиотека целиком ([library]) и карточка раздела ([card]).
 *
 *   значок      гнездо 522,300 360×360 (r 180, дно G11) + «library» 648.3,426.7 113.3×113.4
 *   заголовок   0,710 1404×72 60/700 lh 1.2 по центру
 *   пояснение   152,800 1100 38/500 lh 1.4 по центру, перенос по словам
 *   кнопки      тёмная 202,960 1005×142: «wifi» 267.3,1017.2 27.5×23.6, подпись 42/700 G15 в 331,1002.8;
 *               выпуклая 202,1130 1005×120: «download» 254.7,1172.7 30.7×35.5, подпись 38/700 G1 в 313,1164.3
 *   карточка    425×410 (выпуклая, r 43.875): гнездо +153,+40 120×120 (дно G12), иконка по центру гнезда,
 *               заголовок 36/700 lh 1.25 по центру x+3 w 420, верх +190;
 *               пояснение 30/600 lh 1.3 по центру x+33 w 360, верх +246
 *
 * [library] рисует значок, заголовок и пояснение: в макете это состояние пустой библиотеки,
 * тексты остальных состояний («Идёт поиск книг…», пусто после фильтра) подставляются.
 */
internal class EmptyState(private val ink: Ink, private val icons: Icons) {

    /**
     * Пустая библиотека B44. [buttons] — кнопки «Загрузить по Wi-Fi» и «Сетевые каталоги»:
     * в макете они есть, работа появится в 9.7 и п. 10, до тех пор не рисуются (место под них
     * оставлено, чтобы блок не прыгал, когда их включат).
     *
     * Отступление от макета: на B44 между шапкой и значком нет ни поиска, ни полосы сортировки,
     * а в библиотеке они занимают 248–475. Поэтому весь блок сдвинут вниз на [DY] — в панель
     * списка (41.1,516.4 1320.8×1136.2), по её центру с запасом под кнопки.
     */
    fun library(c: Canvas, title: String, hint: String, buttons: Boolean) {
        ink.inset(c, NEST_X, NEST_Y + DY, NEST, NEST, NEST / 2f)
        icons.draw(c, "library@113.3x113.4", ICON_X, ICON_Y + DY, ICON_W, ICON_H)

        ink.textCenter(c, title, 0f, W, T.base(710f + DY, T.TS_TITLE, 1.2f), T.TS_TITLE, 700, T.G1)
        ink.paragraphCenter(c, hint, 152f, 800f + DY, 1100f, T.TS_LABEL_L, 500, T.G1, 1.4f)

        if (!buttons) return
        ink.convexDark(c, 202f, 960f + DY, 1005f, 142f, T.R_BUTTON)
        icons.draw(c, "wifi@27.5x23.6", 267.3f, 1017.2f + DY, 27.5f, 23.6f)
        ink.text(c, "Загрузить по Wi-Fi", 331f, T.base(1002.8f + DY, T.TS_ACTION, 1.25f),
            T.TS_ACTION, 700, T.G15)

        ink.convex(c, 202f, 1130f + DY, 1005f, 120f, 55f)
        icons.draw(c, "download@30.7x35.5", 254.7f, 1172.7f + DY, 30.7f, 35.5f)
        ink.text(c, "Сетевые каталоги", 313f, T.base(1164.3f + DY, T.TS_LABEL_L, 1.25f),
            T.TS_LABEL_L, 700, T.G1)
    }

    /**
     * Касание кнопок пустой библиотеки ([library] с `buttons = true`):
     * [ACT_WIFI] — «Загрузить по Wi-Fi» (B26), [ACT_CATALOG] — «Сетевые каталоги» (п. 10),
     * иначе [ACT_NONE]. Зоны — рамки кнопок из B44 со сдвигом [DY].
     */
    fun libraryHit(x: Float, y: Float): Int {
        if (x >= 202f && x <= 202f + 1005f) {
            if (y >= 960f + DY && y <= 960f + DY + 142f) return ACT_WIFI
            if (y >= 1130f + DY && y <= 1130f + DY + 120f) return ACT_CATALOG
        }
        return ACT_NONE
    }

    /** Карточка пустого раздела B44 («Пустое / Ничего не найдено» и др.). */
    fun card(c: Canvas, x: Float, y: Float, icon: String, iw: Float, ih: Float,
             title: String, hint: String) {
        ink.convex(c, x, y, CARD_W, CARD_H, R_CARD)
        ink.inset(c, x + NEST_DX, y + NEST_DY, CARD_NEST, CARD_NEST, CARD_NEST / 2f, T.G12)
        icons.draw(c, icon, x + NEST_DX + (CARD_NEST - iw) / 2f, y + NEST_DY + (CARD_NEST - ih) / 2f,
            iw, ih)
        ink.textCenter(c, title, x + 3f, 420f, T.base(y + 190f, T.TS_LABEL, 1.25f),
            T.TS_LABEL, 700, T.G1)
        ink.paragraphCenter(c, hint, x + 33f, y + 246f, 360f, T.TS_META, 600, T.G4, 1.3f)
    }

    companion object {
        const val CARD_W = 425f
        const val CARD_H = 410f

        /** Ответы [libraryHit]. */
        const val ACT_NONE = 0
        const val ACT_WIFI = 1
        const val ACT_CATALOG = 2

        /** Иконки карточек B44: имя спеки и размер рамки в макете. */
        const val ICON_SEARCH = "search@36.2x38.4"
        const val ICON_NOTE = "note"
        const val ICON_DOWNLOAD = "download@34.7x40.1"

        private const val W = 1404f
        /**
         * Сдвиг блока B44 вниз в панель списка: на макете между шапкой и значком нет ни поиска,
         * ни полосы сортировки (они занимают 248–475). Блок 300…1250 (со кнопками) = 950,
         * панель 516.4…1652.6 = 1136, запас 186 — по 93 сверху и снизу.
         */
        private const val DY = 309f
        private const val NEST_X = 522f
        private const val NEST_Y = 300f
        private const val NEST = 360f
        private const val ICON_X = 648.3f
        private const val ICON_Y = 426.7f
        private const val ICON_W = 113.3f
        private const val ICON_H = 113.4f

        private const val R_CARD = 43.875f
        private const val CARD_NEST = 120f
        private const val NEST_DX = 153f
        private const val NEST_DY = 40f
    }
}
