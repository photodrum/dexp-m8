package ru.ogrpfsense.dexpm8.reader

import android.graphics.Canvas
import ru.ogrpfsense.dexpm8.ui.Ink
import ru.ogrpfsense.dexpm8.ui.Icons
import ru.ogrpfsense.dexpm8.ui.T

/**
 * B58 · Открытие книги. Числа — `design/v5/B58.json`.
 *
 *   диалог      92,610 1225×728 (выпуклая, r 56)
 *   значок      гнездо 152,674 140×140 (дно G12) + «library» 197,718 49.9×52
 *   заголовок   «Открываем книгу» 332,680 Manrope 60/700 G1
 *   подзаголовок «книга · автор · формат» 332,762 Manrope 32/600 G4
 *   пояснение   152,880 ширина 1100, Manrope 38/500 lh 1.4 G1
 *   процент     «64%» 1165,990.5 Manrope 40/700 G1 (справа над шкалой)
 *   шкала       вдавленная 152,1030 930×37 (дно G12 r 15), заполнение G3 155,1035 r 14
 *   кнопка      «Отмена» выпуклая 152,1152 425×112 (r 51), подпись 38/700 по центру
 *
 * Экран показывается поверх страницы, когда первое открытие (загрузка + разметка)
 * идёт дольше [SHOW_AFTER_MS] — до этого страница просто пустая, чтобы быстрое
 * открытие не мигало диалогом. Процент — настоящий, из движка (`Engine.onOpenProgress`).
 *
 * Пояснение в макете начинается с числа страниц («Большая книга — 1 274 страницы»),
 * а до разметки число страниц неизвестно. Поэтому фраза начинается со слов «В первый
 * раз…» — вопрос записан в `docs/DESIGN_QUESTIONS.md`.
 */
internal class OpenBookDialog(private val ink: Ink, private val icons: Icons) {

    private var sub = ""
    private var pct = 0

    /** Начать показ: [sub] — «книга · автор · формат», [pct] — процент на сейчас. */
    fun open(sub: String, pct: Int) {
        this.sub = sub
        this.pct = pct.coerceIn(0, 100)
    }

    /** Новый процент от движка; true — если кадр нужно перерисовать. */
    fun progress(p: Int): Boolean {
        val v = p.coerceIn(0, 100)
        if (v == pct) return false
        pct = v
        return true
    }

    fun draw(c: Canvas) {
        ink.convex(c, DX, DY, DW, DH, R_DIALOG)
        ink.inset(c, 152f, 674f, 140f, 140f, 70f, T.G12)
        icons.draw(c, "library", 197f, 718f, 49.9f, 52f)

        ink.text(c, TITLE, 332f, T.base(680f, T.TS_TITLE, 1.25f), T.TS_TITLE, 700, T.G1)
        if (sub.isNotEmpty()) {
            val s = ink.ellipsize(sub, 855f, T.TS_BODY_S, 600)
            ink.text(c, s, 332f, T.base(762f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        }
        ink.paragraph(c, NOTE, 152f, 880f, 1100f, T.TS_LABEL_L, 500, T.G1, 6f)

        // Шкала: то же, что прогресс книги на B3, но одна — подготовка.
        ink.inset(c, 152f, 1030f, 930f, 37f, 18f, T.G12)
        if (pct > 0) {
            ink.roundRect(c, 155f, 1035f, TRACK_INNER_W * pct / 100f, 28f, 14f, T.G3)
        }
        ink.text(c, "$pct%", 1165f, T.base(990.5f, 40f, 1.25f), 40f, 700, T.G1)

        ink.convex(c, 152f, 1152f, 425f, 112f, R_BUTTON)
        ink.textCenter(c, CANCEL, 155f, 420f,
            T.base(1182.3f, T.TS_LABEL_L, 1.25f), T.TS_LABEL_L, 700, T.G1)
    }

    /** Нажата ли кнопка «Отмена»; остальное касание не нажимается. */
    fun hitCancel(x: Float, y: Float): Boolean =
        x >= 152f && x <= 577f && y >= 1152f && y <= 1264f

    companion object {
        /** Порог показа: короче — страница остаётся пустой, диалог не мигает. */
        const val SHOW_AFTER_MS = 2000L

        private const val TITLE = "Открываем книгу"
        private const val CANCEL = "Отмена"
        private const val NOTE =
            "В первый раз разметка книги занимает до минуты, дальше книга открывается сразу."

        private const val DX = 92f
        private const val DY = 610f
        private const val DW = 1225f
        private const val DH = 728f
        private const val R_DIALOG = 56f
        private const val R_BUTTON = 51f

        /** Дно вдавленной шкалы: 930 − 5.5 (INSET_W), как в B58.json (925). */
        private const val TRACK_INNER_W = 924.5f
    }
}
