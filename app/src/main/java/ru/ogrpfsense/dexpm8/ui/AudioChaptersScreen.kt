package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import ru.ogrpfsense.dexpm8.core.AudioPlayer

/**
 * B34 · «Главы аудиокниги» (список). Числа — `design/v5/B34.json`.
 *
 *   шапка    назад 41,113.5 107×112; «Главы» 176,105 Manrope/60/700;
 *            «Мастер и Маргарита · 32 главы» 176,181 32/600 G4
 *   панель   41,246 1321×1420 (r 51)
 *   строка   номер 95,y+25.6 70×(42/700); название 195,y Literata/42/600;
 *            длительность 195,y+58 32/600 G4; разделитель 195,y+144 1092×2 G11
 *   текущая  группа 66,y 1266×200: вдавленная (r 40, дно G12), номер и название G1,
 *            «Идёт · осталось 7:40 из 18:32», дорожка 195,y+140 880×22, пауза 1174,y+42 115×120
 *   пагинация клавиши 41.1 / 1240.4, 1708.3 121.6×127.1; «1 / 5» 44/600
 *
 * Пять глав на страницу. Выравнивание (замечание 2026-11-21): строки лежат в ячейках по 170
 * (168 + разделитель 2), блок «название + длительность» 98 px — по центру ячейки, то есть
 * поровну до разделителя сверху и снизу. Текущая глава — в ячейке 230 (плашка 200 + поля 15);
 * текст в плашке на тех же x, что в обычных строках (в макете так же: 95 / 195), блок и пауза —
 * по её центру; разделителей рядом с плашкой нет.
 * Галочка `check` в макете — у глав, прошедших целиком до текущего места.
 */
class AudioChaptersScreen(act: EinkActivity) : Screen(act) {

    private val status = StatusBar(act, this, ink, icons)
    private val lit = T.literata(ink.res, 600)
    private val kit = SettingsKit(ink, icons)
    private var page = 0

    private val p get() = act.audio

    override fun render(c: Canvas) {
        ink.rect(c, 0f, 0f, W, H, T.BG)
        header(c)
        panel(c)
        pager(c)
        status.draw(c)
    }

    private fun header(c: Canvas) {
        val b = p.book
        val sub = if (b == null) "книга не открыта"
        else "${b.title} · ${AudioKit.chapters(p.chapters.size)}"
        kit.header(c, "Главы", ink.ellipsize(sub, 1150f, T.TS_BODY_S, 600))
    }

    private fun panel(c: Canvas) {
        ink.convex(c, 41f, 246f, 1321f, 1420f, T.R_TILE)
        val total = p.chapters.size
        val first = page * PER_PAGE
        val n = minOf(PER_PAGE, total - first)
        val cur = p.chapterNumber()
        var top = CELL_Y0
        for (i in 0 until n) {
            val num = first + i + 1
            val ch = p.chapters[first + i]
            // Разделитель — только между обычными строками: не под последней строкой страницы
            // и не рядом с плашкой текущей главы (у неё свои поля).
            val divider = num != cur && i < n - 1 && num + 1 != cur
            row(c, num, ch, top, num == cur, num < cur, divider)
            top += cellH(num, cur)
        }
    }

    /** Высота ячейки строки: обычная — шаг до следующего разделителя, текущая — плашка с полями. */
    private fun cellH(num: Int, cur: Int) = if (num == cur) CUR_CELL else ROW_H

