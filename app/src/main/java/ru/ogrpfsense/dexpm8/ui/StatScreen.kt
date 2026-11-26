package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import ru.ogrpfsense.dexpm8.core.LibraryDb

/**
 * B32 · «Статистика чтения» (п. 22). Числа — `design/v5/B32.json`.
 *
 *   шапка        «Назад» 41,113.5 107×112; «Статистика» 176,105 Manrope/60/700;
 *                подзаголовок 176,181 32/600 G4 — «Октябрь 2026» или «3–9 ноября 2026»
 *   периоды      желоб 44,249 1316×88 (r 44), сегмент 327×83, подпись 34/600 G5, активная —
 *                700 G1 (как `SettingsKit.segments`, но желоб шире: 1316 против 1221)
 *   панель итогов 41,389 1321×260: три числа 110/700 и подписи 32/600 G4, разделители 2×180 G11
 *   «МИНУТЫ ПО ДНЯМ»  59,680 30/700 G5
 *   график       панель 41,728 1321×530; поле 88,770 1222×330 (вдавленное, дно G12);
 *                столбцы 27.5 шириной, шаг 37.5 от 122, низ 1070, высота до 260
 *   ось          число месяца 28/600 G5 под каждым седьмым столбцом
 *   сводка       88,1176 30/600 G4 — «В среднем 74 мин в день · лучший день 21 октября»
 *   «ДОЛЬШЕ ВСЕГО» 59,1320 30/700 G5; панель книг 41,1368 1321×430, две строки
 *
 * Данные — база v13: `reading` (секунды и страницы по дням) и `books.finished`. Периодов четыре,
 * как в макете: неделя (7 дней), месяц (календарный), год (365 дней от сегодня назад) и «Всё
 * время» (от первого дня с чтением). Столбец — один день во всех периодах, кроме года: 365 дней
 * в 31 столбец не влезают, поэтому год — по месяцам (12 столбцов, подпись — месяц).
 *
 * Отступления от макета, о которых стоит знать:
 *  - «подряд без пропусков» — серия дней до сегодня, в которой было чтение;
 *  - «лучший день» и «в среднем» — по дням с чтением, а не по всем дням календаря;
 *  - у книги в «Дольше всего» вместо «12 ч 40 мин» — «Автор · 45 %» и дорожка прогресса:
 *    прогресс у нас есть, а времени до конца книги — нет.
 */
class StatScreen(act: EinkActivity) : Screen(act) {

    private val db = act.db
    private val status = StatusBar(act, this, ink, icons)
    private val kit = SettingsKit(ink, icons)
    private val lit = T.literata(ink.res, 600)

    /** Период: 0 — неделя, 1 — месяц, 2 — год, 3 — всё время. */
    private var period = PERIOD_MONTH

    private var from = 0
    private var to = 0
    private var stats = LibraryDb.Stats()
    private var books = ArrayList<LibraryDb.TimeBook>()
    private var streak = 0

    override fun render(c: Canvas) {
        ink.rect(c, 0f, 0f, W, H, T.BG)
        header(c)
        periods(c)
        totals(c)
        section(c, "МИНУТЫ ПО ДНЯМ", 680f)
        chart(c)
        summary(c)
        section(c, "ДОЛЬШЕ ВСЕГО", 1320f)
        booksPanel(c)
        status.draw(c)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
        // Первый кадр рисуется и так, поэтому без redraw(): данные нужны до него.
        load()
    }

