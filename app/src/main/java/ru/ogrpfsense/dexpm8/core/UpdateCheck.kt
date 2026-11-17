package ru.ogrpfsense.dexpm8.core

import android.content.Context
import android.net.ConnectivityManager
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import ru.ogrpfsense.dexpm8.ui.Trace

/**
 * Проверка обновлений оболочки (B43, кнопка «Проверить обновления»).
 *
 * **Откуда берётся версия.** Манифест `update.json` в корне ветки `main` — сначала свой
 * GitLab, при недоступности GitHub-зеркало. Оба хоста публичные и отдают raw-файл без токена
 * (проверено 2026-11-17: GitLab 200, GitHub 200), поэтому в APK нет ни ключей, ни логинов.
 *
 * Порядок жёсткий: первый ответивший источник и решает. Если GitLab отдал манифест —
 * на GitHub не ходим (нет лишнего запроса и «прыгающей» версии). Если GitLab не ответил
 * (нет ответа, 4xx/5xx, битый JSON) — пробуем зеркало, а строкой [why] объясняем, что
 * было не так с основным.
 *
 * Формат `update.json` (обязательны все поля, кроме `notes`):
 * ```
 * { "versionCode": 2, "versionName": "0.1.1", "url": "https://…/app-release.apk",
 *   "sha256": "<hex>", "notes": "что нового" }
 * ```
 *
 * Читаем только версию: сам APK не скачиваем и не ставим — установка это п. 13 плана
 * (подменю B76), а без release-подписи (п. 20) она всё равно не встанет поверх отладочной.
 */
class UpdateCheck(private val ctx: Context) {

    /** Что ответил источник: версия, ссылка на APK, контрольная сумма и кто ответил. */
    class Found(
        val versionCode: Long,
        val versionName: String,
        val url: String,
        val sha256: String,
        val notes: String,
        /** «git.ogrpfsense.ru» или «github.com». */
        val from: String
    )

    /** Почему последняя проверка не удалась — для строки под кнопкой на B43. */
    var why: String? = null
        private set

    /**
     * Манифест с первого ответившего источника. `null` — не вышло ни с одного; причина в [why].
     * Вызывать из фонового потока.
     */
    fun fetch(): Found? {
        why = null
        if (!online()) {
            why = "нет сети"
            return null
        }
        val problems = ArrayList<String>(SOURCES.size)
        for (s in SOURCES) {
            val text = get(s.url)
            val f = text?.let { parse(it, s.host) }
            if (f != null) {
                Trace.step("update: есть ${f.versionName} (${f.versionCode}) с ${s.host}")
                return f
            }
            problems.add(s.host + " — " + (why ?: "нет ответа"))
        }
        Trace.step("update: ни один источник не ответил: " + problems.joinToString("; "))
        why = summarize(problems)
        return null
    }

    /**
     * Короткий итог для строки на B43: человеку нужно знать «нет опубликованной версии» или
     * «сервер не ответил», а не код ответа. Подробности — в трассе.
     */
    private fun summarize(p: List<String>): String = when {
        p.all { it.contains("манифеста нет") } -> "версия ещё не опубликована"
        p.all { it.contains("нет ответа") || it.contains("хост не найден") } -> "сервер не ответил"
        p.any { it.contains("манифеста нет") } -> "нет ответа от сервера"
        else -> "не удалось проверить"
    }

    /**
     * Есть ли версия новее установленной. Сравниваем `versionCode`: он монотонный,
     * а `versionName` правится как угодно.
     */
    fun isNewer(f: Found): Boolean = f.versionCode > installedCode()

    /** `versionCode` установленного APK (`PackageInfo` этого пакета); 0 — не прочитался. */
    fun installedCode(): Long = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode
        else @Suppress("DEPRECATION") pi.versionCode.toLong()
    } catch (t: Throwable) {
        Trace.step("update: PackageInfo FAIL ${t.javaClass.simpleName}: ${t.message}")
        0L
    }

    /** Разбор манифеста; `null` — битый JSON или нет обязательных полей (причина в [why]). */
    private fun parse(text: String, host: String): Found? = try {
        val o = JSONObject(text)
        val code = o.optLong("versionCode", 0L)
        val name = o.optString("versionName", "")
        val url = o.optString("url", "")
        val sha = o.optString("sha256", "")
        if (code <= 0L || name.isEmpty() || url.isEmpty() || sha.isEmpty()) {
            why = "манифест без полей"
            null
        } else {
            Found(code, name, url, sha.lowercase(), o.optString("notes", ""), host)
        }
    } catch (t: Throwable) {
        why = "манифест не читается"
        Trace.step("update: JSON FAIL с $host — ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    // ------------------------------------------------------------------ сеть

    /** Есть ли сеть: при её отсутствии не ждём таймаутов впустую. */
    fun online(): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val ni = cm?.activeNetworkInfo
        ni != null && ni.isConnected
    } catch (t: Throwable) {
        Trace.step("update online FAIL ${t.javaClass.simpleName}: ${t.message}")
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
            // Без своего User-Agent GitHub и GitLab могут ответить 403 — это не браузер.
            setRequestProperty("User-Agent", UA)
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            Trace.step("update: GET $code ${url.substringBefore('?')}")
            why = if (code == 404) "манифеста нет" else "ответ $code"
            null
        } else {
            val raw = BufferedInputStream(conn.inputStream)
            val inp = if (conn.contentEncoding?.contains("gzip", true) == true) GZIPInputStream(raw) else raw
            val s = inp.use { i ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8 * 1024)
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.toString("UTF-8")
            }
            conn.disconnect()
            s
        }
    } catch (t: Throwable) {
        // SocketTimeoutException, UnknownHostException, SSLHandshakeException — всё здесь:
        // для вызывающего это одно и то же «источник недоступен».
        Trace.step("update: GET FAIL ${url.substringBefore('?')} — ${t.javaClass.simpleName}: ${t.message}")
        why = when (t) {
            is java.net.SocketTimeoutException -> "нет ответа"
            is java.net.UnknownHostException -> "хост не найден"
            else -> "сеть: " + t.javaClass.simpleName
        }
        null
    }

    private class Source(val host: String, val url: String)

    companion object {
        private const val CONNECT_MS = 8000
        private const val READ_MS = 10000
        private const val UA = "List/1.0 (E-Ink reader; Android 8.1)"

        /**
         * Источники по порядку: свой GitLab, затем GitHub-зеркало. Оба — raw-файл ветки `main`,
         * поэтому публикация версии = обычный коммит в `update.json`.
         */
        private val SOURCES = arrayOf(
            Source(
                "git.ogrpfsense.ru",
                "https://git.ogrpfsense.ru/denisle/dexp-m8/-/raw/main/update.json"
            ),
            Source(
                "github.com",
                "https://raw.githubusercontent.com/photodrum/dexp-m8/main/update.json"
            )
        )
    }
}
