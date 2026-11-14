package ru.ogrpfsense.dexpm8.reader

/**
 * Нативные методы сеанса DjVuLibre (R2): `libdexp_djvu.so`, `cpp/engines/djvu_jni.cpp`
 * → `djvu_session.cpp`. Снаружи не используется: читалка ходит через [Engine], который
 * выбирает движок по формату книги. Всё — только в `Engine.worker`.
 *
 * Позиция — строка `djvu:<страница>` (READER_SPEC: «страница + смещение»).
 */
internal object DjvuEngine {
    @JvmStatic external fun openNative(path: String): Int
    @JvmStatic external fun closeNative(): Int
    @JvmStatic external fun pageCountNative(): Int
    @JvmStatic external fun curPageNative(): Int
    @JvmStatic external fun pageNative(page: Int, w: Int, h: Int, out: ByteArray): Int
    @JvmStatic external fun posNative(page: Int): String?
    @JvmStatic external fun pageOfNative(pos: String): Int
    @JvmStatic external fun gotoPosNative(pos: String): Int
    @JvmStatic external fun posOnPageNative(pos: String): Int
    @JvmStatic external fun pageTextNative(): String?
    @JvmStatic external fun tocCountNative(): Int
    @JvmStatic external fun tocLevelNative(index: Int): Int
    @JvmStatic external fun tocPageNative(index: Int): Int
    @JvmStatic external fun tocStringNative(index: Int, which: Int): String?
}
