package ru.ogrpfsense.dexpm8.ui

import android.content.Intent
import android.graphics.Canvas
import android.provider.Settings
import ru.ogrpfsense.dexpm8.chess.Color
import ru.ogrpfsense.dexpm8.chess.FicsEnd
import ru.ogrpfsense.dexpm8.chess.Game
import ru.ogrpfsense.dexpm8.chess.Move
import ru.ogrpfsense.dexpm8.chess.NetGame
import ru.ogrpfsense.dexpm8.chess.Piece
import ru.ogrpfsense.dexpm8.chess.PieceType
import ru.ogrpfsense.dexpm8.chess.RuSan
import ru.ogrpfsense.dexpm8.chess.Square

/**
 * B86 · Шахматы · партия по сети (B86а — мало времени; B87, B87а, B87б — диалоги). Числа —
 * design/v5/B86.json, B86а.json, B87*.json; доска — [ChessBoard], листы и диалоги —
 * [ChessSheets], [ChessDialogs], [ChessNetDialogs].
 *
 *   шапка      «По сети» 176,105; подзаголовок «FICS · 15 + 10 · без рейтинга» 176,181
 *   полоса сверху  имя соперника 41,246 42/700; часы справа 1140,250 220×86 (r 38); взятые y 302
 *   доска      [ChessBoard]
 *   полоса снизу   «Вы · GuestMBKL» 41,1578; часы 1140,1584; взятые y 1634; последние ходы справа
 *              (правый край 1116, y 1636) 32/600 G4
 *   кнопки     «Ходы» 41 / «Ничья» 489 / «Сдаться» 937, y 1694, 425×142, подпись 38/700
 *   часы       идут — тёмная плашка (G3, подпись G15), стоят — светлая; осталось меньше минуты —
 *              рамка G0 4 px (1133,y−7 229×100) и вес 800 (B86а)
 *
 * Игра — [ChessNet] / [NetGame]: ход касанием делается сразу, сервер подтверждает его позицией.
 * Диалог выводится из `ChessNet.state` ([layer]): конец партии (B83 / B87а), обрыв связи (B87),
 * предложение ничьей (B87б), затем свои листы — превращение (B80) и «Сдаться?» (B84).
 * Часы: раз в секунду перерисовывается только рамка тех часов, чей текст сменился.
 *
 * «Назад» посреди партии = «Сдаться?» (выход из партии — поражение, см. `ChessNet`). Уйти с экрана
 * без выхода из партии можно только системной кнопкой «Домой»: из меню B77 — «Вернуться к партии».
 *
 * Отступления от макета: на полосе нет «Шах» — кольцо шаха рисует доска; отказ соперника от
 * ничьей и «Ничья предложена» — в подзаголовке вместо контроля времени.
 */
class ChessOnlineScreen(act: EinkActivity) : Screen(act) {

    private val status = StatusBar(act, this, ink, icons)
    private val kit = SettingsKit(ink, icons)
    private val board = ChessBoard(ink, icons)
    private val sheets = ChessSheets(ink, icons)
    private val dialogs = ChessDialogs(ink, icons)
    private val netDialogs = ChessNetDialogs(ink, icons)

    private var sheet = S_NONE
    private var shown = L_NONE
    private var flipped = false
    private var selected = Square.NONE
    private var targets = IntArray(0)
    private var promoFrom = Square.NONE
    private var promoTo = Square.NONE
    private var seenBoards = 0
    private var offeredPly = -1
    private var lastTop = ""
    private var lastBottom = ""
    private var waitWifi = false

    private val change: () -> Unit = { onNet() }

