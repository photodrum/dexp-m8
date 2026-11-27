// Хост-проверка core/EpubMeta.kt (аннотация EPUB, п. 27): собирает EPUB в памяти и сверяет
// результат. Запуск и сборка — docs/CHECKS.md, «Аннотация EPUB на хосте».
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import ru.ogrpfsense.dexpm8.core.EpubMeta

private var fails = 0

private fun check(name: String, got: String?, want: String?) {
    if (got == want) println("OK   $name")
    else { fails++; println("FAIL $name\n  ждали: ${want?.let { "«$it»" }}\n  вышло: ${got?.let { "«$it»" }}") }
}

private fun container(path: String) =
    """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">""" +
        """<rootfiles><rootfile full-path="$path" media-type="application/oebps-package+xml"/></rootfiles></container>"""

private fun opf(desc: String, enc: String = "utf-8") =
    """<?xml version="1.0" encoding="$enc"?><package xmlns="http://www.idpf.org/2007/opf" version="2.0">""" +
        """<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Т</dc:title>$desc</metadata>""" +
        """<manifest/></package>"""

private fun epub(files: Map<String, ByteArray>): File {
    val f = File.createTempFile("epubtest", ".epub")
    f.deleteOnExit()
    ZipOutputStream(f.outputStream()).use { z ->
        for ((n, b) in files) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
    }
    return f
}

private fun book(desc: String, path: String = "OEBPS/content.opf", enc: String = "utf-8",
                 opfBytes: ByteArray? = null) =
    epub(mapOf(
        "mimetype" to "application/epub+zip".toByteArray(),
        "META-INF/container.xml" to container(path).toByteArray(),
        path to (opfBytes ?: opf(desc, enc).toByteArray(charset(enc))),
    ))

private fun ann(desc: String) = EpubMeta.annotation(book(desc))

