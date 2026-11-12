package ru.ogrpfsense.dexpm8.core

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Environment
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Locale
import ru.ogrpfsense.dexpm8.ui.Trace

/**
 * B26 · Загрузка книг по Wi-Fi — свой HTTP на `ServerSocket` (п. 9.7).
 *
 * Почему свой, а не готовый: на 1 ГБ ОЗУ и API 27 тянет только голый `java.net`,
 * а нужен один экран — страница с выбором файлов и приём книги в `Books`.
 *
 *   порт       8080, занят — 8081…8089; слушаем все интерфейсы (право `INTERNET` — в манифесте)
 *   поток      один на сервер, соединения обслуживаются по одному: браузер открывает
 *              несколько, лишние ждут в `accept`
 *   страница   `GET /` — HTML+JS без внешних файлов; файлы уходят по одному запросу
 *              `POST /put?name=<имя>` с телом = содержимое файла (multipart не нужен)
 *   приём      потоком в `Books/<имя>.part`, затем переименование и [Scanner.addFile]:
 *              книга сразу в библиотеке, полный проход сканера не нужен
 *
 * События отдаются в [Listener] из потока сервера — экран переносит их в UI сам.
 * Сервер ничего не рисует и не знает про экран.
 */
class WifiShare(private val ctx: Context, private val db: LibraryDb) {

    interface Listener {
        /** Сервер поднялся на [port] или не поднялся — [why] причина. */
        fun onServer(port: Int, why: String?)

        /** Начался приём файла [name] формата [format] размером [size] байт. */
        fun onFileStart(name: String, format: String, size: Long)

        /** Принято [done] байт файла [name] — для дорожки прогресса. */
        fun onFileProgress(name: String, done: Long)

        /** Файл [name] принят ([ok]) либо отклонён с причиной [why]. */
        fun onFileDone(name: String, ok: Boolean, why: String?)
    }

    @Volatile
    private var server: ServerSocket? = null

    /** Порт, на котором слушаем; 0 — сервер не запущен. */
    @Volatile
    var port = 0
        private set

    val running: Boolean get() = server != null

    /** Каталог загрузки — тот же, что обходит [Scanner]. */
    val dir: File = File(Environment.getExternalStorageDirectory(), "Books")

    /** Один сканер на сервер: `addFile` и `fileFormat` — его методы. */
    private val scanner = Scanner(ctx.applicationContext, db)

    /** Адрес для поля на B26: «192.168.1.47:8080»; пусто — адреса нет. */
    fun address(): String {
        val p = port
        if (p == 0) return ""
        val ip = ip() ?: return ""
        return "$ip:$p"
    }

    /**
     * Имя сети для подзаголовка B26. Без геолокации система отдаёт `<unknown ssid>`
     * (то же ограничение, что у B38, п. 8) — тогда пусто, экран пишет «Wi-Fi».
     */
    fun ssid(): String {
        return try {
            val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return ""
            val s = wm.connectionInfo?.ssid ?: return ""
            if (s.startsWith("<")) "" else s.trim('"')
        } catch (t: Throwable) {
            Trace.step("wifi ssid FAIL: ${t.javaClass.simpleName}: ${t.message}")
            ""
        }
    }

