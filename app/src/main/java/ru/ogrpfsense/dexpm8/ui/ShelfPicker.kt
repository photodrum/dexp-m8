package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import ru.ogrpfsense.dexpm8.core.LibraryDb

/**
 * Лист «На полку» на B12: свои полки с тумблерами (книга лежит / не лежит). На макете его
 * нет: рисуем по стилю B22/B48 — лист −,880 1409×1132 (r 66), захват 644,905, заголовок
 * 60/700 в 59,942; строка y = 1050 + 118·i: название 42/700 в 59, тумблер [SettingsKit.toggle]
 * в x 1209; «Готово» — тёмная кнопка 56,1693 1292×142. До [MAX] полок (листания нет).
 */
class ShelfPicker(private val ink: Ink, icons: Icons) {

    private val kit = SettingsKit(ink, icons)
    private var shelves = ArrayList<LibraryDb.Shelf>()
    private var on = HashSet<Long>()
    private var more = 0

    /** Список полок и те, что уже с книгой. */
    fun open(all: ArrayList<LibraryDb.Shelf>, withBook: HashSet<Long>) {
        more = Math.max(0, all.size - MAX)
        shelves = if (all.size > MAX) ArrayList(all.subList(0, MAX)) else all
        on = HashSet(withBook)
    }

    fun shelfAt(i: Int): LibraryDb.Shelf = shelves[i]

    fun set(id: Long, lies: Boolean) {
        if (lies) on.add(id) else on.remove(id)
    }

    fun draw(c: Canvas) {
        ink.convex(c, -3f, TOP, 1409f, 1132f, 66f)
        ink.roundRect(c, 644f, 905f, 117f, 11f, 5f, T.G11)
        ink.text(c, "На полку", 59f, T.base(942f, 60f, 1.25f), 60f, 700, T.G1)
        if (shelves.isEmpty()) {
            ink.textCenter(c, "Своих полок пока нет", 0f, 1404f, T.base(1190f, 42f, 1.25f), 42f, 700, T.G1)
            ink.textCenter(c, "Создайте полку на экране «Полки»", 0f, 1404f, T.base(1250f, 32f, 1.3f), 32f, 600, T.G4)
        }
        for (i in shelves.indices) {
            val y = ROW_Y0 + i * ROW_STEP
            ink.text(c, ink.ellipsize(shelves[i].name, 1050f, 42f, 700), 59f, T.base(y + 12f, 42f, 1.25f), 42f, 700, T.G1)
            kit.toggle(c, y, shelves[i].id in on, 1209f)
        }
        if (more > 0) {
            ink.text(c, "Ещё ${Words.shelves(more)} — на экране «Полки»", 59f, T.base(1630f, 30f, 1.27f), 30f, 600, T.G5)
        }
        ink.convexDark(c, 56f, 1693f, 1292f, 142f, T.R_BUTTON)
        ink.textCenter(c, "Готово", 59f, 1286f, T.base(1735.7f, T.TS_ACTION, 1.24f), T.TS_ACTION, 700, T.G15)
    }

    /** Касание: [ACT_NONE], [ACT_TOGGLE] (строка в [row]), [ACT_CLOSE]. */
    var row = -1
    private set

    fun hit(x: Float, y: Float): Int {
        if (y < TOP) return ACT_CLOSE
        if (y >= 1690f) return ACT_CLOSE
        for (i in shelves.indices) {
            val top = ROW_Y0 + i * ROW_STEP - 22f
            if (y >= top && y < top + ROW_STEP) {
                row = i
                return ACT_TOGGLE
            }
        }
        return ACT_NONE
    }

    companion object {
        const val ACT_NONE = 0
        const val ACT_TOGGLE = 1
        const val ACT_CLOSE = 2

        const val MAX = 5
        private const val TOP = 880f
        private const val ROW_Y0 = 1050f
        private const val ROW_STEP = 118f
    }
}
