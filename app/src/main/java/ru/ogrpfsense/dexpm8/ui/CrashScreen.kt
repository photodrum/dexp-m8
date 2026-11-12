package ru.ogrpfsense.dexpm8.ui

import android.content.Context
import android.graphics.Canvas
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Аварийный экран: показывает стек падения прямо на E-Ink, без adb и logcat.
 *
 * Особенности реализации, важные для надёжности:
 *  - текст готовится в конструкторе, а не в render: если рисование сорвётся,
 *    у нас уже есть что показать;
 *  - копия стека сразу пишется в файл (filesDir/last-crash.txt), чтобы причину
 *    можно было прочитать потом даже без фотографии;
 *  - вывод в System.err тоже дублируется: на некоторых прошивках он попадает
 *    в /data/anr или в logcat, даже когда основной обработчик не сработал.
 */
class CrashScreen(act: EinkActivity, private val error: Throwable) : Screen(act) {

    private val lines: List<String> = build(error)

    private fun build(t: Throwable): List<String> {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        val full = sw.toString()

        // Три копии, чтобы хоть одна осталась доступной:
        // 1) приватный файл приложения (читается через run-as);
        // 2) внешнее хранилище — видно в файловом менеджере книги, можно открыть
        //    и сфотографировать без adb и без компьютера;
        // 3) System.err — попадает в буфер crash у некоторых прошивок.
        try {
            File(act.filesDir, "last-crash.txt").writeText(full)
        } catch (ignored: Throwable) {
        }
        try {
            val ext = android.os.Environment.getExternalStorageDirectory()
            File(ext, "DexpM8-crash.txt").writeText(full)
        } catch (ignored: Throwable) {
        }
        try {
            System.err.println("DEXPM8_CRASH\n$full")
        } catch (ignored: Throwable) {
        }

        val out = ArrayList<String>(64)
        out.add("СБОЙ")
        for (raw in full.split('\n')) {
            var s = raw.trimEnd()
            if (s.isEmpty()) continue
            while (s.length > 58) {
                out.add(s.substring(0, 58))
                s = s.substring(58)
            }
            out.add(s)
            if (out.size > 320) break
        }
        return out
    }

    override fun render(c: Canvas) {
        ink.rect(c, 0f, 0f, W, H, T.BG)

        var y = 56f
        val mono = T.manrope(ink.res, 400)
        for ((i, line) in lines.withIndex()) {
            if (y > H - 48f) break
            val size = if (i == 0) 44f else 25f
            val color = if (i == 0) T.G1 else T.G3
            ink.text(c, line, 36f, y, size, if (i == 0) 700 else 400, color, mono)
            y += if (i == 0) 66f else 30f
        }
    }

    override fun onUp(x: Float, y: Float) {
        // Тап в нижней полосе — выйти, чтобы не зацикливаться на сбое.
        if (y > H - 200f) act.finish()
    }

    /** На экране сбоя шторки нет. */
    override val shadeZone: Float get() = 0f
}