    /**
     * Строка главы в ячейке с верхом [top]. Обычная: блок «название + длительность» (98 px)
     * стоит по центру между разделителями (поля [PAD] сверху и снизу), разделитель — по низу
     * ячейки. Текущая — вдавленная плашка 1266×200 с дорожкой и клавишей паузы; текст в ней на
     * тех же x, что у остальных строк (95 / 195, как в макете), а блок и пауза — по центру плашки.
     */
    private fun row(c: Canvas, num: Int, ch: AudioPlayer.Chap, top: Float, current: Boolean,
                    done: Boolean, divider: Boolean) {
        val ink1 = if (current || !done) T.G1 else T.G5
        if (current) {
            val y = top + CUR_PAD
            ink.inset(c, 66f, y, 1266f, CUR_H, 40f, T.G12)
            ink.textCenter(c, num.toString(), 95f, 70f,
                T.base(y + 74f, T.TS_ACTION, 1.25f), T.TS_ACTION, 700, T.G1)
            ink.text(c, ink.ellipsize(ch.title, 900f, T.TS_ACTION, 600, lit), 195f,
                T.base(y + 30f, T.TS_ACTION, 1.15f, true), T.TS_ACTION, 600, T.G1, lit)
            val left = maxOf(0L, ch.end - p.pos)
            ink.text(c, "Идёт · осталось ${AudioKit.clock(left)} из ${AudioKit.clock(ch.dur)}",
                195f, T.base(y + 88f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
            val pct = if (ch.dur <= 0L) 0 else ((p.pos - ch.start) * 100 / ch.dur).toInt().coerceIn(0, 100)
            AudioKit.track(ink, c, 195f, y + 148f, 880f, 22f, pct)
            ink.convexDark(c, 1174.1f, y + PAUSE_DY, 115f, 120f, 55f)
            val ic = if (p.playing) "pause@26.5x33.1" else "play"
            icons.drawRound(c, ic, 1174.1f, y + PAUSE_DY, 115f, 120f, 26.5f, 33.1f)
        } else {
            val y = top + PAD
            ink.textCenter(c, num.toString(), 95f, 70f,
                T.base(y + 25.6f, T.TS_ACTION, 1.25f), T.TS_ACTION, 700, ink1)
            ink.text(c, ink.ellipsize(ch.title, 1050f, T.TS_ACTION, 600, lit), 195f,
                T.base(y, T.TS_ACTION, 1.15f, true), T.TS_ACTION, 600,
                if (done) T.G4 else T.G1, lit)
            ink.text(c, AudioKit.dur(ch.dur), 195f, T.base(y + 58f, T.TS_BODY_S, 1.25f),
                T.TS_BODY_S, 600, T.G4)
            if (done) icons.draw(c, "check", 1262.6f, y + 45.3f, 24.5f, 18.4f)
            if (divider) ink.rect(c, 195f, top + ROW_H - 2f, 1092.1f, 2f, T.G11)
        }
    }

    private fun pager(c: Canvas) {
        val pages = totalPages()
        if (pages <= 1) return
        ink.convex(c, 41.1f, 1708.3f, 121.6f, 127.1f, 58.5f)
        icons.drawRound(c, "back@54.8x57.1", 41.1f, 1708.3f, 121.6f, 127.1f, 54.8f, 57.1f)
        ink.convexDark(c, 1240.4f, 1708.3f, 121.6f, 127.1f, 58.5f)
        icons.drawRound(c, "next@53x53", 1240.4f, 1708.3f, 121.6f, 127.1f, 53f, 53f)
        ink.textCenter(c, "${page + 1} / $pages", 0f, W,
            T.base(1738.3f, T.TS_PAGE, 1.25f), T.TS_PAGE, 600, T.G1)
    }

    private fun totalPages() = maxOf(1, (p.chapters.size + PER_PAGE - 1) / PER_PAGE)

    // ------------------------------------------------------------------ касания

    override fun onUp(x: Float, y: Float) {
        if (kit.isBack(x, y)) {
            act.pop()
            return
        }
        if (totalPages() > 1) {
            if (inRect(x, y, 41.1f, 1708.3f, 121.6f, 127.1f) && page > 0) {
                page--
                redraw()
                return
            }
            if (inRect(x, y, 1240.4f, 1708.3f, 121.6f, 127.1f) && page < totalPages() - 1) {
                page++
                redraw()
                return
            }
        }
        val cur = p.chapterNumber()
        var top = CELL_Y0
        val first = page * PER_PAGE
        val n = minOf(PER_PAGE, p.chapters.size - first)
        for (i in 0 until n) {
            val num = first + i + 1
            val h = cellH(num, cur)
            if (num == cur && inRect(x, y, 1174.1f, top + CUR_PAD + PAUSE_DY, 115f, 120f)) {
                p.toggle()
                redraw()
                return
            }
            // Зона касания: у текущей — сама плашка, у остальных — ячейка без разделителя.
            val zt = if (num == cur) top + CUR_PAD else top
            val zh = if (num == cur) CUR_H else ROW_H - 2f
            if (x >= 66f && x <= 1332f && y >= zt && y <= zt + zh) {
                p.playChapter(first + i)
                redraw()
                return
            }
            top += h
        }
    }

    private fun inRect(x: Float, y: Float, rx: Float, ry: Float, rw: Float, rh: Float) =
        x >= rx && x <= rx + rw && y >= ry && y <= ry + rh

    // ------------------------------------------------------------------ данные

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
        // Открываем страницу с текущей главой — возвращаются чаще всего к ней.
        page = ((p.chapterNumber() - 1) / PER_PAGE).coerceAtLeast(0)
        if (page >= totalPages()) page = totalPages() - 1
        p.onTick = { if (parent != null) redraw() }
        redraw()
    }

    override fun onDetachedFromWindow() {
        p.onTick = null
        status.detach()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val PER_PAGE = 5
        /** Верх первой ячейки: блок первой строки встаёт на 287 (макет 286.9). */
        const val CELL_Y0 = 252f
        /** Шаг обычной строки: 168 до разделителя + сам разделитель 2. */
        const val ROW_H = 170f
        /** Поля блока (98 px) в ячейке: (168 − 98) / 2 — поровну над ним и под ним. */
        const val PAD = 35f
        const val CUR_H = 200f
        /** Поля вокруг плашки текущей главы; ячейка плашки = 200 + 2·15. */
        const val CUR_PAD = 15f
        const val CUR_CELL = CUR_H + 2f * CUR_PAD
        /** Верх клавиши паузы в плашке: центр 100 = центр плашки. */
        const val PAUSE_DY = 40f
    }
}
