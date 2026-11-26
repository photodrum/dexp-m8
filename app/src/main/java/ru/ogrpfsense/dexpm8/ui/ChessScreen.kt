package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import ru.ogrpfsense.dexpm8.chess.Color
import ru.ogrpfsense.dexpm8.chess.GameSave
import ru.ogrpfsense.dexpm8.chess.PieceType
import ru.ogrpfsense.dexpm8.chess.RuSan
import ru.ogrpfsense.dexpm8.chess.Square
import ru.ogrpfsense.dexpm8.chess.Status

/**
 * B79 · Шахматы · партия с компьютером (B79а — отметки, B79б — чёрными). Числа —
 * design/v5/B79.json, B79а.json, B79б.json; листы и диалоги — [ChessSheets], [ChessDialogs].
 *
 *   шапка      как у настроек; подзаголовок «С компьютером · уровень N» 176,181
 *   полоса сверху  «Компьютер» 41,246 42/700; состояние справа (правый край 1360, y 252) 32/700 G1
 *              или 32/600 G4; взятые — иконки 44×44 на y 302
 *   доска      [ChessBoard]
 *   полоса снизу   «Вы» 41,1578; состояние (y 1584); последние ходы справа (y 1636) 32/600 G4;
 *              взятые на y 1634
 *   кнопки     «Ходы» 41 / «Отменить ход» 489 / «Меню» 937, y 1694, 425×142, r 66, подпись 38/700
 *
 * Полосы стоят на своих местах при любой ориентации доски: сверху всегда компьютер.
 * Игра — [ChessPlay]; здесь только рисование и касания. Состояние листов — поле `sheet`.
 *
 * Отступление от макета: «Отменить ход» без своих ходов рисуется G6 (не нажимается).
 */
class ChessScreen(act: EinkActivity) : Screen(act) {

    private val status = StatusBar(act, this, ink, icons)
    private val kit = SettingsKit(ink, icons)
    private val board = ChessBoard(ink, icons)
    private val sheets = ChessSheets(ink, icons)
    private val dialogs = ChessDialogs(ink, icons)
    private val play = ChessPlay(act) { onChanged() }
    private var sheet = S_NONE

    private val game get() = ChessSession.game
    private val user get() = ChessSession.userColor

    override fun render(c: Canvas) {
        val dim = if (sheet != S_NONE) dimBegin(c) else -1
        ink.rect(c, 0f, 0f, W, H, T.BG)
        kit.header(c, "Шахматы", "С компьютером · уровень ${ChessSession.level}")
        strips(c)
        board.draw(c, game.position, ChessSession.flipped, game.lastMove, play.selected, play.targets)
        button(c, 41f, "Ходы", true)
        button(c, 489f, "Отменить ход", play.canUndo())
        button(c, 937f, "Меню", true)
        if (dim >= 0) dimEnd(c, dim)
        status.draw(c)
        when (sheet) {
            S_PROMO -> sheets.drawPromo(c, user, play.promoFrom, play.promoTo)
            S_MENU -> sheets.drawMenu(c, subtitle(), ChessSession.flipped, play.canUndo())
            S_RESIGN -> dialogs.drawResign(c, ChessBoard.icon(user, PieceType.KING), subtitle())
            S_END -> drawEnd(c)
        }
    }

    private fun button(c: Canvas, x: Float, label: String, enabled: Boolean) {
        ink.convex(c, x, 1694f, 425f, 142f, 66f)
        ink.textCenter(c, label, x + 3f, 420f, T.base(1739f, T.TS_LABEL_L, 1.25f), T.TS_LABEL_L, 700,
            if (enabled) T.G1 else T.G6)
    }

    private fun strips(c: Canvas) {
        val pos = game.position
        val comp = ChessSession.computerColor
        val over = play.over()

        ink.text(c, "Компьютер", 41f, T.base(246f, T.TS_ACTION, 1.25f), T.TS_ACTION, 700, T.G1)
        when {
            play.thinking -> state(c, "Думает…", 252f, true)
            play.note.isNotEmpty() -> state(c, play.note, 252f, true)
            !over && pos.side == comp && pos.isCheck() -> state(c, "Шах", 252f, true)
            else -> state(c, colorName(comp), 252f, false)
        }
        board.captured(c, pos, user, 302f)

        ink.text(c, "Вы", 41f, T.base(1578f, T.TS_ACTION, 1.25f), T.TS_ACTION, 700, T.G1)
        if (!over && pos.side == user && pos.isCheck()) state(c, "Шах · ваш ход", 1584f, true)
        else state(c, colorName(user), 1584f, false)
        board.captured(c, pos, comp, 1634f)
        right(c, lastMoves(), 1636f, T.TS_BODY_S, 600, T.G4)
    }

