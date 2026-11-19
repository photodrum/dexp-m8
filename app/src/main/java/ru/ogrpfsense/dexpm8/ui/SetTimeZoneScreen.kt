package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import ru.ogrpfsense.dexpm8.core.Zones

/**
 * B71 · Настройки · часовой пояс. Числа — из design/v5/B71.json.
 *
 *   шапка      «Назад» 41,113.5 (107×112); «Часовой пояс» 176,105 60/700;
 *              подзаголовок 176,181 32/600 G4
 *   панель     41,250 1321×160 — «Определять по сети» 88,279, описание 88,332.5,
 *              тумблер 1177,292.5
 *   раздел     «РОССИЯ» 59,450 30/700 G5
 *   панель     41,498 1321×920
 *   строка     город 88,y 42/700 G1; описание 88,y+53.5 32/600 G4;
 *              галочка 1282.7,y+27.1 42×43.9; разделитель 88,y+124 1228×2;
 *              шаг строк 152, первая на 527
 *   пагинация  желоб 614.3,1766 175.5×29.2, сегмент G3 (деталь B6)
 *
 * Список — 11 действующих поясов России (`core/Zones.kt`). В макете шесть строк и порядок
 * «Москва, Калининград, Самара, Екатеринбург, Новосибирск, Владивосток»: это текущий пояс
 * наверху, остальные — по возрастанию смещения. Так список и собирается ([rows]), поэтому
 * выбранный пояс всегда на первой странице; листается страницами по 6 (как B75 — места под
 * пагинацию макет не занимает, но ниже панели его хватает).
 *
 * **Запись — `persist.sys.timezone`** (`Sys.setZone`), а не `AlarmManager.setTimeZone`:
 * второму нужно право `SET_TIME_ZONE` уровня подписи платформы. Проверено на книге
 * 2026-11-19: свойство пишется из процесса оболочки, время переключается сразу.
 *
 * «Определять по сети» — тумблер **только для чтения**, как «Время по сети» на B41:
 * `Settings.Global.auto_time_zone` пишется только с WRITE_SECURE_SETTINGS. Он показывает
 * состояние системы, а не наше: на книге это 1. Касание по строке ничего не делает — своей
 * настройки у оболочки тут нет.
 *
 * Чего экран не делает: не переключает `auto_time_zone` и не гасит автоматику — если
 * прошивка когда-нибудь определит пояс по сети, она вправе переписать выбор (на книге
 * определения местоположения нет, поэтому ручной пояс держится).
 */
class SetTimeZoneScreen(act: EinkActivity) : Screen(act) {

    private val status = StatusBar(act, this, ink, icons)
    private val kit = SettingsKit(ink, icons)

    private var page = 0

    /** `null` — система не отдала значение; тумблер тогда рисуется выключенным. */
    private var autoZone: Boolean? = null

    /** Текущий пояс: идентификатор из свойства либо пояс процесса. */
    private var current = ""

    override fun render(c: Canvas) {
        ink.rect(c, 0f, 0f, W, H, T.BG)
        status.draw(c)
        kit.header(c, "Часовой пояс", "Дата, время, язык")

        kit.panel(c, 250f, 160f)
        kit.title(c, "Определять по сети", 279f)
        kit.desc(c, "Когда подключён Wi-Fi", 332.5f)
        kit.toggle(c, 292.5f, autoZone == true)

        kit.section(c, "РОССИЯ", 450f)
        kit.panel(c, 498f, PANEL_H)

        val rows = rows()
        val first = page * PER_PAGE
        val last = Math.min(rows.size, first + PER_PAGE)
        for (i in first until last) {
            row(c, rows[i], ROW_Y + (i - first) * ROW_STEP, i < last - 1)
        }
        pager(c, rows.size)
    }

    /** Строка: город, описание, галочка у текущего пояса, разделитель (если не последняя). */
    private fun row(c: Canvas, z: Zones.Row, y: Float, line: Boolean) {
        kit.title(c, z.city, y)
        kit.desc(c, ink.ellipsize(z.desc, TEXT_W, T.TS_BODY_S, 600), y + DESC_DY)
        if (z.id == current) icons.draw(c, "check", 1282.7f, y + 27.1f, 42f, 43.9f)
        if (line) kit.divider(c, y + DIVIDER_DY)
    }

