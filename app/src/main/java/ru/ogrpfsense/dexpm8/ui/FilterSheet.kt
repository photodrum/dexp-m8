package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import ru.ogrpfsense.dexpm8.core.LibraryDb
import ru.ogrpfsense.dexpm8.core.Prefs

/**
 * B22 · Сортировка и фильтры — нижний лист поверх библиотеки. Числа — из design/v5/B22.json.
 * Состояние черновое: [load] берёт его из [Prefs], [ACT_APPLY] («Показать N книг») пишет назад
 * ([save]), касание выше листа — [ACT_CANCEL].
 *
 *   лист      выпуклый −3,330 1409×1672 (r 66); захват 644,355 117×11 G11
 *   заголовок 59,388 60/700 G1; метки 30/600 G5 в 59, y 510 / 700 / 890 / 1080
 *   сортировка желоб 56,560 1292×95, сегмент 60 + i·321 (321×90), подпись 32
 *   порядок   желоб 56,750 1292×95, сегмент 60 + i·642 (642×90), подпись 34
 *   таблетки  формат y 942, состояние y 1132, высота 88; нажатая — вдавленная (на 2 выше);
 *      ширина = текст 34/700 + 80, шаг = текст + 104
 *   тумблер   «Скрывать прочитанные» (42/700), 139×73 в x 1209
 *   кнопки    «Сбросить» 56,1708 425×112; основная 516,1693 837×142
 *
 * Отступы от макета: нет «PDF» (решение проекта) и «Аудиокниг» (их нет в базе); нет строки
 * «Группировать по сериям» (группировки в списке пока нет), «Скрывать прочитанные» осталась
 * на своём месте (y 1288).
 */
class FilterSheet(private val ink: Ink, icons: Icons) {

    var sort = LibraryDb.SORT_AUTHOR
    var reverse = false
    var formats = 0
    var states = 0
    var hideRead = false

    private val kit = SettingsKit(ink, icons)

    private val sortLabels = arrayOf("По автору", "По названию", "По дате", "По прогрессу")
    private val sortValues = intArrayOf(
        LibraryDb.SORT_AUTHOR, LibraryDb.SORT_TITLE, LibraryDb.SORT_RECENT, LibraryDb.SORT_PROGRESS,
    )
    private val orderLabels = arrayOf("Сначала новые", "Сначала старые")
    private val stateLabels = arrayOf("Новые", "Читаю", "Прочитанные")

    private val fmtX = FloatArray(LibraryDb.FORMAT_LABELS.size)
    private val fmtW = FloatArray(LibraryDb.FORMAT_LABELS.size)
    private val stX = FloatArray(stateLabels.size)
    private val stW = FloatArray(stateLabels.size)

    init {
        place(LibraryDb.FORMAT_LABELS, fmtX, fmtW)
        place(stateLabels, stX, stW)
    }

    /** Раскладка таблеток ряда: x и ширина «вдавленной» (текст 700 + 80). */
    private fun place(labels: Array<String>, xs: FloatArray, ws: FloatArray) {
        var x = 56f
        for (i in labels.indices) {
            val t = ink.measure(labels[i], 34f, 700)
            xs[i] = x
            ws[i] = t + 80f
            x += t + 104f
        }
    }

    fun load(p: Prefs) {
        sort = p.libSort
        reverse = p.libReverse
        formats = p.libFormats
        states = p.libStates
        hideRead = p.libHideRead
    }

    fun save(p: Prefs) {
        p.libSort = sort
        p.libReverse = reverse
        p.libFormats = formats
        p.libStates = states
        p.libHideRead = hideRead
    }

    fun reset() {
        sort = LibraryDb.SORT_AUTHOR
        reverse = false
        formats = 0
        states = 0
        hideRead = false
    }

    fun filter() = LibraryDb.Filter(formats, states, hideRead)

    /** Активный сегмент «Сортировать»: «Новые» (по добавлению) показываем как «По дате». */
    private fun sortIndex(): Int {
        if (sort == LibraryDb.SORT_ADDED) return 2
        val i = sortValues.indexOf(sort)
        return if (i < 0) 0 else i
    }

    // --------------------------------------------------------------- рисование

