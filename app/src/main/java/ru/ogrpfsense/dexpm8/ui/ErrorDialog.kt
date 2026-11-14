package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas

/**
 * B45 · Ошибка открытия файла. Числа — design/v5/B45.json. Диалог поверх затемнённого экрана
 * (замечание 15: на макете фон не изменён совсем, в коде — `Screen.dimBegin`).
 *
 *   диалог      92,470 1225×1030 (выпуклая, r 56)
 *   значок      гнездо 152,534 140×140 (дно G12) + «close» 212.5,591.5 25×27.5
 *   заголовок   332,540 60/700 lh 1.25; подзаголовок 332,622 32/600 lh 1.25 G4
 *   пояснение   152,740 1100 38/500 lh 1.4, перенос по словам
 *   подробности вдавленная 152,940 1100×150 (r 30): метка 192,970 28/700 G5,
 *               причина 192,1014 34/600
 *   кнопки      «Удалить файл» — выпуклая 152,1318 465×112, «trash» 205.6,1356.7 28.8×35.1,
 *               подпись 38/700 в 263,1348.3; «Понятно» — тёмная 652,1303 605×142, подпись 42/700
 *
 * На макете B45 есть строка «Открыть в другой программе» (152,1154.8) — **решение пользователя
 * 2026-11-14: убираем**. Она и не рисовалась (нужен был ACTION_VIEW через FileProvider), теперь
 * не рисуется намеренно: другой программы для книг на книге нет, а на B59 «страница повреждена»
 * это и вовсе тупик. Макет B45 поправить — строку и `next` убрать, кнопки сдвинуть вверх.
 * Кнопки пока стоят по макету.
 */
internal class ErrorDialog(private val ink: Ink, private val icons: Icons) {

    private var title = ""
    private var sub = ""
    private var note = ""
    private var label = ""
    private var code = ""
    private var canDelete = false

    fun open(title: String, sub: String, note: String, label: String, code: String,
             canDelete: Boolean) {
        this.title = title
        this.sub = sub
        this.note = note
        this.label = label
        this.code = code
        this.canDelete = canDelete
    }

    fun draw(c: Canvas) {
        ink.convex(c, DX, DY, DW, DH, R_DIALOG)
        ink.inset(c, 152f, 534f, 140f, 140f, 70f, T.G12)
        icons.draw(c, "close@25x27.5", 212.5f, 591.5f, 25f, 27.5f)

        ink.text(c, ink.ellipsize(title, 940f, T.TS_TITLE, 700), 332f,
            T.base(540f, T.TS_TITLE, 1.25f), T.TS_TITLE, 700, T.G1)
        ink.text(c, ink.ellipsize(sub, 940f, T.TS_BODY_S, 600), 332f,
            T.base(622f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        ink.paragraph(c, note, 152f, 740f, 1100f, T.TS_LABEL_L, 500, T.G1, 6f)

        ink.inset(c, 152f, 940f, 1100f, 150f, 30f, T.G12)
        ink.text(c, label, 192f, T.base(970f, T.TS_CAPTION, 1.25f), T.TS_CAPTION, 700, T.G5)
        ink.text(c, ink.ellipsize(code, 1020f, T.TS_BODY, 600), 192f,
            T.base(1014f, T.TS_BODY, 1.25f), T.TS_BODY, 600, T.G1)

        if (canDelete) {
            ink.convex(c, 152f, 1318f, 465f, 112f, 51f)
            icons.draw(c, "trash@28.8x35.1", 205.6f, 1356.7f, 28.8f, 35.1f)
            ink.text(c, "Удалить файл", 263f, T.base(1348.3f, T.TS_LABEL_L, 1.25f),
                T.TS_LABEL_L, 700, T.G1)
        }
        ink.convexDark(c, 652f, 1303f, 605f, 142f, T.R_BUTTON)
        ink.textCenter(c, "Понятно", 655f, 600f, T.base(1345.7f, T.TS_ACTION, 1.25f),
            T.TS_ACTION, 700, T.G15)
    }

    /** Касание: [ACT_NONE], [ACT_CLOSE] («Понятно», значок, мимо диалога) или [ACT_DELETE]. */
    fun hit(x: Float, y: Float): Int {
        if (canDelete && inRect(x, y, 152f, 1318f, 465f, 112f)) return ACT_DELETE
        if (inRect(x, y, 652f, 1303f, 605f, 142f)) return ACT_CLOSE
        if (inRect(x, y, 152f, 534f, 140f, 140f)) return ACT_CLOSE
        if (y < DY || y > DY + DH || x < DX || x > DX + DW) return ACT_CLOSE
        return ACT_NONE
    }

    private fun inRect(x: Float, y: Float, rx: Float, ry: Float, rw: Float, rh: Float) =
        x >= rx && x <= rx + rw && y >= ry && y <= ry + rh

    companion object {
        const val ACT_NONE = 0
        const val ACT_CLOSE = 1
        const val ACT_DELETE = 2

        private const val DX = 92f
        private const val DY = 470f
        private const val DW = 1225f
        private const val DH = 1030f
        private const val R_DIALOG = 56f
    }
}
