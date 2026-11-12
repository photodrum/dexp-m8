package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import java.util.Locale
import ru.ogrpfsense.dexpm8.core.WifiShare

/**
 * B26 · Загрузка книг по Wi-Fi. Числа — из design/v5/B26.json.
 *
 *   шапка      «Назад» 41,113.5 107×112 (иконка 87.9,155.3 12.3×26.6); заголовок 176,105 60/700;
 *      подзаголовок 176,181 32/600 G4 («Сеть «Домашняя» · сервер работает»)
 *   панель     41,246 1321×530 (выпуклая, r 51)
 *   значок     гнездо 88,290 120×120 (r 60, дно G12), «wifi» 132.3,333.9 37.5×34.6
 *   шаг 1      248,300 42/700; пояснение 248,356 32/600 G4
 *   поле       вдавленное 88,460 1222×150 (r 40), адрес 91,490.5 72/700 по центру
 *   шаг 2      88,650 1222×90 32/600 G4 — что и куда перетаскивать
 *   раздел     59,812 30/700 G5 «ПРИНЯТО · 3 ИЗ 4»
 *   панель     41,862 1321×710; строки 88,y шагом 172 (в макете 901.5, 1080, 1252, 1424.5 —
 *      разброс ±6.5), гнездо 110×110 r26 с форматом 28/800 G3 в 91,y+37.7 105×34,
 *      имя 42/600 в 237,y+5, описание 32/600 G4 в 237,y+58, разделитель 237,y+147 1079×2
 *   принят     «Готово» 32/700 справа до 1264.7 и «check» 1291.5,y+45.2 24.5×18.4
 *   в приёме   дорожка вдавленная 958,y+42 238×22 (дно G12), заполнение 961,y+47 233×14 r7 G3,
 *      «64%» 34/700 справа до 1316,y+32.5
 *   кнопки     «Остановить» выпуклая 41,1708 505×112; «Готово» тёмная 581,1693 781×142
 *
 * Отступления от макета (вопросы в `DESIGN_QUESTIONS.md`):
 *  - в панели четыре строки и нет пагинации (в макете её тоже нет): показываем последние четыре
 *    файла, общее число — в надписи раздела;
 *  - пустой список — карточка B44 «Пока ничего не принято» (на B26 такого состояния нет);
 *  - левая кнопка переключает сервер: «Остановить» / «Запустить» (вида «сервер остановлен»
 *    в макете нет);
 *  - сети нет — в поле «Нет сети Wi-Fi» вместо адреса.
 *
 * Сервер живёт, пока открыт экран: поднимается в [onAttachedToWindow], глохнет в
 * [onDetachedFromWindow]. События приходят из потока сервера — экран переносит их в UI через `post`
 * и перерисовывается только на них: на E-Ink каждый кадр — мерцание.
 */
class WifiUploadScreen(act: EinkActivity) : Screen(act) {

    /** Принятый или принимаемый файл — строка панели. */
    private class Item(
        val name: String,
        val format: String,
        val size: Long,
        /** Получено байт. */
        var done: Long,
        /** [ST_TAKE] — идёт приём, [ST_DONE] — принят, [ST_FAIL] — ошибка. */
        var state: Int,
        var why: String?,
    )

    private val status = StatusBar(act, this, ink, icons)
    private val empty = EmptyState(ink, icons)
    private val share = WifiShare(act, act.db)

    private val items = ArrayList<Item>(16)

    /** Строка под шапкой: сеть и состояние сервера. */
    private var subtitle = "Сервер не запущен"

    private val listener = object : WifiShare.Listener {
        override fun onServer(port: Int, why: String?) {
            post {
                subtitle = if (why != null) "Сервер не запустился: $why" else subOf(port)
                redraw()
            }
        }

        override fun onFileStart(name: String, format: String, size: Long) {
            post {
                items.add(Item(name, format, size, 0L, ST_TAKE, null))
                if (items.size > MAX_KEEP) items.removeAt(0)
                redraw()
            }
        }

        override fun onFileProgress(name: String, done: Long) {
            post {
                val it = find(name) ?: return@post
                val was = pct(it)
                it.done = done
                // Перерисовка только на заметном шаге: 256 КБ на E-Ink — мерцание впустую.
                if (pct(it) / 10 != was / 10) redraw()
            }
        }

        override fun onFileDone(name: String, ok: Boolean, why: String?) {
            post {
                val it = find(name)
                if (it != null) {
                    it.state = if (ok) ST_DONE else ST_FAIL
                    it.why = why
                    if (ok) it.done = it.size
                } else {
                    // Отказ до приёма (не книга, нет места): строка всё равно нужна — видно причину.
                    items.add(Item(name, formatOf(name), 0L, 0L, ST_FAIL, why))
                    if (items.size > MAX_KEEP) items.removeAt(0)
                }
                redraw()
            }
        }
    }

