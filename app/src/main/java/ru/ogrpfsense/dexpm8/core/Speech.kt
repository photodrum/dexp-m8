package ru.ogrpfsense.dexpm8.core

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import ru.ogrpfsense.dexpm8.ui.Trace
import java.util.Locale

/**
 * Чтение вслух системным движком TTS (п. 19, R6).
 *
 * **Почему системный `TextToSpeech`, а не свой синтез.** На книге стоит
 * `com.google.android.tts` (`/system/app/TTS`) с уже скачанным русским голосом
 * `ru-ru-x-dfc` (32 МБ, `/data/user_de/0/com.google.android.tts/app_voices_greco_v15`) —
 * он и есть движок по умолчанию (`tts_default_synth`). Синтез идёт офлайн, сеть не нужна.
 * Запасной `com.svox.pico` русского не умеет, поэтому проверка языка обязательна:
 * без неё движок молча прочитал бы русский текст английскими буквами.
 *
 * **Кто владеет.** Один экземпляр на всю оболочку (`EinkActivity.speech`): `TextToSpeech`
 * после `shutdown()` не поднимается, а его `init` асинхронный (1–2 с на книге) — держать
 * это в экране читалки значило бы ждать при каждом открытии книги.
 *
 * **Дробление.** `TextToSpeech.getMaxSpeechInputLength()` (4000 знаков) — предел одного
 * вызова `speak`; очередь `QUEUE_ADD` начинает говорить второй кусок, пока читается первый,
 * поэтому склейка бесшовная. Режем по границам предложений — иначе голос обрывает фразу
 * на полуслове и делает паузу посреди неё.
 *
 * **Как узнать, что страница дочитана.** `UtteranceProgressListener.onDone` приходит
 * на последнем куске страницы; оттуда экран листает дальше и отдаёт текст следующей
 * страницы ([onPageDone]). Всё на UI-потоке: слушатель приходит с потока движка TTS.
 */
class Speech(private val ctx: Context) {

    /** Готов ли движок: без него кнопку «Вслух» показывать нечем. null — `init` ещё идёт. */
    @Volatile var ready: Boolean? = null
        private set

    /** Умеет ли движок русский голос (офлайн). false — читать русскую книгу нечем. */
    @Volatile var hasRu = false
        private set

    /** Читаем сейчас: между первым куском и концом страницы. */
    @Volatile var speaking = false
        private set

    /** Страница дочитана: экран листает и присылает текст следующей. */
    var onPageDone: (() -> Unit)? = null

    /** Движок отказал или языка нет — экран показывает это подписью. */
    var onFail: ((String) -> Unit)? = null

    /** Страница, которую сейчас читаем: по смене отсекаются запоздавшие `onDone`. */
    private var page = -1
    /** Номер прочтения; сравнение в `onDone` отсекает эхо прошлой страницы. */
    private var gen = 0
    private var tts: TextToSpeech? = null
    private var rate = 100

    /** Скорость в процентах (100 — как есть, 200 — вдвое быстрее). */
    fun speed(pct: Int) {
        rate = pct.coerceIn(50, 300)
        try {
            tts?.setSpeechRate(rate / 100f)
        } catch (ignored: Throwable) {
        }
    }

    /**
     * Поднять движок. Вызывать один раз на старте оболочки: `init` асинхронный, а ошибка
     * приходит кодом (`ERROR` — движка нет, `MISSING_DATA` — нет голосовых данных).
     */
    fun init() {
        if (tts != null) return
        ready = null
        tts = TextToSpeech(ctx) { status ->
            val t = tts
            if (status != TextToSpeech.SUCCESS || t == null) {
                ready = false
                hasRu = false
                Trace.step("tts: init FAIL status=$status")
                return@TextToSpeech
            }
            hasRu = available(t)
            t.setSpeechRate(rate / 100f)
            t.setOnUtteranceProgressListener(listener)
            ready = true
            Trace.step("tts: init ok ru=$hasRu rate=$rate")
        }
    }

    /**
     * Есть ли русский голос. Порядок: русский, затем локаль системы — у прошивок без
     * русского пакета `isLanguageAvailable` отвечает `LANG_MISSING_DATA`, и читать
     * кириллицу таким движком нельзя (получилась бы латиница).
     */
    private fun available(t: TextToSpeech): Boolean = try {
        val ru = t.isLanguageAvailable(Locale("ru", "RU"))
        val sys = t.isLanguageAvailable(Locale.getDefault())
        Trace.step("tts: lang ru=$ru default(${Locale.getDefault()})=$sys")
        ru >= TextToSpeech.LANG_AVAILABLE || sys >= TextToSpeech.LANG_AVAILABLE
    } catch (t2: Throwable) {
        Trace.step("tts: lang FAIL ${t2.javaClass.simpleName}: ${t2.message}")
        false
    }

