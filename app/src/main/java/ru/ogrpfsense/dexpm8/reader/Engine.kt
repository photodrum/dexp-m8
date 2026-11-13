package ru.ogrpfsense.dexpm8.reader

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import java.io.File
import ru.ogrpfsense.dexpm8.ui.Trace

/**
 * Сеанс чтения (R1): один открытый документ, все вызовы — в одном потоке [worker].
 *
 * Почему один поток: `LVDocView` из crengine не потокобезопасен (READER_SPEC, раздел 4).
 * Очередь `newSingleThreadExecutor` и есть эта гарантия; наружу видны только готовые
 * данные — номер страницы, заполненный буфер, строка позиции.
 *
 * Страница приходит в `ByteArray` (8 бит на точку, stride = w, уже 16 уровней серого
 * и инвертирована под `Bitmap.Config.ALPHA_8` — шаг 7.2, хелпер `engines/cre_gray.h`).
 * Буфер один на сеанс: экран копирует его в свой `Bitmap`, пока не запрошен следующий кадр.
 */
object Engine {

    /** Поток движка, общий с пробой R0: движок один и .so одна. */
    val worker = EngineProbe.worker

    /** Коды ошибок: общие `DEXP_PROBE_*` (probe.h) и свои `DEXP_READER_*` (cre_session.cpp). */
    const val OK = 0
    const val EARG = -1
    const val EOPEN = -2
    const val EPAGE = -3
    const val ERENDER = -4
    const val ENOSESSION = -10
    const val EPOS = -11
    const val EFONT = -12
    const val ELOAD = EngineProbe.E_LOAD

    private val loaded = HashMap<String, String?>(2)     // движок → null (ок) или текст ошибки

    /** Каталог кэша разметки во внутренней памяти приложения (правило READER_SPEC). */
    @Volatile
    private var cacheDir: String? = null

    private var open = false

    /**
     * Задать каталог кэша разметки. Вызывается один раз из `EinkActivity.onCreate`:
     * `/data/data/<пакет>` знает только `Context`.
     */
    fun init(ctx: Context) {
        val d = File(ctx.cacheDir, "cre")
        try {
            if (!d.exists()) d.mkdirs()
            cacheDir = d.absolutePath
        } catch (t: Throwable) {
            Trace.step("reader: cache dir FAIL ${t.javaClass.simpleName}: ${t.message}")
            cacheDir = null
        }
    }

    /** Загрузить `libdexp_cre.so`; null — успех, иначе текст ошибки. Только в [worker]. */
    private fun load(): String? {
        if (loaded.containsKey("cre")) return loaded["cre"]
        val err = try {
            System.loadLibrary("dexp_cre")
            null
        } catch (t: Throwable) {
            t.message ?: t.javaClass.simpleName
        }
        loaded["cre"] = err
        if (err != null) Trace.step("reader: load FAIL $err")
        return err
    }

    /**
     * Открыть [path] и сверстать под окно [w]×[h] (реальные px текста без колонтитулов).
     * Только в [worker]. Возвращает код; место чтения ищется по [pageOf].
     */
    fun open(path: String, w: Int, h: Int): Int {
        val err = load()
        if (err != null) return ELOAD
        val t0 = SystemClock.uptimeMillis()
        val rc = try {
            openNative(path, cacheDir, w, h)
        } catch (t: Throwable) {
            Trace.step("reader: open FAIL ${t.javaClass.simpleName}: ${t.message}")
            ERENDER
        }
        open = rc == OK
        Trace.step("reader: open rc=$rc ${SystemClock.uptimeMillis() - t0}ms $path")
        return rc
    }