    private companion object {
        const val ROUND = 51.2f
        const val R_NEST = 60f
        const val R_FIELD = 40f
        const val R_CELL = 26f
        const val R_TRACK = 11f
        const val R_FILL = 7f

        const val PANEL_Y = 246f
        const val PANEL_H = 530f
        const val LIST_Y = 862f
        const val LIST_H = 710f
        const val ROW_Y0 = 901.5f
        const val ROW_STEP = 172f
        const val ROWS = 4
        const val DIVIDER_DY = 147f
        const val TEXT_X = 237f

        /** Имя обрезаем до дорожки и «Готово»: в макете текст кончается до 1064.7. */
        const val TEXT_W = 790f
        const val STATUS_RIGHT = 1264.7f
        const val TRACK_X = 958f
        const val TRACK_W = 238f
        const val TRACK_H = 22f
        const val FILL_X = 961f
        const val FILL_DY = 47f
        const val FILL_MAX = 233f
        const val PERCENT_RIGHT = 1316f

        const val ST_TAKE = 0
        const val ST_DONE = 1
        const val ST_FAIL = 2

        /** Сколько строк держим в памяти: на экране всё равно четыре. */
        const val MAX_KEEP = 64
    }

    // --------------------------------------------------------------- данные

    private fun find(name: String): Item? {
        for (i in items.indices.reversed()) if (items[i].name == name) return items[i]
        return null
    }

