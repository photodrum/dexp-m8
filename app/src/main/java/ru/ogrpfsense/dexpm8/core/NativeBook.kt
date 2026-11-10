package ru.ogrpfsense.dexpm8.core

/**
 * Мост в C-ядро. Держим минимум: один статический метод парсинга FB2
 * и никакого состояния на стороне Kotlin, кроме результата.
 *
 * Библиотека грузится лениво: если .so почему-то не поднялась, оболочка
 * продолжает жить (библиотека/настройки работают), а читалка сообщает ошибку.
 */
object NativeBook {

    const val BLK_PARA = 0
    const val BLK_TITLE = 1
    const val BLK_SUBTITLE = 2
    const val BLK_EPIGRAPH = 3
    const val BLK_POEM = 4
    const val BLK_ANNOTATION = 5
    const val BLK_EMPTY = 6

    class Block(val type: Int, val text: String)

    private var loadError: String? = null

    init {
        try {
            System.loadLibrary("dexp_core")
        } catch (t: Throwable) {
            loadError = t.message ?: "cannot load dexp_core"
        }
    }

    val available: Boolean get() = loadError == null

    val error: String? get() = loadError

    /** Разбор FB2 целиком. null — ошибка разбора. */
    fun parseFb2(data: ByteArray): Array<Block>? {
        if (loadError != null) return null
        return try {
            parseFb2Native(data)
        } catch (t: Throwable) {
            null
        }
    }

    fun title(data: ByteArray): String? {
        if (loadError != null) return null
        return try {
            titleNative(data)
        } catch (t: Throwable) {
            null
        }
    }

    fun errorText(code: Int): String {
        if (loadError != null) return loadError!!
        return try {
            errorTextNative(code)
        } catch (t: Throwable) {
            "unknown"
        }
    }

    /**
     * Метаданные FB2 из `<title-info>`, без тела книги. Строки — уже без тегов
     * и сущностей, windows-1251 перекодирован; null — поля нет.
     * [authors] — все авторы через ", "; [firstName]…[nickname] — первый автор.
     * [complete] = false — `</title-info>` не попал в прочитанное начало файла
     * (огромная аннотация): поля могут быть неполными, можно перечитать больше.
     */
    class Meta(
        val title: String?,
        val authors: String?,
        val firstName: String?,
        val middleName: String?,
        val lastName: String?,
        val nickname: String?,
        val series: String?,
        val seriesNum: Int,
        val lang: String?,
        val coverId: String?,
        val complete: Boolean,
    )

    /** Сколько байт с начала файла читать для [metaFb2] (как BOOK_META_HEAD в book.h). */
    const val META_HEAD = 64 * 1024

    /**
     * Метаданные по первым [len] байтам [data]: буфер можно переиспользовать
     * между файлами. null — не FB2, кодировка не поддерживается или нет .so.
     */
    fun metaFb2(data: ByteArray, len: Int = data.size): Meta? {
        if (loadError != null) return null
        val f = try {
            metaFb2Native(data, len)
        } catch (t: Throwable) {
            null
        } ?: return null
        return Meta(
            title = f[0], authors = f[1],
            firstName = f[2], middleName = f[3], lastName = f[4], nickname = f[5],
            series = f[6], seriesNum = f[7]?.toIntOrNull() ?: 0,
            lang = f[8], coverId = f[9], complete = f[10] == "1",
        )
    }

    /** Читает начало файла в [buf] (не меньше [META_HEAD]) и разбирает метаданные. */
    fun metaFb2(file: java.io.File, buf: ByteArray): Meta? {
        val n = try {
            java.io.FileInputStream(file).use { s ->
                var got = 0
                while (got < buf.size) {
                    val r = s.read(buf, got, buf.size - got)
                    if (r < 0) break
                    got += r
                }
                got
            }
        } catch (e: java.io.IOException) {
            return null
        }
        return metaFb2(buf, n)
    }

    private external fun parseFb2Native(data: ByteArray): Array<Block>?
    private external fun titleNative(data: ByteArray): String?
    private external fun errorTextNative(code: Int): String
    private external fun metaFb2Native(data: ByteArray, len: Int): Array<String?>?
}