    fun draw(c: Canvas, count: Int) {
        ink.convex(c, -3f, 330f, 1409f, 1672f, 66f)
        ink.roundRect(c, 644f, 355f, 117f, 11f, 5f, T.G11)
        ink.text(c, "Сортировка и фильтры", 59f, T.base(388f, T.TS_TITLE, 1.25f), T.TS_TITLE, 700, T.G1)

        label(c, "Сортировать", 510f)
        ink.inset(c, 56f, 560f, 1292f, 95f, 47.5f, T.G12)
        val si = sortIndex()
        for (i in sortLabels.indices) {
            val on = i == si
            val x = 60f + 321f * i
            if (on) ink.convex(c, x, 564f, 321f, 90f, 40f)
            ink.textCenter(c, sortLabels[i], x, 316f, T.base(if (on) 587f else 586f, T.TS_BODY_S, 1.25f),
                T.TS_BODY_S, if (on) 700 else 600, if (on) T.G1 else T.G5)
        }

        label(c, "Порядок", 700f)
        ink.inset(c, 56f, 750f, 1292f, 95f, 47.5f, T.G12)
        val oi = if (reverse) 1 else 0
        for (i in orderLabels.indices) {
            val on = i == oi
            val x = 60f + 642f * i
            if (on) ink.convex(c, x, 754f, 642f, 90f, 40f)
            ink.textCenter(c, orderLabels[i], x, 637f, T.base(if (on) 775.8f else 774.8f, 34f, 1.25f),
                34f, if (on) 700 else 600, if (on) T.G1 else T.G5)
        }

        label(c, "Формат", 890f)
        for (i in fmtX.indices) {
            pill(c, LibraryDb.FORMAT_LABELS[i], fmtX[i], fmtW[i], FMT_Y, formats and (1 shl i) != 0)
        }

        label(c, "Состояние", 1080f)
        for (i in stX.indices) pill(c, stateLabels[i], stX[i], stW[i], ST_Y, states and (1 shl i) != 0)

        ink.text(c, "Скрывать прочитанные", 59f, T.base(HIDE_Y + 9.5f, 42f, 1.25f), 42f, 700, T.G1)
        kit.toggle(c, HIDE_Y, hideRead, 1209f)

        ink.convex(c, 56f, 1708f, 425f, 112f, 51f)
        ink.textCenter(c, "Сбросить", 59f, 420f, T.base(1738.2f, 38f, 1.26f), 38f, 700, T.G1)
        ink.convexDark(c, 516f, 1693f, 837f, 142f, 66f)
        ink.textCenter(c, "Показать " + booksAcc(count), 519f, 832f, T.base(1735.7f, 42f, 1.24f),
            42f, 700, T.G15)
    }

    private fun label(c: Canvas, s: String, top: Float) =
        ink.text(c, s, 59f, T.base(top, 30f, 1.27f), 30f, 600, T.G5)

    /** Таблетка: нажатая — вдавленная (на 2 выше, 88), иначе выпуклая (+5 на тень). */
    private fun pill(c: Canvas, s: String, x: Float, w: Float, y: Float, on: Boolean) {
        if (on) {
            ink.inset(c, x, y - 2f, w, 88f, 44f, T.G12)
            ink.text(c, s, x + 40f, T.base(y + 20.1f, 34f, 1.25f), 34f, 700, T.G1)
        } else {
            ink.convex(c, x, y, w + 5f, 88f, 39f)
            ink.text(c, s, x + 40f, T.base(y + 20.8f, 34f, 1.25f), 34f, 600, T.G1)
        }
    }

    /** «1 книгу», «2 книги», «5 книг». */
    private fun booksAcc(n: Int): String {
        val m100 = n % 100
        val m10 = n % 10
        val w = if (m100 in 11..14) "книг" else when (m10) {
            1 -> "книгу"
            2, 3, 4 -> "книги"
            else -> "книг"
        }
        return "$n $w"
    }

    // --------------------------------------------------------------- касания

    /** Касание: [ACT_NONE], [ACT_CHANGED] (пересчитать число), [ACT_CANCEL] или [ACT_APPLY]. */
    fun hit(x: Float, y: Float): Int {
        if (y < 330f) return ACT_CANCEL
        if (y in 548f..667f) {
            sort = sortValues[((x - 60f) / 321f).toInt().coerceIn(0, 3)]
            return ACT_CHANGED
        }
        if (y in 738f..857f) {
            reverse = x >= 702f
            return ACT_CHANGED
        }
        if (y in FMT_Y - 12f..FMT_Y + 100f) {
            val i = pillAt(x, fmtX, fmtW)
            if (i >= 0) {
                formats = formats xor (1 shl i)
                return ACT_CHANGED
            }
        }
        if (y in ST_Y - 12f..ST_Y + 100f) {
            val i = pillAt(x, stX, stW)
            if (i >= 0) {
                states = states xor (1 shl i)
                return ACT_CHANGED
            }
        }
        if (y in HIDE_Y - 20f..HIDE_Y + 93f && x >= 56f) {
            hideRead = !hideRead
            return ACT_CHANGED
        }
        if (y in 1690f..1840f) {
            if (x in 56f..496f) {
                reset()
                return ACT_CHANGED
            }
            if (x in 496f..1360f) return ACT_APPLY
        }
        return ACT_NONE
    }

    /** Номер таблетки под [x] (с зазором 12 по краям) или −1. */
    private fun pillAt(x: Float, xs: FloatArray, ws: FloatArray): Int {
        for (i in xs.indices) if (x >= xs[i] - 12f && x <= xs[i] + ws[i] + 12f) return i
        return -1
    }

    companion object {
        const val ACT_NONE = 0
        const val ACT_CHANGED = 1
        const val ACT_CANCEL = 2
        const val ACT_APPLY = 3

        private const val FMT_Y = 942f
        private const val ST_Y = 1132f
        private const val HIDE_Y = 1288f
    }
}