    private val tick = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            clocks()
            postDelayed(this, TICK_MS)
        }
    }

    // ------------------------------------------------------------------ рисование

    override fun render(c: Canvas) {
        val net = ChessNet.net
        if (net == null) {
            ink.rect(c, 0f, 0f, W, H, T.BG)
            kit.header(c, "По сети", "FICS")
            status.draw(c)
            return
        }
        val game = net.game
        val lay = layer()
        val dim = if (lay != L_NONE) dimBegin(c) else -1
        ink.rect(c, 0f, 0f, W, H, T.BG)
        kit.header(c, "По сети", subtitle(net))
        strips(c, net)
        board.draw(c, game.position, flipped, game.lastMove, selected, targets)
        button(c, 41f, "Ходы", true)
        button(c, 489f, "Ничья", ChessNet.state == ChessNet.PLAYING)
        button(c, 937f, "Сдаться", ChessNet.state == ChessNet.PLAYING)
        if (dim >= 0) dimEnd(c, dim)
        status.draw(c)
        drawLayer(c, net, lay)
    }

    private fun drawLayer(c: Canvas, net: NetGame, lay: Int) {
        val move = net.game.position.fullmoveNumber
        when (lay) {
            L_PROMO -> sheets.drawPromo(c, net.myColor, promoFrom, promoTo)
            L_RESIGN -> dialogs.drawResign(c, ChessBoard.icon(net.myColor, PieceType.KING), dlgSub(net))
            L_DRAW -> netDialogs.drawDraw(c, "${net.opponent} предлагает ничью · ход $move")
            L_OFFLINE -> netDialogs.drawOffline(c,
                "Связь с сервером прервалась. Вернуться в эту партию гостю нельзя. " +
                    "С компьютером можно играть и без сети.")
            L_GONE -> netDialogs.drawGone(c, "${net.opponent} · ход $move", goneNote(net))
            L_END -> {
                val t = endTexts(net)
                dialogs.drawEnd(c, ChessBoard.icon(kingColor(net), PieceType.KING), t[0], t[1], t[2])
            }
        }
    }

    private fun button(c: Canvas, x: Float, label: String, enabled: Boolean) {
        ink.convex(c, x, 1694f, 425f, 142f, 66f)
        ink.textCenter(c, label, x + 3f, 420f, T.base(1739f, T.TS_LABEL_L, 1.25f), T.TS_LABEL_L, 700,
            if (enabled) T.G1 else T.G6)
    }

    private fun subtitle(net: NetGame): String = when {
        ChessNet.drawDeclined -> "Соперник отклонил ничью"
        offeredPly == net.game.ply && ChessNet.state == ChessNet.PLAYING -> "Ничья предложена"
        else -> "FICS · ${controlText(net)} · без рейтинга"
    }

    private fun controlText(net: NetGame): String =
        if (net.boards > 0) "${net.minutes} + ${net.increment}"
        else "${ChessNet.minutes} + ${ChessNet.increment}"

    private fun dlgSub(net: NetGame) =
        "По сети · ${controlText(net)} · ход ${net.game.position.fullmoveNumber}"

    private fun strips(c: Canvas, net: NetGame) {
        val game = net.game
        val pos = game.position
        val me = net.myColor
        val opp = Color.opposite(me)
        val now = ChessNet.now()

        ink.text(c, ink.ellipsize(net.opponent, 1050f, T.TS_ACTION, 700), 41f,
            T.base(246f, T.TS_ACTION, 1.25f), T.TS_ACTION, 700, T.G1)
        lastTop = clock(c, 250f, opp, net, now)
        board.captured(c, pos, me, 302f)

        ink.text(c, ink.ellipsize("Вы · ${net.me}", 1050f, T.TS_ACTION, 700), 41f,
            T.base(1578f, T.TS_ACTION, 1.25f), T.TS_ACTION, 700, T.G1)
        lastBottom = clock(c, 1584f, me, net, now)
        board.captured(c, pos, opp, 1634f)
        val s = lastMoves(game)
        ink.text(c, s, 1116f - ink.measure(s, T.TS_BODY_S, 600),
            T.base(1636f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
    }

    /** Часы [color] на плашке с верхом [y]; возвращает нарисованный текст. */
    private fun clock(c: Canvas, y: Float, color: Int, net: NetGame, now: Long): String {
        val ms = net.clockMs(color, now)
        val run = net.running(color)
        val low = ms < LOW_MS && ChessNet.state == ChessNet.PLAYING
        if (low) ink.ring(c, 1133f, y - 7f, 229f, 100f, 46f, T.G0, 4f)
        if (run) ink.convexDark(c, 1140f, y, 220f, 86f, 38f) else ink.convex(c, 1140f, y, 220f, 86f, 38f)
        val s = NetGame.clockText(ms)
        ink.textCenter(c, s, 1143f, 215f, T.base(y + 12f, 48f, 1.2f), 48f, if (low) 800 else 700,
            if (run) T.G15 else T.G1)
        return s
    }

    /** Последние полуходы в русской записи («12. Лfe1 c6  13. Кe4»); влезает в 700 px. */
    private fun lastMoves(g: Game): String {
        var n = minOf(g.ply, 4)
        while (true) {
            val s = movesText(g, n)
            if (n <= 1 || ink.measure(s, T.TS_BODY_S, 600) <= 700f) return s
            n--
        }
    }

    /**
     * Номер полухода из позиции, а не из индекса: если [Game] собрана из FEN посреди партии
     * (`NetGame.resyncs`), её лист SAN начинается не с первого хода.
     */
    private fun absolute(g: Game, i: Int): Int {
        val pos = g.position
        val h = 2 * (pos.fullmoveNumber - 1) + (if (pos.side == Color.BLACK) 1 else 0)
        return h - g.ply + i
    }

    private fun movesText(g: Game, n: Int): String {
        val start = g.ply - n
        val parts = ArrayList<String>(n)
        for (i in start until g.ply) {
            val a = absolute(g, i)
            val s = RuSan.of(g.san[i])
            when {
                a % 2 == 0 -> parts.add("${a / 2 + 1}. $s")
                i == start -> parts.add("${a / 2 + 1}… $s")
                else -> parts[parts.size - 1] = parts[parts.size - 1] + " " + s
            }
        }
        return parts.joinToString("  ")
    }

    private fun lastMove(g: Game): String {
        val i = g.ply - 1
        val a = absolute(g, i)
        val s = RuSan.of(g.san[i])
        return if (a % 2 == 0) "${a / 2 + 1}. $s" else "${a / 2 + 1}… $s"
    }

    // ------------------------------------------------------------------ конец партии

    /** Победитель по итогу сервера: [Color.WHITE], [Color.BLACK] или -1 (ничья, прервана). */
    private fun winner(): Int = when (ChessNet.endResult) {
        "1-0" -> Color.WHITE
        "0-1" -> Color.BLACK
        else -> -1
    }

    /** Король в гнезде: победитель; при ничьей — наш. */
    private fun kingColor(net: NetGame): Int = winner().let { if (it < 0) net.myColor else it }

    private fun colorWord(color: Int) = if (color == Color.WHITE) "Белые" else "Чёрные"

    private fun goneNote(net: NetGame): String {
        val base = "Сервер закончил партию сразу: гостям доигрывание не положено."
        return if (winner() == net.myColor) "$base Победа за вами." else base
    }

    /** Заголовок, подзаголовок и пояснение диалога B83 по итогу сервера. */
    private fun endTexts(net: NetGame): Array<String> {
        val w = winner()
        val me = net.myColor
        val tail = "${net.opponent} · FICS ${controlText(net)}, без рейтинга. Партию можно посмотреть по ходам."
        val text = ChessNet.endText
        val who = if (w == me) "Победа за вами" else "Победа за соперником"
        return when (ChessNet.endKind) {
            FicsEnd.MATE -> arrayOf(
                if (w == me) "Мат. Вы победили" else "Мат. Вы проиграли",
                "${colorWord(w)} · ${if (net.game.ply > 0) lastMove(net.game) else ""}".trimEnd(' ', '·'), tail)
            FicsEnd.RESIGN -> if (w == me) arrayOf("Соперник сдался", who, tail)
                else arrayOf("Вы сдались", who, tail)
            FicsEnd.FLAG -> arrayOf(if (w == me) "Время соперника вышло" else "Ваше время вышло", who, tail)
            FicsEnd.ABORT -> arrayOf("Партия прервана", "Результат не засчитан", tail)
            else -> when {
                w < 0 && ChessNet.endResult == "1/2-1/2" -> arrayOf("Ничья", drawReason(text), tail)
                w < 0 -> arrayOf("Партия окончена", "Результат не засчитан", tail)
                else -> arrayOf(if (w == me) "Вы победили" else "Вы проиграли", who, tail)
            }
        }
    }

    private fun drawReason(text: String): String = when {
        "agreement" in text -> "По соглашению сторон"
        "repetition" in text -> "Троекратное повторение позиции"
        "50" in text -> "50 ходов без взятий и ходов пешек"
        "stalemate" in text -> "Пат"
        "material" in text -> "Мало материала для мата"
        else -> "Партия закончилась вничью"
    }

    // ------------------------------------------------------------------ слой и состояние

    /** Что лежит поверх доски: из состояния сети, затем свои листы. */
    private fun layer(): Int = when {
        ChessNet.state == ChessNet.OFFLINE -> L_OFFLINE
        ChessNet.state == ChessNet.ENDED ->
            if (ChessNet.endKind == FicsEnd.DISCONNECT) L_GONE else L_END
        ChessNet.state == ChessNet.PLAYING && ChessNet.drawOffered -> L_DRAW
        sheet == S_PROMO -> L_PROMO
        sheet == S_RESIGN -> L_RESIGN
        else -> L_NONE
    }

    /** Любая смена состояния сети: позиция, часы, ничья, конец партии, обрыв. */
    private fun onNet() {
        val net = ChessNet.net
        if (net != null && net.boards != seenBoards) {
            seenBoards = net.boards
            clearSelection()
            if (sheet == S_PROMO) sheet = S_NONE
        }
        refresh()
    }

    /** Перерисовать; если лист или диалог открылся или закрылся — большой кадр (как на B79). */
    private fun refresh() {
        val l = layer()
        if (l != shown) {
            shown = l
            act.bigFrame("chess online layer")
        }
        redraw()
    }

    /** Секундный тик: перерисовать рамку тех часов, чей текст сменился. */
    private fun clocks() {
        val net = ChessNet.net ?: return
        if (ChessNet.state != ChessNet.PLAYING || layer() != L_NONE) return
        val now = ChessNet.now()
        val top = NetGame.clockText(net.clockMs(Color.opposite(net.myColor), now))
        val bottom = NetGame.clockText(net.clockMs(net.myColor, now))
        if (top != lastTop) {
            lastTop = top
            dirty(250f)
        }
        if (bottom != lastBottom) {
            lastBottom = bottom
            dirty(1584f)
        }
    }

    /**
     * Рамка часов с верхом плашки [y] вместе с рамкой «мало времени» (дизайн-px → реальные).
     * `invalidate(l, t, r, b)` объявлен устаревшим с API 28, но на Android 8.1 книги он единственный
     * способ перерисовать часть кадра.
     */
    @Suppress("DEPRECATION")
    private fun dirty(y: Float) {
        invalidate(D.px(1126f), D.px(y - 14f), D.px(1368f), D.px(y + 100f))
    }

    // ------------------------------------------------------------------ касания

    override fun onUp(x: Float, y: Float) {
        when (layer()) {
            L_NONE -> mainUp(x, y)
            L_PROMO -> when (val h = sheets.promoHit(x, y)) {
                ChessSheets.NONE -> Unit
                ChessSheets.CANCEL -> closeSheet()
                else -> promote(h)
            }
            L_RESIGN -> when (dialogs.resignHit(x, y)) {
                ChessDialogs.RESIGN_OK -> {
                    ChessNet.resign()
                    closeSheet()
                }
                ChessSheets.CANCEL -> closeSheet()
            }
            L_DRAW -> when (netDialogs.hit(x, y)) {
                ChessNetDialogs.LEFT -> ChessNet.declineDraw()
                ChessNetDialogs.RIGHT -> ChessNet.draw()
            }
            else -> endUp(x, y)
        }
    }

    /** Касания под диалогами конца партии и обрыва связи. */
    private fun endUp(x: Float, y: Float) {
        when (layer()) {
            L_OFFLINE -> when (netDialogs.hit(x, y)) {
                ChessNetDialogs.LEFT -> toSearch()
                ChessNetDialogs.RIGHT -> wifiSettings()
                else -> if (kit.isBack(x, y)) toMenu()
            }
            L_GONE -> when (netDialogs.hit(x, y)) {
                ChessNetDialogs.LEFT -> toMenu()
                ChessNetDialogs.RIGHT -> toSearch()
            }
            L_END -> when (dialogs.endHit(x, y)) {
                ChessDialogs.END_NEW -> toSearch()
                ChessDialogs.END_MOVES -> act.go(EinkActivity.SCR_CHESS_MOVES)
                ChessDialogs.END_MENU -> toMenu()
            }
        }
    }

    private fun mainUp(x: Float, y: Float) {
        if (kit.isBack(x, y)) {
            back()
            return
        }
        if (y >= 1694f && y <= 1836f) {
            when {
                x in 41f..466f -> act.go(EinkActivity.SCR_CHESS_MOVES)
                x in 489f..914f -> offerDraw()
                x in 937f..1362f -> if (ChessNet.state == ChessNet.PLAYING) openSheet(S_RESIGN)
            }
            return
        }
        val sq = board.cellAt(x, y, flipped)
        if (sq != Square.NONE) tap(sq)
    }

    /** «Назад»: посреди партии — «Сдаться?»; после неё — выйти. */
    private fun back() {
        if (ChessNet.state == ChessNet.PLAYING) openSheet(S_RESIGN) else toMenu()
    }

    override fun backPressed(): Boolean {
        when (layer()) {
            L_NONE -> back()
            L_PROMO, L_RESIGN -> closeSheet()
            L_DRAW -> ChessNet.declineDraw()
            else -> toMenu()
        }
        return true
    }

    private fun offerDraw() {
        val net = ChessNet.net ?: return
        if (ChessNet.state != ChessNet.PLAYING) return
        offeredPly = net.game.ply
        ChessNet.draw()
        refresh()
    }

    private fun openSheet(s: Int) {
        sheet = s
        refresh()
    }

    private fun closeSheet() = openSheet(S_NONE)

    // ------------------------------------------------------------------ ход касанием

    private fun tap(sq: Int) {
        val net = ChessNet.net ?: return
        if (ChessNet.state != ChessNet.PLAYING || !net.myTurn) return
        val g = net.game
        val pc = g.position.board[sq]
        if (selected != Square.NONE && targets.contains(sq)) {
            val from = selected
            val moves = g.legalMoves().filter { Move.origin(it) == from && Move.target(it) == sq }
            if (moves.size > 1) {
                promoFrom = from
                promoTo = sq
                openSheet(S_PROMO)
                return
            }
            play(moves[0])
            return
        }
        if (pc != Piece.NONE && Piece.color(pc) == net.myColor && sq != selected) {
            selected = sq
            targets = g.legalMoves().filter { Move.origin(it) == sq }.map { Move.target(it) }
                .distinct().toIntArray()
        } else {
            clearSelection()
        }
        redraw()
    }

    private fun promote(type: Int) {
        val g = ChessNet.net?.game ?: return
        val from = promoFrom
        val to = promoTo
        sheet = S_NONE
        val m = g.legalMoves().firstOrNull {
            Move.origin(it) == from && Move.target(it) == to && Move.promotion(it) == type
        }
        if (m == null) refresh() else play(m)
    }

    private fun play(m: Int) {
        clearSelection()
        if (!ChessNet.move(m)) refresh()
    }

    private fun clearSelection() {
        selected = Square.NONE
        targets = IntArray(0)
    }

    // ------------------------------------------------------------------ выход

    /** Выйти из сети и в меню шахмат. */
    private fun toMenu() {
        ChessNet.leave()
        act.popTo(EinkActivity.SCR_CHESS_MENU)
    }

    /** Новый поиск: соединение сохраняется (после конца партии), экран — B85. */
    private fun toSearch() {
        act.popTo(EinkActivity.SCR_CHESS_MENU)
        act.go(EinkActivity.SCR_CHESS_NET)
    }

    private fun wifiSettings() {
        try {
            waitWifi = true
            act.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        } catch (t: Throwable) {
            waitWifi = false
            Trace.step("chess: wifi settings FAIL: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Вернулись из настроек Wi-Fi: чистим панель после системного экрана. */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus || parent == null || !waitWifi) return
        waitWifi = false
        act.cleanScreen()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
        ChessNet.onChange = change
        ChessNet.movesOnline = true
        val net = ChessNet.net
        flipped = net?.myColor == Color.BLACK
        seenBoards = net?.boards ?: 0
        sheet = S_NONE
        clearSelection()
        offeredPly = -1
        lastTop = ""
        lastBottom = ""
        shown = layer()
        postDelayed(tick, TICK_MS)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        if (ChessNet.onChange === change) ChessNet.onChange = null
        status.detach()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val S_NONE = 0
        const val S_PROMO = 1
        const val S_RESIGN = 2

        const val L_NONE = 0
        const val L_PROMO = 1
        const val L_RESIGN = 2
        const val L_DRAW = 3
        const val L_OFFLINE = 4
        const val L_GONE = 5
        const val L_END = 6

        const val TICK_MS = 1000L

        /** Меньше минуты на часах — рамка «мало времени» (B86а). */
        const val LOW_MS = 60_000L
    }
}
