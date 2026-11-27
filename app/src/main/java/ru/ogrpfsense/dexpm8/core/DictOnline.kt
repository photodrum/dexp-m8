package ru.ogrpfsense.dexpm8.core

import android.content.Context
import android.net.ConnectivityManager
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream
import ru.ogrpfsense.dexpm8.ui.Trace

/**
 * Словарь и перевод по сети (R5, B30). Источники — без ключа и без регистрации.
 *
 * **Словарь — Викисловарь, `action=api` с `prop=extracts&explaintext=1`.** Готовый
 * `/api/rest_v1/page/definition/…` не годится: русский раздел отвечает на него
 * `{"status":501}` (проверено 2026-11-17), а `extracts` отдаёт уже чистый текст без
 * вики-разметки: строка после «Морфологические и синтаксические свойства» — грамматика B30,
 * строки `#` под «==== Значение ====» — определение, пример после «◆» отрезается.
 *
 * **Начальная форма.** Косвенные формы (`гувернанткой`, `стриженым`) своей статьи не имеют:
 * страница `missing`, текста нет. Тогда `list=search` по той же форме даёт лемму
 * («гувернантка») — её и открываем. Это приёмка R5 «словарь находит слово в начальной форме».
 *
 * **Перевод — MyMemory.** Публичный `libretranslate.com` требует ключ, а зеркала
 * `translate.disroot.org`, `translate.terraprint.co`, `libretranslate.de` API не отдают
 * (проверено 2026-11-17); свой LibreTranslate подключим на B73. Направление — [TrDir].
 *
 * Сеть — `HttpURLConnection` с таймаутами и распаковкой gzip, как в [Opds]; `org.json`
 * в системе есть. Вызывать только из фонового потока, иначе `NetworkOnMainThreadException`.
 * Для Wikimedia обязателен свой `User-Agent` ([UA]), иначе ответ 403.
 */
class DictOnline(private val ctx: Context) {

    /** Статья словаря для листа B30: слово, грамматика, определение и подпись источника. */
    class Def(val word: String, val grammar: String, val definition: String, val source: String)

    /** Перевод: направление, текст и подпись источника. */
    class Tr(val from: String, val to: String, val text: String, val source: String)

    /** Почему последний вызов вернул null — для строки под определением на B30. */
    var why: String? = null
        private set

    // ------------------------------------------------------------------ словарь

    /**
     * Статья для [word] в Викисловаре. `null` — не нашлось или нет сети; причина в [why].
     *
     * Сначала точное название, потом (если статьи нет) поиск по форме и статья найденной
     * леммы: `гувернанткой` → `гувернантка`.
     */
    fun define(word: String): Def? {
        why = null
        if (!online()) return noDef("Нет сети")
        val w = clean(word)
        if (w.isEmpty()) return noDef("Пустое слово")
        val d = article(w) ?: return noDef("Слова не нашлось")
        // «дееприч. от согрешать», «прич. от …», «мн. ч. от …» — статья есть, но значения
        // в ней нет. Идём по ссылке на одно слово вперёд: у глагола/существительного
        // определение уже настоящее.
        val base = baseOf(d.definition)
        if (base != null && !base.equals(d.word, ignoreCase = true)) {
            article(base)?.let { if (baseOf(it.definition) == null) return it }
        }
        return d
    }

    /**
     * Статья по слову: точное название, затем нижний регистр (капс «ЧАСТЬ» статьи не имеет,
     * а «часть» имеет), затем результаты поиска по форме.
     */
    private fun article(w: String): Def? {
        extract(w)?.let { return it }
        val lower = w.lowercase()
        if (lower != w) extract(lower)?.let { return it }
        // По форме находится несколько статей («согрешающему» → «согрешав», «согрешать»),
        // и у первой может не быть раздела «Значение» — берём первую, у которой он есть.
        for (lemma in search(w, SEARCH_TRY)) {
            extract(lemma)?.let { return it }
        }
        return null
    }

    /**
     * Одно слово, к которому отсылает определение производной формы: «дееприч. от согрешать»
     * → «согрешать». `null` — определение самостоятельное.
     */
    private fun baseOf(definition: String): String? {
        if (definition.length > FORM_MAX) return null
        val i = definition.indexOf(" от ")
        if (i <= 0) return null
        val head = definition.substring(0, i).trim()
        if (FORM_MARK.none { head.startsWith(it) }) return null
        return definition.substring(i + 4).trim()
            .trim { !it.isLetter() && it != '-' }
            .ifEmpty { null }
    }

