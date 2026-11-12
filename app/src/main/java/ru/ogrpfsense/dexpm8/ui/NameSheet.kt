package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas

/**
 * Нижний лист ввода названия полки: «Новая полка» (B48) и «Переименовать полку» (B54) — одна
 * вёрстка. Числа — из design/v5/B48.json и B54.json:
 *
 *   лист      выпуклый −3,880 1409×1132 (r 66); захват 644,905 117×11 G11
 *   заголовок 59,942 60/700 G1; метка «Название» 30/600 G5 в 59,1030
 *   поле      вдавленное 56,1077 1292×124 (r 48, дно G12); ввод 44/600 в 100,1111;
 *      курсор 4×56 в 1110.5, сразу за текстом
 *   подсказка 59,1230 30/600 G5; клавиатура — [Keyboard], верх 1410, тёмная клавиша — действие
 *
 * Состояние внутри: [text], [error] (вместо подсказки). Экран зовёт [open], рисует [draw]
 * и передаёт касания в [hit].
 */
class NameSheet(private val ink: Ink) {

    var text = ""
    private set

    /** Ошибка на месте подсказки («Такая полка уже есть»); сбрасывается при вводе. */
    var error = ""

    private var title = ""
    private var hint = ""
    private val kb = Keyboard(ink)

    fun open(title: String, action: String, initial: String, hint: String) {
        this.title = title
        this.hint = hint
        text = initial
        error = ""
        kb.setAction(action)
    }

    fun draw(c: Canvas) {
        ink.convex(c, -3f, TOP, 1409f, 1132f, 66f)
        ink.roundRect(c, 644f, 905f, 117f, 11f, 5f, T.G11)
        ink.text(c, title, 59f, T.base(942f, 60f, 1.25f), 60f, 700, T.G1)
        ink.text(c, "Название", 59f, T.base(1030f, 30f, 1.27f), 30f, 600, T.G5)
        ink.inset(c, 56f, 1077f, 1292f, 124f, 48f, T.G12)
        val base = T.base(1111f, 44f, 1.25f)
        if (text.isEmpty()) {
            ink.text(c, "Название полки", 100f, base, 44f, 500, T.G6)
            ink.rect(c, 100f, 1110.5f, 4f, 56f, T.G1)
        } else {
            val s = ink.ellipsize(text, 1150f, 44f, 600)
            ink.text(c, s, 100f, base, 44f, 600, T.G1)
            ink.rect(c, 106f + ink.measure(s, 44f, 600), 1110.5f, 4f, 56f, T.G1)
        }
        if (error.isNotEmpty()) {
            ink.text(c, error, 59f, T.base(1230f, 30f, 1.27f), 30f, 700, T.G1)
        } else {
            ink.text(c, hint, 59f, T.base(1230f, 30f, 1.27f), 30f, 600, T.G5)
        }
        kb.draw(c)
    }

    /** Касание: [ACT_NONE], [ACT_CHANGED] (перерисовать), [ACT_CANCEL] (мимо листа) или [ACT_OK]. */
    fun hit(x: Float, y: Float): Int {
        if (y < TOP) return ACT_CANCEL
        if (y < Keyboard.TOP - 5f) return ACT_NONE
        val k = kb.hit(x, y) ?: return ACT_NONE
        when (k.kind) {
            Keyboard.K_DONE -> return ACT_OK
            Keyboard.K_DEL -> {
                if (text.isEmpty()) return ACT_NONE
                text = text.dropLast(1)
            }
            else -> {
                val s = kb.press(k)
                if (s != null && text.length < MAX) text += s
            }
        }
        error = ""
        return ACT_CHANGED
    }

    companion object {
        const val ACT_NONE = 0
        const val ACT_CHANGED = 1
        const val ACT_CANCEL = 2
        const val ACT_OK = 3

        const val MAX = 40
        private const val TOP = 880f
    }
}