fun main() {
    // 1. Calibre: HTML, экранированный как XML; &amp;amp; -> &.
    check("calibre", ann("<dc:description>&lt;p&gt;Первый &amp;amp; абзац.&lt;/p&gt;\n" +
        "&lt;p&gt;Второй &lt;i&gt;абзац&lt;/i&gt;.&lt;/p&gt;</dc:description>"),
        "Первый & абзац.\nВторой абзац.")

    // 2. Обычный текст: жёсткие переносы склеиваются, пустая строка — абзац; числовые сущности.
    check("plain wrapped", ann("<dc:description>Строка один\nпродолжение &#171;цитата&#xBB;.\n\n" +
        "  Абзац два</dc:description>"), "Строка один продолжение «цитата».\nАбзац два")

    // 3. CDATA с HTML, &nbsp; и &mdash; внутри.
    check("cdata", ann("<dc:description><![CDATA[<p>Текст&nbsp;&mdash; тест</p><br/>Ещё <b>жирное</b>]]>" +
        "</dc:description>"), "Текст — тест\nЕщё жирное")

    // 4. Теги прямо в элементе, комментарий, список.
    check("inline xhtml", ann("<dc:description><!-- служебное --><p>Раз</p><ul><li>два</li><li>три</li></ul>" +
        "</dc:description>"), "Раз\nдва\nтри")

    // 5. Нет / пусто / самозакрытый / пустой после чистки -> null; потом настоящий description берётся.
    check("no description", ann(""), null)
    check("self-closing", ann("<dc:description/>"), null)
    check("empty after strip", ann("<dc:description>&lt;p&gt; &lt;/p&gt;</dc:description>"), null)
    check("only punctuation", ann("<dc:description> . </dc:description>"), null)
    check("ellipsis only", ann("<dc:description>&hellip;</dc:description>"), null)
    check("first non-empty", ann("<dc:description> </dc:description><dc:description>Вторая</dc:description>"),
        "Вторая")

    // 6. Без префикса, атрибуты, регистр.
    check("no prefix + attrs", ann("""<DESCRIPTION xml:lang="ru" id="d">Текст</DESCRIPTION>"""), "Текст")

    // 7. OPF в корне, путь с %20 в container.xml.
    check("root opf", EpubMeta.annotation(book("<dc:description>Корень</dc:description>", "content.opf")),
        "Корень")
    check("percent path", EpubMeta.annotation(epub(mapOf(
        "META-INF/container.xml" to container("My%20Book/c.opf").toByteArray(),
        "My Book/c.opf" to opf("<dc:description>Пробел</dc:description>").toByteArray()))), "Пробел")

    // 8. windows-1251 в объявлении OPF и BOM.
    check("cp1251", EpubMeta.annotation(book("", "OEBPS/content.opf", "windows-1251",
        opf("<dc:description>Привет, мир</dc:description>", "windows-1251").toByteArray(charset("windows-1251")))),
        "Привет, мир")
    val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
        opf("<dc:description>BOM</dc:description>").toByteArray()
    check("utf8 bom", EpubMeta.annotation(book("", opfBytes = bom)), "BOM")

    // 9. Длинное: ≤ MAX_CHARS, обрезано по пробелу; без пробелов — символ не порван.
    val long = (1..400).joinToString(" ") { "слово$it" }
    val cut = ann("<dc:description>$long</dc:description>")
    check("long capped", if (cut != null && cut.length <= EpubMeta.MAX_CHARS &&
        cut.length > EpubMeta.MAX_CHARS - 20 && long.startsWith(cut) && long[cut.length] == ' ') "ok"
        else "len=${cut?.length}", "ok")
    // «а» + эмодзи (пара UTF-16): на границе 2000 приходится первая половина пары.
    val ce = ann("<dc:description>" + "а😀".repeat(1000) + "</dc:description>")
    check("long no-space surrogate", if (ce != null && ce.length <= EpubMeta.MAX_CHARS &&
        !Character.isHighSurrogate(ce.last())) "ok" else "bad", "ok")

    // 10. Не zip, нет container.xml, нет OPF по пути, нет файла -> null без исключения.
    val junk = File.createTempFile("junk", ".epub").also { it.deleteOnExit(); it.writeText("не архив") }
    check("not a zip", EpubMeta.annotation(junk), null)
    check("no container", EpubMeta.annotation(epub(mapOf("a.txt" to "x".toByteArray()))), null)
    check("opf missing", EpubMeta.annotation(epub(mapOf(
        "META-INF/container.xml" to container("nope.opf").toByteArray()))), null)
    check("no file", EpubMeta.annotation(File("/nonexistent/x.epub")), null)

    // 11. Неизвестные сущности остаются как есть, управляющий символ из текста не ломает абзацы.
    check("odd entities", ann("<dc:description>a &bogus; b &#99999999; c &#0; d\u0001e</dc:description>"),
        "a &bogus; b &#99999999; c &#0; d e")

    // 12. Язык книги (dc:language, база v16, перевод слова): коды, префиксы, мусор.
    fun lang(desc: String) = EpubMeta.language(book(desc))
    check("lang ru", lang("<dc:language>ru</dc:language>"), "ru")
    check("lang ru-RU", lang("<dc:language>ru-RU</dc:language>"), "ru-RU")
    check("lang eng", lang("<dc:language>eng</dc:language>"), "eng")
    check("lang attrs + spaces", lang("""<dc:language xsi:type="dcterms:RFC4646">  en-US </dc:language>"""), "en-US")
    check("lang cdata", lang("<dc:language><![CDATA[de]]></dc:language>"), "de")
    check("lang no prefix, upper", lang("<LANGUAGE>FR</LANGUAGE>"), "FR")
    check("lang first valid", lang("<dc:language></dc:language><dc:language>Russian</dc:language>" +
        "<dc:language>uk</dc:language>"), "uk")
    check("lang none", lang(""), null)
    check("lang self-closing", lang("<dc:language/>"), null)
    check("lang word instead of code", lang("<dc:language>Russian</dc:language>"), null)
    check("lang not a zip", EpubMeta.language(junk), null)
    check("lang no file", EpubMeta.language(File("/nonexistent/x.epub")), null)
    // read(): язык и аннотация за один проход; одно без другого не мешает.
    val both = EpubMeta.read(book("<dc:language>ru</dc:language><dc:description>Текст</dc:description>"))
    check("read both", "${both.language}|${both.annotation}", "ru|Текст")
    val onlyLang = EpubMeta.read(book("<dc:language>en</dc:language>"))
    check("read only lang", "${onlyLang.language}|${onlyLang.annotation}", "en|null")
    val onlyAnn = EpubMeta.read(book("<dc:description>Текст</dc:description>"))
    check("read only annotation", "${onlyAnn.language}|${onlyAnn.annotation}", "null|Текст")
    check("read cp1251 lang", EpubMeta.read(book("", "OEBPS/content.opf", "windows-1251",
        opf("<dc:language>ru</dc:language>", "windows-1251").toByteArray(charset("windows-1251")))).language, "ru")

    println(if (fails == 0) "=== ВСЁ ОК" else "=== ПАДЕНИЙ: $fails")
    if (fails != 0) System.exit(1)
}