    override fun onDetachedFromWindow() {
        status.detach()
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------ данные

    /** Пересчитать период и сводку. Только UI-поток: база читается на нём же. */
    private fun load() {
        val today = LibraryDb.day()
        when (period) {
            PERIOD_WEEK -> {
                to = today
                from = LibraryDb.dayMinus(today, 6)
            }
            PERIOD_MONTH -> {
                from = LibraryDb.monthStart(today)
                to = today
            }
            PERIOD_YEAR -> {
                // Год — 12 календарных месяцев, последний из них текущий: столбцов ровно 12,
                // и каждый месяц попадает в свой (365 дней назад месяцем не считаются).
                from = LibraryDb.monthStart(today, 11)
                to = today
            }
            else -> {
                // «Всё время»: от первого дня статистики до сегодня; записей нет — сегодня.
                from = readingStart() ?: today
                to = today
            }
        }
        if (from > to) from = to
        stats = try {
            db.stats(from, to)
        } catch (t: Throwable) {
            Trace.step("b32: stats FAIL ${t.javaClass.simpleName}: ${t.message}")
            LibraryDb.Stats()
        }
        if (period == PERIOD_ALL) {
            // «Всё время»: дочитано за всё время, а не за отрезок.
            stats.finished = db.finishedBetween(0, today)
        }
        books = try {
            db.topBooks(from, to, BOOK_ROWS)
        } catch (t: Throwable) {
            Trace.step("b32: topBooks FAIL ${t.javaClass.simpleName}: ${t.message}")
            ArrayList()
        }
        streak = try {
            db.streak(today)
        } catch (t: Throwable) {
            0
        }
    }

    /** Первый день статистики, `null` — ещё ничего не читали. */
    private fun readingStart(): Int? = try {
        db.readingStart()
    } catch (t: Throwable) {
        null
    }

    /** Данные заново и кадр — при показе экрана и после смены периода. */
    private fun reload() {
        load()
        if (parent != null) redraw()
    }

    // ------------------------------------------------------------------ шапка

    private fun header(c: Canvas) {
        kit.header(c, "Статистика", subtitle())
    }

    /**
     * Подзаголовок, как в макете: месяц — «Октябрь 2026», неделя — «3–9 ноября 2026»,
     * год — «Ноябрь 2025 — ноябрь 2026», всё время — «с марта 2024».
     */
    private fun subtitle(): String {
        val a = LibraryDb.ymd(from)
        val b = LibraryDb.ymd(to)
        val ma = monthNom(a[1] - 1)
        val mb = monthNom(b[1] - 1)
        return when (period) {
            PERIOD_MONTH -> "$ma ${a[0]}"
            PERIOD_WEEK -> if (a[1] == b[1] && a[0] == b[0])
                "${a[2]}–${b[2]} ${genitive(b[1] - 1)} ${b[0]}"
            else "${a[2]} ${genitive(a[1] - 1)} — ${b[2]} ${genitive(b[1] - 1)} ${b[0]}"
            PERIOD_YEAR -> "$ma ${a[0]} — $mb ${b[0]}"
            else -> "с ${genitive(a[1] - 1)} ${a[0]}"
        }
    }

    // ------------------------------------------------------------------ периоды

    /** Желоб сегментов 1316×88 (макет) — шире общего `SettingsKit.segments` (1221). */
    private fun periods(c: Canvas) {
        ink.inset(c, 44f, 249f, 1316f, 88f, 44f, T.G11)
        val step = PERIOD_STEP
        for (i in PERIOD_LABELS.indices) {
            val x = 47f + step * i
            val on = i == period
            if (on) ink.convex(c, x, 253f, step - 1.4f, 83f, 41f)
            ink.textCenter(c, PERIOD_LABELS[i], x, step - 7f,
                T.base(if (on) 271f else 270f, 34f, 1.25f), 34f, if (on) 700 else 600,
                if (on) T.G1 else T.G5)
        }
    }

    // ------------------------------------------------------------------ итоги

    private fun totals(c: Canvas) {
        ink.convex(c, 41f, 389f, 1321f, 260f, T.R_TILE)
        // Число — в часах, как в макете; у короткой недели часов нет — показываем минуты.
        val hours = stats.totalSec / 3600L
        val word = when (period) {
            PERIOD_WEEK -> "за неделю"
            PERIOD_MONTH -> "в " + prepositional(LibraryDb.ymd(from)[1] - 1)
            PERIOD_YEAR -> "за год"
            else -> "всего"
        }
        if (hours > 0) bigNumber(c, 94f, hours.toString(), "ч", "читали $word")
        else bigNumber(c, 94f, (stats.totalSec / 60L).toString(), "мин", "читали $word")
        divider(c, 482.7f)
        bigNumber(c, 532.7f, stats.finished.toString(), null, "книг закончено")
        divider(c, 921.3f)
        bigNumber(c, 971.3f, streak.toString(), "дн.", "подряд без пропусков")
    }

    /** Число 110/700 и единица 36/700 у его базовой линии, подпись 32/600 G4 под ними. */
    private fun bigNumber(c: Canvas, x: Float, n: String, unit: String?, caption: String) {
        ink.text(c, n, x, T.base(412f, 110f, 1.1f), 110f, 700, T.G1)
        if (unit != null) {
            val ux = x + ink.measure(n, 110f, 700) + 8f
            ink.text(c, unit, ux, T.base(481.5f, 36f, 1.25f), 36f, 700, T.G1)
        }
        ink.text(c, ink.ellipsize(caption, 358.7f, T.TS_BODY_S, 600), x,
            T.base(547f, T.TS_BODY_S, 1.3f), T.TS_BODY_S, 600, T.G4)
    }

    private fun divider(c: Canvas, x: Float) = ink.rect(c, x, 427f, 2f, 180f, T.G11)

    private fun section(c: Canvas, label: String, y: Float) =
        ink.text(c, label, 59f, T.base(y, T.TS_META, 1.25f), T.TS_META, 700, T.G5)

    // ------------------------------------------------------------------ график

    /**
     * Столбцы по дням (или по месяцам у года). Поле 88,770 1222×330 вдавленное, дно G12;
     * столбцы — 27.5 шириной с шагом 37.5 от x 122, низ 1070. Высота — доля от самого длинного
     * столбца периода, не меньше [BAR_MIN]: день с нулём рисуется короткой засечкой (в макете
     * так у дней без чтения), иначе пустой день исчезал бы совсем.
     */
    private fun chart(c: Canvas) {
        ink.convex(c, 41f, 728f, 1321f, 530f, T.R_TILE)
        ink.inset(c, 88f, 770f, 1222f, 330f, 30f, T.G12)

        val n = if (period == PERIOD_YEAR) 12 else daysInPeriod()
        val values = LongArray(n)
        if (period == PERIOD_YEAR) {
            // Год — по месяцам: сумма секунд каждого месяца отрезка.
            for ((day, sec) in stats.sec) {
                val i = monthIndex(day)
                if (i in 0 until n) values[i] += sec
            }
        } else {
            for ((day, sec) in stats.sec) {
                // Индекс — от левого края: 0 — самый старый день периода, n−1 — сегодня.
                // `daysBack` считает наоборот (0 — сегодня), поэтому переворачиваем: иначе
                // столбик сегодняшнего дня вставал под подписью самого старого числа
                // (было: 20 ноября под «16» в неделе). Месяц и год индексуются прямо.
                val i = if (period == PERIOD_MONTH) LibraryDb.ymd(day)[2] - 1
                else (n - 1) - daysBack(day)
                if (i in 0 until n) values[i] += sec
            }
        }
        val max = values.maxOrNull() ?: 0L
        val step = if (n > 1) (1246.5f - 122f) / (n - 1) else 0f
        for (i in 0 until n) {
            val v = values[i]
            val h = if (max <= 0L) BAR_MIN
            else (BAR_MIN + (BAR_MAX - BAR_MIN) * (v.toFloat() / max)).coerceAtLeast(BAR_MIN)
            // День 1 — у левого края, последний — у правого (как в макете: 122 … 1246.5).
            val x = 122f + step * i
            // Последний столбец — «сегодня» (или последний день месяца), он тёмный в макете.
            val color = if (i == n - 1) T.G1 else T.G4
            ink.roundRect(c, x, BAR_BOTTOM - h, BAR_W, h, 4f, color)
        }
        // Ось: число месяца под каждым седьмым столбцом (у года — месяцы через один).
        // Подпись — по центру столбца: в макете рамка 60 шириной, её центр = центр столбца.
        for ((i, s) in axisLabels(n)) {
            val x = 122f + step * i + BAR_W / 2f - AXIS_W / 2f
            ink.textCenter(c, s, x, AXIS_W, T.base(1116f, 28f, 1.25f), 28f, 600, T.G5)
        }
    }

    /** Подписи оси: индекс столбца → строка. У года — месяцы через один, иначе день месяца. */
    private fun axisLabels(n: Int): ArrayList<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>(8)
        if (period == PERIOD_YEAR) {
            val start = LibraryDb.ymd(from)
            for (i in 0 until n step 2) {
                out.add(i to MONTH_SHORT[(start[1] - 1 + i) % 12])
            }
            // Последний столбец — текущий месяц; он один такой у правого края.
            out.add(n - 1 to MONTH_SHORT[(start[1] - 1 + n - 1) % 12])
            return out
        }
        if (period == PERIOD_MONTH) {
            // Каждое седьмое число и последний день месяца — как «1, 8, 15, 22, 31» в макете.
            var i = 0
            while (i < n) {
                out.add(i to (i + 1).toString())
                i += 7
            }
            if (out.last().first != n - 1) out.add(n - 1 to n.toString())
            return out
        }
        // Неделя и «всё время»: подпись — число месяца, у «всего времени» через неделю.
        val stepDays = if (period == PERIOD_WEEK) 1 else 7
        // «Всё время» шагает через границу месяца: без названия месяца ось читается как случайный
        // набор чисел (22, 29 — октябрь, 5, 12, 19 — ноябрь). У недели месяц в подзаголовке, там
        // лишний текст только мешает.
        val withMonth = period != PERIOD_WEEK
        var i = 0
        var prevMonth = -1
        while (i < n) {
            val day = LibraryDb.dayMinus(to, n - 1 - i)
            out.add(i to dayLabel(day, prevMonth, withMonth))
            prevMonth = LibraryDb.ymd(day)[1] - 1
            i += stepDays
        }
        if (out.lastOrNull()?.first != n - 1) {
            out.add(n - 1 to dayLabel(to, prevMonth, withMonth))
        }
        return out
    }

