package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas

/**
 * Диалоги поверх доски: B83 конец партии (и B83а — ничья) и B84 «Сдаться?». Числа —
 * design/v5/B83.json, B83а.json, B84.json. Под диалогом экран затемнён (`Screen.dimBegin`).
 *
 *   B83  диалог 92,520 1225×800 (r 56); гнездо 152,584 140×140 с королём 96×96 в (174, 607);
 *        заголовок 332,590 60/700; подзаголовок 332,672 32/600 G4; пояснение 152,790 1100 38/500;
 *        «Новая партия» тёмная 152,966 1100×142; «Смотреть ходы» 152,1138 и «В меню» 714,1138 538×112
 *   B84  диалог 92,560 1225×662; гнездо 152,624, король в (174, 647); заголовок 332,630;
 *        подзаголовок 332,712; пояснение 152,830; «Отмена» 152,1021 425×112; «Сдаться» тёмная
 *        612,1006 645×142
 *
 * Диалог B83 закрывается только своими кнопками (и «Назад» = «В меню»): партия кончилась,
 * случайное касание мимо не должно её стирать.
 */
internal class ChessDialogs(private val ink: Ink, private val icons: Icons) {

    fun drawEnd(c: Canvas, king: String, title: String, sub: String, note: String) {
        ink.convex(c, 92f, 520f, 1225f, 800f, 56f)
        nest(c, 584f, king, 607f)
        ink.text(c, title, 332f, T.base(590f, T.TS_TITLE, 1.25f), T.TS_TITLE, 700, T.G1)
        ink.text(c, ink.ellipsize(sub, 940f, T.TS_BODY_S, 600), 332f,
            T.base(672f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        ink.paragraph(c, note, 152f, 790f, 1100f, T.TS_LABEL_L, 500, T.G1, 6f)
        ink.convexDark(c, 152f, 966f, 1100f, 142f, T.R_BUTTON)
        ink.textCenter(c, "Новая партия", 155f, 1095f, T.base(1009f, T.TS_ACTION, 1.25f),
            T.TS_ACTION, 700, T.G15)
        ink.convex(c, 152f, 1138f, 538f, 112f, 51f)
        ink.textCenter(c, "Смотреть ходы", 155f, 533f, T.base(1168f, T.TS_LABEL_L, 1.25f),
            T.TS_LABEL_L, 700, T.G1)
        ink.convex(c, 714f, 1138f, 538f, 112f, 51f)
        ink.textCenter(c, "В меню", 717f, 533f, T.base(1168f, T.TS_LABEL_L, 1.25f),
            T.TS_LABEL_L, 700, T.G1)
    }

    /** [END_NEW], [END_MOVES], [END_MENU] или [ChessSheets.NONE]. */
    fun endHit(x: Float, y: Float): Int = when {
        ChessSheets.inRect(x, y, 152f, 966f, 1100f, 142f) -> END_NEW
        ChessSheets.inRect(x, y, 152f, 1138f, 538f, 112f) -> END_MOVES
        ChessSheets.inRect(x, y, 714f, 1138f, 538f, 112f) -> END_MENU
        else -> ChessSheets.NONE
    }

    fun drawResign(c: Canvas, king: String, sub: String) {
        ink.convex(c, 92f, 560f, 1225f, 662f, 56f)
        nest(c, 624f, king, 647f)
        ink.text(c, "Сдаться?", 332f, T.base(630f, T.TS_TITLE, 1.25f), T.TS_TITLE, 700, T.G1)
        ink.text(c, ink.ellipsize(sub, 940f, T.TS_BODY_S, 600), 332f,
            T.base(712f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        ink.paragraph(c, "Партия закончится вашим поражением.", 152f, 830f, 1100f,
            T.TS_LABEL_L, 500, T.G1, 6f)
        ink.convex(c, 152f, 1021f, 425f, 112f, 51f)
        ink.textCenter(c, "Отмена", 155f, 420f, T.base(1051f, T.TS_LABEL_L, 1.25f),
            T.TS_LABEL_L, 700, T.G1)
        ink.convexDark(c, 612f, 1006f, 645f, 142f, T.R_BUTTON)
        ink.textCenter(c, "Сдаться", 615f, 640f, T.base(1049f, T.TS_ACTION, 1.25f),
            T.TS_ACTION, 700, T.G15)
    }

    /** [RESIGN_OK], [ChessSheets.CANCEL] («Отмена» и мимо диалога) или [ChessSheets.NONE]. */
    fun resignHit(x: Float, y: Float): Int = when {
        ChessSheets.inRect(x, y, 612f, 1006f, 645f, 142f) -> RESIGN_OK
        ChessSheets.inRect(x, y, 152f, 1021f, 425f, 112f) -> ChessSheets.CANCEL
        y < 560f || y > 1222f || x < 92f || x > 1317f -> ChessSheets.CANCEL
        else -> ChessSheets.NONE
    }

    /** Гнездо значка 140×140 с королём 96×96; [boxY] — верх коробки иконки (гнездо + 23). */
    private fun nest(c: Canvas, y: Float, king: String, boxY: Float) {
        ink.inset(c, 152f, y, 140f, 140f, 70f, T.G12)
        icons.draw(c, king, 174f, boxY, 96f, 96f)
    }

    companion object {
        const val END_NEW = 10
        const val END_MOVES = 11
        const val END_MENU = 12
        const val RESIGN_OK = 20
    }
}