    private fun formatOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(dot + 1).uppercase(Locale.ROOT) else "ФАЙЛ"
    }

    private fun pct(i: Item): Int =
        if (i.size <= 0L) 0 else Math.min(100, (i.done * 100 / i.size).toInt())

    /** Сколько готово — «3» из «ПРИНЯТО · 3 ИЗ 4». */
    private fun doneCount(): Int {
        var n = 0
        for (i in items) if (i.state == ST_DONE) n++
        return n
    }

    private fun subOf(port: Int): String {
        val ssid = share.ssid()
        return if (ssid.isEmpty()) "Сервер работает · порт $port"
        else "Сеть «$ssid» · сервер работает"
    }

    private fun addressText(): String {
        if (!share.running) return "Сервер остановлен"
        val a = share.address()
        return if (a.isEmpty()) "Нет сети Wi-Fi" else a
    }

    private fun sizeOf(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format(Locale("ru"), "%.1f ГБ", bytes / 1e9)
        bytes >= 1_000_000L -> {
            val s = String.format(Locale("ru"), "%.1f", bytes / 1e6)
            (if (s.endsWith(",0")) s.dropLast(2) else s) + " МБ"
        }
        else -> "${Math.max(1L, Math.round(bytes / 1e3))} КБ"
    }

    // --------------------------------------------------------------- рисование

    override fun render(c: Canvas) {
        ink.rect(c, 0f, 0f, W, H, T.BG)
        header(c)
        panel(c)
        listPanel(c)
        buttons(c)
        status.draw(c)
    }

    private fun header(c: Canvas) {
        ink.convex(c, 41f, 113.5f, 107f, 112f, ROUND)
        icons.draw(c, "back@12.3x26.6", 87.9f, 155.3f, 12.3f, 26.6f)
        ink.text(c, "Загрузка по Wi-Fi", 176f, T.base(105f, T.TS_TITLE, 1.25f), T.TS_TITLE, 700, T.G1)
        ink.text(c, ink.ellipsize(subtitle, 1100f, T.TS_BODY_S, 600), 176f,
            T.base(181f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
    }

    private fun panel(c: Canvas) {
        ink.convex(c, 41f, PANEL_Y, 1321f, PANEL_H, T.R_TILE)

        ink.inset(c, 88f, 290f, 120f, 120f, R_NEST, T.G12)
        icons.draw(c, "wifi@37.5x34.6", 132.3f, 333.9f, 37.5f, 34.6f)

        ink.text(c, "Откройте адрес в браузере", 248f, T.base(300f, T.TS_ACTION, 1.25f),
            T.TS_ACTION, 700, T.G1)
        ink.text(c, "на компьютере или телефоне в той же сети", 248f,
            T.base(356f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)

        ink.inset(c, 88f, 460f, 1222f, 150f, R_FIELD, T.G12)
        ink.textCenter(c, ink.ellipsize(addressText(), 1150f, 72f, 700), 91f, 1216f,
            T.base(490.5f, 72f, 1.2f), 72f, 700, T.G1)

        // В макете шаг 2 — две строки 32/600 в рамке 88,650 1222×90 (перенос после «TXT —»).
        ink.text(c, "Перетащите в окно файлы FB2, EPUB, DjVu, MOBI или TXT —", 88f,
            T.base(650f, T.TS_BODY_S, 1.4f), T.TS_BODY_S, 600, T.G4)
        ink.text(c, "они появятся в библиотеке.", 88f,
            T.base(695f, T.TS_BODY_S, 1.4f), T.TS_BODY_S, 600, T.G4)
    }

    private fun listPanel(c: Canvas) {
        val head = if (items.isEmpty()) "ПРИНЯТО · 0"
        else "ПРИНЯТО · ${doneCount()} ИЗ ${items.size}"
        ink.text(c, head, 59f, T.base(812f, T.TS_META, 1.25f), T.TS_META, 700, T.G5)
        ink.convex(c, 41f, LIST_Y, 1321f, LIST_H, T.R_TILE)

        if (items.isEmpty()) {
            // «Ничего не принято» на B26 нет — карточка B44 (решение 9.7).
            // Панель 862…1572, карточка 425×410 по центру: верх 862 + (710 − 410) / 2 = 1012.
            empty.card(c, 489.5f, 1012f, EmptyState.ICON_DOWNLOAD, 34.7f, 40.1f,
                "Пусто",
                "Отправьте книги из браузера — они появятся здесь")
            return
        }

        // Четыре последних: новые снизу, как в макете (в приёме — нижняя строка).
        val from = Math.max(0, items.size - ROWS)
        for (i in from until items.size) {
            val y = ROW_Y0 + (i - from) * ROW_STEP
            row(c, items[i], y)
            if (i < items.size - 1) ink.rect(c, TEXT_X, y + DIVIDER_DY, 1079f, 2f, T.G11)
        }
    }

    private fun row(c: Canvas, it: Item, y: Float) {
        ink.inset(c, 88f, y, 110f, 110f, R_CELL, T.G12)
        ink.textCenter(c, it.format.uppercase(Locale.ROOT), 91f, 105f,
            T.base(y + 37.7f, T.TS_CAPTION, 1.21f), T.TS_CAPTION, 800, T.G3)

        ink.text(c, ink.ellipsize(it.name, TEXT_W, T.TS_ACTION, 600), TEXT_X,
            T.base(y + 5f, T.TS_ACTION, 1.25f), T.TS_ACTION, 600, T.G1)

        when (it.state) {
            ST_TAKE -> {
                ink.text(c, "${sizeOf(it.size)} · идёт приём", TEXT_X,
                    T.base(y + 58f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
                val p = pct(it)
                // Дорожка: вдавленная 238×22 с дном G12, заполнение 233×14 от 961,y+47.
                ink.inset(c, TRACK_X, y + 42f, TRACK_W, TRACK_H, R_TRACK, T.G12)
                if (p > 0) {
                    ink.roundRect(c, FILL_X, y + FILL_DY, FILL_MAX * p / 100f, 14f, R_FILL, T.G3)
                }
                val s = "$p%"
                ink.text(c, s, PERCENT_RIGHT - ink.measure(s, T.TS_BODY, 700),
                    T.base(y + 32.5f, T.TS_BODY, 1.2f), T.TS_BODY, 700, T.G1)
            }

            ST_DONE -> {
                ink.text(c, sizeOf(it.size), TEXT_X,
                    T.base(y + 58f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
                val s = "Готово"
                ink.text(c, s, STATUS_RIGHT - ink.measure(s, T.TS_BODY_S, 700),
                    T.base(y + 32.9f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 700, T.G4)
                icons.draw(c, "check@24.5x18.4", 1291.5f, y + 45.2f, 24.5f, 18.4f)
            }

            else -> {
                val s = (it.why ?: "не принят") + " · не принят"
                ink.text(c, ink.ellipsize(s, TEXT_W, T.TS_BODY_S, 600), TEXT_X,
                    T.base(y + 58f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
            }
        }
    }

    private fun buttons(c: Canvas) {
        ink.convex(c, 41f, 1708f, 505f, 112f, 56f)
        val label = if (share.running) "Остановить" else "Запустить"
        ink.textCenter(c, label, 44f, 500f, T.base(1738.2f, T.TS_LABEL_L, 1.25f),
            T.TS_LABEL_L, 700, T.G1)

        ink.convexDark(c, 581f, 1693f, 781f, 142f, T.R_BUTTON)
        ink.textCenter(c, "Готово", 584f, 776f, T.base(1735.7f, T.TS_ACTION, 1.25f),
            T.TS_ACTION, 700, T.G15)
    }

    // --------------------------------------------------------------- ввод

    private fun inRect(x: Float, y: Float, rx: Float, ry: Float, rw: Float, rh: Float) =
        x >= rx && x <= rx + rw && y >= ry && y <= ry + rh

    override fun onUp(x: Float, y: Float) {
        if (inRect(x, y, 41f, 113.5f, 107f, 112f)) {
            act.pop()
            return
        }
        if (inRect(x, y, 41f, 1708f, 505f, 112f)) {
            if (share.running) {
                share.stop()
                subtitle = "Сервер остановлен"
            } else {
                startServer()
            }
            redraw()
            return
        }
        if (inRect(x, y, 581f, 1693f, 781f, 142f)) act.pop()
    }

    // --------------------------------------------------------------- жизненный цикл

    private fun startServer() {
        if (!act.hasStorage()) {
            subtitle = "Нет доступа к памяти"
            return
        }
        share.start(listener)
        subtitle = subOf(share.port)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
        if (!share.running) startServer()
    }

    override fun onDetachedFromWindow() {
        // Экран закрыли — сервер гасим: иначе порт и поток висят до конца жизни оболочки.
        share.stop()
        subtitle = "Сервер не запущен"
        status.detach()
        super.onDetachedFromWindow()
    }
}
