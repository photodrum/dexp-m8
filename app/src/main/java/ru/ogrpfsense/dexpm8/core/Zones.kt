package ru.ogrpfsense.dexpm8.core

import java.util.Date
import java.util.TimeZone

/**
 * Часовые пояса России — данные B71 и подпись пояса на B41/B60.
 *
 * В списке 11 строк: по одной на каждый действующий пояс России. Летнего времени с 2014
 * года нет, поэтому зоны с одинаковым смещением неразличимы на практике и вторую строку
 * не занимают — Крым и Красноярск идут в описании своего пояса (в списке зон Android они
 * по-прежнему отдельные: `Europe/Simferopol`, `Asia/Krasnoyarsk`, но смещение то же).
 *
 * Названия строк и порядок («Москва» первой, затем по смещению) — как в макете B71; текущий
 * пояс на экране поднимается наверх, поэтому порядок здесь — только исходный.
 *
 * Названия зон проверены на книге 2026-11-19: все 11 принимаются свойством
 * `persist.sys.timezone` и дают ожидаемое время (UTC+2 … UTC+12).
 */
object Zones {

    /** Строка списка: идентификатор зоны Android, город и описание под ним. */
    class Row(val id: String, val city: String, val desc: String)

    val ROWS = arrayOf(
        Row("Europe/Kaliningrad", "Калининград", "UTC+2"),
        Row("Europe/Moscow", "Москва", "UTC+3 · Санкт-Петербург, Казань"),
        Row("Europe/Samara", "Самара", "UTC+4 · Ижевск"),
        Row("Asia/Yekaterinburg", "Екатеринбург", "UTC+5 · Пермь, Уфа"),
        Row("Asia/Omsk", "Омск", "UTC+6"),
        Row("Asia/Novosibirsk", "Новосибирск", "UTC+7 · Томск, Красноярск"),
        Row("Asia/Irkutsk", "Иркутск", "UTC+8"),
        Row("Asia/Yakutsk", "Якутск", "UTC+9"),
        Row("Asia/Vladivostok", "Владивосток", "UTC+10 · Хабаровск"),
        Row("Asia/Magadan", "Магадан", "UTC+11"),
        Row("Asia/Kamchatka", "Камчатка", "UTC+12 · Петропавловск-Камчатский"),
    )

    /** Смещение зоны в минутах от UTC на момент [now]; неизвестная зона — 0. */
    fun offsetMin(id: String, now: Date = Date()): Int =
        TimeZone.getTimeZone(id).getOffset(now.time) / 60_000

    /** «UTC+3», «UTC−9:30» — знак и минуты, если они есть. */
    fun utc(min: Int): String {
        val h = Math.abs(min) / 60
        val m = Math.abs(min) % 60
        return "UTC" + (if (min < 0) "−" else "+") + h + (if (m != 0) ":" + String.format("%02d", m) else "")
    }

    /**
     * Подпись пояса для строки B41/B60: «Москва, UTC+3». Пояс не из списка (человек выбрал
     * его в системных настройках) — название из `TimeZone` без города, как было до B71.
     */
    fun label(id: String?, now: Date = Date()): String {
        val tz = if (id == null) TimeZone.getDefault() else TimeZone.getTimeZone(id)
        val min = tz.getOffset(now.time) / 60_000
        val city = ROWS.firstOrNull { it.id == id }?.city
        if (city != null) return "$city, ${utc(min)}"
        val name = tz.getDisplayName(tz.inDaylightTime(now), TimeZone.LONG, java.util.Locale.getDefault())
            .substringBefore(',')
        return if (name.startsWith("GMT") || name.startsWith("UTC") || name.isEmpty()) utc(min)
        else "$name, ${utc(min)}"
    }
}
