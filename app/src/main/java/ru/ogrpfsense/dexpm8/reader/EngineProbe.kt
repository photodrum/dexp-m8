package ru.ogrpfsense.dexpm8.reader

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Проба движков R0 (шаг 7.2): первая страница книги в серый буфер через `dexp_*_probe`
 * (`cpp/engines/probe.h`, JNI — `cpp/engines/probe_jni.c`).
 *
 * Каждая `.so` грузится лениво и отдельно: не поднялся один движок — остальные работают.
 * Все вызовы — в одном фоновом потоке [worker] (CREngine не потокобезопасен).
 * Буфер на выходе — уже 16 уровней серого и инвертирован: альфа для `Bitmap.Config.ALPHA_8`.
 */
object EngineProbe {

    const val CRE = "cre"
    const val DJVU = "djvu"

    /** Коды `DEXP_PROBE_*` из probe.h и свой код «.so не загрузилась». */
    const val OK = 0
    const val E_LOAD = -100

    val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "dexp-engine").apply { priority = Thread.NORM_PRIORITY }
    }

    private val loaded = HashMap<String, String?>(4)   // движок → null (ок) или текст ошибки

    /**
     * Движок по формату из `LibraryDb` (`Scanner.formatOf`); null — формат движкам не отдан
     * (PDF и CBZ не поддерживаются — решение 2026-11-11, лицензия).
     */
    fun engineFor(format: String): String? = when (format) {
        "fb2", "fb2.zip", "epub", "mobi", "txt", "rtf", "doc" -> CRE
        "djvu", "djv" -> DJVU
        else -> null
    }

    /** Загрузить `libdexp_<engine>.so`; null — успех, иначе текст ошибки. Только в [worker]. */
    fun load(engine: String): String? {
        if (loaded.containsKey(engine)) return loaded[engine]
        val err = try {
            System.loadLibrary("dexp_$engine")
            null
        } catch (t: Throwable) {
            t.message ?: t.javaClass.simpleName
        }
        loaded[engine] = err
        return err
    }

    /**
     * Первая страница [path] в [out] (не меньше w×h). Возвращает код `DEXP_PROBE_*`
     * или [E_LOAD]. Только в [worker].
     */
    fun render(engine: String, path: String, w: Int, h: Int, out: ByteArray): Int {
        if (load(engine) != null) return E_LOAD
        return when (engine) {
            CRE -> creNative(path, w, h, out)
            DJVU -> djvuNative(path, w, h, out)
            else -> -1
        }
    }

    fun codeText(rc: Int): String = when (rc) {
        OK -> "ok"
        -1 -> "EARG — неверные аргументы"
        -2 -> "EOPEN — файл не открылся"
        -3 -> "EPAGE — страница не загрузилась"
        -4 -> "ERENDER — ошибка отрисовки"
        E_LOAD -> ".so не загрузилась"
        else -> "код $rc"
    }

    @JvmStatic private external fun creNative(path: String, w: Int, h: Int, out: ByteArray): Int
    @JvmStatic private external fun djvuNative(path: String, w: Int, h: Int, out: ByteArray): Int
}
