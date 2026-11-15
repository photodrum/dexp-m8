package ru.ogrpfsense.dexpm8.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import ru.ogrpfsense.dexpm8.ui.Ink
import ru.ogrpfsense.dexpm8.ui.SettingsKit
import ru.ogrpfsense.dexpm8.ui.T

/**
 * B31 · DjVu «Масштаб и поля» — нижний лист читалки (R2.5). Числа — `design/v5/B31.json`,
 * состояния — `B31а` (ручка обрезки активна) и `B31б` (желоб масштаба раскрыт в «− · 140 % · +»).
 *
 *   превью    лист 402,110 605×830: тень G10 +5,+10, блик G15, поверхность G14 600×820 (r 8);
 *             в нём кадр страницы 600×820 из 405,113 — без обрезки (решение пользователя)
 *   рамка     452,170 506×700, пунктир G3/5 r 6; ручки 44×44 (группа 49×54) в углах рамки,
 *             активная — кольцо G1 60×60 в 427.5,141 (`B31а`)
 *   лист      выпуклый −3,1000 1409×1002 r 66; захват 644,1025 117×11 G11
 *   заголовок «Масштаб и поля» 59,1056 60/700 G1; «Готово» тёмная 1074,1042.6 273×108
 *   масштаб   метка 59,1206 30/600 G5; желоб 56,1256 1292×95, шаг 428, подписи 32 в 60/488/916,
 *             активный сегмент — выпуклая плашка 423×80 в 488,1260
 *   «свой»    тот же желоб (`B31б`): знаки − 247.5,1301 48×8 и + 1103.5,1301/1281 G1,
 *             значение «140 %» 42/700 по центру; касание значения возвращает сегменты
 *   обрезка   метка 59,1426; желоб 56,1476 «Авто / Вручную / Без обрезки»
 *   контраст  «Усилить контраст скана» 59,1761 42/700 G1 + тумблер 139×73 в 1206
 *   подсказка 59,1646 32/600 G5 — текст зависит от режима масштаба
 *
 * Желоб B31 шире, чем `SettingsKit.segments` (56, 1292×95, шаг 428), поэтому нарисован
 * здесь; тумблер и подписи — из `SettingsKit`, как на остальных листах.
 *
 * Под листом затемнения нет (отступление от замечания 15, вопрос в `DESIGN_QUESTIONS.md`):
 * по превью надо видеть поля скана. Прежняя страница читалки остаётся сверху нетронутой.
 *
 * Состояние живёт в экране ([ReaderScreen]): лист получает настройки в [draw] и [hit],
 * возвращает новое через [result] — двух копий настроек нет.
 */
class DjvuViewSheet(private val ink: Ink, icons: ru.ogrpfsense.dexpm8.ui.Icons) {

    private val kit = SettingsKit(ink, icons)

    /** Превью текущей страницы: кадр 600×820 без обрезки; null — ещё не отрисовано. */
    var preview: Bitmap? = null
        private set

    /** Какая ручка рамки выбрана кольцом G1 (`B31а`); −1 — ни одна. */
    var handle = -1
        private set

    /** Новое состояние после [hit]; до первого касания — то, что передали в [draw]. */
    var result: DjvuView = DjvuView.DEFAULT
        private set

    /** Верхняя строка колонтитула — «Атлас мира.djvu · стр. 12 из 240». */
    private var header = ""

    /** Желоб масштаба раскрыт в «− · 140 % · +» (`B31б`). */
    private var selfOpen = false

    fun open(header: String) {
        this.header = header
        selfOpen = false
        handle = -1
    }

    /** Превью отрисовано движком: лист перерисует [ReaderScreen] (правило «worker → post → UI»). */
    fun setPreview(b: Bitmap?) {
        preview = b
    }

    fun draw(c: Canvas, view: DjvuView) {
        result = view
        header(c)
        frame(c, view)
        sheet(c, view)
    }

    // ------------------------------------------------------------------ превью и рамка

    /** Колонтитул над превью: 73,38 28/600 G5 (в макете «Атлас мира.djvu · стр. 12 из 240»). */
    private fun header(c: Canvas) {
        if (header.isEmpty()) return
        ink.text(c, ink.ellipsize(header, 1258f, 28f, 600), 73f,
            T.base(38f, 28f, 1.25f), 28f, 600, T.G5)
    }

