package ru.ogrpfsense.dexpm8.reader

import android.graphics.Canvas
import ru.ogrpfsense.dexpm8.ui.Ink
import ru.ogrpfsense.dexpm8.ui.Icons
import ru.ogrpfsense.dexpm8.ui.T

/**
 * B30 · «Словарь и сноски» — нижний лист поверх страницы (R5). Числа — `design/v5/B30.json`:
 *
 *   лист      выпуклый −3,980 1409×1022 r 66 (тень G10 @ +4.6,+10.1, блик G15, поверхность G14)
 *   захват    644,1005 117×11 G11 r 5
 *   сегменты  желоб 56,1040 820×88 r 44 (вдавленный, дно G12); активный — выпуклая плашка
 *             60,1044 270.7×83 r 36.5; подписи 32/600 по центру своих полос, шаг 270.7:
 *             «Словарь» (60), «Сноска» (330.7), «Перевод» (601)
 *   закрыть   круглая клавиша 1250,1033 97×102 + `close@16.7x18.3`
 *   слово     59,1180 Literata 66/600 G1
 *   грамматика 59,1270 Manrope 32/600 G4
 *   определение 59,1340 Manrope 40/400 G1, шаг строки 64 (межстрочный 1.6), три строки
 *   источник  59,1555 Manrope 28/600 G5
 *   действия  четыре круглые клавиши 115×120 на 1620 (121 / 471 / 821 / 1171) с подписями
 *             32/600 на 1752: «Выделить», «Заметка», «Копировать», «Найти в книге»
 *
 * Лист показывает слово под пальцем; текст статьи приходит от [ru.ogrpfsense.dexpm8.core.DictOnline]
 * уже разобранным — здесь только раскладка. Пока ответа нет, на месте определения стоит
 * статичная строка [WAIT]: анимаций на E-Ink нет, поэтому ожидание — это кадр, а не прогресс.
 *
 * Рисование и касания в одном классе: геометрия у них общая (READER_SPEC, «одна геометрия
 * для рисования и касаний»). Страница под листом не приглушается — в макете её текст G1,
 * а слово подчёркнуто вдавленной плашкой (её рисует [ReaderScreen.drawSelectionUnder]).
 */
class DictSheet(private val ink: Ink, private val icons: Icons) {

    /** Слово под пальцем (заголовок листа): одинаково для всех вкладок. */
    private var word = ""
    /** Вкладка «Словарь»: грамматика, определение, источник, ожидание ответа сети. */
    private var dictGrammar = ""
    private var dictBody = ""
    private var dictSource = ""
    private var dictWaiting = false
    /** Вкладка «Перевод»: направление, перевод, источник, ожидание. */
    private var trGrammar = ""
    private var trBody = ""
    private var trSource = ""
    private var trWaiting = false
    /** Вкладка «Сноска»: текст сноски из книги, ожидания сети нет — только движок. */
    private var noteBody = ""
    private var noteWaiting = false
    /** Открытая вкладка: [TAB_DICT], [TAB_NOTE], [TAB_TRANSLATE]. */
    private var tab = TAB_DICT

    /** Открыть лист на вкладке «Словарь»: сразу ожидание ответа по слову [w]. */
    fun open(w: String) {
        word = w
        dictGrammar = ""
        dictBody = ""
        dictSource = ""
        dictWaiting = true
        trGrammar = ""
        trBody = ""
        trSource = ""
        trWaiting = false
        noteBody = ""
        noteWaiting = false
        tab = TAB_DICT
    }

    /**
     * Ответ словаря пришёл: [w] — лемма (может отличаться от слова под пальцем — начальная
     * форма), [g] — грамматика, [d] — определение, [src] — подпись источника.
     * `d` = null — статьи нет, вместо определения [why].
     */
    fun show(w: String, g: String, d: String?, src: String, why: String?) {
        word = w
        dictGrammar = g
        dictBody = d ?: (why ?: NOT_FOUND)
        dictSource = if (d == null) SOURCE_NONE else src
        dictWaiting = false
    }

    /** Перевод пришёл на вкладку «Перевод»: [dir] — «ru → en», [t] — текст, [src] — источник. */
    fun showTranslate(dir: String, t: String?, why: String?) {
        trGrammar = dir
        trBody = t ?: (why ?: NOT_FOUND_TR)
        trSource = SOURCE_TR
        trWaiting = false
    }

