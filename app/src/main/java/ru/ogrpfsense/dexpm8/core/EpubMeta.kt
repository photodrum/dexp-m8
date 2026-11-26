package ru.ogrpfsense.dexpm8.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Аннотация EPUB для карточки B12 (п. 27): `dc:description` из OPF-файла книги.
 *
 * EPUB — zip: `META-INF/container.xml` называет OPF (`rootfile full-path`), в OPF —
 * `<dc:description>`. Читаются только эти два файла (`ZipFile` берёт запись по оглавлению
 * архива, книга целиком не распаковывается). Без Android и без `XmlPullParser`: описания
 * в чужих книгах бывают с HTML-сущностями, которых XML-разбор не прощает (`&nbsp;`), поэтому
 * достаём элемент регулярным выражением и чистим сами.
 *
 * Что приходит в `description` на практике (все случаи — в `tools/epub_meta_test.kt`):
 *  - Calibre: HTML, экранированный как XML (`&lt;p&gt;Текст&lt;/p&gt;`, амперсанд — `&amp;amp;`);
 *  - обычный текст, иногда с жёсткими переносами строк (абзац — пустая строка);
 *  - `<![CDATA[<p>…</p>]]>` и вложенные теги прямо в элементе.
 * На выходе — как у FB2: абзацы через `\n`, без тегов и лишних пробелов, не длиннее [MAX_CHARS];
 * null — описания нет или книга не читается. Любая ошибка разбора даёт null, не исключение.
 */
object EpubMeta {

    /** Предел длины: на B12 влезает ~5 строк (≈300 знаков), остальное в базе не нужно. */
    const val MAX_CHARS = 2000

    private const val MAX_CONTAINER = 64 * 1024
    private const val MAX_OPF = 1024 * 1024
    private const val MARK = '\u0001'

    private val ROOTFILE = Regex("""<rootfile\b[^>]*?full-path\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE)
    private val DESCRIPTION = Regex(
        """<(?:[\w.-]+:)?description\b[^>]*?(?<!/)>(.*?)</(?:[\w.-]+:)?description\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val XML_ENCODING = Regex("""^\s*<\?xml[^>]*?encoding\s*=\s*["']([\w.-]+)["']""",
        RegexOption.IGNORE_CASE)
    private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val TAG = Regex("""<\s*/?\s*([A-Za-z][\w:.-]*)[^>]*>""")
    private val ENTITY = Regex("&(#[xX][0-9a-fA-F]+|#[0-9]+|[A-Za-z][A-Za-z0-9]*);")
    private val BLANK_LINE = Regex("""\n[ \t]*(?:\n[ \t]*)+""")
    private val SPACES = Regex(" {2,}")

    /** Теги, после которых начинается новая строка аннотации. */
    private val BLOCK = setOf("p", "br", "div", "li", "ul", "ol", "tr", "hr", "blockquote",
        "h1", "h2", "h3", "h4", "h5", "h6")

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "thinsp" to " ", "ensp" to " ", "emsp" to " ", "shy" to "",
        "laquo" to "«", "raquo" to "»", "mdash" to "—", "ndash" to "–", "hellip" to "…",
        "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’", "bdquo" to "„",
        "copy" to "©",
    )

    /** Аннотация книги [f]; null — нет `dc:description`, файл не EPUB или не открылся. */
    fun annotation(f: File): String? = try {
        ZipFile(f).use { z ->
            val container = read(z, "META-INF/container.xml", MAX_CONTAINER) ?: return@use null
            val opfPath = ROOTFILE.find(text(container))?.groupValues?.get(1) ?: return@use null
            val opf = read(z, opfPath, MAX_OPF) ?: return@use null
            fromOpf(text(opf))
        }
    } catch (e: Exception) {
        null
    }

    /** Аннотация из текста OPF: первый непустой `description`. */
    fun fromOpf(opf: String): String? {
        for (m in DESCRIPTION.findAll(opf)) {
            val t = toText(m.groupValues[1])
            if (t != null) return t
        }
        return null
    }

