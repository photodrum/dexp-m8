package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import android.os.Environment
import java.io.File
import java.text.Collator
import java.util.Locale

/**
 * Лист «Открыть шрифт…» (B70 «Шрифт и размер», строка «TTF или OTF из папки Fonts»).
 * На макете листа нет — рисуем по стилю B25/B48: лист −3,880 1409×1132 (r 66), захват
 * 644,905 117×11, заголовок 60/700 в 59,942, подзаголовок 32/600 G4 в 59,1010;
 * строки от 1100 шагом 118: имя файла 42/600 в 59 (обрезается по 1250), `next` 11×23.8
 * в 1305; пусто — «В папке Fonts нет шрифтов» 42/700 по центру 1190; «Готово» — тёмная
 * кнопка 56,1693 1292×142.
 *
 * Показываем только `.ttf`/`.otf` из папки `Fonts` на карте: свой шрифт пользователь кладёт
 * туда, а не ищет по всей памяти (первый вариант — файловый обзор B25, но у него в макете
 * только книги; вопрос в `docs/DESIGN_QUESTIONS.md`).
 * До [MAX] строк: листания в листе нет, как у [ShelfPicker].
 */
class FontPicker(private val ink: Ink, private val icons: Icons) {

    private var files = ArrayList<File>(MAX)
    private var more = 0

    /** Собрать список шрифтов из `Fonts` в корне памяти. */
    fun open() {
        val dir = File(Environment.getExternalStorageDirectory(), DIR)
        val found = ArrayList<File>(16)
        try {
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.substringAfterLast('.', "").lowercase(Locale.ROOT) in EXT)
                    found.add(f)
            }
        } catch (t: Throwable) {
            // Каталога нет или нет доступа — лист покажет пустое состояние.
        }
        found.sortWith(compareBy(Collator.getInstance(Locale("ru"))) { it.name })
        more = Math.max(0, found.size - MAX)
        files = if (found.size > MAX) ArrayList(found.subList(0, MAX)) else found
    }

    fun fileAt(i: Int): File = files[i]

    fun isEmpty() = files.isEmpty()

    fun draw(c: Canvas) {
        ink.convex(c, -3f, TOP, 1409f, 1132f, 66f)
        ink.roundRect(c, 644f, 905f, 117f, 11f, 5f, T.G11)
        ink.text(c, TITLE, 59f, T.base(942f, 60f, 1.25f), 60f, 700, T.G1)
        ink.text(c, "Папка «$DIR» в памяти книги", 59f, T.base(1010f, T.TS_BODY_S, 1.25f),
            T.TS_BODY_S, 600, T.G4)
        if (files.isEmpty()) {
            ink.textCenter(c, "В папке «$DIR» нет шрифтов", 0f, 1404f,
                T.base(1190f, 42f, 1.25f), 42f, 700, T.G1)
            ink.textCenter(c, "Положите файл .ttf или .otf в папку", 0f, 1404f,
                T.base(1250f, T.TS_BODY_S, 1.3f), T.TS_BODY_S, 600, T.G4)
        }
        for (i in files.indices) {
            val y = ROW_Y0 + i * ROW_STEP
            ink.text(c, ink.ellipsize(files[i].name, 1230f, 42f, 600), 59f,
                T.base(y + 12f, 42f, 1.25f), 42f, 600, T.G1)
            icons.draw(c, "next@11x23.8", 1305f, y + 47f, 11f, 23.8f)
        }
        if (more > 0) {
            ink.text(c, "Ещё $more", 59f, T.base(1630f, T.TS_META, 1.27f), T.TS_META, 600, T.G5)
        }
        ink.convexDark(c, 56f, 1693f, 1292f, 142f, T.R_BUTTON)
        ink.textCenter(c, "Готово", 59f, 1286f, T.base(1735.7f, T.TS_ACTION, 1.24f),
            T.TS_ACTION, 700, T.G15)
    }

    /** Касание: [ACT_NONE], [ACT_PICK] (строка в [row]), [ACT_CLOSE]. */
    var row = -1
        private set

    fun hit(x: Float, y: Float): Int {
        row = -1
        if (y < TOP) return ACT_CLOSE
        if (y >= 1690f) return ACT_CLOSE
        for (i in files.indices) {
            val top = ROW_Y0 + i * ROW_STEP - 22f
            if (x >= ROW_X0 && y >= top && y < top + ROW_STEP) {
                row = i
                return ACT_PICK
            }
        }
        return ACT_NONE
    }

    companion object {
        const val ACT_NONE = 0
        const val ACT_PICK = 1
        const val ACT_CLOSE = 2

        /** Папка со шрифтами пользователя в корне памяти. */
        const val DIR = "Fonts"

        private val EXT = setOf("ttf", "otf")
        private const val TITLE = "Открыть шрифт"
        private const val MAX = 7
        private const val TOP = 880f
        /** Строка начинается с 59 (подпись), тянется до правого края листа. */
        private const val ROW_X0 = 41f
        private const val ROW_Y0 = 1100f
        private const val ROW_STEP = 118f
    }
}