    /**
     * Подпись дня на оси: «22 окт» — у первого столбца и там, где сменился месяц ([prevMonth]
     * — месяц предыдущей подписи, −1 в начале); иначе просто число.
     */
    private fun dayLabel(day: Int, prevMonth: Int, withMonth: Boolean): String {
        val a = LibraryDb.ymd(day)
        val num = a[2].toString()
        return if (withMonth && a[1] - 1 != prevMonth) "$num ${MONTH_SHORT[a[1] - 1]}" else num
    }

    /** Сколько дней в периоде (для столбцов): месяц — по числу дней, неделя — 7, всё время — до 31. */
    private fun daysInPeriod(): Int = when (period) {
        PERIOD_WEEK -> 7
        PERIOD_MONTH -> LibraryDb.ymd(to)[2]
        else -> 31
    }

    /** Индекс месяца в отрезке года (0…11) для дня `ГГГГММДД`. */
    private fun monthIndex(day: Int): Int {
        val a = LibraryDb.ymd(from)
        val b = LibraryDb.ymd(day)
        return (b[0] - a[0]) * 12 + (b[1] - a[1])
    }

    /** Сколько дней назад от [to] день [day] (0 — сегодня); больше 31 — за пределами столбцов. */
    private fun daysBack(day: Int): Int {
        var n = 0
        var d = to
        while (d > day && n <= 400) {
            d = LibraryDb.dayMinus(d, 1)
            n++
        }
        return n
    }