    /** Закрыть документ (шрифты и кэш остаются). Только в [worker]. */
    fun close() {
        if (!open) return
        open = false
        try {
            closeNative()
        } catch (t: Throwable) {
            Trace.step("reader: close FAIL ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Число страниц; `< 0` — документ не открыт. Только в [worker]. */
    fun pageCount(): Int = if (!open) ENOSESSION else try {
        pageCountNative()
    } catch (t: Throwable) {
        EPAGE
    }

    /** Страница, на которой стоит движок сейчас (0-based); `< 0` — нет документа. Только в [worker]. */
    fun curPage(): Int = if (!open) ENOSESSION else try {
        curPageNative()
    } catch (t: Throwable) {
        EPAGE
    }

    /**
     * Отрисовать страницу [page] в [out] (не меньше w×h, 8 бит на точку).
     * Только в [worker]; возвращает код.
     */
    fun page(page: Int, w: Int, h: Int, out: ByteArray): Int {
        if (!open) return ENOSESSION
        return try {
            pageNative(page, w, h, out)
        } catch (t: Throwable) {
            Trace.step("reader: page FAIL ${t.javaClass.simpleName}: ${t.message}")
            ERENDER
        }
    }

    /** Сменить кегль, сохранив место чтения [keepPos]. Только в [worker]. */
    fun setFont(size: Int, keepPos: String?): Int {
        if (!open) return ENOSESSION
        return try {
            setFontNative(size, keepPos)
        } catch (t: Throwable) {
            Trace.step("reader: setFont FAIL ${t.javaClass.simpleName}: ${t.message}")
            ERENDER
        }
    }

    /** Позиция страницы строкой xpointer; null — не получилась. Только в [worker]. */
    fun pos(page: Int): String? {
        if (!open) return null
        return try {
            posNative(page)
        } catch (t: Throwable) {
            null
        }
    }

    /** Страница по xpointer; -1 — позиция не нашлась. Только в [worker]. */
    fun pageOf(pos: String?): Int {
        if (!open || pos.isNullOrEmpty()) return -1
        return try {
            pageOfNative(pos)
        } catch (t: Throwable) {
            -1
        }
    }

    /**
     * Встать на позицию [pos] — восстановление места чтения при открытии.
     * Возвращает страницу (0-based); `< 0` — позиция не нашлась (начинаем с начала).
     * Только в [worker].
     */
    fun gotoPos(pos: String?): Int {
        if (!open || pos.isNullOrEmpty()) return -1
        return try {
            gotoPosNative(pos)
        } catch (t: Throwable) {
            -1
        }
    }

    /** Первые символы текущей страницы (трасса и проверка места чтения); null — нет. */
    fun pageText(): String? {
        if (!open) return null
        return try {
            pageTextNative()
        } catch (t: Throwable) {
            null
        }
    }

    /** Текст кода ошибки по-русски — для трассы и экранов B45/B58. */
    fun codeText(rc: Int): String = when (rc) {
        OK -> "ok"
        EARG -> "неверные аргументы"
        EOPEN -> "файл не открылся"
        EPAGE -> "страница не загрузилась"
        ERENDER -> "ошибка отрисовки"
        ENOSESSION -> "книга не открыта"
        EPOS -> "позиция не нашлась"
        EFONT -> "нет шрифтов"
        ELOAD -> ".so не загрузилась"
        else -> "код $rc"
    }

    /** Пустой `Bitmap` под страницу [w]×[h] — экран держит его весь сеанс. */
    fun newPageBitmap(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)

    @JvmStatic private external fun openNative(path: String, cacheDir: String?, w: Int, h: Int): Int
    @JvmStatic private external fun closeNative(): Int
    @JvmStatic private external fun pageCountNative(): Int
    @JvmStatic private external fun curPageNative(): Int
    @JvmStatic private external fun pageNative(page: Int, w: Int, h: Int, out: ByteArray): Int
    @JvmStatic private external fun setFontNative(size: Int, keepPos: String?): Int
    @JvmStatic private external fun posNative(page: Int): String?
    @JvmStatic private external fun pageOfNative(pos: String): Int
    @JvmStatic private external fun gotoPosNative(pos: String): Int
    @JvmStatic private external fun pageTextNative(): String?
}
