// Хостовый тест парсеров встроенных глав аудиокниг (п. 12): ID3 `CHAP` и MP4 `chpl`.
//
// Запуск (без Android и без Gradle), рецепт — docs/CHECKS.md, «Главы аудиокниг»:
//   ~/.local/kotlinc/bin/kotlinc -d /tmp/audiocheck \
//       app/src/main/java/ru/ogrpfsense/dexpm8/core/Id3Chapters.kt \
//       app/src/main/java/ru/ogrpfsense/dexpm8/core/Mp4Chapters.kt tools/audio_chapters_test.kt
//   java -cp /tmp/audiocheck:$HOME/.local/kotlinc/lib/kotlin-stdlib.jar Audio_chapters_testKt
//
// Файлы синтетические: mp3 с тегом ID3v2.4 и чанком MP4 `chpl` в двух вариантах заголовка
// (с резервным полем и без — редакторы пишут по-разному). Ожидание — 11 проверок, 0 провалов.
import java.io.File
import ru.ogrpfsense.dexpm8.core.Id3Chapters
import ru.ogrpfsense.dexpm8.core.Mp4Chapters

private fun syncsafe(v: Int): ByteArray = byteArrayOf(
    ((v shr 21) and 0x7F).toByte(), ((v shr 14) and 0x7F).toByte(),
    ((v shr 7) and 0x7F).toByte(), (v and 0x7F).toByte())

private fun be32(v: Int): ByteArray = byteArrayOf(
    (v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

private fun be64(v: Long): ByteArray = be32((v shr 32).toInt()) + be32(v.toInt())

/** Кадр `CHAP`: id, границы в мс, вложенный `TIT2` (кодировка 3 — UTF-8, ID3v2.4). */
private fun chap(id: String, start: Int, end: Int, title: String): ByteArray {
    val tit2Body = byteArrayOf(3) + title.toByteArray(Charsets.UTF_8)
    val tit2 = "TIT2".toByteArray() + syncsafe(tit2Body.size) + byteArrayOf(0, 0) + tit2Body
    val body = id.toByteArray() + byteArrayOf(0) + be32(start) + be32(end) + be32(0) + be32(0) + tit2
    return "CHAP".toByteArray() + syncsafe(body.size) + byteArrayOf(0, 0) + body
}

/** MP3 с одним тегом ID3v2.4, где три главы `CHAP`. */
private fun writeMp3(f: File) {
    val frames = chap("ch0", 0, 10000, "Первая") + chap("ch1", 10000, 25000, "Вторая") +
        chap("ch2", 25000, 40000, "Третья")
    val tag = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0) +
        syncsafe(frames.size) + frames
    f.writeBytes(tag + ByteArray(64))
}

private fun atom(name: String, body: ByteArray): ByteArray =
    be32(8 + body.size) + name.toByteArray() + body

/** MP4 `moov{mvhd(timescale 1000), udta{chpl}}`; [reserve] — вариант с резервным полем. */
private fun writeM4b(f: File, reserve: Boolean) {
    // mvhd версии 0: version+flags, creation, modification, timescale, duration.
    val mvhdBody = be32(0) + be32(0) + be32(0) + be32(1000) + be32(40000) + ByteArray(80)
    val chapters = listOf(0L to "Intro", 12000L to "Глава вторая", 30000L to "Финал")
    var chplBody = byteArrayOf(0, 0, 0, 0)
    if (reserve) chplBody += byteArrayOf(0, 0, 0, 0)
    chplBody += be32(chapters.size)
    for ((start, title) in chapters) {
        val b = title.toByteArray(Charsets.UTF_8)
        chplBody += be64(start) + byteArrayOf(b.size.toByte()) + b
    }
    val moov = atom("moov", atom("mvhd", mvhdBody) + atom("udta", atom("chpl", chplBody)))
    f.writeBytes(atom("ftyp", "M4B ".toByteArray() + be32(0)) + moov + atom("mdat", ByteArray(64)))
}

fun main() {
    val dir = File(System.getProperty("java.io.tmpdir"), "audiocheck-files")
    dir.mkdirs()
    var fail = 0
    fun check(what: String, ok: Boolean) {
        if (!ok) fail++
        println((if (ok) "ok   " else "FAIL ") + what)
    }

    val mp3 = File(dir, "a.mp3")
    writeMp3(mp3)
    val id3 = Id3Chapters.read(mp3)
    check("id3: три главы (${id3.size})", id3.size == 3)
    check("id3: «Первая» @0, 10000 мс",
        id3.getOrNull(0)?.let { it.first == 0L && it.second == "Первая" && it.third == 10000L } == true)
    check("id3: вторая @10000, 15000 мс",
        id3.getOrNull(1)?.let { it.first == 10000L && it.third == 15000L } == true)
    check("id3: «Третья»", id3.getOrNull(2)?.second == "Третья")

    val plain = File(dir, "plain.m4b")
    writeM4b(plain, reserve = false)
    val cPlain = Mp4Chapters.read(plain)
    check("mp4 без резерва: три главы (${cPlain.size})", cPlain.size == 3)
    check("mp4 без резерва: «Intro» @0",
        cPlain.getOrNull(0)?.let { it.first == 0L && it.second == "Intro" } == true)
    check("mp4 без резерва: 12000 мс", cPlain.getOrNull(1)?.first == 12000L)
    check("mp4 без резерва: первая длится 12000", cPlain.getOrNull(0)?.third == 12000L)

    val reserved = File(dir, "reserved.m4b")
    writeM4b(reserved, reserve = true)
    val cRes = Mp4Chapters.read(reserved)
    check("mp4 с резервом: три главы (${cRes.size})", cRes.size == 3)
    check("mp4 с резервом: «Финал»", cRes.getOrNull(2)?.second == "Финал")
    check("mp4 с резервом: 30000 мс", cRes.getOrNull(2)?.first == 30000L)

    println(if (fail == 0) "all good" else "провалов: $fail")
    if (fail != 0) System.exit(1)
}
