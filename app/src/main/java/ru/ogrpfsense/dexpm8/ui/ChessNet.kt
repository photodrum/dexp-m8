package ru.ogrpfsense.dexpm8.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import ru.ogrpfsense.dexpm8.chess.Fics
import ru.ogrpfsense.dexpm8.chess.FicsClient
import ru.ogrpfsense.dexpm8.chess.FicsEnd
import ru.ogrpfsense.dexpm8.chess.FicsEvent
import ru.ogrpfsense.dexpm8.chess.NetGame
import ru.ogrpfsense.dexpm8.chess.Style12

/**
 * Игра по сети гостем FICS (п. 25.3, B85–B87): одно соединение на процесс, общее для экранов, —
 * как [ChessSession], без `Context` и `View`. Всё — **в UI-потоке**: события [FicsClient]
 * приходят в его потоке чтения, [Handler] главного потока переносит их сюда, затем — [onChange].
 *
 * Состояния: [IDLE] → [CONNECTING] → [READY] ⇄ [SEEKING] → [PLAYING] → [ENDED] → [SEEKING] …;
 * обрыв или нет сети — [OFFLINE] (B87, причина — [error]). [leave] — назад в [IDLE].
 *
 * Сон: пока поиск или партия — [onAwake] (true); его ставит [EinkActivity] — флаг окна
 * `FLAG_KEEP_SCREEN_ON` и без заставки B19 (подпись B85: «книга не засыпает»), иначе гость
 * проиграл бы по времени, пока книга спит.
 *
 * Выход посреди партии — поражение («forfeits by disconnection» сразу; доигрывания гостям нет,
 * проверено 2026-11-26): экран спрашивает до [leave], как «Сдаться?» B84.
 */
internal object ChessNet {
    const val IDLE = 0
    const val CONNECTING = 1
    const val READY = 2
    const val SEEKING = 3
    const val PLAYING = 4
    const val ENDED = 5
    const val OFFLINE = 6

    var state = IDLE
        private set

    /** Имя гостя («GuestQRTZ»); пусто — не вошли. */
    var me = ""
        private set

    /** Причина [OFFLINE] по-русски. */
    var error = ""
        private set

    /** Идущая или законченная партия; null — партии не было. */
    var net: NetGame? = null
        private set

    /** Контроль поиска (B85, [Fics.CONTROLS]): минут + секунд за ход. */
    var minutes = 15
        private set
    var increment = 10
        private set

    /** Начало поиска ([now]) — для «0:42» на B85а. */
    var seekSince = 0L
        private set

    /** Соперник предлагает ничью (B87б) — до ответа или нашего хода. */
    var drawOffered = false
        private set

    /** Соперник отказался от нашей ничьей — до следующего хода. */
    var drawDeclined = false
        private set

    /** Итог [ENDED]: вид ([FicsEnd]), «1-0» / «0-1» / «1/2-1/2» / «*», текст сервера. */
    var endKind = FicsEnd.OTHER
        private set
    var endResult = ""
        private set
    var endText = ""
        private set

    /** Текущий экран: перерисоваться. Ставит экран при показе, снимает при уходе. */
    var onChange: (() -> Unit)? = null

    /** Запрет сна: ставит [EinkActivity] в onCreate, снимает в onDestroy. */
    var onAwake: ((Boolean) -> Unit)? = null
        set(v) {
            field = v
            v?.invoke(awake)
        }

    private val ui = Handler(Looper.getMainLooper())
    private var client: FicsClient? = null

    /** Поиск сразу после входа. */
    private var seekAfterLogin = false

    private val awake: Boolean get() = state == SEEKING || state == PLAYING

    /** Монотонное время, мс — для часов [NetGame] и счётчика поиска. */
    fun now(): Long = SystemClock.elapsedRealtime()

    /** Найти соперника с контролем [min] + [inc]; соединение — по надобности. */
    fun seek(min: Int, inc: Int, appVersion: String) {
        minutes = min
        increment = inc
        when (state) {
            READY, ENDED -> sendSeek()
            IDLE, OFFLINE -> {
                error = ""
                seekAfterLogin = true
                set(CONNECTING)
                // Событие помечено своим клиентом: хвост старого после leave() не тронет новый.
                lateinit var c: FicsClient
                c = FicsClient(appVersion, listener = { e -> ui.post { handle(c, e) } })
                client = c
                c.start()
            }
            else -> {}
        }
    }

