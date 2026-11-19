package ru.ogrpfsense.dexpm8.core

import java.io.File
import java.io.RandomAccessFile

/**
 * Главы из атома `chpl` в MP4/M4B (п. 12). Атом лежит в `moov` → `udta` → `chpl`.
 *
 * Устройство атома в спецификации Nero двусмысленно в одном месте: есть ли после
 * `version+flags` ещё одно 4-байтовое резервное поле. Редакторы пишут по-разному, поэтому
 * разбор пробует оба варианта и берёт тот, при котором главы занимают ровно размер атома
 * ([layout]).
 *
 * Глава: `start` (8 байт, единицы `timescale` из `mvhd` — обычно 1000, то есть мс),
 * `len` (1 байт) и название в UTF-8. Длительность главы — до начала следующей; у последней
 * она 0, остаток добирает вызывающий.
 */
internal object Mp4Chapters {

    /** (смещение от начала файла в мс, название, длительность в мс) */
    fun read(f: File): List<Triple<Long, String, Long>> = try {
        RandomAccessFile(f, "r").use { raf ->
            val moov = find(raf, 0L, raf.length(), "moov")
            if (moov == null) return emptyList()
            val udta = find(raf, moov.body, moov.end, "udta")
            val own = find(raf, moov.body, moov.end, "chpl")
            val chpl = own ?: (if (udta != null) find(raf, udta.body, udta.end, "chpl") else null)
            if (chpl == null) return emptyList()
            val ts = timescale(raf, moov)
            parse(raf, chpl, ts)
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** Атом: смещение тела и конец атома (начало + размер). */
    private class Atom(val body: Long, val end: Long)

    /** Первый атом с именем [name] среди прямых детей диапазона [from, to). */
    private fun find(raf: RandomAccessFile, from: Long, to: Long, name: String): Atom? {
        var p = from
        val head = ByteArray(8)
        while (p + 8 <= to) {
            raf.seek(p)
            if (raf.read(head) < 8) return null
            var size = be32(head, 0)
            if (size == 1L) return null              // 64-битный размер: у глав не встречается
            if (size == 0L) size = to - p            // атом до конца файла
            if (size < 8 || p + size > to) return null
            if (String(head, 4, 4, Charsets.ISO_8859_1) == name) return Atom(p + 8, p + size)
            p += size
        }
        return null
    }

    /** `timescale` из `mvhd`: у версии 1 — по +20, у версии 0 — по +12. */
    private fun timescale(raf: RandomAccessFile, moov: Atom): Long {
        val mvhd = find(raf, moov.body, moov.end, "mvhd") ?: return 1000L
        raf.seek(mvhd.body)
        val b = ByteArray(24)
        if (raf.read(b) < 24) return 1000L
        val v = be32(b, 0)
        val ts = if (v == 1L) be32(b, 20) else be32(b, 12)
        return if (ts > 0L) ts else 1000L
    }

    private fun parse(raf: RandomAccessFile, chpl: Atom, timescale: Long): List<Triple<Long, String, Long>> {
        // Счётчик глав стоит либо сразу после version+flags (4), либо после резервного поля (8).
        val reserve = when {
            counter(raf, chpl, 8) > 0 -> true
            counter(raf, chpl, 4) > 0 -> false
            else -> return emptyList()
        }
        return tryLayout(raf, chpl, reserve, timescale)
    }

    /** Сколько глав по записи со смещением [at] от тела атома; 0 — там не счётчик. */
    private fun counter(raf: RandomAccessFile, chpl: Atom, at: Long): Int {
        if (chpl.body + at + 4 > chpl.end) return 0
        raf.seek(chpl.body + at)
        val b = ByteArray(4)
        if (raf.read(b) < 4) return 0
        val n = be32(b, 0)
        if (n <= 0L || n > 5000L) return 0
        // Сами главы должны поместиться в атом: иначе это не счётчик.
        return if (chpl.body + at + 4 + 9L * n <= chpl.end) n.toInt() else 0
    }

    private fun tryLayout(raf: RandomAccessFile, chpl: Atom, reserve: Boolean,
                          timescale: Long): List<Triple<Long, String, Long>> {
        val ts = if (timescale > 0L) timescale else 1000L
        val at = if (reserve) 8 else 4
        raf.seek(chpl.body + at)
        val cnt = ByteArray(4)
        if (raf.read(cnt) < 4) return emptyList()
        val n = be32(cnt, 0).toInt()
        val out = ArrayList<Pair<Long, String>>(n)
        for (i in 0 until n) {
            val b = ByteArray(9)
            if (raf.read(b) < 9) break
            val start = be64(b, 0) * 1000L / ts
            val len = b[8].toInt() and 0xFF
            val name = ByteArray(len)
            if (len > 0 && raf.read(name) < len) break
            val title = String(name, Charsets.UTF_8).trim().ifEmpty { "Глава ${i + 1}" }
            out.add(start to title)
        }
        return out.mapIndexed { i, t ->
            val dur = if (i + 1 < out.size) out[i + 1].first - t.first else 0L
            Triple(t.first, t.second, maxOf(0L, dur))
        }
    }

    private fun be32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

    private fun be64(b: ByteArray, off: Int): Long = (be32(b, off) shl 32) or be32(b, off + 4)
}
