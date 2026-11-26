package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import ru.ogrpfsense.dexpm8.chess.Color
import ru.ogrpfsense.dexpm8.chess.FicsEnd
import ru.ogrpfsense.dexpm8.chess.GameSave
import ru.ogrpfsense.dexpm8.chess.NetGame
import ru.ogrpfsense.dexpm8.chess.RuSan
import ru.ogrpfsense.dexpm8.chess.Status

/**
 * B81 · Шахматы · ходы партии. Числа — design/v5/B81.json.
 *
 *   шапка      «Ходы партии» 176,105 60/700; подзаголовок «С компьютером · уровень N · M ходов»
 *   панель     41,246 1321×1430 (выпуклая, R_TILE)
 *   заголовки  «ХОД» 88,286; «БЕЛЫЕ» 300,286; «ЧЁРНЫЕ» 830,286 — 30/700 G5; линия 88,340 1228×2 G11
 *   строка     номер 88 42/600 G4, ходы 300 и 830 42/700 G1; первая на y 390, шаг 128;
 *              разделитель на y + 89 (под последней строкой страницы его нет)
 *   текущий    вдавленная плашка 480×100 r 36 (дно G12) под последним ходом: x 270 или 800, y − 24
 *   итог       88, последняя строка + 120, 32/600 G4 («1–0 · мат на 17-м ходу»), когда партия
 *              закончена и в панели хватает места
 *   пагинация  кнопки 121.6×127.1 на y 1708.3 и «N / M» — как B11, B25, B75
 *
 * 10 ходов на странице; экран открывается на последней — там виден последний ход.
 *
 * Из B86 (`ChessNet.movesOnline`) показывает партию по сети: подзаголовок «По сети · 15 + 10 · N
 * ходов», итог — по `ChessNet.endResult`. Если партию пришлось собрать из FEN посреди игры
 * (`NetGame.resyncs`), лист начинается с этого места.
 */
class ChessMovesScreen(act: EinkActivity) : Screen(act) {

    private val status = StatusBar(act, this, ink, icons)
    private val kit = SettingsKit(ink, icons)
    private var page = 0

    /** Партия по сети, если B81 открыт из B86 (`ChessNet.movesOnline`); иначе null. */
    private val net: NetGame? get() = if (ChessNet.movesOnline) ChessNet.net else null

    private val game get() = net?.game ?: ChessSession.game

    private fun subtitle(): String {
        val n = net
        val tail = "${moves()} ${word(moves())}"
        return if (n != null) "По сети · ${n.minutes} + ${n.increment} · $tail"
        else "С компьютером · уровень ${ChessSession.level} · $tail"
    }

    /** Ходов в листе партии, считая неполный последний. */
    private fun moves() = (game.ply + 1) / 2

    private fun pages() = Math.max(1, (moves() + PER_PAGE - 1) / PER_PAGE)

