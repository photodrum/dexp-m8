package ru.ogrpfsense.dexpm8.core

import android.content.Context
import android.net.ConnectivityManager
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
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
 * Формат `update.json` (обязательны все поля, кроме `notes` и `size`):
 * ```
 * { "versionCode": 2, "versionName": "0.1.1", "url": "https://…/app-release.apk",
 *   "sha256": "<hex>", "size": 14680064, "notes": "что нового" }
 * ```
 *
 * [fetch] читает только версию — её показывает B43. Скачивание ([download]) и установка
 * (B76, `core/ApkInstall`) — п. 13. Сумма `sha256` обязательна: непроверенный APK в установку
 * не отдаём, а `size` (если есть) позволяет проверить место и показать размер до скачивания.
 *
 * **Автопроверка при старте** — [autoCheck]: раз в неделю ([dueWeekly]) и только на Wi-Fi
 * ([onWifi]), тихо, без экрана и скачивания; включена тумблером `Prefs.updateAuto`.
 * Наружу она выдаёт себя только строкой «Последняя проверка — …» на B43.
 */
class UpdateCheck(private val ctx: Context) {

    /** Что ответил источник: версия, ссылка на APK, контрольная сумма и кто ответил. */
    class Found(
        val versionCode: Long,
        val versionName: String,
        val url: String,
        val sha256: String,
        val notes: String,
        /** Размер APK в байтах из манифеста; 0 — не указан. */
        val size: Long,
        /** «git.ogrpfsense.ru» или «github.com». */
        val from: String
    )

    /** Почему последняя проверка не удалась — для строки под кнопкой на B43. */
    var why: String? = null
        private set

    /**
     * Адрес манифеста вместо встроенных источников — только для отладки на книге
     * (`--es update <url>`, см. `SetUpdateScreen.devManifest`). Пусто — обычный порядок.
     */
    var devManifest: String = ""

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
        // Отладка (`--es update <url>`): один источник вместо встроенного списка.
        val src = if (devManifest.isNotEmpty()) arrayOf(Source("отладка", devManifest)) else SOURCES
        val problems = ArrayList<String>(src.size)
        for (s in src) {
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
            Found(code, name, url, sha.lowercase(), o.optString("notes", ""),
                o.optLong("size", 0L).coerceAtLeast(0L), host)
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

    // ------------------------------------------------------------------ автопроверка (п. 13)

    /**
     * Пора ли проверять автоматически: неделя с прошлой проверки прошла или её не было.
     * Считаем от `Prefs.updateCheckedAt` — его пишут и автопроверка, и ручная на B76,
     * поэтому ручная проверка сдвигает и недельный срок.
     */
    fun dueWeekly(now: Long = System.currentTimeMillis()): Boolean {
        val at = Prefs(ctx).updateCheckedAt
        return at <= 0L || now - at >= WEEK_MS
    }

    /**
     * Сеть сейчас — Wi-Fi. Тумблер на B76 обещает «раз в неделю **при Wi-Fi**»: на мобильном
     * не проверяем, срок не сдвигаем — следующее включение на Wi-Fi проверит.
     */
    fun onWifi(): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val ni = cm?.activeNetworkInfo
        ni != null && ni.isConnected && ni.type == ConnectivityManager.TYPE_WIFI
    } catch (t: Throwable) {
        Trace.step("update wifi FAIL ${t.javaClass.simpleName}: ${t.message}")
        false
    }

    /**
     * Проверка при старте оболочки (тумблер «Проверять автоматически»). Тихая: ни экрана,
     * ни скачивания — только `Prefs`, откуда итог читают B43 (строка «Последняя проверка — …»)
     * и B76 (при открытии проверит заново и покажет версию).
     *
     * Неудачу в `Prefs` не пишем: `updateCheckedAt` не сдвигаем, значит следующий старт
     * попробует снова, а строка на B43 останется с прошлым честным итогом. Версию, отложенную
     * кнопкой «Позже» (`Prefs.updateSkipCode`), не навязываем — так и говорим в итоге.
     * Вызывать из фонового потока.
     */
    fun autoCheck() {
        val p = Prefs(ctx)
        val f = fetch()
        val fail = why
        if (f == null) {
            Trace.step("update auto: не вышло — " + (fail ?: "нет ответа"))
            return
        }
        p.updateCheckedAt = System.currentTimeMillis()
        p.updateResult = when {
            !isNewer(f) -> "обновлений нет"
            f.versionCode <= p.updateSkipCode -> "версия ${f.versionName} отложена"
            else -> "есть версия ${f.versionName}"
        }
        Trace.step("update auto: " + p.updateResult)
    }

    // ------------------------------------------------------------------ скачивание (B76)

