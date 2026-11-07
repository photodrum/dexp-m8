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

    private external fun parseFb2Native(data: ByteArray): Array<Block>?
    private external fun titleNative(data: ByteArray): String?
    private external fun errorTextNative(code: Int): String
}
