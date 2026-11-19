package ru.ogrpfsense.dexpm8.core

import java.util.Date
import java.util.TimeZone

/**
 * Часовые пояса мира — данные B71 и подпись пояса на B41/B60.
 *
 * Строка — это **пояс**, а не город: 418 зон tzdata дают 63 разных «часовых поведения», а по
 * круглым часам их ровно 24 (UTC−11 … UTC+12). Экран B71 показывает эти 24 на одной странице,
 * поэтому строка отвечает за целый пояс: время в ней верное для всех городов пояса, конкретный
 * город из 418 выбрать нельзя (решение человека 2026-11-19: 24 пояса, одна страница, нецелые
 * пояса не делаем).
 *
 * У пояса есть зона-представитель [Row.id] — её и пишем в `persist.sys.timezone`. Пояс, в
 * котором есть переход на летнее время, помечен [Row.summer]: житель Лагоса (UTC+1 круглый год)
 * и Берлина (UTC+1 зимой, UTC+2 летом) видят разные строки, иначе летом у одного из них время
 * было бы неверным.
 *
 * Представитель выбран так, чтобы подпись не врала: где можно — город без перехода на летнее
 * время (Гонолулу, Финикс, Богота, Каракас, вся Россия). Там, где без перехода пояса нет
 * (Берлин, Лондон, Азоры, Анкоридж, Лос-Анджелес, Чикаго), в [Row.summer] стоит «летом UTC+N».
 *
 * Все 24 представителя проверены на книге 2026-11-19: зона принимается свойством
 * `persist.sys.timezone` и даёт ожидаемое смещение. Грабля той проверки — tzdata Android 8.1
 * старая: `America/Sao_Paulo` отдаёт UTC−2, потому что в ней ещё действует летнее время
 * Бразилии (отменено в 2019). Поэтому представитель UTC−3 — `America/Buenos_Aires`.
 */
object Zones {

    /**
     * Строка списка: пояс по часам от UTC.
     *
     * [city] — город-представитель, он же подпись строки; [id] — зона для записи;
     * [summer] — «летом UTC+N», если в поясе есть переход на летнее время (иначе пусто);
     * [same] — другие города этого пояса, по ним пояс узнаётся.
     */
    class Row(val id: String, val city: String, val utc: Int, val summer: String = "", val same: String = "") {
        /** «MOSCOW» — для выравнивания колонок в подписи, не используется в коде. */
        val desc: String get() = utc(utc * 60) + (if (summer.isEmpty()) "" else " · $summer") + (if (same.isEmpty()) "" else " · $same")
    }

    /**
     * 24 пояса по порядку смещения, UTC−11 … UTC+12. Порядок в этом массиве — единственный
     * источник порядка на экране: колонки сетки 2×12 режут его пополам (первая — 0…11,
     * вторая — 12…23).
     */
    val ROWS = arrayOf(
        Row("Pacific/Pago_Pago", "Паго-Паго", -11, same = "Ниуэ"),
        Row("Pacific/Honolulu", "Гонолулу", -10, same = "Таити"),
        Row("America/Anchorage", "Анкоридж", -9, "летом UTC−8", "Аляска"),
        Row("America/Los_Angeles", "Лос-Анджелес", -8, "летом UTC−7", "Сан-Франциско"),
        Row("America/Phoenix", "Финикс", -7, same = "Денвер летом"),
        Row("America/Chicago", "Чикаго", -6, "летом UTC−5", "Мехико"),
        Row("America/Bogota", "Богота", -5, same = "Лима, Нью-Йорк летом"),
        Row("America/Caracas", "Каракас", -4, same = "Сантьяго летом"),
        Row("America/Buenos_Aires", "Буэнос-Айрес", -3, same = "Рио-де-Жанейро"),
        Row("America/Noronha", "Норонья", -2, same = "Фернанду-ди-Норонья"),
        Row("Atlantic/Azores", "Азоры", -1, "летом UTC+0"),
        Row("Europe/London", "Лондон", 0, "летом UTC+1", "Лиссабон"),
        Row("Europe/Berlin", "Берлин", 1, "летом UTC+2", "Париж, Рим"),
        Row("Europe/Kaliningrad", "Калининград", 2, same = "Киев, Бухарест летом"),
        Row("Europe/Moscow", "Москва", 3, same = "Стамбул, Минск"),
        Row("Europe/Samara", "Самара", 4, same = "Ижевск, Баку"),
        Row("Asia/Yekaterinburg", "Екатеринбург", 5, same = "Пермь, Уфа"),
        Row("Asia/Omsk", "Омск", 6, same = "Астана"),
        Row("Asia/Novosibirsk", "Новосибирск", 7, same = "Томск, Красноярск"),
        Row("Asia/Irkutsk", "Иркутск", 8, same = "Улан-Батор"),
        Row("Asia/Yakutsk", "Якутск", 9, same = "Токио, Сеул"),
        Row("Asia/Vladivostok", "Владивосток", 10, same = "Хабаровск, Сидней летом"),
        Row("Asia/Magadan", "Магадан", 11, same = "Сахалин"),
        Row("Asia/Kamchatka", "Камчатка", 12, same = "Петропавловск-Камчатский"),
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
     * Подпись пояса для строки B41/B60 и подзаголовка B71: «Москва, UTC+3».
     *
     * Пояс опознаётся по смещению и понятию летнего времени, а не по имени зоны: человек мог
     * выбрать пояс и в системных настройках (`Asia/Krasnoyarsk`, `Europe/Simferopol` и любой из
     * 418 городов), и тогда город берётся из системного названия — но своё имя строки из
     * [ROWS] он сохраняет, если смещение совпало.
     */
    fun label(id: String?, now: Date = Date()): String {
        val tz = if (id == null) TimeZone.getDefault() else TimeZone.getTimeZone(id)
        val min = tz.getOffset(now.time) / 60_000
        val same = ROWS.firstOrNull { it.utc * 60 == min }
        if (same != null && (id == null || id == same.id)) return "${same.city}, ${utc(min)}"
        val name = tz.getDisplayName(tz.inDaylightTime(now), TimeZone.LONG, java.util.Locale.getDefault())
            .substringBefore(',')
        return if (name.startsWith("GMT") || name.startsWith("UTC") || name.isEmpty()) utc(min)
        else "$name, ${utc(min)}"
    }
}

