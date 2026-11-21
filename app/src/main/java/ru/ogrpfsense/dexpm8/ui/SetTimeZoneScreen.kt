package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import ru.ogrpfsense.dexpm8.core.Zones

/**
 * B71 · Настройки · часовой пояс. Числа — из design/v5/B71.json, списком — сетка 2×12.
 *
 *   шапка      «Назад» 41,113.5 (107×112); «Часовой пояс» 176,105 60/700;
 *              подзаголовок 176,181 32/600 G4 — текущий пояс, «Москва, UTC+3»
 *   панель     41,250 1321×160 — «Определять по сети» 88,279, описание 88,332.5,
 *              тумблер 1177,292.5 (деталь B71, только чтение)
 *   панель     41,466 1321×1320 — 24 пояса сеткой 2×12, шаг строк 110
 *   строка     город 88,y 38/700 G1; описание 88,y+44 28/600 G4;
 *              разделитель 2 G11 на y+104; галочка 42×43.9 у правого края колонки
 *   колонки    левая 41…670 (текст с 88), правая 733…1362 (с 780)
 *
 * **24 пояса, а не 11 и не 418** (решение человека 2026-11-19). 418 зон tzdata дают 63
 * «часовых поведения», по круглым часам — ровно 24 (UTC−11 … UTC+12). Одна строка отвечает
 * за пояс: время верное для всех городов пояса, конкретный город выбрать нельзя. Порядок —
 * строго по смещению, поэтому сетка читается сверху вниз по колонке (левая UTC−11…UTC0,
 * правая UTC+1…UTC+12): вся Россия оказывается в правой колонке, её не надо искать.
 *
 * Пояса с переходом на летнее время идут отдельной строкой с подписью «летом UTC+N»: иначе
 * житель Лагоса (UTC+1 круглый год) и Берлина (UTC+1 зимой, UTC+2 летом) получили бы одну
 * строку, и летом у одного из них время было бы неверным. Нецелые пояса (Индия +5:30, Непал
 * +5:45, Chatham +12:45 …) в эти 24 не входят — решение 2026-11-19: не делаем.
 *
 * **Запись — `persist.sys.timezone`** (`Sys.setZone`), а не `AlarmManager.setTimeZone`:
 * второму нужно право `SET_TIME_ZONE` уровня подписи платформы. Проверено на книге
 * 2026-11-19: свойство пишется из процесса оболочки, время переключается сразу.
 *
 * «Определять по сети» — тумблер **только для чтения**, как «Время по сети» на B41:
 * `Settings.Global.auto_time_zone` пишется только с WRITE_SECURE_SETTINGS. Он показывает
 * состояние системы (на книге 1), касание по строке ничего не делает.
 */
class SetTimeZoneScreen(act: EinkActivity) : Screen(act) {

    private val status = StatusBar(act, this, ink, icons)
    private val kit = SettingsKit(ink, icons)

    /** `null` — система не отдала значение; тумблер тогда рисуется выключенным. */
    private var autoZone: Boolean? = null

    /** Текущий пояс: идентификатор из свойства либо пояс процесса. */
    private var current = ""

    override fun render(c: Canvas) {
        ink.rect(c, 0f, 0f, W, H, T.BG)
        status.draw(c)
        kit.header(c, "Часовой пояс", Zones.label(act.sys.zone()))

        kit.panel(c, 250f, 160f)
        kit.title(c, "Определять по сети", 279f)
        kit.desc(c, "Когда подключён Wi-Fi", 332.5f)
        kit.toggle(c, 292.5f, autoZone == true)

        kit.panel(c, PANEL_Y, PANEL_H)
        for (i in Zones.ROWS.indices) {
            val col = i / PER_COL
            val row = i % PER_COL
            // Разделитель — между строками колонки: под последней строкой каждой колонки его нет.
            row(c, Zones.ROWS[i], COL_X[col], ROW_Y + row * ROW_STEP, row < PER_COL - 1)
        }
    }