    /** IPv4 в локальной сети; null — сети нет. */
    fun ip(): String? {
        try {
            val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val raw = wm?.connectionInfo?.ipAddress ?: 0
            if (raw != 0) {
                return String.format(Locale.ROOT, "%d.%d.%d.%d",
                    raw and 0xff, raw shr 8 and 0xff, raw shr 16 and 0xff, raw shr 24 and 0xff)
            }
        } catch (t: Throwable) {
            Trace.step("wifi ip FAIL: ${t.javaClass.simpleName}: ${t.message}")
        }
        // WifiManager молчит (Wi-Fi выключен) — берём любой адрес IPv4, кроме loopback.
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (a in nif.inetAddresses) {
                    if (a is Inet4Address && !a.isLoopbackAddress) return a.hostAddress
                }
            }
        } catch (t: Throwable) {
            Trace.step("wifi ip net FAIL: ${t.javaClass.simpleName}: ${t.message}")
        }
        return null
    }

    /** Поднять сервер. false — уже запущен либо не поднялся (итог — в [Listener.onServer]). */
    fun start(l: Listener): Boolean {
        if (server != null) return false
        val srv = open() ?: run {
            l.onServer(0, "нет свободного порта")
            return false
        }
        server = srv
        port = srv.localPort
        Trace.step("wifi share: listen ${srv.localPort} ip=${ip()}")
        l.onServer(srv.localPort, null)
        val t = Thread({ serve(srv, l) }, "wifi-share")
        t.isDaemon = true
        t.start()
        return true
    }

    /** Остановить: закрыть сокет, поток завершится сам. Текущий файл дописывается. */
    fun stop() {
        val s = server ?: return
        server = null
        port = 0
        try {
            s.close()
        } catch (ignored: Throwable) {
        }
        Trace.step("wifi share: stop")
    }

    private fun open(): ServerSocket? {
        for (p in PORTS) {
            try {
                return ServerSocket(p)
            } catch (t: Throwable) {
                Trace.step("wifi share: порт $p занят (${t.javaClass.simpleName})")
            }
        }
        return null
    }

    // ------------------------------------------------------------------ потоки

    private fun serve(srv: ServerSocket, l: Listener) {
        while (server === srv) {
            val s = try {
                srv.accept()
            } catch (t: Throwable) {
                // Закрытый сокет — это наш stop(); прочее пишем один раз.
                if (server === srv) Trace.step("wifi share accept FAIL: ${t.javaClass.simpleName}")
                break
            }
            try {
                s.use { handle(it, l) }
            } catch (t: Throwable) {
                Trace.step("wifi share conn FAIL: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        try {
            srv.close()
        } catch (ignored: Throwable) {
        }
        Trace.step("wifi share: поток сервера завершён")
    }

    private fun handle(s: Socket, l: Listener) {
        s.soTimeout = CONN_TIMEOUT_MS
        val ins = BufferedInputStream(s.getInputStream())
        val out = s.getOutputStream()

        val head = readLine(ins)
        val parts = head?.split(' ')
        if (parts == null || parts.size < 2) {
            respond(out, 400, TEXT, "Плохой запрос".toByteArray(Charsets.UTF_8))
            return
        }
        val method = parts[0].uppercase(Locale.ROOT)
        val target = parts[1]

        var length = -1L
        while (true) {
            val h = readLine(ins) ?: break
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i <= 0) continue
            if (h.substring(0, i).trim().equals("Content-Length", true)) {
                length = h.substring(i + 1).trim().toLongOrNull() ?: -1L
            }
        }

        when {
            method == "GET" && (target == "/" || target == "/index.html" || target.startsWith("/?")) ->
                respond(out, 200, HTML, PAGE.toByteArray(Charsets.UTF_8))

            method == "GET" && target.startsWith("/favicon") ->
                respond(out, 204, null, null)

            method == "POST" -> upload(target, ins, out, length, l)

            else -> respond(out, 404, TEXT, "Нет такой страницы".toByteArray(Charsets.UTF_8))
        }
    }

    // ------------------------------------------------------------------ приём файла

    private fun upload(target: String, ins: InputStream, out: OutputStream, length: Long, l: Listener) {
        val asked = param(target, "name")
        val name = safeName(asked)
        val format = if (name.isEmpty()) null else scanner.fileFormat(name)

        // Отказ: тело всё равно дочитываем и выбрасываем — иначе браузер увидит обрыв.
        if (name.isEmpty() || format == null) {
            drain(ins, length)
            Trace.step("wifi share: отклонён «$asked» — не книга")
            l.onFileDone(if (asked.isEmpty()) "файл" else asked, false, "не книга")
            respond(out, 400, TEXT, "Такой файл читалка не откроет".toByteArray(Charsets.UTF_8))
            return
        }
        if (length < 0L) {
            drain(ins, 0L)
            l.onFileDone(name, false, "нет размера")
            respond(out, 411, TEXT, "Нет размера файла".toByteArray(Charsets.UTF_8))
            return
        }
        if (!dir.isDirectory && !dir.mkdirs()) {
            drain(ins, length)
            Trace.step("wifi share: нет каталога ${dir.path}")
            l.onFileDone(name, false, "нет папки Books")
            respond(out, 500, TEXT, "Не открылась папка Books".toByteArray(Charsets.UTF_8))
            return
        }
        val free = try {
            dir.usableSpace
        } catch (t: Throwable) {
            0L
        }
        if (free in 1 until length) {
            drain(ins, length)
            l.onFileDone(name, false, "нет места")
            respond(out, 507, TEXT, "Не хватает места на книге".toByteArray(Charsets.UTF_8))
            return
        }

        val dest = unique(File(dir, name))
        val part = File(dir, dest.name + ".part")
        l.onFileStart(dest.name, format, length)
        var done = 0L
        var next = PROGRESS_STEP
        try {
            FileOutputStream(part).use { fos ->
                val buf = ByteArray(BUF)
                while (done < length) {
                    val want = Math.min(buf.size.toLong(), length - done).toInt()
                    val n = ins.read(buf, 0, want)
                    if (n < 0) break
                    fos.write(buf, 0, n)
                    done += n
                    if (done >= next) {
                        next = done + PROGRESS_STEP
                        l.onFileProgress(dest.name, done)
                    }
                }
            }
            if (done < length) throw java.io.IOException("обрыв на $done из $length")
            if (!part.renameTo(dest)) throw java.io.IOException("не переименован")
        } catch (t: Throwable) {
            part.delete()
            Trace.step("wifi share: «${dest.name}» FAIL: ${t.javaClass.simpleName}: ${t.message}")
            l.onFileDone(dest.name, false, "обрыв связи")
            try {
                respond(out, 500, TEXT, "Файл не принят".toByteArray(Charsets.UTF_8))
            } catch (ignored: Throwable) {
            }
            return
        }

        val ok = try {
            scanner.addFile(dest)
        } catch (t: Throwable) {
            Trace.step("wifi share: addFile FAIL: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
        Trace.step("wifi share: принят ${dest.name} ($done б), в библиотеке=$ok")
        l.onFileDone(dest.name, ok, if (ok) null else "в библиотеку не попал")
        respond(out, 200, TEXT, "ok".toByteArray(Charsets.UTF_8))
    }

    /** Дочитать и выбросить тело запроса: [length] байт либо (0) — до конца потока. */
    private fun drain(ins: InputStream, length: Long) {
        try {
            val buf = ByteArray(BUF)
            var left = length
            while (true) {
                val want = if (length > 0) Math.min(buf.size.toLong(), left).toInt() else buf.size
                if (want <= 0) return
                val n = ins.read(buf, 0, want)
                if (n < 0) return
                if (length > 0) {
                    left -= n
                    if (left <= 0) return
                }
            }
        } catch (ignored: Throwable) {
        }
    }

    // ------------------------------------------------------------------ HTTP-ответ

    private fun respond(out: OutputStream, code: Int, type: String?, body: ByteArray?) {
        val b = body ?: ByteArray(0)
        val head = StringBuilder(128)
        head.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n")
        if (type != null) head.append("Content-Type: ").append(type).append("\r\n")
        head.append("Content-Length: ").append(b.size).append("\r\n")
        head.append("Cache-Control: no-store\r\n")
        head.append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        if (b.isNotEmpty()) out.write(b)
        out.flush()
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        404 -> "Not Found"
        411 -> "Length Required"
        507 -> "Insufficient Storage"
        else -> "Internal Server Error"
    }

    /** Строка заголовка до CRLF; null — поток кончился. Байты читаются как UTF-8:
     *  браузер кодирует имя в percent-последовательности, но сырые байты тоже не должны портиться. */
    private fun readLine(ins: InputStream): String? {
        val buf = java.io.ByteArrayOutputStream(64)
        while (true) {
            val c = ins.read()
            if (c < 0) return if (buf.size() == 0) null else String(buf.toByteArray(), Charsets.UTF_8)
            if (c == '\n'.code) return String(buf.toByteArray(), Charsets.UTF_8).trimEnd('\r')
            if (buf.size() >= MAX_LINE) return null
            buf.write(c)
        }
    }

    private fun param(target: String, key: String): String {
        val q = target.substringAfter('?', "")
        if (q.isEmpty()) return ""
        for (pair in q.split('&')) {
            val i = pair.indexOf('=')
            if (i <= 0) continue
            if (!pair.substring(0, i).equals(key, true)) continue
            return try {
                URLDecoder.decode(pair.substring(i + 1), "UTF-8")
            } catch (t: Throwable) {
                pair.substring(i + 1)
            }
        }
        return ""
    }

    // ------------------------------------------------------------------ имена

    /**
     * Имя файла из запроса: без пути, управляющих символов и знаков, запрещённых в FAT.
     * Пусто — принимать нечего.
     */
    private fun safeName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        val sb = StringBuilder(base.length)
        for (ch in base) {
            if (ch.code < 32) continue
            if (ch in BAD) continue
            sb.append(ch)
        }
        var s = sb.toString().trim().trimStart('.').trim()
        if (s.length > MAX_NAME) {
            val dot = s.lastIndexOf('.')
            s = if (dot > 0 && s.length - dot <= 8) {
                s.substring(0, MAX_NAME - (s.length - dot)) + s.substring(dot)
            } else {
                s.substring(0, MAX_NAME)
            }
        }
        return s
    }

    /** «Идиот.fb2» уже есть → «Идиот (2).fb2»: браузер не затрёт чужую книгу молча. */
    private fun unique(f: File): File {
        if (!f.exists()) return f
        val name = f.name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (i in 2 until 1000) {
            val c = File(f.parentFile, "$stem ($i)$ext")
            if (!c.exists()) return c
        }
        return File(f.parentFile, "$stem-${System.currentTimeMillis()}$ext")
    }

    private companion object {
        /** Порты по порядку: 8080 занят другим приложением — берём следующий. */
        val PORTS = intArrayOf(8080, 8081, 8082, 8083, 8084, 8085, 8086, 8087, 8088, 8089)

        const val BUF = 64 * 1024

        /** Сколько принять, прежде чем сообщить прогресс: дорожка на E-Ink обновляется редко. */
        const val PROGRESS_STEP = 256L * 1024L

        const val CONN_TIMEOUT_MS = 60_000
        const val MAX_LINE = 8 * 1024
        const val MAX_NAME = 120

        const val TEXT = "text/plain; charset=utf-8"
        const val HTML = "text/html; charset=utf-8"

        /** Знаки, недопустимые в имени файла: разделители пути и запрет FAT. */
        val BAD = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

        /**
         * Страница загрузки. Ни одного внешнего файла: её открывают с телефона или компьютера
         * в той же сети, шрифты и скрипт — свои. Файлы уходят по одному запросу
         * `POST /put?name=<имя>`, тело — сам файл (без multipart).
         */
        val PAGE = """
<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Загрузка книг — DEXP M8</title>
<style>
body{font:16px/1.45 -apple-system,Roboto,Arial,sans-serif;margin:0;padding:28px 20px;background:#fff;color:#111}
h1{font-size:22px;margin:0 0 6px}
p.hint{color:#444;margin:0 0 20px;max-width:640px}
#drop{border:2px dashed #999;border-radius:14px;padding:40px 16px;text-align:center;color:#555;cursor:pointer}
#drop.on{border-color:#111;color:#111;background:#f2f2f2}
ul{list-style:none;padding:0;margin:20px 0 0;max-width:640px}
li{padding:10px 0;border-bottom:1px solid #ddd}
.name{font-weight:600;word-break:break-all}
.state{color:#555;font-size:14px}
.err{color:#000;font-weight:700}
.bar{height:6px;background:#ddd;border-radius:3px;margin-top:6px;overflow:hidden}
.bar i{display:block;height:100%;width:0;background:#333}
</style>
</head>
<body>
<h1>Загрузка книг по Wi-Fi</h1>
<p class="hint">Перетащите файлы в рамку или нажмите на неё и выберите книги. Файлы попадут
в папку Books и сразу появятся в библиотеке читалки.</p>
<div id="drop">Перетащите файлы сюда<br><small>или нажмите, чтобы выбрать</small></div>
<input id="pick" type="file" multiple hidden>
<ul id="list"></ul>
<script>
(function(){
  var drop = document.getElementById('drop'), pick = document.getElementById('pick'),
      list = document.getElementById('list');
  drop.onclick = function(){ pick.click(); };
  pick.onchange = function(){ send(pick.files); pick.value = ''; };
  ['dragenter','dragover'].forEach(function(n){
    drop.addEventListener(n, function(e){ e.preventDefault(); drop.className = 'on'; });
  });
  ['dragleave','drop'].forEach(function(n){
    drop.addEventListener(n, function(e){ e.preventDefault(); drop.className = ''; });
  });
  drop.addEventListener('drop', function(e){ if (e.dataTransfer) send(e.dataTransfer.files); });

  function send(files){
    for (var i = 0; i < files.length; i++) upload(files[i]);
  }
  function row(file){
    var li = document.createElement('li');
    var n = document.createElement('div'); n.className = 'name'; n.textContent = file.name;
    var s = document.createElement('div'); s.className = 'state'; s.textContent = 'Ждёт отправки';
    var b = document.createElement('div'); b.className = 'bar';
    var f = document.createElement('i'); b.appendChild(f);
    li.appendChild(n); li.appendChild(s); li.appendChild(b);
    list.appendChild(li);
    return { state: s, bar: f };
  }
  function upload(file){
    var r = row(file);
    r.state.textContent = 'Отправка…';
    var x = new XMLHttpRequest();
    x.open('POST', '/put?name=' + encodeURIComponent(file.name), true);
    x.setRequestHeader('Content-Type', 'application/octet-stream');
    x.upload.onprogress = function(e){
      if (!e.lengthComputable) return;
      var p = Math.round(e.loaded * 100 / e.total);
      r.bar.style.width = p + '%';
      r.state.textContent = 'Отправка… ' + p + '%';
    };
    x.onload = function(){
      if (x.status == 200) {
        r.bar.style.width = '100%';
        r.state.textContent = 'Готово — книга в библиотеке';
      } else {
        r.state.textContent = x.responseText || ('Ошибка ' + x.status);
        r.state.className = 'state err';
      }
    };
    x.onerror = function(){
      r.state.textContent = 'Связь с книгой прервалась';
      r.state.className = 'state err';
    };
    x.send(file);
  }
})();
</script>
</body>
</html>
""".trimIndent()
    }
}
