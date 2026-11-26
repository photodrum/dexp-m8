package ru.ogrpfsense.dexpm8.chess

// События сервера FICS (п. 25.3): что [Fics.parse] делает из строки. Строки сняты с сервера
// 2026-11-26 (docs/archive/DEVLOG-2026-11-26-25.3.md). Пакет без android.*.

/** Событие сервера. Приходит в потоке чтения [FicsClient]. */
sealed class FicsEvent {
    /** Вход гостем выполнен, сервер выдал имя («GuestFSMB»). */
    class LoggedIn(val name: String) : FicsEvent()

    /** Позиция партии (наша, наблюдаемая или разбираемая). */
    class Board(val s: Style12) : FicsEvent()

    /** «{Game 28 (A vs. B) Creating unrated standard match.}» — партия началась. */
    class Started(val game: Int, val white: String, val black: String, val text: String) : FicsEvent()

    /** «{Game 2 (A vs. B) A resigns} 1-0» — партия кончилась; [kind] — [FicsEnd]. */
    class Ended(
        val game: Int, val white: String, val black: String,
        val text: String, val result: String, val kind: Int,
    ) : FicsEvent()

    /** «Your seek has been posted with index 22.» */
    class SeekPosted(val index: Int) : FicsEvent()

    /** «Your seeks have been removed.» */
    object SeekRemoved : FicsEvent()

    /** «GuestFLMP offers you a draw.» */
    class DrawOffer(val who: String) : FicsEvent()

    /** «GuestMXXT declines the draw request.» — соперник отказал в ничьей. */
    class DrawDeclined(val who: String) : FicsEvent()

    /** «GuestFLMP would like to take back 1 half move(s).» — на книге не принимаем. */
    class TakebackAsk(val who: String) : FicsEvent()

    /** «GuestX would like to abort the game; …» */
    class AbortAsk(val who: String) : FicsEvent()

    /** «Illegal move (e2e5).» — следом сервер сам присылает прежнюю позицию. */
    class Illegal(val move: String) : FicsEvent()

    /** Прочие строки: на экран не идут, в трассе пригодятся. */
    class Text(val line: String) : FicsEvent()

    /** Соединение закрыто: [error] — причина по-русски, null — закрыли мы сами. */
    class Closed(val error: String?) : FicsEvent()
}

/** Как кончилась партия — по тексту в фигурных скобках. */
object FicsEnd {
    const val OTHER = 0
    const val MATE = 1
    const val RESIGN = 2
    const val FLAG = 3
    const val DRAW = 4
    const val ABORT = 5

    /** Соперник (или мы) потерял связь. */
    const val DISCONNECT = 6
    const val ADJOURN = 7

    fun kind(text: String, result: String): Int = when {
        "checkmated" in text -> MATE
        "resigns" in text -> RESIGN
        "disconnect" in text || "lost connection" in text -> DISCONNECT
        "adjourned" in text -> ADJOURN
        "aborted" in text -> ABORT
        "forfeits on time" in text -> FLAG
        result == "1/2-1/2" -> DRAW
        else -> OTHER
    }
}