    /** Вкладка «Перевод» открыта и ждёт ответа. */
    fun waitTranslate(dir: String) {
        trGrammar = dir
        trBody = ""
        trSource = ""
        trWaiting = true
        tab = TAB_TRANSLATE
    }

    /**
     * Вкладка «Сноска» открыта: пока движок читает текст, лист показывает [WAIT_NOTE].
     * Текст уходит в лист через [showNote].
     */
    fun waitNote() {
        noteBody = ""
        noteWaiting = true
    }

    /**
     * Вкладка «Сноска»: [href] — якорь под словом (`null` — слово не ссылка),
     * [text] — текст сноски из книги (`null` — якоря в книге нет или он пуст).
     *
     * Сеть здесь ни при чём: движок отдаёт готовую строку сразу, поэтому ожидания
     * в листе не бывает — бывает «Это слово не ссылка» и «Сноска пуста».
     */
    fun showNote(href: String?, text: String?) {
        noteBody = when {
            href == null -> NOT_A_LINK
            text == null -> NOTE_EMPTY
            else -> text
        }
        noteWaiting = false
    }

    fun tab(): Int = tab

    /** Переключить вкладку касанием; true — вкладка сменилась (нужен запрос к сети). */
    fun setTab(t: Int): Boolean {
        if (t == tab) return false
        tab = t
        return true
    }

    /** Слово, по которому открыт лист (его берёт перевод). */
    fun word(): String = word

    /** Строка грамматики/направления для открытой вкладки. */
    private fun grammar(): String = when (tab) {
        TAB_TRANSLATE -> trGrammar
        else -> dictGrammar
    }

    /** Текст под заголовком для открытой вкладки. */
    private fun bodyText(): String = when (tab) {
        TAB_NOTE -> noteBody
        TAB_TRANSLATE -> trBody
        else -> dictBody
    }

    /** Подпись источника для открытой вкладки (у сноски — откуда она взялась). */
    private fun sourceText(): String = when (tab) {
        TAB_NOTE -> if (noteBody == NOT_A_LINK || noteBody == NOTE_EMPTY) "" else SOURCE_NOTE
        TAB_TRANSLATE -> trSource
        else -> dictSource
    }

    /** Ответ ещё не пришёл: на месте текста стоит ожидание, а не пустота. */
    private fun waiting(): Boolean = when (tab) {
        TAB_NOTE -> noteWaiting
        TAB_TRANSLATE -> trWaiting
        else -> dictWaiting
    }

    // ------------------------------------------------------------------ рисование

    fun draw(c: Canvas) {
        ink.convex(c, SHEET_X, SHEET_Y, SHEET_W, SHEET_H, R_SHEET)
        ink.roundRect(c, GRIP_X, GRIP_Y, GRIP_W, GRIP_H, R_GRIP, T.G11)
        tabs(c)
        closeKey(c)
        title(c)
        val g = grammar()
        if (g.isNotEmpty()) {
            ink.text(c, ink.ellipsize(g, GRAMMAR_W, TS_GRAMMAR, 600), X,
                T.base(GRAMMAR_Y, TS_GRAMMAR, 1.25f), TS_GRAMMAR, 600, T.G4)
        }
        bodyLines(c)
        val s = sourceText()
        if (s.isNotEmpty()) {
            ink.text(c, ink.ellipsize(s, SOURCE_W, TS_SOURCE, 600), X,
                T.base(SOURCE_Y, TS_SOURCE, 1.25f), TS_SOURCE, 600, T.G5)
        }
        actions(c)
    }

    /** Вкладки в желобе: активная — выпуклая плашка, подписи 32/600. */
    private fun tabs(c: Canvas) {
        ink.inset(c, SEG_X, SEG_Y, SEG_GROOVE_W, SEG_GROOVE_H, T.R_GROOVE, T.G12)
        for (i in TAB_LABELS.indices) {
            val x = SEG_X + SEG_STEP * i
            val on = i == tab
            if (on) ink.convex(c, x + SEG_INSET, SEG_Y + SEG_PLATE_DY, SEG_PLATE_W, SEG_PLATE_H, R_SEG)
            ink.textCenter(c, TAB_LABELS[i], x, SEG_STEP,
                T.base(SEG_LABEL_Y, TS_SEG, 1.25f), TS_SEG, if (on) 700 else 600,
                if (on) T.G1 else T.G5)
        }
    }