    /**
     * Прочитать [text] как страницу [page]: текст следующей страницы экран пришлёт
     * из [onPageDone]. Текст короче одного вызова — один кусок.
     */
    fun readPage(page: Int, text: String) {
        val t = tts ?: return
        if (ready != true) return
        if (!hasRu) {
            onFail?.invoke("Нет голоса")
            Trace.step("tts: нет русского голоса")
            return
        }
        val clean = clean(text)
        if (clean.isEmpty()) return
        this.page = page
        gen++
        speaking = true
        val my = gen
        val chunks = split(clean, PAGE_LIMIT)
        for ((i, chunk) in chunks.withIndex()) {
            val last = i == chunks.size - 1
            val id = "$my:$i:$page:${if (last) 1 else 0}"
            t.speak(chunk, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null, id)
        }
        Trace.step("tts: read page=$page знаков=${clean.length} кусков=${chunks.size}")
    }

    /** Остановить и не читать дальше (листание руками, уход с экрана, второе нажатие). */
    fun stop() {
        if (!speaking && tts == null) return
        val was = speaking
        speaking = false
        gen++
        page = -1
        try {
            tts?.stop()
        } catch (ignored: Throwable) {
        }
        if (was) Trace.step("tts: stop")
    }

    /** Закрыть движок с оболочкой. */
    fun shutdown() {
        stop()
        try {
            tts?.shutdown()
        } catch (ignored: Throwable) {
        }
        tts = null
        ready = null
        hasRu = false
    }

    /**
     * Слушатель: `onDone` последнего куска страницы зовёт [onPageDone]. Остальное — трасса:
     * на E-Ink ошибка синтеза не видна, а без строки в логах причину не найти.
     */
    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}

        override fun onDone(utteranceId: String?) {
            val parts = utteranceId?.split(':') ?: return
            if (parts.size < 4) return
            val my = parts[0].toIntOrNull() ?: return
            if (my != gen) return          // остановили или уже другая страница
            if (parts[3] != "1") return     // не последний кусок страницы
            speaking = false
            Trace.step("tts: page done ${parts[2]}")
            onPageDone?.invoke()
        }

        override fun onError(utteranceId: String?) {
            fail("ошибка синтеза", utteranceId)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            fail("код $errorCode", utteranceId)
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            Trace.step("tts: stop id=$utteranceId")
        }
    }

    private fun fail(why: String, id: String?) {
        speaking = false
        Trace.step("tts: error $why id=$id")
        onFail?.invoke("Не удалось прочитать")
    }

    private companion object {
        /**
         * Предел куска: `getMaxSpeechInputLength()` — 4000, берём с запасом. Страница книги
         * в кириллице редко больше 3000 знаков.
         */
        const val PAGE_LIMIT = 3500

        /** Неразрывный пробел и CR — голос на них спотыкается. */
        fun clean(s: String): String = s.replace('\u00A0', ' ').replace("\r\n", "\n").trim()
    }

    /**
     * Резать текст на куски не длиннее [limit] по границам предложений: точка, знак
     * вопроса, восклицания, перевод строки. Внутри предложения — по запятой, затем
     * по пробелу: абзац может быть длиннее предела.
     */
    private fun split(text: String, limit: Int): List<String> {
        if (text.length <= limit) return listOf(text)
        val out = ArrayList<String>(text.length / limit + 1)
        var rest = text
        while (rest.length > limit) {
            var cut = -1
            for (i in limit - 1 downTo limit / 2) {
                val c = rest[i]
                if (c == '.' || c == '!' || c == '?' || c == '\n') {
                    cut = i + 1
                    break
                }
            }
            if (cut < 0) {
                val comma = rest.lastIndexOf(',', limit - 1)
                cut = if (comma > limit / 2) comma + 1 else rest.lastIndexOf(' ', limit - 1) + 1
            }
            if (cut <= 0) cut = limit
            out.add(rest.substring(0, cut).trim())
            rest = rest.substring(cut)
        }
        if (rest.isNotBlank()) out.add(rest.trim())
        return out.filter { it.isNotEmpty() }
    }
}