    /** Статья по точному названию; `null` — страницы нет или она пустая. */
    private fun extract(title: String): Def? {
        val url = API + "?action=query&prop=extracts&explaintext=1&redirects=1" +
            "&format=json&formatversion=2&titles=" + enc(title)
        val json = get(url) ?: return null
        val pages = try {
            JSONObject(json).optJSONObject("query")?.optJSONArray("pages")
        } catch (t: Throwable) {
            null
        } ?: return null
        val p = pages.optJSONObject(0) ?: return null
        if (p.has("missing")) return null
        val text = p.optString("extract", "")
        if (text.isEmpty()) return null
        val definition = definitionOf(text) ?: return null
        return Def(p.optString("title", title), grammarOf(text), definition, SOURCE_DICT)
    }

    /** Начальные формы по косвенной: названия статей из полнотекстового поиска. */
    private fun search(word: String, limit: Int): List<String> {
        val url = API + "?action=query&list=search&srlimit=$limit" +
            "&format=json&formatversion=2&srsearch=" + enc(word)
        val json = get(url) ?: return emptyList()
        val found = try {
            JSONObject(json).optJSONObject("query")?.optJSONArray("search")
        } catch (t: Throwable) {
            null
        } ?: return emptyList()
        val out = ArrayList<String>(limit)
        for (i in 0 until found.length()) {
            val title = found.optJSONObject(i)?.optString("title", "") ?: ""
            if (title.isEmpty() || title.equals(word, ignoreCase = true)) continue
            out.add(title)
        }
        return out
    }

    /**
     * Строка грамматики B30 («Существительное, одушевлённое, женский род, 1-е склонение»):
     * первая содержательная строка после заголовка «Морфологические и синтаксические
     * свойства». Пропускаем перенос по слогам («гу-вер-на́нт-ка») и строки про морфемы.
     */
    private fun grammarOf(text: String): String {
        val i = text.indexOf(HEAD_GRAMMAR)
        if (i < 0) return ""
        // После заголовка идут остатки его же строки («=== … свойства ===»): начинаем
        // со следующей строки, иначе разбор упирается в «===» и сразу обрывается.
        var from = text.indexOf('\n', i)
        if (from < 0) return ""
        from++
        for (raw in text.substring(from).lineSequence()) {
            val s = raw.trim()
            if (s.isEmpty()) continue
            if (s.startsWith("=")) break
            if (MORPHEME.any { s.startsWith(it) }) continue
            // Перенос по слогам: строка без пробелов, где есть дефисы («гу-вер-на́нт-ка») или
            // знак ударения («согреша́ть»). Грамматика из одного слова («Наречие») остаётся:
            // в ней нет ни дефиса, ни ударения.
            if (s.indexOf(' ') < 0 && allLetters(s) &&
                (s.indexOf('-') >= 0 || s.indexOf('\u0301') >= 0)) continue
            // «(тип склонения 3*a по классификации Зализняка)» — для строки B30 лишнее.
            val cut = s.indexOf(" (")
            return oneSpaces(if (cut > 0) s.substring(0, cut) else s).trim().trimEnd('.', ',', ';')
        }
        return ""
    }

    /** Только буквы, дефисы и знак ударения — так выглядит строка переноса по слогам. */
    private fun allLetters(s: String): Boolean {
        for (ch in s) if (!ch.isLetter() && ch != '-' && ch != '\u0301') return false
        return true
    }

    /**
     * Определение из раздела «Значение»: непустые строки до следующего заголовка.
     * Списка-маркеров `#` в `explaintext` уже нет (он есть только в викитексте), поэтому
     * берём любую строку; пример после «◆» отрезается — он длиной в абзац и на B30 не
     * помещается. Длиннее [DEF_MAX] не берём: под определение на листе три строки.
     */
    private fun definitionOf(text: String): String? {
        var i = text.indexOf(HEAD_MEANING)
        if (i < 0) i = text.indexOf(HEAD_MEANING_SHORT)
        if (i < 0) return null
        val sb = StringBuilder(DEF_MAX)
        var last = false
        for (raw in text.substring(text.indexOf('\n', i) + 1).lineSequence()) {
            val s = raw.trim()
            if (s.startsWith("=")) break
            if (s.isEmpty()) continue
            var d = s
            val ex = d.indexOf('◆')
            if (ex >= 0) d = d.substring(0, ex)
            d = oneSpaces(stripTemplates(d)).trim().trimEnd('.', ',', ';', ':')
            if (d.isEmpty()) continue
            // Значения идут отдельными строками: в тексте нет маркеров списка, и без точки
            // с запятой они слиплись бы в одну фразу («грешить вступать во связь»).
            if (sb.isNotEmpty()) sb.append(if (last) ", " else ". ")
            sb.append(d)
            last = d.length < RAW_LINE
            if (sb.length >= DEF_MAX) break
        }
        if (sb.isEmpty()) return null
        return cutSentences(sb.toString(), DEF_MAX)
    }