    private fun state(c: Canvas, s: String, top: Float, bold: Boolean) =
        right(c, s, top, T.TS_BODY_S, if (bold) 700 else 600, if (bold) T.G1 else T.G4)

    /** Текст с правым краем 1360 (полоса 660…1360 в макете). */
    private fun right(c: Canvas, s: String, top: Float, fs: Float, fw: Int, color: Int) {
        ink.text(c, s, 1360f - ink.measure(s, fs, fw), T.base(top, fs, 1.25f), fs, fw, color)
    }

    private fun colorName(color: Int) = if (color == Color.WHITE) "белые" else "чёрные"

    /** Последние полуходы в русской записи: «12. Лfe1 c6  13. Кe4»; влезает в 700 px. */
    private fun lastMoves(): String {
        var n = minOf(game.ply, 4)
        while (true) {
            val s = movesText(n)
            if (n <= 1 || ink.measure(s, T.TS_BODY_S, 600) <= 700f) return s
            n--
        }
    }

    private fun movesText(n: Int): String {
        val start = game.ply - n
        val parts = ArrayList<String>(n)
        for (i in start until game.ply) {
            val s = RuSan.of(game.san[i])
            when {
                i % 2 == 0 -> parts.add("${i / 2 + 1}. $s")
                i == start -> parts.add("${i / 2 + 1}… $s")
                else -> parts[parts.size - 1] = parts[parts.size - 1] + " " + s
            }
        }
        return parts.joinToString("  ")
    }

    private fun subtitle() =
        "С компьютером · уровень ${ChessSession.level} · ход ${game.position.fullmoveNumber}"

    // ------------------------------------------------------------------ конец партии

    private fun drawEnd(c: Canvas) {
        val t = endTexts()
        dialogs.drawEnd(c, ChessBoard.icon(kingColor(), PieceType.KING), t[0], t[1], t[2])
    }

    /** Король в гнезде: победитель; при ничьей — король игрока. */
    private fun kingColor(): Int = when {
        ChessSession.end == GameSave.END_RESIGN -> ChessSession.computerColor
        ChessSession.end == GameSave.END_NONE && game.status == Status.CHECKMATE ->
            Color.opposite(game.position.side)
        else -> user
    }

    /** Заголовок, подзаголовок и пояснение диалога B83 / B83а. */
    private fun endTexts(): Array<String> {
        val tail = "Компьютер, уровень ${ChessSession.level}. Партию можно посмотреть по ходам."
        if (ChessSession.end == GameSave.END_RESIGN)
            return arrayOf("Вы сдались", "Победа за компьютером", tail)
        if (ChessSession.end == GameSave.END_DRAW) {
            if (!game.canClaimDraw())
                return arrayOf("Ничья", "По соглашению сторон", "Компьютер принял предложение ничьей.")
            return if (game.repetitions() >= 3)
                arrayOf("Ничья", "Троекратное повторение позиции", "Одна и та же позиция повторилась три раза.")
            else
                arrayOf("Ничья", "50 ходов без взятий и ходов пешек", "Пятьдесят ходов подряд без взятий и ходов пешек.")
        }
        return when (game.status) {
            Status.CHECKMATE -> {
                val winner = Color.opposite(game.position.side)
                arrayOf(if (winner == user) "Мат. Вы победили" else "Мат. Вы проиграли",
                    "${if (winner == Color.WHITE) "Белые" else "Чёрные"} · ${lastMove()}", tail)
            }
            Status.STALEMATE -> arrayOf("Ничья", "Пат", "Ходов нет, но король не под шахом.")
            Status.FIVEFOLD -> arrayOf("Ничья", "Пятикратное повторение позиции",
                "Одна и та же позиция повторилась пять раз.")
            Status.MOVES_75 -> arrayOf("Ничья", "75 ходов без взятий и ходов пешек",
                "Партия закончена по правилу 75 ходов.")
            else -> arrayOf("Ничья", "Мало материала для мата", "Ни у одной из сторон нет сил поставить мат.")
        }
    }