    /**
     * Скачать APK из манифеста [f] в каталог [dir]. Возвращает файл; `null` — не вышло,
     * причина в [why].
     *
     * Почему не `DownloadManager`: на этой прошивке его уведомление тянет системную шторку
     * и перерисовывает экран мимо нас, а прогресс нужен на своём экране B76. Свой поток —
     * два десятка строк против лишнего APK-компонента.
     *
     * Порядок строгий: пишем в `.part` → сверяем `sha256` с манифестом → только потом
     * переименовываем. Не сошлось — файл удаляется, в установку не отдаём ничего.
     * Вызывать из фонового потока; [onPct] вызывается не чаще, чем каждые 10 %.
     */
    fun download(f: Found, dir: File, onPct: ((Int) -> Unit)? = null): File? {
        why = null
        if (!online()) {
            why = "нет сети"
            return null
        }
        if (!dir.isDirectory && !dir.mkdirs()) {
            why = "нет каталога"
            return null
        }
        val dest = File(dir, "update-${f.versionCode}.apk")
        val part = File(dir, dest.name + ".part")
        part.delete()
        // Манифест говорит 64 нуля — суммы нет. Ставить такое вслепую нельзя.
        if (f.sha256.isEmpty() || f.sha256.all { it == '0' }) {
            why = "в манифесте нет суммы"
            Trace.step("update: нет sha256 у ${f.versionName}")
            return null
        }
        try {
            val conn = (URL(f.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_MS
                readTimeout = READ_MS
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("Accept", "application/vnd.android.package-archive,*/*")
                setRequestProperty("User-Agent", UA)
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    // Частая причина: APK лежит в GitLab Packages, а тот требует входа.
                    why = if (code == 401 || code == 403) "файл закрыт" else "ответ $code"
                    Trace.step("update: APK GET $code ${f.url}")
                    return null
                }
                val total = conn.contentLengthLong
                val need = if (total > 0) total else f.size
                if (need > 0 && dir.usableSpace < need + SPARE) {
                    why = "мало места"
                    Trace.step("update: места нет, нужно $need, есть ${dir.usableSpace}")
                    return null
                }
                var done = 0L
                var step = 0
                FileOutputStream(part).use { out ->
                    val inp = BufferedInputStream(conn.inputStream, 16 * 1024)
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (need > 0) {
                            val pct = (done * 100 / need).toInt().coerceIn(0, 100)
                            if (pct >= step + 10) {
                                step = pct / 10 * 10
                                onPct?.invoke(step)
                            }
                        }
                    }
                }
                if (need > 0 && done < need) throw java.io.IOException("обрыв на $done из $need")
                val sum = sha256(part)
                if (!sum.equals(f.sha256, ignoreCase = true)) {
                    why = "сумма не сошлась"
                    Trace.step("update: sha256 $sum ≠ ${f.sha256}")
                    return null
                }
                if (!part.renameTo(dest)) throw java.io.IOException("не переименован")
                Trace.step("update: скачан ${dest.name} ($done б, сумма ок)")
                onPct?.invoke(100)
                return dest
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            Trace.step("update download FAIL ${t.javaClass.simpleName}: ${t.message}")
            why = when (t) {
                is java.net.SocketTimeoutException -> "нет ответа"
                is java.net.UnknownHostException -> "хост не найден"
                else -> "обрыв загрузки"
            }
            return null
        } finally {
            // Осталась только при неудаче: у готового файла имя уже без .part.
            part.delete()
        }
    }

    /** `sha256` файла строчными hex; пусто — файл не прочитался. Потоком, APK ~14 МБ. */
    fun sha256(file: File): String = try {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { inp ->
            val buf = ByteArray(8 * 1024)
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        val out = StringBuilder(64)
        for (b in md.digest()) out.append(Character.forDigit((b.toInt() shr 4) and 0xF, 16))
            .append(Character.forDigit(b.toInt() and 0xF, 16))
        out.toString()
    } catch (t: Throwable) {
        Trace.step("update sha256 FAIL ${t.javaClass.simpleName}: ${t.message}")
        ""
    }

    private class Source(val host: String, val url: String)

    companion object {
        private const val CONNECT_MS = 8000
        private const val READ_MS = 10000

        /** Срок автопроверки: «раз в неделю при Wi-Fi» (B76). */
        private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
        private const val UA = "List/1.0 (E-Ink reader; Android 8.1)"

        /** Запас места под APK: установщик распаковывает копию, и без него ловим ENOSPC. */
        private const val SPARE = 4L * 1024 * 1024

        /** Каталог скачанных обновлений — `cacheDir/update` (B76). */
        fun dirOf(ctx: Context): File = File(ctx.cacheDir, "update")

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
