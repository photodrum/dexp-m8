package ru.ogrpfsense.dexpm8.ui

import ru.ogrpfsense.dexpm8.chess.GameSave
import ru.ogrpfsense.dexpm8.chess.Level
import ru.ogrpfsense.dexpm8.chess.Limits
import ru.ogrpfsense.dexpm8.chess.Move
import ru.ogrpfsense.dexpm8.chess.Piece
import ru.ogrpfsense.dexpm8.chess.Search
import ru.ogrpfsense.dexpm8.chess.SearchResult
import ru.ogrpfsense.dexpm8.chess.Square
import ru.ogrpfsense.dexpm8.chess.Status

/**
 * Игра на B79 (п. 25.2): касания доски, ход компьютера, отмена, ничья, сдача, сохранение.
 * Листы рисует [ChessScreen]. Всё идёт в потоке интерфейса; поиск — в своём потоке, результат
 * возвращается через `runOnUiThread`. [changed] зовётся после любого видимого изменения.
 *
 * Ненужный поиск (экран ушёл, ход отменён) останавливается [Search.stop], а его результат
 * отбрасывается по счётчику поколений `gen`. Ходы пишутся в `Prefs.chessSave` после каждого
 * полухода: выйти из партии можно в любой момент, при возврате компьютер, если его очередь,
 * думает заново.
 */
internal class ChessPlay(private val act: EinkActivity, private val changed: () -> Unit) {

    /** Выбранное поле игрока ([Square.NONE] — нет) и поля, куда оттуда можно пойти. */
    var selected = Square.NONE
        private set
    var targets = IntArray(0)
        private set

    /** Идёт поиск (ход компьютера или оценка для ничьей) — на полосе «Думает…». */
    var thinking = false
        private set

    /** Сообщение вместо цвета на полосе компьютера («Компьютер отказался»). */
    var note = ""
        private set

    /** Ход пешки на последний ряд ждёт выбора фигуры (B80). */
    var promoFrom = Square.NONE
        private set
    var promoTo = Square.NONE
        private set

    private var gen = 0
    private var search: Search? = null

    private val g get() = ChessSession.game
    private val user get() = ChessSession.userColor

    /** Партия закончилась: на доске (мат, пат…), сдачей или ничьей по соглашению. */
    fun over() = ChessSession.end != GameSave.END_NONE || g.status != Status.PLAYING

    fun userTurn() = !over() && g.position.side == user

    fun canUndo() = !over() && ChessSession.userMoves() > 0

    /** Экран появился: сбросить выбор; если очередь компьютера — думать. */
    fun attach() {
        clearSelection()
        note = ""
        thinking = false
        if (!over() && g.position.side != user) think()
    }

    /** Экран ушёл: поиск остановить, его результат отбросить. */
    fun detach() = stopSearch()

    /**
     * Касание поля [sq]. true — нужен выбор фигуры для превращения ([promoFrom], [promoTo]);
     * остальное делается здесь и показывается через [changed].
     */
    fun tap(sq: Int): Boolean {
        if (!userTurn() || thinking) return false
        val pc = g.position.board[sq]
        if (selected != Square.NONE && targets.contains(sq)) {
            val from = selected
            val moves = g.legalMoves().filter { Move.origin(it) == from && Move.target(it) == sq }
            if (moves.size > 1) {
                promoFrom = from
                promoTo = sq
                return true
            }
            play(moves[0])
            return false
        }
        if (pc != Piece.NONE && Piece.color(pc) == user && sq != selected) {
            selected = sq
            targets = g.legalMoves().filter { Move.origin(it) == sq }.map { Move.target(it) }
                .distinct().toIntArray()
        } else {
            clearSelection()
        }
        changed()
        return false
    }

    /** Фигура для превращения выбрана на B80 ([type] — `PieceType`). */
    fun promote(type: Int) {
        val from = promoFrom
        val to = promoTo
        promoFrom = Square.NONE
        promoTo = Square.NONE
        val m = g.legalMoves().firstOrNull {
            Move.origin(it) == from && Move.target(it) == to && Move.promotion(it) == type
        } ?: return
        play(m)
    }

    fun cancelPromo() {
        promoFrom = Square.NONE
        promoTo = Square.NONE
        changed()
    }

    /**
     * «Отменить ход»: свой последний ход вместе с ответом компьютера (или без ответа, если он ещё
     * думает). Только пока партия идёт и есть что отменять.
     */
    fun undo() {
        if (!canUndo()) return
        stopSearch()
        if (g.position.side == user) {
            g.undo()
            g.undo()
        } else {
            g.undo()
        }
        clearSelection()
        note = ""
        save()
        changed()
    }

    /**
     * «Предложить ничью». Право на ничью (троекратный повтор, 50 ходов) принимается сразу.
     * Иначе компьютер оценивает позицию (глубина 3, до 2 с): принимает, когда игрок стоит лучше
     * чем на 0,3 пешки, а после 30-го хода — и при равенстве (иначе в мёртвых ничейных
     * эндшпилях ничью было бы не заключить).
     */
    fun offerDraw() {
        if (!userTurn() || thinking) return
        if (g.canClaimDraw()) {
            agreeDraw()
            return
        }
        job(Limits(depth = 3, timeMs = 2000)) { r ->
            // Ход игрока, значит и оценка — с его стороны.
            val accept = r.value >= 30 || (r.value > -30 && g.position.fullmoveNumber >= 30)
            if (accept) {
                agreeDraw()
            } else {
                note = "Компьютер отказался"
                changed()
            }
        }
    }

    fun resign() {
        stopSearch()
        ChessSession.end = GameSave.END_RESIGN
        save()
        changed()
    }

    private fun agreeDraw() {
        ChessSession.end = GameSave.END_DRAW
        save()
        changed()
    }

    private fun play(m: Int) {
        g.play(m)
        afterMove()
    }

    private fun afterMove() {
        clearSelection()
        note = ""
        save()
        changed()
        if (!over()) think()
    }

    private fun think() {
        if (thinking || over() || g.position.side == user) return
        job(Level.of(ChessSession.level)) { r ->
            if (r.move != Move.NONE && g.isLegal(r.move)) {
                g.play(r.move)
                afterMove()
            } else {
                changed()
            }
        }
    }

    /** Поиск в отдельном потоке на копии позиции; [done] вызывается в потоке интерфейса. */
    private fun job(limits: Limits, done: (SearchResult) -> Unit) {
        thinking = true
        val my = ++gen
        val s = Search()
        search = s
        val copy = g.position.copy()
        changed()
        val worker = Thread {
            val r = try {
                s.think(copy, limits)
            } catch (t: Throwable) {
                Trace.step("chess: think FAIL ${t.javaClass.simpleName}: ${t.message}")
                null
            }
            act.runOnUiThread {
                if (my != gen) return@runOnUiThread
                thinking = false
                search = null
                if (r != null) done(r) else changed()
            }
        }
        worker.isDaemon = true
        worker.start()
    }

    private fun stopSearch() {
        gen++
        search?.stop()
        search = null
        thinking = false
    }

    private fun clearSelection() {
        selected = Square.NONE
        targets = IntArray(0)
    }

    private fun save() {
        act.prefs.chessSave = ChessSession.encode()
    }
}
