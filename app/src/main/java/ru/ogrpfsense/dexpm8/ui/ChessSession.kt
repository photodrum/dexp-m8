package ru.ogrpfsense.dexpm8.ui

import ru.ogrpfsense.dexpm8.chess.Color
import ru.ogrpfsense.dexpm8.chess.Game
import ru.ogrpfsense.dexpm8.chess.GameSave
import ru.ogrpfsense.dexpm8.chess.Level
import ru.ogrpfsense.dexpm8.chess.Move
import ru.ogrpfsense.dexpm8.chess.SavedGame

/**
 * Идущая партия с компьютером (п. 25.2): общая для экранов B79 и B81. Держит только числа и
 * [Game] — ни `Context`, ни `View`, поэтому утечки нет. Вечное хранилище — `Prefs.chessSave`:
 * [encode] пишется после каждого хода, [restore] вызывает меню B77 перед входом в партию.
 */
internal object ChessSession {
    var game = Game()
        private set
    var level = 4
        private set

    /** Цвет игрока ([Color.WHITE] или [Color.BLACK]); компьютер — противоположный. */
    var userColor = Color.WHITE
        private set

    /** [GameSave.END_NONE], [GameSave.END_RESIGN] или [GameSave.END_DRAW]. */
    var end = GameSave.END_NONE

    /** Доска повёрнута чёрными вниз: при игре чёрными сама, дальше — «Перевернуть доску» в B82. */
    var flipped = false

    /** Партия загружена и экрану B79 есть что показывать. */
    var live = false
        private set

    fun start(level: Int, color: Int) {
        game = Game()
        this.level = level.coerceIn(1, Level.COUNT)
        userColor = color
        end = GameSave.END_NONE
        flipped = color == Color.BLACK
        live = true
    }

    /** Загрузить партию из `Prefs.chessSave`; false — сохранения нет или оно повреждено. */
    fun restore(text: String): Boolean {
        val s = GameSave.decode(text) ?: return false
        val g = GameSave.replay(s) ?: return false
        game = g
        level = s.level
        userColor = s.color
        end = s.end
        flipped = s.color == Color.BLACK
        live = true
        return true
    }

    fun clear() {
        live = false
    }

    fun encode(): String = GameSave.encode(
        SavedGame(level, userColor, end, System.currentTimeMillis(),
            (0 until game.ply).map { Move.uci(game.moveAt(it)) })
    )

    val computerColor: Int get() = Color.opposite(userColor)

    /** Сколько ходов сделал игрок (для «Отменить ход»: без своих ходов отменять нечего). */
    fun userMoves(): Int = if (userColor == Color.WHITE) (game.ply + 1) / 2 else game.ply / 2
}
