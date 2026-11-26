package ru.ogrpfsense.dexpm8.chess

// Сохранённая партия с компьютером (п. 25.2): одна строка в Prefs, ходы — в UCI.
// Формат: «1|уровень|цвет игрока|как закончилась|время, мс|e2e4 e7e5 …».
// Позиция получается проигрыванием ходов через [Game] — лист SAN, повторы и 50 ходов
// восстанавливаются сами, поэтому FEN в сохранении не нужен (партия всегда с начальной позиции).
// Пакет без android.* — проверяется на хосте.

class SavedGame(
    val level: Int,
    /** Цвет игрока: [Color.WHITE] или [Color.BLACK]. */
    val color: Int,
    /** [GameSave.END_NONE], [GameSave.END_RESIGN] или [GameSave.END_DRAW]. */
    val end: Int,
    val time: Long,
    val moves: List<String>,
)

object GameSave {
    /** Партия идёт или закончилась на доске (мат, пат, повтор…) — это видно по самим ходам. */
    const val END_NONE = 0
    /** Игрок сдался. */
    const val END_RESIGN = 1
    /** Ничья по требованию (повтор, 50 ходов) или по согласию компьютера. */
    const val END_DRAW = 2

    private const val VERSION = "1"

    fun encode(s: SavedGame): String =
        "$VERSION|${s.level}|${s.color}|${s.end}|${s.time}|${s.moves.joinToString(" ")}"

    /** null — строка пуста, чужая версия или повреждена. */
    fun decode(text: String): SavedGame? {
        if (text.isEmpty()) return null
        val p = text.split('|', limit = 6)
        if (p.size != 6 || p[0] != VERSION) return null
        return try {
            val level = p[1].toInt()
            val color = p[2].toInt()
            val end = p[3].toInt()
            if (level !in 1..Level.COUNT || (color != Color.WHITE && color != Color.BLACK)) return null
            if (end != END_NONE && end != END_RESIGN && end != END_DRAW) return null
            val moves = if (p[5].isBlank()) emptyList() else p[5].trim().split(' ')
            SavedGame(level, color, end, p[4].toLong(), moves)
        } catch (e: NumberFormatException) {
            null
        }
    }

    /** Партия с начала до последнего сохранённого хода; null — ход не проигрывается. */
    fun replay(s: SavedGame): Game? {
        val g = Game()
        return try {
            for (m in s.moves) g.playUci(m)
            g
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