    /** Лист превью 600×820 и рамка обрезки с ручками. Раскладка — по пикселям макета. */
    private fun frame(c: Canvas, view: DjvuView) {
        ink.convex(c, PREVIEW_X, PREVIEW_Y, 605f, 830f, 8f)
        val b = preview
        if (b != null) c.drawBitmap(b, null, PREVIEW_DST, PAGE)
        // Рамка и ручки — только «Вручную»: в других режимах режет движок (авто/никак).
        if (view.cropMode != DjvuView.CROP_MANUAL) return
        ink.ringDashed(c, 452f, 170f, 506f, 700f, 6f, T.G3, 5f)
        for (i in 0 until 4) {
            val hx = HANDLE_X[i]
            val hy = HANDLE_Y[i]
            if (i == handle) ink.ring(c, hx - 2.5f, hy - 5f, 60f, 60f, 30f, T.G1, 5f)
            ink.convex(c, hx + 3f, hy + 3f, 44f, 44f, 22f)
        }
    }

    // ------------------------------------------------------------------ нижний лист

    private fun sheet(c: Canvas, view: DjvuView) {
        ink.convex(c, -3f, 1000f, 1409f, 1002f, 66f)
        ink.roundRect(c, 644f, 1025f, 117f, 11f, 5f, T.G11)
        ink.text(c, "Масштаб и поля", 59f, T.base(1056f, 60f, 1.25f), 60f, 700, T.G1)
        ink.convexDark(c, 1074f, 1042.6f, 273f, 108f, 49f)
        ink.textCenter(c, "Готово", 1077f, 268f, T.base(1068.6f, 42f, 1.24f), 42f, 700, T.G15)

        label(c, "Масштаб", 1206f)
        groove(c, ZOOM_Y)
        if (selfOpen || view.isSelf) zoomSelf(c, view) else zoomSegments(c, view)

        label(c, "Обрезка полей", 1426f)
        groove(c, CROP_Y)
        val seg = view.cropSegment()
        activeSegment(c, CROP_Y, seg)
        for (i in CROP_LABELS.indices) caption(c, CROP_LABELS[i], CROP_Y, i, i == seg)

        hint(c, if (selfOpen || view.isSelf) HINT_SELF else HINT_SEGMENTS)
        ink.text(c, "Усилить контраст скана", 59f, T.base(1761f + 9.5f, 42f, 1.25f), 42f, 700, T.G1)
        kit.toggle(c, 1761f, view.contrast, 1206f)
    }

    /** Три сегмента масштаба: «По ширине / По странице / Свой · 140 %». */
    private fun zoomSegments(c: Canvas, view: DjvuView) {
        val seg = view.zoomSegment()
        activeSegment(c, ZOOM_Y, seg)
        caption(c, "По ширине", ZOOM_Y, 0, seg == 0)
        caption(c, "По странице", ZOOM_Y, 1, seg == 1)
        caption(c, view.selfLabel(), ZOOM_Y, 2, seg == 2)
    }

    /** Раскрытый желоб (`B31б`): «− · 140 % · +» и значение по центру. */
    private fun zoomSelf(c: Canvas, view: DjvuView) {
        activeSegment(c, ZOOM_Y, 1)
        ink.rect(c, 247.5f, 1301f, 48f, 8f, T.G1)              // знак −
        ink.rect(c, 1103.5f, 1301f, 48f, 8f, T.G1)             // знак +
        ink.rect(c, 1123.5f, 1281f, 8f, 48f, T.G1)             // стойка +
        val p = view.zoom.coerceIn(DjvuView.ZOOM_MIN, DjvuView.ZOOM_MAX)
        ink.textCenter(c, "$p%", SEG_MID, 423f, T.base(1277f, 42f, 1.25f), 42f, 700, T.G1)
    }

    private fun label(c: Canvas, s: String, top: Float) =
        ink.text(c, s, 59f, T.base(top, 30f, 1.27f), 30f, 600, T.G5)

    /** Вдавленный желоб B31: 56,y 1292×95 с дном G12 (числа слоёв «Желоб»). */
    private fun groove(c: Canvas, y: Float) = ink.inset(c, 56f, y, 1292f, 95f, 47.5f, T.G12)

    /** Активный сегмент — выпуклая плашка 423×80 (слой «Активный сегмент»). */
    private fun activeSegment(c: Canvas, y: Float, i: Int) =
        ink.convex(c, SEG_X + SEG_STEP * i, y + 4f, 423f, 80f, 40f)

    /** Подпись сегмента: 32/600 G5, активная — 700 G1 (в макете +1 к базовой линии). */
    private fun caption(c: Canvas, s: String, y: Float, i: Int, on: Boolean) =
        ink.textCenter(c, s, SEG_X + SEG_STEP * i, 423f,
            T.base(y + (if (on) 27f else 26f), 32f, 1.25f), 32f, if (on) 700 else 600,
            if (on) T.G1 else T.G5)

    private fun hint(c: Canvas, s: String) =
        ink.text(c, s, 59f, T.base(1646f, 32f, 1.27f), 32f, 600, T.G5)

    // ------------------------------------------------------------------ касания