    /**
     * Строка пояса: город, описание, галочка у текущего пояса, разделитель (если не последняя
     * в колонке). [x] — левый край текста колонки.
     *
     * Галочка стоит у правого края **своей колонки**, а не у края панели: в правой это
     * 1282.7 из макета (правый край 1324.7, до панели 1362 ещё 37), в левой — зеркально
     * 622.2. Текст занимает всю колонку, а укорачивается только у строки с галочкой —
     * иначе длинное описание уехало бы под неё, а короткие стали бы обрезаться зря
     * («Петропавловск-Камчатский» так и терялся).
     */
    private fun row(c: Canvas, z: Zones.Row, x: Float, y: Float, line: Boolean) {
        val ci = if (x < COL_SPLIT) 0 else 1
        val cur = z.id == current
        val w = if (cur) CHECK_X[ci] - x - CHECK_PAD else COL_W[ci]
        ink.text(c, ink.ellipsize(z.city, w, T.TS_LABEL_L, 700), x,
            T.base(y, T.TS_LABEL_L, 1.25f), T.TS_LABEL_L, 700, T.G1)
        ink.text(c, ink.ellipsize(z.desc, w, T.TS_CAPTION, 600), x,
            T.base(y + DESC_DY, T.TS_CAPTION, 1.25f), T.TS_CAPTION, 600, T.G4)
        if (cur) icons.draw(c, "check", CHECK_X[ci], y + 13f, 42f, 43.9f)
        if (line) ink.rect(c, x, y + DIVIDER_DY, COL_W[ci], 2f, T.G11)
    }


    private fun refresh() {
        autoZone = act.sys.autoZone()
        current = act.sys.zone() ?: java.util.TimeZone.getDefault().id
    }

    /**
     * Поставить пояс касанием. Итог — в трассу; экран показывает прочитанное обратно значение,
     * а если система его не отдала — выбранное. Часы статус-бара обновляет повторный
     * [StatusBar.attach]: он перечитывает время, не переподписываясь на события.
     */
    private fun pick(id: String) {
        if (id == current) return
        val back = act.sys.setZone(id)
        current = back ?: id
        if (back == null) Trace.step("B71: zone FAIL $id")
        status.attach()
        redraw()
    }

    override fun onUp(x: Float, y: Float) {
        if (kit.isBack(x, y)) {
            act.pop()
            return
        }
        if (y < ROW_Y || y >= ROW_Y + PER_COL * ROW_STEP) return
        val row = ((y - ROW_Y) / ROW_STEP).toInt().coerceIn(0, PER_COL - 1)
        val i = (if (x < COL_SPLIT) 0 else 1) * PER_COL + row
        if (i in Zones.ROWS.indices) pick(Zones.ROWS[i].id)
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
        /** Строк в колонке: 24 пояса делятся на две колонки по 12. */
        private const val PER_COL = 12
        private const val PANEL_Y = 466f
        /** Панель: 12 строк × 106. */
        private const val PANEL_H = 1300f
        /** Верх первой строки: панель 466 + отступ 24. */
        private const val ROW_Y = 490f
        /** Шаг строк: город 38/700 + описание 28/600 + разделитель. */
        private const val ROW_STEP = 106f
        /** Описание ниже города: 44. */
        private const val DESC_DY = 44f
        /**
         * Разделитель: блок 0…79 по центру ячейки 106 — (106 − 2 + 79) / 2 = 91.5. Было 100:
         * до следующей строки оставалось 4 px, а над разделителем 21.
         */
        private const val DIVIDER_DY = 91.5f
        /** Левый край колонок: 88 и 780; граница касания — посередине (710.6). */
        private val COL_X = floatArrayOf(88f, 780f)
        private const val COL_SPLIT = 710.6f
        /** Ширина текста колонки до края панели: 88…660 и 780…1352. */
        private val COL_W = floatArrayOf(572f, 572f)
        /** Левый край галочки в колонке: 622.2 в левой (зеркально макету), 1282.7 в правой. */
        private val CHECK_X = floatArrayOf(622.2f, 1282.7f)
        /** Зазор между текстом и галочкой у строки текущего пояса. */
        private const val CHECK_PAD = 24f
    }
}
