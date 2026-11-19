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
 * Пять глав на страницу: панель 1420 при шаге 170 — ровно макет, у текущей строки высота 200.
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
        var y = ROW_Y0
        for (i in 0 until n) {
            val num = first + i + 1
            val ch = p.chapters[first + i]
            row(c, num, ch, y, num == cur, num < cur, y + (if (num == cur) CUR_H else ROW_H) < PANEL_BOTTOM)
            y += if (num == cur) CUR_H else ROW_H
        }
    }

    /**
     * Строка главы. Текущая — вдавленная плашка 1266×200 с дорожкой и клавишей паузы;
     * остальные — номер, название, длительность и разделитель.
     */
    private fun row(c: Canvas, num: Int, ch: AudioPlayer.Chap, y: Float, current: Boolean,
                    done: Boolean, divider: Boolean) {
        if (current) ink.inset(c, 66f, y, 1266f, CUR_H, 40f, T.G12)
        val dx = if (current) 29f else 0f
        ink.textCenter(c, num.toString(), 95f + dx, 70f,
            T.base(y + (if (current) 73f else 25.6f), T.TS_ACTION, 1.25f), T.TS_ACTION, 700,
            if (current || !done) T.G1 else T.G5)
        ink.text(c, ink.ellipsize(ch.title, 900f, T.TS_ACTION, 600, lit), 195f + dx,
            T.base(y + (if (current) 22.4f else 0f), T.TS_ACTION, 1.15f, true), T.TS_ACTION, 600,
            if (current || !done) T.G1 else T.G4, lit)
        if (current) {
            val left = maxOf(0L, ch.end - p.pos)
            ink.text(c, "Идёт · осталось ${AudioKit.clock(left)} из ${AudioKit.clock(ch.dur)}",
                195f + dx, T.base(y + 80.4f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
            val pct = if (ch.dur <= 0L) 0 else ((p.pos - ch.start) * 100 / ch.dur).toInt().coerceIn(0, 100)
            AudioKit.track(ink, c, 195f + dx, y + 140.4f, 880f, 22f, pct)
            ink.convexDark(c, 1174.1f, y + 42f, 115f, 120f, 55f)
            icons.drawRound(c, if (p.playing) "pause@26.5x33.1" else "play@26.5x33.1",
                1174.1f, y + 42f, 115f, 120f, 26.5f, 33.1f)
        } else {
            ink.text(c, AudioKit.dur(ch.dur), 195f, T.base(y + 58f, T.TS_BODY_S, 1.25f),
                T.TS_BODY_S, 600, T.G4)
            if (done) icons.draw(c, "check", 1262.6f, y + 45.3f, 24.5f, 18.4f)
            if (divider) ink.rect(c, 195f, y + 144f, 1092.1f, 2f, T.G11)
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
        var top = ROW_Y0
        val first = page * PER_PAGE
        val n = minOf(PER_PAGE, p.chapters.size - first)
        for (i in 0 until n) {
            val num = first + i + 1
            val h = if (num == cur) CUR_H else ROW_H
            if (num == cur && inRect(x, y, 1174.1f, top + 42f, 115f, 120f)) {
                p.toggle()
                redraw()
                return
            }
            if (x >= 66f && x <= 1332f && y >= top && y <= top + h - 6f) {
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
        const val ROW_Y0 = 286.9f
        const val ROW_H = 170f
        const val CUR_H = 200f
        const val PANEL_BOTTOM = 1666f
    }
}