    /** Последний ход с номером: «17. Лd8#» / «17… Кc6». */
    private fun lastMove(): String {
        val i = game.ply - 1
        val s = RuSan.of(game.san[i])
        return if (i % 2 == 0) "${i / 2 + 1}. $s" else "${i / 2 + 1}… $s"
    }

    // ------------------------------------------------------------------ касания

    override fun onUp(x: Float, y: Float) {
        when (sheet) {
            S_NONE -> mainUp(x, y)
            S_PROMO -> when (val h = sheets.promoHit(x, y)) {
                ChessSheets.NONE -> Unit
                ChessSheets.CANCEL -> {
                    play.cancelPromo()
                    closeSheet()
                }
                else -> {
                    closeSheet()
                    play.promote(h)
                }
            }
            S_MENU -> when (sheets.menuHit(x, y)) {
                ChessSheets.CANCEL -> closeSheet()
                ChessSheets.MENU_DRAW -> {
                    closeSheet()
                    play.offerDraw()
                }
                ChessSheets.MENU_UNDO -> if (play.canUndo()) {
                    closeSheet()
                    play.undo()
                }
                ChessSheets.MENU_FLIP -> {
                    ChessSession.flipped = !ChessSession.flipped
                    closeSheet()
                }
                ChessSheets.MENU_NEW -> newGame()
                ChessSheets.MENU_RESIGN -> openSheet(S_RESIGN)
            }
            S_RESIGN -> when (dialogs.resignHit(x, y)) {
                ChessDialogs.RESIGN_OK -> play.resign()
                ChessSheets.CANCEL -> closeSheet()
            }
            S_END -> when (dialogs.endHit(x, y)) {
                ChessDialogs.END_NEW -> newGame()
                ChessDialogs.END_MOVES -> act.go(EinkActivity.SCR_CHESS_MOVES)
                ChessDialogs.END_MENU -> toMenu()
            }
        }
    }

    private fun mainUp(x: Float, y: Float) {
        if (kit.isBack(x, y)) {
            leave()
            return
        }
        if (y >= 1694f && y <= 1836f) {
            when {
                x in 41f..466f -> act.go(EinkActivity.SCR_CHESS_MOVES)
                x in 489f..914f -> play.undo()
                x in 937f..1362f -> openSheet(S_MENU)
            }
            return
        }
        val sq = board.cellAt(x, y, ChessSession.flipped)
        if (sq != Square.NONE && play.tap(sq)) openSheet(S_PROMO)
    }

    private fun openSheet(s: Int) {
        sheet = s
        act.bigFrame("chess sheet")    // «Быстрый»: большой кадр — полной перерисовкой (8.2)
        redraw()
    }

    private fun closeSheet() = openSheet(S_NONE)

    /** Любое изменение игры: конец партии открывает B83, остальное — перерисовка. */
    private fun onChanged() {
        if (play.over() && sheet != S_END) {
            sheet = S_END
            act.bigFrame("chess end")
        }
        redraw()
    }



    /** Выйти в меню, оставив партию в сохранении. */
    private fun leave() {
        act.popTo(EinkActivity.SCR_CHESS_MENU)
    }

    /** Выйти в меню без партии: сохранение стирается. */
    private fun toMenu() {
        act.prefs.chessSave = ""
        ChessSession.clear()
        act.popTo(EinkActivity.SCR_CHESS_MENU)
    }

    /** «Новая партия»: сохранение стирается, дальше — B78. */
    private fun newGame() {
        toMenu()
        act.go(EinkActivity.SCR_CHESS_NEW)
    }

    override fun backPressed(): Boolean {
        when (sheet) {
            S_NONE -> leave()
            S_END -> toMenu()
            else -> closeSheet()
        }
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
        sheet = if (play.over()) S_END else S_NONE
        play.attach()
    }

    override fun onDetachedFromWindow() {
        play.detach()
        status.detach()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val S_NONE = 0
        const val S_PROMO = 1
        const val S_MENU = 2
        const val S_RESIGN = 3
        const val S_END = 4
    }
}