    /**
     * Убрать вики-шаблоны `{{…}}` из строки определения. Не регекс: `{{` и `}}` — метасимволы,
     * а вложенные шаблоны в статьях встречаются, поэтому разбираем вручную посчётом скобок.
     */
    private fun stripTemplates(s: String): String {
        val b = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (i + 1 < s.length && s[i] == '{' && s[i + 1] == '{') {
                var depth = 0
                var j = i
                while (j < s.length) {
                    if (j + 1 < s.length && s[j] == '{' && s[j + 1] == '{') { depth++; j += 2; continue }
                    if (j + 1 < s.length && s[j] == '}' && s[j + 1] == '}') {
                        depth--; j += 2
                        if (depth == 0) break
                        continue
                    }
                    j++
                }
                i = j
            } else {
                b.append(s[i])
                i++
            }
        }
        return b.toString()
    }

    /** Обрезать по границе предложения, если текст длиннее [max]. */
    private fun cutSentences(s: String, max: Int): String {
        val t = oneSpaces(s)
        if (t.length <= max) return t
        val head = t.substring(0, max)
        val dot = head.lastIndexOf(". ")
        return (if (dot > max / 2) head.substring(0, dot + 1) else head.trimEnd() + "…").trim()
    }

    /** Одиночные пробелы вместо любых пробельных серий (переносы, табуляции, неразрывные). */
    private fun oneSpaces(s: String): String {
        val b = StringBuilder(s.length)
        var space = false
        for (ch in s) {
            val sp = ch.isWhitespace() || ch == '\u00A0'
            if (sp) {
                if (!space && b.isNotEmpty()) b.append(' ')
                space = true
            } else {
                b.append(ch)
                space = false
            }
        }
        while (b.isNotEmpty() && b[b.length - 1] == ' ') b.setLength(b.length - 1)
        return b.toString()
    }

    // ------------------------------------------------------------------ перевод

    /**
     * Перевод [text] через MyMemory. Направление — [TrDir.pair] (решение 2026-11-27): русское
     * слово → `en`, любое другое → `ru`; язык «откуда» — язык книги [bookLang], без него —
     * автоопределение сервиса. `null` — перевода нет, нет сети или кончился дневной предел;
     * причина в [why].
     */
    fun translate(text: String, bookLang: String? = null): Tr? {
        why = null
        if (!online()) return noTr("Нет сети")
        val s = clean(text)
        if (s.isEmpty()) return noTr("Пустой текст")
        val dir = TrDir.pair(s, bookLang)
        val from = dir.fromLabel()
        val to = dir.to
        val url = MYMEMORY + "?q=" + enc(s) + "&langpair=" + enc(dir.api())
        val json = get(url) ?: return null
        val o = try {
            JSONObject(json)
        } catch (t: Throwable) {
            return noTr("Ответ не разобран")
        }
        if (o.optBoolean("quotaFinished", false)) return noTr("Предел перевода на сегодня")
        val data = o.optJSONObject("responseData") ?: return noTr("Перевод не найден")
        val t = unescape(data.optString("translatedText", "")).trim()
        if (t.isEmpty()) return noTr("Перевод не найден")
        // MyMemory на редком слове возвращает запрос как есть с match=0.99 — это не перевод,
        // а «не нашлось»: показывать то же слово вторым нечего.
        if (t.equals(s, ignoreCase = true)) return noTr("Такого слова сервис ещё не переводил")
        return Tr(from, to, t, SOURCE_TR)
    }

    // ------------------------------------------------------------------ сеть

    /** Есть ли сеть: подключён хотя бы один интерфейс. Как в `Opds.online`. */
    fun online(): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val ni = cm?.activeNetworkInfo
        ni != null && ni.isConnected
    } catch (t: Throwable) {
        Trace.step("dict online FAIL ${t.javaClass.simpleName}: ${t.message}")
        false
    }

    /** GET и тело ответа строкой; `null` — ошибка сети или код не 2xx (причина в [why]). */
    private fun get(url: String): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_MS
            readTimeout = READ_MS
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Encoding", "gzip")
            // Wikimedia без своего User-Agent отвечает 403 — это не браузер.
            setRequestProperty("User-Agent", UA)
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            Trace.step("dict: GET $code ${url.substringBefore('?')}")
            why = "Ответ сети $code"
            null
        } else {
            val raw = conn.inputStream
            val ins: InputStream =
                if ("gzip".equals(conn.contentEncoding, true)) GZIPInputStream(BufferedInputStream(raw, BUF))
                else BufferedInputStream(raw, BUF)
            val body = ins.use { readAll(it) }
            conn.disconnect()
            if (body.size > MAX_BYTES) {
                why = "Ответ слишком большой"
                null
            } else String(body, Charsets.UTF_8)
        }
    } catch (t: Throwable) {
        why = when (t) {
            is java.net.SocketTimeoutException -> "Нет ответа"
            is java.io.IOException -> "Нет сети"
            else -> "Ошибка: ${t.javaClass.simpleName}"
        }
        Trace.step("dict: GET FAIL ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    private fun readAll(ins: InputStream): ByteArray {
        val out = ByteArrayOutputStream(8 * 1024)
        val buf = ByteArray(BUF)
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BYTES) break
        }
        return out.toByteArray()
    }

    private fun noDef(reason: String): Def? {
        why = reason
        return null
    }

    private fun noTr(reason: String): Tr? {
        why = reason
        return null
    }

    // ------------------------------------------------------------- служебное

    /** Слово под пальцем приходит с неразрывным пробелом и знаками препинания по краям. */
    private fun clean(s: String): String = s.replace('\u00A0', ' ')
        .replace('\n', ' ')
        .trim()
        .trim { !it.isLetter() && it != '-' }

    private fun enc(s: String): String = try {
        URLEncoder.encode(s, "UTF-8")
    } catch (t: Throwable) {
        s
    }

    /** MyMemory отдаёт `translatedText` с HTML-мнемониками. */
    private fun unescape(s: String): String = s
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

    companion object {
        private const val API = "https://ru.wiktionary.org/w/api.php"
        private const val MYMEMORY = "https://api.mymemory.translated.net/get"

        /** Свой User-Agent обязателен: без него Wikimedia блокирует запросы как бота. */
        private const val UA = "DexpM8-reader/0.1 (E-Ink reader; ru.ogrpfsense.dexpm8)"

        private const val SOURCE_DICT = "Викисловарь · CC BY-SA"
        private const val SOURCE_TR = "MyMemory · перевод по сети"

        private const val HEAD_GRAMMAR = "Морфологические и синтаксические свойства"
        private const val HEAD_MEANING = "==== Значение ===="
        private const val HEAD_MEANING_SHORT = "=== Значение ==="

        /** Строки про морфемы — не грамматика. */
        private val MORPHEME = arrayOf(
            "Корень", "Приставка", "Префикс", "Суффикс", "Постфикс", "Окончание", "Основа",
        )

        /** Сколько знаков определения держим: B30 отводит под него три строки. */
        private const val DEF_MAX = 320

        /** Сколько результатов поиска пробуем открыть: у части статей нет раздела «Значение». */
        private const val SEARCH_TRY = 4

        /** Определение производной формы короче — иначе это уже обычная статья. */
        private const val FORM_MAX = 90

        /** Короткая строка значения — оно продолжение предыдущего, а не новое («перен. …»). */
        private const val RAW_LINE = 40

        /** Начала определений-отсылок: «дееприч. от …», «прич. от …», «сравн. ст. от …». */
        private val FORM_MARK = arrayOf(
            "дееприч", "прич", "кратк", "сравн", "мн. ч", "ед. ч", "род. п", "прош. вр", "повел",
        )

        private const val CONNECT_MS = 8000
        private const val READ_MS = 12000
        private const val BUF = 8 * 1024
        private const val MAX_BYTES = 512 * 1024
    }
}
