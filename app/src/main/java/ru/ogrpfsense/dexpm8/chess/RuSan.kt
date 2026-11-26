package ru.ogrpfsense.dexpm8.chess

// Русская запись ходов для экрана (п. 25.2, решение человека 2026-11-26): Кр Ф Л С К вместо
// K Q R B N; буквы вертикалей остаются латинскими, рокировка и знаки «x + # =» — как есть.
// Пакет без android.* — проверяется на хосте вместе с остальным ядром.

object RuSan {
    /** «Nf3» → «Кf3», «Kxe2» → «Крxe2», «e8=Q+» → «e8=Ф+», «O-O» → «O-O». */
    fun of(san: String): String {
        if (san.isEmpty()) return san
        val sb = StringBuilder(san.length + 2)
        var i = 0
        // Первая буква — фигура; у пешки и рокировки её нет (рокировка пишется заглавной «O»).
        val first = piece(san[0])
        if (first != null) {
            sb.append(first)
            i = 1
        }
        while (i < san.length) {
            val ch = san[i]
            // Превращение: после «=» стоит фигура.
            if (ch == '=' && i + 1 < san.length) {
                sb.append('=').append(piece(san[i + 1]) ?: san[i + 1].toString())
                i += 2
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }

    private fun piece(ch: Char): String? = when (ch) {
        'K' -> "Кр"
        'Q' -> "Ф"
        'R' -> "Л"
        'B' -> "С"
        'N' -> "К"
        else -> null
    }
}