    override fun render(c: Canvas) {
        ink.rect(c, 0f, 0f, W, H, T.BG)
        kit.header(c, "Ходы партии", subtitle())
        kit.panel(c, 246f, 1430f)

        ink.text(c, "ХОД", 88f, T.base(286f, T.TS_META, 1.25f), T.TS_META, 700, T.G5)
        ink.text(c, "БЕЛЫЕ", 300f, T.base(286f, T.TS_META, 1.25f), T.TS_META, 700, T.G5)
        ink.text(c, "ЧЁРНЫЕ", 830f, T.base(286f, T.TS_META, 1.25f), T.TS_META, 700, T.G5)
        ink.rect(c, 88f, 340f, 1228f, 2f, T.G11)

        val first = page * PER_PAGE
        val last = Math.min(moves(), first + PER_PAGE)
        for (n in first until last) {
            val y = ROW_Y + (n - first) * ROW_STEP
            // Плашка «текущий ход» — под последним полуходом партии.
            if (n == moves() - 1) {
                ink.inset(c, if (game.ply % 2 == 1) 270f else 800f, y - 24f, 480f, 100f, 36f, T.G12)
            }
            ink.text(c, "${n + 1}.", 88f, T.base(y, 42f, 1.25f), 42f, 600, T.G4)
            ink.text(c, RuSan.of(game.san[2 * n]), 300f, T.base(y, 42f, 1.25f), 42f, 700, T.G1)
            if (2 * n + 1 < game.ply) {
                ink.text(c, RuSan.of(game.san[2 * n + 1]), 830f, T.base(y, 42f, 1.25f), 42f, 700, T.G1)
            }
            if (n < last - 1) ink.rect(c, 88f, y + 89f, 1228f, 2f, T.G11)
        }

        val summary = summary()
        val sy = ROW_Y + (last - first - 1) * ROW_STEP + 120f
        if (page == pages() - 1 && summary != null && sy <= 1620f) {
            ink.text(c, summary, 88f, T.base(sy, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        }
        pager(c)
        status.draw(c)
    }

    /** «1–0 · мат на 17-м ходу»; null — партия идёт. */
    private fun summary(): String? {
        if (net != null) return netSummary()
        val n = moves()
        val win = if (game.position.side == Color.WHITE) "0–1" else "1–0"
        return when {
            ChessSession.end == GameSave.END_RESIGN ->
                (if (ChessSession.userColor == Color.WHITE) "0–1" else "1–0") + " · сдача"
            ChessSession.end == GameSave.END_DRAW -> "½–½ · ничья по соглашению"
            game.status == Status.PLAYING -> null
            game.status == Status.CHECKMATE -> "$win · мат на $n-м ходу"
            game.status == Status.STALEMATE -> "½–½ · пат"
            game.status == Status.FIVEFOLD -> "½–½ · пятикратное повторение"
            game.status == Status.MOVES_75 -> "½–½ · 75 ходов без взятий"
            else -> "½–½ · мало материала"
        }
    }

    /** Итог партии по сети: «1–0 · мат на 17-м ходу», «½–½ · ничья»; null — партия идёт. */
    private fun netSummary(): String? {
        if (ChessNet.state != ChessNet.ENDED) return null
        val score = when (ChessNet.endResult) {
            "1-0" -> "1–0"
            "0-1" -> "0–1"
            "1/2-1/2" -> "½–½"
            else -> "*"
        }
        val why = when (ChessNet.endKind) {
            FicsEnd.MATE -> "мат на ${moves()}-м ходу"
            FicsEnd.RESIGN -> "сдача"
            FicsEnd.FLAG -> "время вышло"
            FicsEnd.DRAW -> "ничья"
            FicsEnd.DISCONNECT -> "соперник отключился"
            FicsEnd.ABORT -> "партия прервана"
            else -> "партия окончена"
        }
        return "$score · $why"
    }

    private fun word(n: Int): String = when {
        n % 100 in 11..14 -> "ходов"
        n % 10 == 1 -> "ход"
        n % 10 in 2..4 -> "хода"
        else -> "ходов"
    }

    private fun pager(c: Canvas) {
        val n = pages()
        if (n < 2) return
        ink.convex(c, PAGER_L_X, PAGER_Y, PAGER_W, PAGER_H, R_PAGE)
        ink.convexDark(c, PAGER_R_X, PAGER_Y, PAGER_W, PAGER_H, R_PAGE)
        icons.drawRound(c, "back@54.8x57.1", PAGER_L_X, PAGER_Y, PAGER_W, PAGER_H, 54.8f, 57.1f)
        icons.drawRound(c, "next@53x53", PAGER_R_X, PAGER_Y, PAGER_W, PAGER_H, 53f, 53f)
        ink.textCenter(c, "${page + 1} / $n", 0f, W,
            T.base(1738.3f, T.TS_PAGE, 1.25f), T.TS_PAGE, 600, T.G1)
    }

    override fun onUp(x: Float, y: Float) {
        if (kit.isBack(x, y)) {
            act.pop()
            return
        }
        if (pages() < 2 || y < PAGER_Y || y > PAGER_Y + PAGER_H) return
        if (x >= PAGER_L_X && x <= PAGER_L_X + PAGER_W) goPage(page - 1)
        else if (x >= PAGER_R_X && x <= PAGER_R_X + PAGER_W) goPage(page + 1)
    }

    private fun goPage(p: Int) {
        val np = Math.max(0, Math.min(pages() - 1, p))
        if (np == page) return
        page = np
        redraw()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
        page = pages() - 1
    }

    override fun onDetachedFromWindow() {
        status.detach()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val PER_PAGE = 10
        const val ROW_Y = 390f
        const val ROW_STEP = 128f
        const val PAGER_Y = 1708.3f
        const val PAGER_L_X = 41.1f
        const val PAGER_R_X = 1240.4f
        const val PAGER_W = 121.6f
        const val PAGER_H = 127.1f
        const val R_PAGE = 58.5f
    }
}
