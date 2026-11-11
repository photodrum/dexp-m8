package ru.ogrpfsense.dexpm8.ui

import android.content.Context
import android.text.format.DateFormat
import java.util.Locale

/** Значения настроек, которые нужны сразу нескольким экранам (B13 и B41). Только чтение. */
object SettingsData {

    /** Название языка оболочки с заглавной буквы: «Русский». */
    fun langName(): String {
        val l = Locale.getDefault()
        return l.getDisplayLanguage(l).replaceFirstChar { if (it.isLowerCase()) it.titlecase(l) else it.toString() }
    }

    /** «Русский · 24-часовой формат» — строка B13 «Дата, время, язык» и подзаголовок B41. */
    fun langValue(ctx: Context): String {
        val fmt = if (DateFormat.is24HourFormat(ctx)) "24-часовой формат" else "12-часовой формат"
        return "${langName()} · $fmt"
    }
}
