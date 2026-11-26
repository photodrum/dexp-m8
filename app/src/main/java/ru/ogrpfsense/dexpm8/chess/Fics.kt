package ru.ogrpfsense.dexpm8.chess

// Протокол FICS (freechess.org:5000) для игры гостем, п. 25.3: текст по TCP, без timeseal.
// Здесь — чистый разбор: байты → строки → события ([FicsEvent]). Сокет и потоки — FicsClient.kt.
// Строки сняты с сервера 2026-11-26 двумя гостями с хоста (стенограмма и разбор —
// docs/archive/DEVLOG-2026-11-26-25.3.md). Пакет без android.* — проверяется на хосте.

/**
 * Байты сервера → строки. Сервер шлёт «\n\r», приглашение «fics% » без перевода строки
 * (оно оказывается в начале следующей строки), а после входа — telnet IAC WONT ECHO
 * (`FF FC 01`). Чанки режутся посреди строки, поэтому хвост копится до «\n».
 */
class FicsLines {
    private val line = StringBuilder(256)
    private var iac = 0

    /** Недособранный хвост («login: », «fics% ») — по нему виден запрос входа. */
    val tail: String get() = line.toString()

    /** Разобрать [n] байт; готовые строки (без приглашения, пустые — нет) — в [out]. */
    fun feed(buf: ByteArray, n: Int, out: MutableList<String>) {
        for (i in 0 until n) {
            val b = buf[i].toInt() and 0xFF
            when {
                // IAC и команда; WILL/WONT/DO/DONT (FB…FE) несут ещё байт опции
                iac == 1 -> iac = if (b in 0xFB..0xFE) 2 else 0
                iac == 2 -> iac = 0
                b == 0xFF -> iac = 1
                b == '\r'.code -> {}
                b == '\n'.code -> {
                    val s = strip(line.toString())
                    line.setLength(0)
                    if (s.isNotEmpty()) out.add(s)
                }
                else -> line.append(b.toChar())
            }
        }
    }

    companion object {
        const val PROMPT = "fics% "

        fun strip(s: String): String {
            var t = s
            while (t.startsWith(PROMPT)) t = t.substring(PROMPT.length)
            t = t.trimEnd()
            return if (t == PROMPT.trimEnd()) "" else t
        }
    }
}

object Fics {
    const val HOST = "freechess.org"
    const val PORT = 5000

    /** Контроли времени B85: минут на партию + секунд за ход. */
    val CONTROLS = listOf(15 to 10, 30 to 0, 10 to 5)

    private val GAME = Regex("""^\{Game (\d+) \((\w+) vs\. (\w+)\) ([^}]*)\}\s*(\S*)""")
    private val SEEK = Regex("""^Your seek has been posted with index (\d+)""")
    private val NAME = Regex("""enter the server as "(\w+)"""")
    private val SESSION = Regex("""^\*\*\*\* Starting FICS session as (\w+)""")
    private val ILLEGAL = Regex("""^Illegal move \((.*)\)""")

    /** Имя гостя из «Press return to enter the server as "GuestFSMB":» или null. */
    fun guestName(line: String): String? = NAME.find(line)?.groupValues?.get(1)

    /** «**** Starting FICS session as GuestFSMB(U) ****» — вход завершён; имя или null. */
    fun sessionName(line: String): String? = SESSION.find(line)?.groupValues?.get(1)

    /** Строка после входа → событие. */
    fun parse(line: String): FicsEvent {
        Style12.parse(line)?.let { return FicsEvent.Board(it) }
        GAME.find(line)?.let { m ->
            val (id, w, b, text, result) = m.destructured
            return if (text.startsWith("Creating") || text.startsWith("Continuing")) {
                FicsEvent.Started(id.toInt(), w, b, text)
            } else {
                FicsEvent.Ended(id.toInt(), w, b, text, result, FicsEnd.kind(text, result))
            }
        }
        SEEK.find(line)?.let { return FicsEvent.SeekPosted(it.groupValues[1].toInt()) }
        if (line.startsWith("Your seeks have been removed")) return FicsEvent.SeekRemoved
        ILLEGAL.find(line)?.let { return FicsEvent.Illegal(it.groupValues[1]) }
        val who = line.substringBefore(' ')
        return when {
            line.endsWith(" offers you a draw.") -> FicsEvent.DrawOffer(who)
            line.endsWith(" declines the draw request.") -> FicsEvent.DrawDeclined(who)
            line.contains(" would like to take back ") -> FicsEvent.TakebackAsk(who)
            line.contains(" would like to abort the game") -> FicsEvent.AbortAsk(who)
            else -> FicsEvent.Text(line)
        }
    }

    /**
     * Ход для сервера: «e2e4», рокировка — ходом короля «e1g1» (принимается, проверено),
     * превращение — «a7a8=q» (запись «a7a8q» сервер не понимает: «Command not found»).
     */
    fun moveCommand(m: Int): String {
        val s = Square.name(Move.origin(m)) + Square.name(Move.target(m))
        return if (Move.type(m) == Move.PROMOTION) s + "=" + "pnbrqk"[Move.promotion(m)] else s
    }

    /** Поиск соперника: только безрейтинговые — гости рейтинговых не играют. */
    fun seekCommand(minutes: Int, increment: Int) = "seek $minutes $increment u"

    /**
     * Настройки после входа. `style 12` и `iset ms 1` — позиция и часы в мс; `nowrap` и
     * `width` — строки не переносятся; `autoflag` — флаг соперника сервер ставит сам (на
     * E-Ink «флаг» вовремя не нажать); чаты, объявления, результаты чужих партий выключены.
     */
    fun setup(version: String): List<String> = listOf(
        "set style 12", "iset nowrap 1", "iset ms 1", "set width 240", "set bell 0",
        "set shout 0", "set cshout 0", "set seek 0", "set kibitz 0", "set gin 0", "set pin 0",
        "set ptime 0", "set autoflag 1", "set open $OPEN", "- channel 4", "- channel 53",
        "set interface DEXP M8 Prudentia $version",
    )

    /** Принимать ли чужие «match»: поиск идёт через `seek`, вызовы не нужны (проверено ли — см. DEVLOG). */
    const val OPEN = 0
}
