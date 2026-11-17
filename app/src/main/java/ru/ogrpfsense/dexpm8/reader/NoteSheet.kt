package ru.ogrpfsense.dexpm8.reader

import android.graphics.Canvas
import ru.ogrpfsense.dexpm8.ui.Ink
import ru.ogrpfsense.dexpm8.ui.Keyboard
import ru.ogrpfsense.dexpm8.ui.T

/**
 * B51 · «Новая заметка» — нижний лист с цитатой и клавиатурой (R4.3). Числа —
 * `design/v5/B51.json`:
 *
 *   лист      выпуклый −3,820 1409×1192 r 66 (тень G10 @ +5,+10, блик G15, поверхность G14)
 *   захват    644,845 117×11 G11 r 5
 *   заголовок «Заметка» 59,882 60/700 G1; место «Часть 1 · Глава I · стр. 124» 59,970 30/600 G5
 *   цитата    черта G3 6×108 в 59,1030; текст Literata 34/400 G4 в 90,1025 шириной 1250,
 *             межстрочный 1.5 — как в макете, две строки по 51
 *   поле      вдавленное 56,1172 1292×170 r 48; ввод Manrope 38/600 G1 в 96,1204;
 *             курсор 4×50 G1 за последним символом
 *   клавиатура [Keyboard] с 1410 (общая с B28 и B48), тёмная клавиша — «Сохранить»
 *
 * Страница под листом приглушена: в макете её текст G8, а колонтитул G9 (не G10, как под B29
 * и B4), — [ReaderScreen] берёт для этого отдельную кисть.
 *
 * Набираемый текст живёт здесь, как ввод B29 и раскладка `Keyboard`: экран спрашивает
 * [note] при сохранении. Выделенный текст и подпись места лист не хранит — их передают
 * в [draw] при каждом кадре.
 */
class NoteSheet(private val ink: Ink) {

    private val kb = Keyboard(ink)

    /** Набранный текст заметки; пусто — сохранять нечего. */
    private var input = ""

    /** Открыть лист на пустом поле: каждая заметка начинается с чистого листа. */
    fun open() {
        input = ""
        kb.setAction(ACTION_SAVE)
    }

    /** Текст заметки как набран (без обрезки) — его пишет `LibraryDb.addNote`. */
    fun note(): String = input.trim()

    /**
     * Нарисовать лист. [place] — «Часть 1 · Глава I · стр. 124», [quote] — выделенный текст
     * (в макете две строки: больше не влезает между подписью места и полем ввода).
     */
    fun draw(c: Canvas, place: String, quote: String) {
        ink.convex(c, SHEET_X, SHEET_Y, SHEET_W, SHEET_H, R_SHEET)
        ink.roundRect(c, GRIP_X, GRIP_Y, GRIP_W, GRIP_H, R_GRIP, T.G11)
        ink.text(c, TITLE, TITLE_X, T.base(TITLE_Y, T.TS_TITLE, 1.25f), T.TS_TITLE, 700, T.G1)
        ink.text(c, ink.ellipsize(place, PLACE_W, T.TS_META, 600), TITLE_X,
            T.base(PLACE_Y, T.TS_META, 1.25f), T.TS_META, 600, T.G5)
        quote(c, quote)
        field(c)
        kb.draw(c)
    }

    /**
     * Цитата: черта G3 во всю высоту текста и до двух строк Literata 34/400 G4.
     * Межстрочный 1.5 задаётся шагом ([QUOTE_STEP]), а не `Ink.paragraph`: у него `lineGap`
     * добавляется к высоте шрифта, и в 51 из макета так не попасть.
     */
    private fun quote(c: Canvas, text: String) {
        val font = T.literata(ink.res, 400)
        val lines = if (text.isBlank()) ArrayList<String>(1)
                    else ink.ellipsize2(text, QUOTE_W, QUOTE_SIZE, 400, font, 2)
        var y = T.base(QUOTE_Y, QUOTE_SIZE, QUOTE_LH, literata = true)
        for (s in lines) {
            ink.text(c, s, QUOTE_X, y, QUOTE_SIZE, 400, T.G4, font)
            y += QUOTE_STEP
        }
        // Черта — по числу строк: одна строка 51 + 6, две — 108, как в макете.
        if (lines.isNotEmpty()) {
            ink.rect(c, QUOTE_BAR_X, QUOTE_BAR_Y, QUOTE_BAR_W,
                QUOTE_STEP * lines.size + QUOTE_BAR_PAD, T.G3)
        }
    }

