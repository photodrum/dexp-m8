package ru.ogrpfsense.dexpm8.core

import java.io.File
import java.io.RandomAccessFile

/**
 * Главы из ID3v2-тега `CHAP` (MP3, п. 12).
 *
 * Кадр тега: 4 байта идентификатора, 4 байта размера (syncsafe — 7 бит на байт, чтобы
 * старший бит не ломал длину), 2 байта флагов, тело. Тело `CHAP`:
 *   element ID (строка с нулём), start / end / startOffset / endOffset — по 4 байта
 *   big-endian (в мс), дальше вложенный кадр `TIT2` — название главы.
 *
 * Возвращаем (смещение от начала файла в мс, название, длительность в мс). Длительность 0
 * означает «до конца файла» — её добирает вызывающий.
 *
 * Тег может быть больше одного (v2.4 разрешает дописывать); читаем первый — его и пишут
 * все известные редакторы аудиокниг.
 */
internal object Id3Chapters {

    /** (смещение от начала файла в мс, название, длительность в мс) */
    fun read(f: File): List<Triple<Long, String, Long>> {
        try {
            RandomAccessFile(f, "r").use { raf ->
                val head = ByteArray(10)
                if (raf.read(head) < 10) return emptyList()
                if (head[0] != 'I'.code.toByte() || head[1] != 'D'.code.toByte() ||
                    head[2] != '3'.code.toByte()
                ) return emptyList()
                val size = syncsafe(head, 6, 4)
                if (size <= 0) return emptyList()
                val tag = ByteArray(size)
                if (raf.read(tag) < size) return emptyList()
                return parse(tag)
            }
        } catch (e: Exception) {
            return emptyList()
        }
    }

    private fun parse(tag: ByteArray): List<Triple<Long, String, Long>> {
        val out = ArrayList<Triple<Long, String, Long>>(16)
        var p = 0
        while (p + 10 <= tag.size) {
            val id = String(tag, p, 4, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val sz = syncsafe(tag, p + 4, 4)
            if (sz <= 0 || p + 10 + sz > tag.size) break
            if (id == "CHAP") chap(tag, p + 10, sz)?.let { out.add(it) }
            p += 10 + sz
        }
        return out
    }

    private fun chap(b: ByteArray, off: Int, len: Int): Triple<Long, String, Long>? {
        val end = off + len
        var p = off
        while (p < end && b[p] != 0.toByte()) p++
        if (p >= end) return null
        p++                             // строка элемента вместе с завершающим нулём
        if (p + 16 > end) return null
        val start = be32(b, p)
        val stop = be32(b, p + 4)
        p += 16
        val title = tit2(b, p, end)
        val dur = if (stop > start) stop - start else 0L
        return Triple(start, title ?: "Глава", dur)
    }

    /**
     * Вложенный `TIT2`: заголовок 10 байт, кодировка в первом байте —
     * 0 — ISO-8859-1, 1 — UTF-16 с BOM, 2 — UTF-16BE, 3 — UTF-8 (v2.4).
     */
    private fun tit2(b: ByteArray, off: Int, end: Int): String? {
        if (off + 10 > end) return null
        if (String(b, off, 4, Charsets.ISO_8859_1) != "TIT2") return null
        val sz = syncsafe(b, off + 4, 4)
        if (sz <= 1 || off + 10 + sz > end) return null
        val enc = b[off + 10].toInt()
        return try {
            val cs = when (enc) {
                1 -> Charsets.UTF_16
                2 -> Charsets.UTF_16BE
                3 -> Charsets.UTF_8
                else -> Charsets.ISO_8859_1
            }
            String(b, off + 11, sz - 1, cs).trimEnd('\u0000').trim().ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    /** Размер по syncsafe-схеме: семь значащих бит на байт. */
    fun syncsafe(b: ByteArray, off: Int, n: Int): Int {
        var v = 0
        for (i in 0 until n) v = (v shl 7) or (b[off + i].toInt() and 0x7F)
        return v
    }

    fun be32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)
}