    /** Содержимое элемента → абзацы через `\n`; null — после чистки пусто. */
    private fun toText(raw: String): String? {
        val cdata = raw.contains("<![CDATA[")
        var s = COMMENT.replace(raw, "")
        // XML-уровень: в CDATA сущностей нет, остальное раскрываем (Calibre кладёт HTML экранированным).
        s = if (cdata) s.replace("<![CDATA[", "").replace("]]>", "") else decode(s)
        val html = s.indexOf('<') >= 0
        s = s.replace(MARK, ' ').replace('\u00A0', ' ').replace("\r\n", "\n").replace('\r', '\n')
        // Обычный текст: абзац — пустая строка, одиночный перенос — жёсткий перенос строки.
        if (!html) s = BLANK_LINE.replace(s, MARK.toString())
        s = s.replace('\n', ' ').replace('\t', ' ')
        if (html) {
            s = TAG.replace(s) { m ->
                if (m.groupValues[1].lowercase(Locale.ROOT) in BLOCK) MARK.toString() else ""
            }
        }
        val parts = ArrayList<String>()
        for (p in s.split(MARK)) {
            // HTML-уровень: сущности внутри тегов (`&amp;` после первого раскрытия).
            val t = SPACES.replace((if (html) decode(p) else p).replace('\u00A0', ' '), " ").trim()
            if (t.isNotEmpty()) parts.add(t)
        }
        // Одна точка или «…» вместо описания (бывает в чужих файлах) — это не аннотация.
        if (parts.none { p -> p.any { it.isLetterOrDigit() } }) return null
        return cap(parts.joinToString("\n"))
    }

    /** Не длиннее [MAX_CHARS]: режем по последнему пробелу или переносу, символ не рвём. */
    private fun cap(s: String): String {
        if (s.length <= MAX_CHARS) return s
        var k = MAX_CHARS
        while (k > 0 && s[k] != ' ' && s[k] != '\n') k--
        if (k < MAX_CHARS / 2) {
            k = MAX_CHARS
            if (Character.isHighSurrogate(s[k - 1])) k--
        }
        return s.substring(0, k).trimEnd()
    }

    /** Сущности `&name;`, `&#N;`, `&#xH;` за один проход (`&amp;lt;` → `&lt;`, а не `<`). */
    private fun decode(s: String): String {
        if (s.indexOf('&') < 0) return s
        return ENTITY.replace(s) { m ->
            val e = m.groupValues[1]
            if (e[0] == '#') codePoint(e)?.let { String(Character.toChars(it)) } ?: m.value
            else NAMED[e] ?: m.value
        }
    }

    private fun codePoint(e: String): Int? {
        val cp = try {
            if (e[1] == 'x' || e[1] == 'X') e.substring(2).toInt(16) else e.substring(1).toInt()
        } catch (x: NumberFormatException) {
            return null
        }
        return if (cp in 1..0x10FFFF && cp !in 0xD800..0xDFFF) cp else null
    }

    /** Запись архива, не больше [cap] байт; путь из container.xml бывает с `%20`. */
    private fun read(z: ZipFile, name: String, cap: Int): ByteArray? {
        val e = z.getEntry(name)
            ?: try { z.getEntry(URLDecoder.decode(name, "UTF-8")) } catch (x: Exception) { null }
            ?: return null
        z.getInputStream(e).use { s ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (out.size() < cap) {
                val n = s.read(buf, 0, minOf(buf.size, cap - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }

    /** Байты XML → строка: BOM, затем `encoding` из объявления, иначе UTF-8. */
    private fun text(b: ByteArray): String {
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte())
            return String(b, 3, b.size - 3, Charsets.UTF_8)
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte())
            return String(b, 2, b.size - 2, Charsets.UTF_16BE)
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte())
            return String(b, 2, b.size - 2, Charsets.UTF_16LE)
        val head = String(b, 0, minOf(b.size, 200), Charsets.ISO_8859_1)
        val cs = XML_ENCODING.find(head)?.groupValues?.get(1)?.let {
            try { Charset.forName(it) } catch (x: Exception) { null }
        } ?: Charsets.UTF_8
        return String(b, cs)
    }
}