    /** Поле ввода: вдавленный желоб, текст и курсор за последним символом. */
    private fun field(c: Canvas) {
        // Дно углубления — G12: поле лежит на поверхности листа G14 (B51.json, «Поле / Заметка»
        // 56,1172: блик G15, внутренняя тень G10, углубление G12 r 45).
        ink.inset(c, FIELD_X, FIELD_Y, FIELD_W, FIELD_H, R_FIELD, T.G12)
        val base = T.base(INPUT_Y, INPUT_SIZE, 1.25f)
        if (input.isEmpty()) {
            ink.text(c, HINT, INPUT_X, base, INPUT_SIZE, 600, T.G6)
            ink.rect(c, INPUT_X, CURSOR_Y, CURSOR_W, CURSOR_H, T.G1)
            return
        }
        val s = ink.ellipsize(input, INPUT_W, INPUT_SIZE, 600)
        ink.text(c, s, INPUT_X, base, INPUT_SIZE, 600, T.G1)
        ink.rect(c, INPUT_X + ink.measure(s, INPUT_SIZE, 600), CURSOR_Y,
            CURSOR_W, CURSOR_H, T.G1)
    }

    /**
     * Касание листа. `ACT_*` или [ACT_NONE]; [ACT_SAVE] — нажата тёмная клавиша.
     * Выше листа — [ACT_CLOSE]: касание уходит на страницу, как у B29.
     */
    fun hit(x: Float, y: Float): Int {
        if (y < SHEET_Y) return ACT_CLOSE
        if (y < Keyboard.TOP) return ACT_NONE     // шапка листа, цитата и поле — не кнопки
        val k = kb.hit(x, y) ?: return ACT_NONE
        when (k.kind) {
            Keyboard.K_DONE -> return if (note().isEmpty()) ACT_NONE else ACT_SAVE
            Keyboard.K_DEL -> if (input.isNotEmpty()) input = input.substring(0, input.length - 1)
            else -> {
                val s = kb.press(k)
                if (s != null && input.length < MAX_NOTE) input += s
            }
        }
        return ACT_CHANGED
    }

    companion object {
        const val ACT_NONE = 0
        /** Касание мимо листа (выше него) — экран закрывает заметку. */
        const val ACT_CLOSE = 1
        /** Текст заметки изменился — перерисовать лист. */
        const val ACT_CHANGED = 2
        /** Нажато «Сохранить» — экран пишет заметку в базу. */
        const val ACT_SAVE = 3

        private const val TITLE = "Заметка"
        private const val HINT = "Текст заметки"
        private const val ACTION_SAVE = "Сохранить"
        /** Сколько символов принимает поле: заметка — абзац-другой, а не страница текста. */
        private const val MAX_NOTE = 500

        private const val SHEET_X = -3f
        private const val SHEET_Y = 820f
        private const val SHEET_W = 1409f
        private const val SHEET_H = 1192f
        private const val R_SHEET = 66f
        private const val GRIP_X = 644f
        private const val GRIP_Y = 845f
        private const val GRIP_W = 117f
        private const val GRIP_H = 11f
        private const val R_GRIP = 5f

        private const val TITLE_X = 59f
        private const val TITLE_Y = 882f
        private const val PLACE_Y = 970f
        /** Ширина подписи места — 355 из макета: дальше идёт цитата, подпись обрезается. */
        private const val PLACE_W = 355f

        private const val QUOTE_BAR_X = 59f
        private const val QUOTE_BAR_Y = 1030f
        private const val QUOTE_BAR_W = 6f
        /** Черта длиннее текста на 6: в макете 108 при двух строках текста по 51. */
        private const val QUOTE_BAR_PAD = 6f
        private const val QUOTE_X = 90f
        private const val QUOTE_Y = 1025f
        private const val QUOTE_W = 1250f
        /** Literata 34/400, межстрочный 1.5 — из слоя «Цитата» B51.json. */
        private const val QUOTE_SIZE = 34f
        private const val QUOTE_LH = 1.5f
        private const val QUOTE_STEP = QUOTE_SIZE * QUOTE_LH

        private const val FIELD_X = 56f
        private const val FIELD_Y = 1172f
        private const val FIELD_W = 1292f
        private const val FIELD_H = 170f
        private const val R_FIELD = 48f
        private const val INPUT_X = 96f
        private const val INPUT_Y = 1204f
        private const val INPUT_SIZE = 38f
        /** Ширина текста в поле: 1292 минус отступы 40 слева и 40 справа. */
        private const val INPUT_W = 1210f
        private const val CURSOR_Y = 1205f
        private const val CURSOR_W = 4f
        private const val CURSOR_H = 50f
    }
}
