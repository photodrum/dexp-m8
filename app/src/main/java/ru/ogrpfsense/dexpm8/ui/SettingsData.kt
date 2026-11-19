package ru.ogrpfsense.dexpm8.ui

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.text.format.DateFormat
import java.util.Locale

/** Значения настроек, которые нужны сразу нескольким экранам (B13 и B41). Только чтение. */
object SettingsData {

    /** Название языка оболочки с заглавной буквы: «Русский». */
    fun langName(): String {
        val l = Locale.getDefault()
        return l.getDisplayLanguage(l).replaceFirstChar { if (it.isLowerCase()) it.titlecase(l) else it.toString() }
    }

    /** Размер в русском формате: «5,2 ГБ», а меньше 0,1 ГБ — «48 МБ». */
    fun size(bytes: Long): String {
        if (bytes < 100_000_000L) return "${Math.round(bytes / 1e6)} МБ"
        return String.format(Locale("ru"), "%.1f", bytes / 1e9) + " ГБ"
    }

    /** Число гигабайт одной цифрой после запятой: «9,1». */
    fun gb1(bytes: Long): String = String.format(Locale("ru"), "%.1f", bytes / 1e9)

    /** Общее хранилище: (всего, свободно) в байтах; null — не прочитано. */
    fun storage(): Pair<Long, Long>? = try {
        val st = StatFs(Environment.getExternalStorageDirectory().path)
        Pair(st.blockCountLong * st.blockSizeLong, st.availableBlocksLong * st.blockSizeLong)
    } catch (t: Throwable) {
        Trace.step("settings storage FAIL: ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    /** Размер каталога со всем содержимым, байт; недоступный каталог — 0. */
    fun dirSize(f: java.io.File?): Long {
        if (f == null || !f.exists()) return 0L
        if (f.isFile) return f.length()
        var n = 0L
        f.listFiles()?.forEach { n += dirSize(it) }
        return n
    }

    /** Заявленный объём накопителя, ГБ: ближайшая степень двойки не меньше доступного объёма (16, а не 12,4). */
    fun capacityGb(totalBytes: Long): Int {
        var g = 1
        while (g * 1e9 < totalBytes) g *= 2
        return g
    }

    /** «Русский · 24-часовой формат» — строка B13 «Дата, время, язык» и подзаголовок B41. */
    fun langValue(ctx: Context): String {
        val fmt = if (DateFormat.is24HourFormat(ctx)) "24-часовой формат" else "12-часовой формат"
        return "${langName()} · $fmt"
    }

    /** Автор оболочки — в строке версии на B43 и B63. */
    const val AUTHOR = "Денис Лампусов"

    /**
     * Строка версии для B43 «Об устройстве» и B63 «Готово» — одна на оба экрана (замечание 23):
     * «Оболочка 0.3.0 · сборка 3, автор Денис Лампусов». Пустое имя версии или нулевой номер
     * сборки просто выпадают.
     */
    fun versionLine(ctx: Context): String {
        val info = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        } catch (t: Throwable) {
            null
        }
        val name = info?.versionName ?: ""
        val code = when {
            info == null -> 0L
            android.os.Build.VERSION.SDK_INT >= 28 -> info.longVersionCode
            else -> info.versionCode.toLong()
        }
        return "Оболочка $name" + (if (code > 0L) " · сборка $code" else "") + ", автор " + AUTHOR
    }
}
