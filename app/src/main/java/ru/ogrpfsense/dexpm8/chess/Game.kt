package ru.ogrpfsense.dexpm8.chess

// Партия: ходы с отменой, лист партии в SAN и исход по Правилам FIDE (ред. 2023, ст. 5 и 9).
// В Pulse этого нет (там только грубые проверки для поиска) — написано заново для п. 25.1б.
// Сама партия игрока и экран (25.2): поиск компьютера считает на своей копии позиции, а
// для отсечений в поиске хватает Position.isRepetition / hasInsufficientMaterial.
//
// Что партия заканчивает сама (FIDE 5.1–5.2, 9.6):
// - мат и пат (мат последним ходом важнее любой ничьей);
// - пятикратное повторение позиции и 75 ходов каждого без хода пешки и взятия;
// - мёртвая позиция по материалу: K–K, K и одна лёгкая против K, только слоны одного цвета полей.
// Что только даёт право требовать ничью (FIDE 9.2–9.3): троекратное повторение и 50 ходов.
// Требование ничьей, сдача, согласие и флаг — решения игроков и часов: это экран и сеть.

/** Состояние партии. */
object Status {
    const val PLAYING = 0
    const val CHECKMATE = 1
    const val STALEMATE = 2
    const val FIVEFOLD = 3
    const val MOVES_75 = 4
    const val DEAD = 5
}

class Game(fen: String = Fen.START) {
    private val pos = Fen.parse(fen)

    /** Позиция сейчас — только читать (ходы — через [play] и [undo]). */
    val position: Position get() = pos

    /** Начальная позиция партии (нормализованный FEN). */
    val startFen: String = Fen.write(pos)

    /** Сделано полуходов с начала партии. */
    var ply = 0
        private set

    /** Лист партии: SAN каждого полухода. */
    val san = ArrayList<String>()

    var status = Status.PLAYING
        private set

    private var moves = IntArray(64)

    /** Ключ позиции для повторений по каждому полуходу; [0] — начальная. */
    private var keys = LongArray(65)

    private val gen = MoveGen()
    private var legal = IntArray(0)

    init {
        refresh()
    }

    /** Ход номер [i] (с нуля). */
    fun moveAt(i: Int): Int {
        require(i in 0 until ply) { "Партия: нет хода $i" }
        return moves[i]
    }

    /** Последний ход или [Move.NONE]. */
    val lastMove: Int get() = if (ply > 0) moves[ply - 1] else Move.NONE

    /** Законные ходы сейчас (копия); партия окончена — пусто. */
    fun legalMoves(): IntArray = if (status == Status.PLAYING) legal.copyOf() else IntArray(0)

    fun isLegal(m: Int) = status == Status.PLAYING && legal.contains(m)

    /** Сделать законный ход; незаконный или партия окончена — [IllegalArgumentException]. */
    fun play(m: Int) {
        require(status == Status.PLAYING) { "Партия окончена" }
        require(legal.contains(m)) { "Партия: ход ${Move.uci(m)} незаконен" }
        san.add(San.write(pos, m))
        pos.makeMove(m)
        if (ply == moves.size) {
            moves = moves.copyOf(ply * 2)
            keys = keys.copyOf(ply * 2 + 1)
        }
        moves[ply++] = m
        refresh()
    }

    /** Ход по SAN («Nf3», «exd5», «O-O»); возвращает сделанный ход. */
    fun playSan(text: String): Int = San.parse(pos, text).also { play(it) }

    /** Ход по UCI («e2e4», «e7e8q»); возвращает сделанный ход. */
    fun playUci(text: String): Int = San.fromUci(pos, text).also { play(it) }

    /** Отменить последний ход; ходов нет — false. Окончание партии отменяется вместе с ходом. */
    fun undo(): Boolean {
        if (ply == 0) return false
        ply--
        pos.undoMove(moves[ply])
        san.removeAt(san.size - 1)
        refresh()
        return true
    }

    /** Сколько раз текущая позиция встречалась в партии (сама она — первый раз). */
    fun repetitions(): Int {
        val k = keys[ply]
        var n = 0
        // Раньше последнего хода пешки или взятия позиция повториться не могла.
        val stop = maxOf(0, ply - pos.halfmoveClock)
        var i = ply
        while (i >= stop) {
            if (keys[i] == k) n++
            i -= 2
        }
        return n
    }

    /** Можно ли сейчас потребовать ничью: троекратное повторение или 50 ходов (FIDE 9.2, 9.3). */
    fun canClaimDraw() = status == Status.PLAYING && (pos.halfmoveClock >= 100 || repetitions() >= 3)

    /** Итог для PGN: «1-0», «0-1», «1/2-1/2»; идёт — «*». */
    fun result(): String = when (status) {
        Status.PLAYING -> "*"
        Status.CHECKMATE -> if (pos.side == Color.WHITE) "0-1" else "1-0"
        else -> "1/2-1/2"
    }

    private fun refresh() {
        val l = gen.legal(pos)
        legal = l.moves.copyOf(l.size)
        keys[ply] = repetitionKey()
        status = when {
            legal.isEmpty() -> if (pos.isCheck()) Status.CHECKMATE else Status.STALEMATE
            repetitions() >= 5 -> Status.FIVEFOLD
            pos.halfmoveClock >= 150 -> Status.MOVES_75
            isDeadMaterial(pos) -> Status.DEAD
            else -> Status.PLAYING
        }
    }

    /**
     * Ключ позиции для повторений. FIDE 9.2.3: позиции одинаковы, если одинаковы возможные ходы.
     * Поле взятия на проходе в [Position.key] есть, если рядом стоит пешка, — но взять ей может
     * мешать связка; тогда для повторения поле не считается.
     */
    private fun repetitionKey(): Long {
        val ep = pos.epSquare
        if (ep == Square.NONE) return pos.key
        for (m in legal) if (Move.type(m) == Move.EN_PASSANT) return pos.key
        return pos.key xor Zobrist.ep[ep]
    }

    companion object {
        /**
         * Мат невозможен никакой серией ходов из-за материала (FIDE 9.6.2, частный случай):
         * нет пешек, ладей и ферзей и либо лёгких фигур на доске не больше одной,
         * либо коней нет, а все слоны (с обеих сторон) — на полях одного цвета.
         * Запертые пешки и прочие мёртвые позиции не ищем (как lichess).
         */
        fun isDeadMaterial(pos: Position): Boolean {
            var knights = 0
            var bishops = 0
            var colors = 0
            for (c in 0..1) {
                if (pos.count(c, PieceType.PAWN) + pos.count(c, PieceType.ROOK) + pos.count(c, PieceType.QUEEN) != 0)
                    return false
                knights += pos.count(c, PieceType.KNIGHT)
                var bb = pos.pieces[Piece.of(c, PieceType.BISHOP)]
                while (bb != 0L) {
                    val sq = Bb.next(bb)
                    bishops++
                    colors = colors or (1 shl ((Square.file(sq) + Square.rank(sq)) and 1))
                    bb = bb and (bb - 1)
                }
            }
            return knights + bishops <= 1 || (knights == 0 && colors != 3)
        }
    }
}
