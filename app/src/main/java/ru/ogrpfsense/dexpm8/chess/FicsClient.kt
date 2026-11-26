package ru.ogrpfsense.dexpm8.chess

import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

// Соединение с FICS гостем (п. 25.3): сокет и два потока.
// - «fics-read»: соединяется, входит («login:» → «guest», «Press return…» → пустая строка),
//   шлёт [Fics.setup] и отдаёт события [listener] — **в этом потоке** (на UI переносит Handler).
// - «fics-write»: очередь команд — сеть в UI-потоке Android запрещена, [send] только кладёт
//   строку. Тишина дольше [PING_MS] — пустая строка (сервер отвечает приглашением), и чтение
//   с тайм-аутом [READ_MS] замечает мёртвую связь за ~2 мин, а не за часы TCP.
// - [close] — «quit» и закрытие. Посреди партии «quit» — поражение («forfeits by
//   disconnection», проверено): экран спрашивает до этого.
// Без android.* — тот же класс работает на хосте (tools/fics_live.kt).

class FicsClient(
    /** Версия оболочки — в `set interface`. */
    private val version: String,
    private val listener: (FicsEvent) -> Unit,
    private val host: String = Fics.HOST,
    private val port: Int = Fics.PORT,
) {
    /** Имя гостя; null — вход ещё не выполнен. */
    @Volatile
    var name: String? = null
        private set

    @Volatile
    private var closing = false

    private val queue = LinkedBlockingQueue<String>()

    /** Соединиться и войти в своём потоке; итог — [FicsEvent.LoggedIn] или [FicsEvent.Closed]. */
    fun start() {
        Thread({ read() }, "fics-read").apply { isDaemon = true }.start()
    }

    /** Команда серверу (без «\n»). После [close] — ничего. */
    fun send(cmd: String) {
        if (!closing) queue.offer(cmd)
    }

    /** Выйти: «quit» и закрыть сокет; ещё соединяется — закроется сразу после. */
    fun close() {
        if (closing) return
        closing = true
        queue.offer(BYE)
    }

    private fun read() {
        val s = Socket()
        var error: String? = null
        try {
            s.connect(InetSocketAddress(host, port), CONNECT_MS)
            s.tcpNoDelay = true
            s.soTimeout = LOGIN_MS
            if (!closing) {
                Thread({ write(s) }, "fics-write").apply { isDaemon = true }.start()
                loop(s)
                if (!closing) error = "Сервер закрыл соединение"
            }
        } catch (e: UnknownHostException) {
            error = "Сервер не найден — нет сети?"
        } catch (e: SocketTimeoutException) {
            error = "Сервер не отвечает"
        } catch (e: ConnectException) {
            error = "Сервер недоступен"
        } catch (e: NoRouteToHostException) {
            error = "Сервер недоступен"
        } catch (e: IOException) {
            error = "Связь прервалась"
        } finally {
            try {
                s.close()
            } catch (ignored: IOException) {
            }
            queue.offer(BYE) // писатель, если жив, тоже выходит
        }
        listener(FicsEvent.Closed(if (closing) null else error))
    }

    private fun loop(s: Socket) {
        val input = s.getInputStream()
        val lines = FicsLines()
        val batch = ArrayList<String>()
        val buf = ByteArray(8192)
        var guestSent = false
        while (true) {
            val n = input.read(buf)
            if (n < 0) return
            lines.feed(buf, n, batch)
            for (line in batch) {
                if (name != null) {
                    listener(Fics.parse(line))
                    continue
                }
                if (Fics.guestName(line) != null) queue.offer("") // «Press return …»
                val who = Fics.sessionName(line)
                if (who != null) {
                    s.soTimeout = READ_MS
                    for (c in Fics.setup(version)) queue.offer(c)
                    name = who
                    listener(FicsEvent.LoggedIn(who))
                } else {
                    listener(FicsEvent.Text(line))
                }
            }
            batch.clear()
            if (!guestSent && name == null && lines.tail.trimEnd().endsWith("login:")) {
                guestSent = true
                queue.offer("guest")
            }
        }
    }

    private fun write(s: Socket) {
        try {
            val out = s.getOutputStream()
            while (true) {
                val cmd = queue.poll(PING_MS, TimeUnit.MILLISECONDS) ?: ""
                if (cmd === BYE) {
                    if (closing) {
                        out.write("quit\n".toByteArray(Charsets.ISO_8859_1))
                        out.flush()
                    }
                    break
                }
                out.write((cmd + "\n").toByteArray(Charsets.ISO_8859_1))
                out.flush()
            }
        } catch (e: IOException) {
            // Обрыв заметит чтение и скажет слушателю.
        } catch (e: InterruptedException) {
        } finally {
            // Сокет закрывает писатель только при нашем выходе: иначе «quit» не успел бы уйти.
            if (closing) try {
                s.close()
            } catch (ignored: IOException) {
            }
        }
    }

    companion object {
        const val CONNECT_MS = 15_000
        const val LOGIN_MS = 30_000

        /** Без единого байта дольше — связь мертва (пинг раз в [PING_MS] всегда получает ответ). */
        const val READ_MS = 150_000
        const val PING_MS = 60_000L

        /** Метка «выйти» в очереди записи — сравнивается по ссылке. */
        private val BYE = String(charArrayOf('q', 'u', 'i', 't', '!'))
    }
}