    // ------------------------------------------------------------------ сводка

    /**
     * «В среднем 74 мин в день · лучший день 21 октября». Среднее — по дням, в которые читали:
     * делить на пустые дни календаря значит показывать минуты, которых не было.
     */
    private fun summary(c: Canvas) {
        if (stats.totalSec <= 0L) {
            ink.text(c, "Статистика начнётся, когда вы прочитаете первую книгу", 88f,
                T.base(1176f, T.TS_META, 1.25f), T.TS_META, 600, T.G4)
            return
        }
        val active = stats.sec.size.toLong().coerceAtLeast(1L)
        val avgMin = stats.totalSec / active / 60L
        val best = if (stats.bestDay > 0) {
            val d = LibraryDb.ymd(stats.bestDay)
            " · лучший день ${d[2]} ${genitive(d[1] - 1)}"
        } else ""
        val line = "В среднем $avgMin мин в день$best"
        ink.text(c, ink.ellipsize(line, 1228f, T.TS_META, 600), 88f,
            T.base(1176f, T.TS_META, 1.25f), T.TS_META, 600, T.G4)
    }

    // ------------------------------------------------------------------ книги

    /**
     * Панель 41,1368 1321×430: две строки по 164 и 132 высотой. Строка: миниатюра 91×132,
     * название Literata/42/600, «Автор · NN %», дорожка 238×22 справа от центра и процент
     * 34/700 по правому краю 1316.
     */
    private fun booksPanel(c: Canvas) {
        ink.convex(c, 41f, 1368f, 1321f, 430f, T.R_TILE)
        if (books.isEmpty()) {
            ink.text(c, "Пока нечего показать", 88f, T.base(1401f, T.TS_META, 1.25f),
                T.TS_META, 600, T.G4)
            return
        }
        // Первая строка выше (164) — в макете у неё две строки подписи; дальше шаг 211.
        var y = 1401f
        for ((i, b) in books.withIndex()) {
            bookRow(c, b, y, i < books.size - 1)
            y += if (i == 0) 211f else 132f
        }
    }

