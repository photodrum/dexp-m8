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
        ink.rect(c, 0f, 0f, W, H, T.BG)

        // Шапка — как на B2: «Назад» 41.1,112.3 (круг 102.4), заголовок и подзаголовок с x 175.5.
        ink.convex(c, 41.1f, 112.3f, 106.9f, 112.4f, 51.2f)
        icons.draw(c, "back", 72.2f, 144.3f, 45.7f, 47.6f)

        ink.text(c, title, 175.5f, T.base(104f, T.TS_TITLE, 1.2f), T.TS_TITLE, 700, T.G1)
        ink.text(c, subtitle, 175.5f, T.base(180f, T.TS_BODY_S, 1.3f), T.TS_BODY_S, 600, T.G4)

        // Панель-«пластик» с иконкой раздела по центру
        val py = 516.4f
        ink.convex(c, 41.1f, py, 1320.8f, 1136.2f, T.R_TILE)
        ink.convex(c, 41f + 594f, py + 424f, 132f, 132f, 66f)
        icons.draw(c, icon, 41f + 594f + 29f, py + 424f + 28f, 73f, 76f)

        ink.textCenter(c, "экран не расписан · этап 1", 0f, W, py + 640f,
            T.TS_BODY_S, 600, T.G5)
    }

    override fun onUp(x: Float, y: Float) {
        if (x >= 41.1f && x <= 148f && y >= 112.3f && y <= 224.7f) act.pop()
    }
}
