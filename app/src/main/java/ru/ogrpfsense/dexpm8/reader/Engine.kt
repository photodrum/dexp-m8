package ru.ogrpfsense.dexpm8.reader

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import java.io.File
import ru.ogrpfsense.dexpm8.ui.Trace

/**
 * Сеанс чтения (R1, R2): один открытый документ, все вызовы — в одном потоке [worker].
 * Движок выбирается в [open] по расширению: DjVu — [DjvuEngine] (`libdexp_djvu.so`),
 * остальное — crengine (`libdexp_cre.so`). Снаружи разницы нет.
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

    // ---------------------------------------------------------------- прогресс открытия (B58)

    /**
     * Прогресс открытия 0…100, который присылает движок: колбэк crengine
     * (`OnLoadFileProgress` / `OnFormatProgress`) → `cre_session.cpp` → JNI-переходник
     * (`reader_jni.cpp`) → [onOpenProgress]. Кладётся из потока движка, читается с UI.
     */
    @Volatile
    private var openPercent = -1

    /** Последний отданный экрану процент: E-Ink не любит частую перерисовку. */
    private var sentPercent = -1

    /** Приёмник прогресса от нативного кода — зовётся из потока движка. */
    @JvmStatic
    fun onOpenProgress(percent: Int) {
        openPercent = percent
        val l = progressListener
        // Шаг в 2 % — примерно строка журнала: мельче на E-Ink не видно, а кадр дорог.
        if (l != null && (percent - sentPercent >= 2 || percent >= 100)) {
            sentPercent = percent
            l(percent)
        }
    }

    /** Экран B58 подписывается на прогресс открытия; null — отписка. */
    @Volatile
    private var progressListener: ((Int) -> Unit)? = null

    fun setProgressListener(l: ((Int) -> Unit)?) {
        progressListener = l
        sentPercent = -1
    }

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

    /**
     * Открытый документ — DjVu (R2, [DjvuEngine], `libdexp_djvu.so`), иначе crengine.
     * Ставится в [open] по расширению файла и не меняется до следующего [open].
     */
    var djvu = false
        private set

    /** Файл [path] — DjVu (по расширению, как `EngineProbe.engineFor`). */
    fun isDjvu(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
        return EngineProbe.engineFor(ext) == EngineProbe.DJVU
    }

    /** Загрузить `libdexp_<lib>.so`; null — успех, иначе текст ошибки. Только в [worker]. */
    private fun load(lib: String): String? {
        if (loaded.containsKey(lib)) return loaded[lib]
        val err = try {
            System.loadLibrary("dexp_$lib")
            null
        } catch (t: Throwable) {
            t.message ?: t.javaClass.simpleName
        }
        loaded[lib] = err
        if (err != null) Trace.step("reader: load $lib FAIL $err")
        return err
    }

    /**
     * Открыть [path] и сверстать под окно [w]×[h] (реальные px текста без колонтитулов;
     * DjVu вписывает страницу в это окно при отрисовке). Только в [worker].
     * Возвращает код; место чтения ищется по [pageOf].
     */
    fun open(path: String, w: Int, h: Int): Int {
        close()                         // прошлый документ — того же или другого движка
        val dj = isDjvu(path)
        val err = load(if (dj) "djvu" else "cre")
        if (err != null) return ELOAD
        djvu = dj
        val t0 = SystemClock.uptimeMillis()
        val rc = try {
            if (dj) DjvuEngine.openNative(path) else openNative(path, cacheDir, w, h)
        } catch (t: Throwable) {
            Trace.step("reader: open FAIL ${t.javaClass.simpleName}: ${t.message}")
            ERENDER
        }
        open = rc == OK
        Trace.step("reader: open ${if (dj) "djvu" else "cre"} rc=$rc " +
            "${SystemClock.uptimeMillis() - t0}ms $path")
        return rc
    }

    /** Закрыть документ (шрифты и кэш остаются). Только в [worker]. */
    fun close() {
        if (!open) return
        open = false
        try {
            if (djvu) DjvuEngine.closeNative() else closeNative()
        } catch (t: Throwable) {
            Trace.step("reader: close FAIL ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Число страниц; `< 0` — документ не открыт. Только в [worker]. */
    fun pageCount(): Int = if (!open) ENOSESSION else try {
        if (djvu) DjvuEngine.pageCountNative() else pageCountNative()
    } catch (t: Throwable) {
        EPAGE
    }

    /** Страница, на которой стоит движок сейчас (0-based); `< 0` — нет документа. Только в [worker]. */
    fun curPage(): Int = if (!open) ENOSESSION else try {
        if (djvu) DjvuEngine.curPageNative() else curPageNative()
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
            if (djvu) DjvuEngine.pageNative(page, w, h, out) else pageNative(page, w, h, out)
        } catch (t: Throwable) {
            Trace.step("reader: page FAIL ${t.javaClass.simpleName}: ${t.message}")
            ERENDER
        }
    }

    /** Сменить кегль, сохранив место чтения [keepPos]. Только в [worker]. */
    fun setFont(size: Int, keepPos: String?): Int {
        if (!open) return ENOSESSION
        return try {
            if (djvu) OK else setFontNative(size, keepPos)   // DjVu не верстается
        } catch (t: Throwable) {
            Trace.step("reader: setFont FAIL ${t.javaClass.simpleName}: ${t.message}")
            ERENDER
        }
    }

    /** Позиция страницы строкой xpointer; null — не получилась. Только в [worker]. */
    fun pos(page: Int): String? {
        if (!open) return null
        return try {
            if (djvu) DjvuEngine.posNative(page) else posNative(page)
        } catch (t: Throwable) {
            null
        }
    }

    /** Страница по xpointer; -1 — позиция не нашлась. Только в [worker]. */
    fun pageOf(pos: String?): Int {
        if (!open || pos.isNullOrEmpty()) return -1
        return try {
            if (djvu) DjvuEngine.pageOfNative(pos) else pageOfNative(pos)
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
            if (djvu) DjvuEngine.gotoPosNative(pos) else gotoPosNative(pos)
        } catch (t: Throwable) {
            -1
        }
    }

    /** Первые символы текущей страницы (трасса и проверка места чтения); null — нет. */
    fun pageText(): String? {
        if (!open) return null
        return try {
            if (djvu) DjvuEngine.pageTextNative() else pageTextNative()
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Лежит ли позиция [pos] на странице, где сейчас стоит движок: 1 — да, 0 — нет,
     * `< 0` — нет документа или позиция не нашлась. Только в [worker].
     *
     * Этим проверяется приёмка «та же фраза» после смены кегля. Сравнивать первые
     * символы страницы нельзя: страница начинается раньше середины абзаца, откуда
     * обычно снята позиция (разбор — DEVLOG, R1.2).
     */
    fun posOnPage(pos: String?): Int {
        if (!open || pos.isNullOrEmpty()) return -1
        return try {
            if (djvu) DjvuEngine.posOnPageNative(pos) else posOnPageNative(pos)
        } catch (t: Throwable) {
            -1
        }
    }

    /**
     * Оглавление книги плоско, в порядке текста (B10 «Оглавление», R3 — здесь только
     * данные). Пустой список — в книге нет оглавления или документ не открыт.
     * Копия строится один раз на разметку: после [setFont] номера страниц пересчитываются.
     * Только в [worker].
     */
    fun toc(): List<TocItem> {
        if (!open) return emptyList()
        return try {
            val n = if (djvu) DjvuEngine.tocCountNative() else tocCountNative()
            if (n <= 0) return emptyList()
            ArrayList<TocItem>(n).apply {
                for (i in 0 until n) {
                    val title = if (djvu) DjvuEngine.tocStringNative(i, 0) else tocStringNative(i, 0)
                    if (title == null) continue
                    add(TocItem(
                        title = title,
                        level = if (djvu) DjvuEngine.tocLevelNative(i) else tocLevelNative(i),
                        page = if (djvu) DjvuEngine.tocPageNative(i) else tocPageNative(i),
                        pos = (if (djvu) DjvuEngine.tocStringNative(i, 1) else tocStringNative(i, 1))?.ifEmpty { null },
                    ))
                }
            }
        } catch (t: Throwable) {
            Trace.step("reader: toc FAIL ${t.javaClass.simpleName}: ${t.message}")
            emptyList()
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
    @JvmStatic private external fun tocCountNative(): Int
    @JvmStatic private external fun tocLevelNative(index: Int): Int
    @JvmStatic private external fun tocPageNative(index: Int): Int
    @JvmStatic private external fun tocStringNative(index: Int, which: Int): String?
    @JvmStatic private external fun posOnPageNative(pos: String): Int
}
