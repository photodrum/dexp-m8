package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import ru.ogrpfsense.dexpm8.chess.Color
import ru.ogrpfsense.dexpm8.chess.PieceType

/**
 * Диалоги игры по сети (п. 25.3б): B85а «Ищем соперника», B87 «Нет подключения», B87а «Соперник
 * отключился», B87б «Ничья?». Числа — design/v5/B85а.json, B87.json, B87а.json, B87б.json.
 * Под диалогом экран затемнён (`Screen.dimBegin`).
 *
 *   B85а  диалог 92,560 1225×662 (r 56); гнездо 152,624 140×140, «clock» 195.5,667.9 53×55.2;
 *         заголовок 332,630 60/700; подзаголовок 332,712 32/600 G4; счётчик 152,810 1100×130
 *         108/200 по центру; пояснение 152,950 32/600 G4 по центру; «Отмена» 152,1046 1100×112
 *   B87*  диалог 92,610 1225×662; гнездо 152,674; заголовок 332,680; подзаголовок 332,762;
 *         пояснение 152,880 1100 38/500; левая кнопка 152,1071 425×112; правая тёмная 612,1056
 *         645×142. Значок: «wifi» 197.2,719.5 49.6×52 (B87, B87а) или король 96×96 в (174, 697)
 *
 * Отступление от макета B87а: «Ждать» нет — гостю FICS сервер не даёт вернуться в партию
 * (проверено 2026-11-26: «forfeits by disconnection» сразу), вместо неё — «Новая партия».
 */
internal class ChessNetDialogs(private val ink: Ink, private val icons: Icons) {

    fun drawSeek(c: Canvas, control: String, counter: String) {
        ink.convex(c, 92f, 560f, 1225f, 662f, 56f)
        nest(c, 624f)
        icons.draw(c, "clock", 195.5f, 667.9f, 53f, 55.2f)
        ink.text(c, "Ищем соперника…", 332f, T.base(630f, T.TS_TITLE, 1.25f), T.TS_TITLE, 700, T.G1)
        ink.text(c, "$control · FICS, гостем", 332f, T.base(712f, T.TS_BODY_S, 1.25f),
            T.TS_BODY_S, 600, T.G4)
        ink.textCenter(c, counter, 152f, 1100f, T.base(810f, 108f, 1.2f), 108f, 200, T.G1)
        ink.textCenter(c, "Ждём человека с тем же контролем времени", 152f, 1100f,
            T.base(950f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        ink.convex(c, 152f, 1046f, 1100f, 112f, 51f)
        ink.textCenter(c, "Отмена", 155f, 1095f, T.base(1076f, T.TS_LABEL_L, 1.25f),
            T.TS_LABEL_L, 700, T.G1)
    }

    /** «Отмена» на B85а. */
    fun seekCancelHit(x: Float, y: Float): Boolean =
        ChessSheets.inRect(x, y, 152f, 1046f, 1100f, 112f)

    /** B87: [note] — пояснение под заголовком. */
    fun drawOffline(c: Canvas, note: String) {
        frame(c, "Нет подключения", "Игра по сети · FICS", note, "Повторить", "Настройки Wi-Fi")
        icons.draw(c, "wifi@49.6x52", 197.2f, 719.5f, 49.6f, 52f)
    }

    /** B87а: [sub] — «GuestQRTZ · ход 13». */
    fun drawGone(c: Canvas, sub: String, note: String) {
        frame(c, "Соперник отключился", sub, note, "В меню", "Новая партия")
        icons.draw(c, "wifi@49.6x52", 197.2f, 719.5f, 49.6f, 52f)
    }

    /** B87б: [sub] — «GuestQRTZ предлагает ничью · ход 13». */
    fun drawDraw(c: Canvas, sub: String) {
        frame(c, "Ничья?", sub, "Если отклонить, партия продолжится. Часы идут.", "Отклонить", "Принять")
        icons.draw(c, ChessBoard.icon(Color.BLACK, PieceType.KING), 174f, 697f, 96f, 96f)
    }

    /** Кнопки B87, B87а, B87б: [LEFT] — светлая, [RIGHT] — тёмная; мимо — [NONE]. */
    fun hit(x: Float, y: Float): Int = when {
        ChessSheets.inRect(x, y, 152f, 1071f, 425f, 112f) -> LEFT
        ChessSheets.inRect(x, y, 612f, 1056f, 645f, 142f) -> RIGHT
        else -> NONE
    }

    private fun frame(c: Canvas, title: String, sub: String, note: String, left: String, right: String) {
        ink.convex(c, 92f, 610f, 1225f, 662f, 56f)
        nest(c, 674f)
        ink.text(c, title, 332f, T.base(680f, T.TS_TITLE, 1.25f), T.TS_TITLE, 700, T.G1)
        ink.text(c, ink.ellipsize(sub, 940f, T.TS_BODY_S, 600), 332f,
            T.base(762f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        ink.paragraph(c, note, 152f, 880f, 1100f, T.TS_LABEL_L, 500, T.G1, 6f)
        ink.convex(c, 152f, 1071f, 425f, 112f, 51f)
        ink.textCenter(c, left, 155f, 420f, T.base(1101f, T.TS_LABEL_L, 1.25f),
            T.TS_LABEL_L, 700, T.G1)
        ink.convexDark(c, 612f, 1056f, 645f, 142f, T.R_BUTTON)
        ink.textCenter(c, right, 615f, 640f, T.base(1099f, T.TS_ACTION, 1.25f),
            T.TS_ACTION, 700, T.G15)
    }

    /** Гнездо значка 140×140 (дно G12) на высоте [y]. */
    private fun nest(c: Canvas, y: Float) {
        ink.inset(c, 152f, y, 140f, 140f, 70f, T.G12)
    }

    companion object {
        const val NONE = 0
        const val LEFT = 1
        const val RIGHT = 2

        /** Рамка счётчика «0:42» на B85а — только её перерисовывает секундный тик. */
        const val COUNTER_X = 152f
        const val COUNTER_Y = 810f
        const val COUNTER_W = 1100f
        const val COUNTER_H = 130f
    }
}
