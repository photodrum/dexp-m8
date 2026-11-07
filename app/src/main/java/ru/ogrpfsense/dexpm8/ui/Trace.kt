package ru.ogrpfsense.dexpm8.ui

import android.content.Context
import java.io.File

/**
 * Трасса запуска: каждая контрольная точка дописывается в файл и в logcat.
 *
 * Зачем: обработчик падений не всегда успевает отрисовать экран (система
 * показывает свой диалог раньше), а logcat бывает недоступен — adb отваливается
 * ровно в момент сбоя. Файл же остаётся, и по последней записанной строке видно,
 * на каком шаге всё встало.
 *
 * Куда пишем:
 *  1) filesDir — приватный каталог, читается через run-as;
 *  2) getExternalFilesDir — /sdcard/Android/data/<пакет>/files/, НЕ требует
 *     разрешений (в отличие от корня хранилища) и виден файловым менеджером;
 *  3) logcat с тегом DexpM8.
 */
object Trace {

    const val TAG = "DexpM8"
    private const val FILE_NAME = "DexpM8-trace.txt"

    private var dirs: Array<File> = emptyArray()

    fun init(ctx: Context) {
        val list = ArrayList<File>(2)
        try {
            val priv = File(ctx.filesDir, FILE_NAME)
            priv.writeText("")
            list.add(priv)
        } catch (ignored: Throwable) {
        }
        try {
            // Внешний каталог приложения: разрешений не требует, доступен
            // файловому менеджеру книги.
            val extDir = ctx.getExternalFilesDir(null)
            if (extDir != null) {
                val ext = File(extDir, FILE_NAME)
                ext.writeText("")
                list.add(ext)
            }
        } catch (ignored: Throwable) {
        }
        dirs = list.toTypedArray()
        if (dirs.isEmpty()) {
            android.util.Log.e(TAG, "Trace: не удалось открыть ни один файл трассы")
        }
    }

    fun step(what: String) {
        android.util.Log.i(TAG, what)
        if (dirs.isEmpty()) return
        val line = "${System.currentTimeMillis()}  $what\n"
        for (f in dirs) {
            try {
                f.appendText(line)
            } catch (ignored: Throwable) {
            }
        }
    }
}