    /** «Отмена» на B85а: снять поиск; соединение остаётся для следующего. */
    fun cancelSeek() {
        seekAfterLogin = false
        when (state) {
            SEEKING -> {
                client?.send("unseek")
                set(READY)
            }
            CONNECTING -> leave()
        }
    }

    /** Ход касанием; false — не наш ход или ход незаконен. */
    fun move(m: Int): Boolean {
        if (state != PLAYING) return false
        val cmd = net?.play(m) ?: return false
        drawOffered = false
        drawDeclined = false
        client?.send(cmd)
        changed()
        return true
    }

    /** Предложить ничью; при предложении соперника (B87б) — это согласие. */
    fun draw() {
        if (state != PLAYING) return
        drawOffered = false
        client?.send("draw")
        changed()
    }

    /** Отказ от ничьей соперника (B87б). */
    fun declineDraw() {
        if (!drawOffered) return
        drawOffered = false
        client?.send("decline")
        changed()
    }

    fun resign() {
        if (state == PLAYING) client?.send("resign")
    }

    /** Выйти из сети: «quit» и закрытие. Посреди партии — поражение (см. выше). */
    fun leave() {
        seekAfterLogin = false
        client?.close()
        client = null
        set(IDLE)
    }

    private fun sendSeek() {
        seekAfterLogin = false
        drawOffered = false
        drawDeclined = false
        seekSince = now()
        client?.send(Fics.seekCommand(minutes, increment))
        set(SEEKING)
    }

    private fun handle(from: FicsClient, e: FicsEvent) {
        if (from !== client) return
        when (e) {
            is FicsEvent.LoggedIn -> {
                me = e.name
                Trace.step("fics: вход ${e.name}")
                set(READY)
                if (seekAfterLogin) sendSeek()
            }
            is FicsEvent.Started -> {
                if (!e.white.equals(me, true) && !e.black.equals(me, true)) return
                Trace.step("fics: партия ${e.game} ${e.white}–${e.black}")
                client?.send("unseek") // одна партия за раз: прочие объявления — долой
                net = NetGame(me, e.game)
                drawOffered = false
                drawDeclined = false
                set(PLAYING)
            }
            is FicsEvent.Board -> if (net?.apply(e.s, now()) == true) {
                if (e.s.relation == Style12.MY_MOVE) drawDeclined = false
                changed()
            }
            is FicsEvent.Ended -> if (e.game == net?.id && state == PLAYING) {
                Trace.step("fics: конец ${e.game} ${e.result} ${e.text}")
                endKind = e.kind
                endResult = e.result
                endText = e.text
                drawOffered = false
                set(ENDED)
            }
            is FicsEvent.DrawOffer -> if (state == PLAYING) {
                drawOffered = true
                changed()
            }
            is FicsEvent.DrawDeclined -> if (state == PLAYING) {
                drawDeclined = true
                changed()
            }
            // Возврат ходов и отмену партии на книге не предлагаем — отказ сразу.
            is FicsEvent.TakebackAsk, is FicsEvent.AbortAsk -> client?.send("decline")
            is FicsEvent.Illegal -> Trace.step("fics: сервер отверг ход ${e.move}")
            is FicsEvent.Closed -> {
                client = null
                Trace.step("fics: закрыто ${e.error ?: "нами"}")
                // B87 — только если связь была нужна сейчас; после конца партии следующий
                // поиск просто соединится заново.
                val needed = state == CONNECTING || state == SEEKING || state == PLAYING
                error = if (needed) e.error ?: "Связь прервалась" else ""
                set(if (needed) OFFLINE else if (state == ENDED) ENDED else IDLE)
            }
            else -> {}
        }
    }

    private fun set(s: Int) {
        val was = awake
        state = s
        if (awake != was) onAwake?.invoke(awake)
        changed()
    }

    private fun changed() {
        onChange?.invoke()
    }
}