    /** «Закрыть» — круглая клавиша в правом верхнем углу листа. */
    private fun closeKey(c: Canvas) {
        ink.convex(c, CLOSE_X, CLOSE_Y, CLOSE_W, CLOSE_H, R_CLOSE)
        icons.drawRound(c, "close", CLOSE_X, CLOSE_Y, CLOSE_W, CLOSE_H, 16.7f, 18.3f)
    }

    /** Слово статьи: Literata 66/600 — как в макете; длиннее полосы — по «…». */
    private fun title(c: Canvas) {
        val font = T.literata(ink.res, 600)
        val s = ink.ellipsize(word, WORD_W, TS_WORD, 600, font)
        ink.text(c, s, X, T.base(WORD_Y, TS_WORD, 1.2f, literata = true), TS_WORD, 600, T.G1, font)
    }

    /** Определение: до трёх строк 40/400 с шагом 64 (межстрочный 1.6 из макета). */
    private fun bodyLines(c: Canvas) {
        val text = when {
            !waiting() -> bodyText()
            tab == TAB_NOTE -> WAIT_NOTE
            tab == TAB_TRANSLATE -> WAIT_TR
            else -> WAIT
        }
        val lines = ink.ellipsize2(text, DEF_W, TS_DEF, 400, lines = DEF_LINES)
        var y = T.base(DEF_Y, TS_DEF, 1.6f)
        for (s in lines) {
            ink.text(c, s, X, y, TS_DEF, 400, T.G1)
            y += DEF_STEP
        }
    }

    /** Четыре действия внизу листа: круглая клавиша и подпись под ней. */
    private fun actions(c: Canvas) {
        for (i in ACTS.indices) {
            val a = ACTS[i]
            val gx = ACT_X0 + ACT_STEP * i
            val kx = gx + (ACT_PITCH - KEY_W) / 2f
            ink.convex(c, kx, KEY_Y, KEY_W, KEY_H, 55f)
            icons.drawRound(c, a.icon, kx, KEY_Y, KEY_W, KEY_H, a.iw, a.ih)
            ink.textCenter(c, a.label, gx, ACT_PITCH,
                T.base(ACT_LABEL_Y, TS_ACT_LABEL, 1.25f), TS_ACT_LABEL, 600, T.G1)
        }
    }

    // ------------------------------------------------------------------ касания

    /**
     * Касание листа: код действия или [ACT_NONE]. Выше листа — [ACT_CLOSE] (касание уходит
     * на страницу, как у B51: закрыть лист можно и по странице над ним).
     */
    fun hit(x: Float, y: Float): Int {
        if (y < SHEET_Y) return ACT_CLOSE
        // Вкладки: желоб 56…876 по x, 1040…1128 по y; полосы по 270.7.
        if (y >= SEG_Y - 4f && y <= SEG_Y + SEG_GROOVE_H && x >= SEG_X && x <= SEG_X + SEG_GROOVE_W) {
            val i = ((x - SEG_X) / SEG_STEP).toInt().coerceIn(0, TAB_LABELS.size - 1)
            return ACT_TAB + i
        }
        // «Закрыть» — с запасом вокруг клавиши 97×102.
        if (x >= CLOSE_X - HIT_PAD && x <= CLOSE_X + CLOSE_W + HIT_PAD &&
            y >= CLOSE_Y - HIT_PAD && y <= CLOSE_Y + CLOSE_H + HIT_PAD) return ACT_CLOSE
        // Действия: клавиша 115×120 на 1620 и подпись под ней до 1792.
        if (y >= KEY_Y - HIT_PAD && y <= ACT_LABEL_Y + 40f) {
            for (i in ACTS.indices) {
                val gx = ACT_X0 + ACT_STEP * i
                if (x >= gx && x <= gx + ACT_PITCH) return ACT_HIGHLIGHT + i
            }
        }
        return ACT_NONE
    }