    /**
     * Строки по макету: текущий пояс первым, остальные — по возрастанию смещения (в макете
     * за «Москвой» идут Калининград +2, Самара +4, Екатеринбург +5, Новосибирск +7,
     * Владивосток +10). Пояс, которого нет в списке (его выбрали в системных настройках),
     * в списке не отмечается: галочки просто нет ни у одной строки.
     */
    private fun rows(): List<Zones.Row> {
        val cur = Zones.ROWS.firstOrNull { it.id == current }
        val rest = Zones.ROWS.filter { it.id != current }
            .sortedBy { Zones.offsetMin(it.id) }
        return if (cur == null) rest else listOf(cur) + rest
    }

    /** Пагинация B6: желоб 175.5×29.2 с углублением и сегментом G3 по числу страниц. */
    private fun pager(c: Canvas, total: Int) {
        val n = pages(total)
        if (n < 2) return
        ink.inset(c, SEG_X, SEG_Y, SEG_W, SEG_H, T.R_TRACK)
        val w = SEG_TOTAL / n
        ink.roundRect(c, SEG_FILL_X + page * w, SEG_FILL_Y, w, SEG_FILL_H, 8.2265625f, T.G3)
    }

    private fun pages(total: Int) = Math.max(1, (total + PER_PAGE - 1) / PER_PAGE)

    private fun goPage(p: Int, total: Int) {
        val np = Math.max(0, Math.min(pages(total) - 1, p))
        if (np == page) return
        page = np
        redraw()
    }

    private fun refresh() {
        autoZone = act.sys.autoZone()
        current = act.sys.zone() ?: java.util.TimeZone.getDefault().id
    }

    /**
     * Поставить пояс касанием. Итог — в трассу; экран показывает прочитанное обратно значение,
     * а если система его не отдала — выбранное. Часы статус-бара обновляет повторный
     * [StatusBar.attach]: он перечитывает время, не переподписываясь на события.
     *
     * Новый пояс поднимается наверх списка, поэтому страница возвращается на первую — иначе
     * галочка оказалась бы не на виду.
     */
    private fun pick(id: String) {
        if (id == current) return
        val back = act.sys.setZone(id)
        current = back ?: id
        if (back == null) Trace.step("B71: zone FAIL $id")
        page = 0
        status.attach()
        redraw()
    }

    override fun onUp(x: Float, y: Float) {
        if (kit.isBack(x, y)) {
            act.pop()
            return
        }
        val rows = rows()
        // Пагинация: полоса под списком, левая половина — назад, правая — вперёд (как B6).
        if (y >= 1730f && y <= 1840f) {
            goPage(if (x < W / 2f) page - 1 else page + 1, rows.size)
            return
        }
        val first = page * PER_PAGE
        for (i in first until Math.min(rows.size, first + PER_PAGE)) {
            if (kit.inRow(x, y, ROW_Y + (i - first) * ROW_STEP, ROW_STEP)) {
                pick(rows[i].id)
                return
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
        refresh()
    }

    override fun onDetachedFromWindow() {
        status.detach()
        super.onDetachedFromWindow()
    }

    private companion object {
        /** Строк на странице: 6-я на 1287, её описание кончается на 1380 — панель до 1418. */
        private const val PER_PAGE = 6
        private const val PANEL_H = 920f
        /** Верх города первой строки (design/v5/B71.json). */
        private const val ROW_Y = 527f
        /** Шаг строк: 527 → 679 → 831 … */
        private const val ROW_STEP = 152f
        /** Описание ниже города: 580.5 − 527. */
        private const val DESC_DY = 53.5f
        /** Разделитель ниже города: 651 − 527. */
        private const val DIVIDER_DY = 124f
        /** Ширина описания: колонка до 1282.7, где стоит галочка. */
        private const val TEXT_W = 1170f

        // Пагинация — деталь B6: желоб 614.3,1766 175.5×29.2, дно 617,1771.5 170×21,
        // сегмент 619.7,1771.5 высотой 16.5.
        private const val SEG_X = 614.3f
        private const val SEG_Y = 1766f
        private const val SEG_W = 175.5f
        private const val SEG_H = 29.2f
        private const val SEG_FILL_X = 619.7f
        private const val SEG_FILL_Y = 1771.5f
        private const val SEG_FILL_H = 16.5f
        private const val SEG_TOTAL = 164.6f
    }
}

