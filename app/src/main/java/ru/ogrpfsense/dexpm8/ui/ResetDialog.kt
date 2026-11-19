package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas

/**
 * B56 · Сброс настроек · подтверждение. Числа — design/v5/B56.json, слой «Диалог».
 *
 *   диалог       92,610 1225×662 (выпуклая, r 56)
 *   значок       гнездо 152,674 140×140 (дно G12) + «refresh@49.9x52»
 *   заголовок    332,680 60/700 lh 1.25 G1 — «Сбросить настройки?»
 *   подзаголовок 332,762 32/600 lh 1.25 G4 — «Оболочка 0.3.0»
 *   пояснение    152,880 1100 38/500 lh 1.4 G1, перенос по словам
 *   кнопки       «Отмена» — выпуклая 152,1071 425×112, подпись 38/700 G1 по центру 155,1101.3;
 *                «Сбросить» — тёмная 612,1056 645×142, подпись 42/700 G15 по центру 615,1098.7
 *
 * Показывается полосой «Сбросить настройки оболочки» на B43: до 2026-11-19 сброс шёл вторым
 * нажатием по полосе, без подтверждения (макет диалога был, но не рисовался).
 * Мимо диалога касание ничего не делает: закрыть его можно «Отменой» или «Назад» — на E-Ink
 * случайное касание не должно сбрасывать настройки.
 */
internal class ResetDialog(private val ink: Ink, private val icons: Icons) {

    /** Строка версии для подзаголовка: «Оболочка 0.3.0» (в макете — «Оболочка 0.1»). */
    private var version = ""

    fun open(version: String) {
        this.version = version
    }

    fun draw(c: Canvas) {
        ink.convex(c, DX, DY, DW, DH, R_DIALOG)
        ink.inset(c, 152f, 674f, 140f, 140f, 70f, T.G12)
        icons.drawInset(c, "refresh@49.9x52", 152f, 674f, 140f, 140f, 49.9f, 52f)

        ink.text(c, "Сбросить настройки?", 332f, T.base(680f, T.TS_TITLE, 1.25f),
            T.TS_TITLE, 700, T.G1)
        if (version.isNotEmpty()) {
            ink.text(c, ink.ellipsize(version, 940f, T.TS_BODY_S, 600), 332f,
                T.base(762f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        }
        // Межстрочный 1.4 от кегля 38 (макет) — в проекте это lineGap 6f, как в B20/B45/B57.
        ink.paragraph(c, NOTE, 152f, 880f, 1100f, T.TS_LABEL_L, 500, T.G1, 6f)

        ink.convex(c, 152f, 1071f, 425f, 112f, 51f)
        ink.textCenter(c, "Отмена", 155f, 420f, T.base(1101.3f, T.TS_LABEL_L, 1.25f),
            T.TS_LABEL_L, 700, T.G1)

        ink.convexDark(c, 612f, 1056f, 645f, 142f, T.R_BUTTON)
        ink.textCenter(c, "Сбросить", 615f, 640f, T.base(1098.7f, T.TS_ACTION, 1.25f),
            T.TS_ACTION, 700, T.G15)
    }

    /** Касание: [ACT_CANCEL] («Отмена»), [ACT_RESET] («Сбросить») или [ACT_NONE]. */
    fun hit(x: Float, y: Float): Int {
        if (inRect(x, y, 152f, 1071f, 425f, 112f)) return ACT_CANCEL
        if (inRect(x, y, 612f, 1056f, 645f, 142f)) return ACT_RESET
        return ACT_NONE
    }

    private fun inRect(x: Float, y: Float, rx: Float, ry: Float, rw: Float, rh: Float) =
        x >= rx && x <= rx + rw && y >= ry && y <= ry + rh

    companion object {
        const val ACT_NONE = 0
        const val ACT_CANCEL = 1
        const val ACT_RESET = 2

        private const val DX = 92f
        private const val DY = 610f
        private const val DW = 1225f
        private const val DH = 662f
        private const val R_DIALOG = 56f

        /** Пояснение из макета B56 дословно. */
        private const val NOTE = "Вернутся стандартные шрифт, поля, зоны касаний, кнопки и сон. " +
            "Книги, полки, заметки и прогресс чтения сохранятся."
    }
}