    /**
     * Касание в дизайн-px. [view] — текущие настройки страницы. Возвращает [ACT_*];
     * новое состояние — в [result] (при [ACT_REPAINT] его нет: меняется только выбранная ручка).
     */
    fun hit(x: Float, y: Float, view: DjvuView): Int {
        result = view
        if (y < 1000f) {
            // Над листом: ручки рамки (только «Вручную»), иначе — «Готово».
            val i = handleAt(x, y)
            if (i >= 0 && view.cropMode == DjvuView.CROP_MANUAL) {
                handle = i
                return ACT_REPAINT
            }
            return ACT_CLOSE
        }
        if (y < 1042f) return ACT_CLOSE                     // захват и строка заголовка
        if (x >= 1060f && x <= 1360f && y <= 1160f) return ACT_CLOSE   // «Готово»
        if (y in ZOOM_Y - 12f..ZOOM_Y + 100f) return zoomHit(x, view)
        if (y in CROP_Y - 12f..CROP_Y + 100f) {
            val seg = ((x - SEG_X) / SEG_STEP).toInt().coerceIn(0, 2)
            result = view.mode(mode = seg)
            handle = -1
            return ACT_CHANGED
        }
        if (y in 1740f..1850f && x >= 700f) {
            result = view.copy(contrast = !view.contrast)
            return ACT_CHANGED
        }
        return ACT_NONE
    }

    /** Желоб масштаба: три сегмента или раскрытый «− · значение · +». */
    private fun zoomHit(x: Float, view: DjvuView): Int {
        if (!selfOpen && !view.isSelf) {
            // «По ширине» / «По странице» / «Свой»: третье раскрывает «− · + » (B31б).
            val seg = ((x - SEG_X) / SEG_STEP).toInt().coerceIn(0, 2)
            result = when (seg) {
                0 -> view.mode(zoom = DjvuView.ZOOM_WIDTH)
                1 -> view.mode(zoom = DjvuView.ZOOM_PAGE)
                else -> view.zoomSelf(view.self)
            }
            return ACT_CHANGED
        }
        // Раскрытый желоб: «−», значение (возвращает сегменты), «+». Зоны шире знаков.
        if (x in 200f..350f) {
            result = view.zoomSelf(view.zoom - DjvuView.ZOOM_STEP)
            return ACT_CHANGED
        }
        if (x in 1060f..1210f) {
            result = view.zoomSelf(view.zoom + DjvuView.ZOOM_STEP)
            return ACT_CHANGED
        }
        if (x in 430f..970f) {
            selfOpen = false
            result = view.mode(zoom = DjvuView.ZOOM_PAGE)
            return ACT_CHANGED
        }
        return ACT_NONE
    }

    /** Какая ручка под касанием (−1 — мимо); зона 96×96 вокруг центра ручки. */
    private fun handleAt(x: Float, y: Float): Int {
        for (i in 0 until 4) {
            val cx = HANDLE_X[i] + 25f
            val cy = HANDLE_Y[i] + 27f
            if (Math.abs(x - cx) <= 48f && Math.abs(y - cy) <= 48f) return i
        }
        return -1
    }

    companion object {
        const val ACT_NONE = 0
        /** Перерисовать лист: выбрана ручка или раскрыт желоб. */
        const val ACT_REPAINT = 1
        /** Настройки изменились: страницу перерисовать, строку записать в базу. */
        const val ACT_CHANGED = 2
        /** «Готово» или касание мимо листа. */
        const val ACT_CLOSE = 3

        private const val ZOOM_Y = 1256f
        private const val CROP_Y = 1476f
        private const val SEG_X = 60f          // левый край первого сегмента: 60 / 488 / 916
        private const val SEG_STEP = 428f
        private const val SEG_MID = 488f      // середина желоба: значение «140 %» в раскрытом виде

        private const val PREVIEW_X = 402f
        private const val PREVIEW_Y = 110f

        private val CROP_LABELS = arrayOf("Авто", "Вручную", "Без обрезки")

        private const val HINT_SEGMENTS = "Коснитесь ручки, затем — края страницы"
        private const val HINT_SELF = "Коснитесь значения, чтобы вернуть режимы"

        /** Левые верхние углы групп ручек рамки (49×54): 430/936 × 146/846. */
        private val HANDLE_X = floatArrayOf(430f, 936f, 430f, 936f)
        private val HANDLE_Y = floatArrayOf(146f, 146f, 846f, 846f)

        /** Куда рисуется кадр страницы: поверхность листа 405,113 600×820. */
        private val PREVIEW_DST = android.graphics.RectF(405f, 113f, 1005f, 933f)

        /** Превью рисуется как есть: шаг палитры уже в буфере, фильтрация только мылит. */
        private val PAGE = android.graphics.Paint().apply {
            isFilterBitmap = false
            isDither = false
        }
    }
}
