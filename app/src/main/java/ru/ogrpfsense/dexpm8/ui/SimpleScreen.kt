package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas

/**
 * Заглушка раздела: шапка с кнопкой «Назад» + название + иконка.
 * Нужна, чтобы каркас навигации был проверяем на устройстве целиком,
 * пока разделы не расписаны по бордам.
 */
class SimpleScreen(
    act: EinkActivity,
    private val title: String,
    private val subtitle: String,
    private val icon: String
) : Screen(act) {

    override fun render(c: Canvas) {
        ink.rect(c, 0f, 0f, W, H, T.G13)

        ink.convex(c, 41f, 107f, 107f, 112f, 33f)
        icons.draw(c, "back", 41f + 30f, 107f + 32f, 46f, 48f)

        ink.text(c, title, 176f, 104f + 56f, T.TS_TITLE, 700, T.G1)
        ink.text(c, subtitle, 176f, 180f + 32f, T.TS_BODY_S, 600, T.G4)

        // Панель-«пластик» с иконкой раздела по центру
        val py = 516f
        ink.convex(c, 41f, py, 1321f, 1136f, T.R_TILE)
        ink.convex(c, 41f + 594f, py + 424f, 132f, 132f, 66f)
        icons.draw(c, icon, 41f + 594f + 29f, py + 424f + 28f, 73f, 76f)

        ink.textCenter(c, "экран не расписан · этап 1", 0f, W, py + 640f,
            T.TS_BODY_S, 600, T.G5)
    }

    override fun onUp(x: Float, y: Float) {
        if (x >= 41f && x <= 148f && y >= 107f && y <= 219f) act.pop()
    }
}