    private fun bookRow(c: Canvas, b: LibraryDb.TimeBook, y: Float, divider: Boolean) {
        // Миниатюра: гнездо G12 с корешком G7 — как в списке аудиокниг B33.
        ink.roundRect(c, 88f, y, 91f, 132f, 11f, T.G12)
        ink.roundRect(c, 88f, y, 13f, 132f, 11f, T.G7)
        ink.text(c, ink.ellipsize(b.title, 700f, 42f, 600, lit), 219f,
            T.base(y + 14f, 42f, 1.15f, true), 42f, 600, T.G1, lit)
        val author = b.author ?: "Автор неизвестен"
        val pct = b.progress.coerceIn(0, 100)
        val meta = "$author · $pct %"
        ink.text(c, ink.ellipsize(meta, 700f, T.TS_BODY_S, 600), 219f,
            T.base(y + 70f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        // Дорожка 958,y+53 238×22: дно G12, заливка G3 по проценту (как B1 и B2).
        ink.inset(c, 958f, y + 53f, 238f, 22f, 11f, T.G12)
        val fill = 233f * pct / 100f
        if (fill >= 14f) ink.roundRect(c, 961f, y + 58f, fill, 14f, 7f, T.G3)
        val ps = "$pct%"
        ink.text(c, ps, 1316f - ink.measure(ps, T.TS_BODY, 700), T.base(y + 44.6f, T.TS_BODY, 1.2f),
            T.TS_BODY, 700, T.G1)
        // Блок 14…110 (96 px) по центру между соседними строками: 14 + (211 − 2 + 96) / 2 = 166.5.
        if (divider) ink.rect(c, 219f, y + 166f, 1097f, 2f, T.G11)
    }

    // ------------------------------------------------------------------ ввод

    override fun onUp(x: Float, y: Float) {
        if (kit.isBack(x, y)) {
            act.pop()
            return
        }
        // Периоды: тот же желоб, что нарисован, зона — на 10 шире, как у SettingsKit.
        if (y >= 239f && y <= 354f && x >= 44f && x <= 1360f) {
            val i = ((x - 47f) / PERIOD_STEP).toInt().coerceIn(0, PERIOD_LABELS.size - 1)
            if (i != period) {
                period = i
                reload()
            }
        }
    }

    private companion object {
        const val PERIOD_WEEK = 0
        const val PERIOD_MONTH = 1
        const val PERIOD_YEAR = 2
        const val PERIOD_ALL = 3

        val PERIOD_LABELS = arrayOf("Неделя", "Месяц", "Год", "Всё время")

        /** Шаг сегмента: желоб 1313.6 / 4. */
        const val PERIOD_STEP = 1313.6f / 4f

        /** Сколько строк «Дольше всего» на экране: две в макете, панель 430 высотой. */
        const val BOOK_ROWS = 2

        // Столбцы графика: ширина 27.5, низ 1070 (поле 770…1100), высота засечки и потолок.
        const val BAR_W = 27.5f
        const val BAR_BOTTOM = 1070f
        const val BAR_MIN = 6f
        const val BAR_MAX = 260f

        /** Рамка подписи оси (макет: 60×35, текст по центру) — центр совпадает с центром столбца. */
        const val AXIS_W = 60f

        /** Месяцы в родительном падеже: «21 октября», «3–9 ноября». */
        val MONTHS = arrayOf(
            "января", "февраля", "марта", "апреля", "мая", "июня",
            "июля", "августа", "сентября", "октября", "ноября", "декабря",
        )

        /** Месяцы в именительном: «Ноябрь 2026» в подзаголовке. */
        val MONTHS_NOM = arrayOf(
            "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
            "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь",
        )

        /** Месяцы в предложном: «читали в ноябре». */
        val MONTHS_PREP = arrayOf(
            "январе", "феврале", "марте", "апреле", "мае", "июне",
            "июле", "августе", "сентябре", "октябре", "ноябре", "декабре",
        )

        /** Короткие подписи оси у года: «янв», «мар», «май»… */
        val MONTH_SHORT = arrayOf(
            "янв", "фев", "мар", "апр", "май", "июн",
            "июл", "авг", "сен", "окт", "ноя", "дек",
        )

        /** «Октябрь» — именительный для подзаголовка «Октябрь 2026». */
        fun monthNom(i: Int): String = MONTHS_NOM[i.coerceIn(0, 11)]

        /** «октября» — родительный, для «3–9 ноября» и «лучший день 21 октября». */
        fun genitive(i: Int): String = MONTHS[i.coerceIn(0, 11)]

        /** «октябре» — предложный, для «читали в октябре». */
        fun prepositional(i: Int): String = MONTHS_PREP[i.coerceIn(0, 11)]
    }
}