    companion object {
        const val ACT_NONE = 0
        /** Касание выше листа или по «Закрыть»: экран снимает лист. */
        const val ACT_CLOSE = 1
        /** Вкладки: [ACT_TAB] + номер ([TAB_DICT], [TAB_NOTE], [TAB_TRANSLATE]). */
        const val ACT_TAB = 2
        /** Действия внизу: [ACT_HIGHLIGHT] + номер (0…3). */
        const val ACT_HIGHLIGHT = 6

        const val TAB_DICT = 0
        const val TAB_NOTE = 1
        const val TAB_TRANSLATE = 2

        private val TAB_LABELS = arrayOf("Словарь", "Сноска", "Перевод")

        /** Действие внизу листа: иконка, рамка иконки и подпись. */
        private class Act(val icon: String, val iw: Float, val ih: Float, val label: String)

        private val ACTS = arrayOf(
            Act("highlight", 39.8f, 35.4f, "Выделить"),
            Act("note", 35.3f, 39.8f, "Заметка"),
            Act("copy", 34.2f, 40.9f, "Копировать"),
            Act("search", 36.9f, 39.1f, "Найти в книге"),
        )

        /** Строки, которые лист показывает вместо ответа: ожидание и «не нашлось». */
        const val WAIT = "Ищу в словаре…"
        const val WAIT_TR = "Перевожу…"
        /** Сноска читается из книги: движок отдаёт её без сети. */
        const val WAIT_NOTE = "Ищу сноску…"
        private const val NOT_FOUND = "Значения в словаре нет."
        private const val NOT_FOUND_TR = "Перевода не нашлось."
        private const val SOURCE_NONE = "Словарь недоступен"
        /** Вкладка «Сноска»: слово не ссылка и сноска пустая — это не ошибка, а факт. */
        private const val NOT_A_LINK = "Это слово не ссылка: сноски в книге нет."
        private const val NOTE_EMPTY = "Сноска в книге пустая."
        /** Подпись под сноской: текст взят из самой книги, сеть не нужна. */
        private const val SOURCE_NOTE = "Сноска книги"
        /** Подпись под переводом: сервис тот же, что и в ответе, но без опоры на сеть. */
        private const val SOURCE_TR = "MyMemory · перевод по сети"

        private const val SHEET_X = -3f
        private const val SHEET_Y = 980f
        private const val SHEET_W = 1409f
        private const val SHEET_H = 1022f
        private const val R_SHEET = 66f
        private const val GRIP_X = 644f
        private const val GRIP_Y = 1005f
        private const val GRIP_W = 117f
        private const val GRIP_H = 11f
        private const val R_GRIP = 5f

        // Вкладки: желоб 56,1040 820×88; плашка активной 60,1044 270.7×83.
        private const val SEG_X = 56f
        private const val SEG_Y = 1040f
        private const val SEG_GROOVE_W = 820f
        private const val SEG_GROOVE_H = 88f
        /** Шаг полосы: 270.7 = 812 / 3 (макет: подписи 60 / 330.7 / 601). */
        private const val SEG_STEP = 270.66667f
        private const val SEG_INSET = 4f
        private const val SEG_PLATE_DY = 4f
        private const val SEG_PLATE_W = 270.7f
        private const val SEG_PLATE_H = 83f
        private const val R_SEG = 36.5f
        /** Подписи вкладок: верх рамки 1062.5 (у активной в макете 1063.5). */
        private const val SEG_LABEL_Y = 1062.5f
        private const val TS_SEG = 32f

        private const val CLOSE_X = 1250f
        private const val CLOSE_Y = 1033f
        private const val CLOSE_W = 97f
        private const val CLOSE_H = 102f
        private const val R_CLOSE = 48.5f

        private const val X = 59f
        private const val TS_WORD = 66f
        private const val WORD_Y = 1180f
        /** Полоса слова: до «Закрыть» (1250) минус зазор. */
        private const val WORD_W = 1130f

        private const val TS_GRAMMAR = 32f
        private const val GRAMMAR_Y = 1270f
        private const val GRAMMAR_W = 1130f

        private const val TS_DEF = 40f
        private const val DEF_Y = 1340f
        /** Три строки по 64 до строки источника (1555): 1340 + 3·64 + 23 < 1555. */
        private const val DEF_LINES = 3
        private const val DEF_STEP = 64f
        /** Определение до «Закрыть» — 59…1250 с зазором. */
        private const val DEF_W = 1180f

        private const val TS_SOURCE = 28f
        private const val SOURCE_Y = 1555f
        private const val SOURCE_W = 1100f

        private const val ACT_X0 = 26f
        private const val ACT_STEP = 350f
        private const val ACT_PITCH = 300f
        private const val KEY_W = 115f
        private const val KEY_H = 120f
        private const val KEY_Y = 1620f
        private const val TS_ACT_LABEL = 32f
        private const val ACT_LABEL_Y = 1752f
        private const val HIT_PAD = 6f
    }
}
