package ru.ogrpfsense.dexpm8.core

/**
 * Направление перевода слова на вкладке «Перевод» (R5, B30; решение 2026-11-27).
 *
 * Правило: **русское слово — на английский, любое другое — на русский.**
 *  - в слове есть кириллица → `ru → en`; исключение — книга на другом кириллическом языке
 *    (`uk`, `be`, `bg`, `sr`, …): тогда `<язык книги> → ru`;
 *  - латиница, язык книги — латинский (`en`, `de`, `fr`, …) → `<язык книги> → ru`;
 *  - латиница, а языка книги нет, он русский или не латинский (EPUB и DjVu язык не отдают,
 *    в русской книге бывают чужие слова) → автоопределение MyMemory: `Autodetect|ru`.
 *
 * Автоопределение по одному слову сервис делает неточно (`Haus` → `da`, `дом` → `uk`), поэтому
 * подпись «откуда» из его ответа не берём — на вкладке пишется «авто → ru».
 *
 * Чистый Kotlin без Android: собирается вместе с `tools/tr_dir_test.kt` (`docs/CHECKS.md`).
 */
object TrDir {

    /** Направление: [from] — код языка, пусто = автоопределение; [to] — `ru` или `en`. */
    class Dir(val from: String, val to: String) {
        /** Значение `langpair` для MyMemory. */
        fun api(): String = (if (from.isEmpty()) "Autodetect" else from) + "|" + to

        /** Подпись на листе: «ru → en», «de → ru», «авто → ru». */
        fun label(): String = (if (from.isEmpty()) "авто" else from) + " → " + to

        /** Код «откуда» для [DictOnline.Tr]: у автоопределения — «авто». */
        fun fromLabel(): String = if (from.isEmpty()) "авто" else from
    }

    /** Кириллические языки, кроме русского: слово в такой книге русским не считаем. */
    private val CYRILLIC = setOf("uk", "be", "bg", "sr", "mk", "kk", "ky", "tg", "mn", "tt", "ba", "cv")

    /** Языки на латинице, которые MyMemory принимает кодом `xx|ru`. */
    private val LATIN = setOf(
        "en", "de", "fr", "es", "it", "pt", "nl", "sv", "da", "no", "nb", "nn", "fi", "pl", "cs",
        "sk", "sl", "hr", "hu", "ro", "tr", "lt", "lv", "et", "la", "eo", "ca", "id", "ms", "vi",
        "af", "sq", "eu", "gl", "is", "ga", "cy", "mt",
    )

    /** `ru-RU`, `RU`, `en_US`, ` de ` → `ru`, `ru`, `en`, `de`; пусто и мусор → null. */
    fun lang(raw: String?): String? {
        val s = raw?.trim()?.lowercase() ?: return null
        val code = s.takeWhile { it != '-' && it != '_' && it != ' ' }
        if (code.length !in 2..3 || !code.all { it in 'a'..'z' }) return null
        return code
    }

    /** Кириллица (русская, украинская, …) — блок U+0400…U+04FF. */
    fun hasCyrillic(s: String): Boolean = s.any { it in '\u0400'..'\u04FF' }

    /** Направление для [word] в книге с языком [bookLang] (`dc:language`/`<lang>`; null — неизвестен). */
    fun pair(word: String, bookLang: String?): Dir {
        val l = lang(bookLang)
        if (hasCyrillic(word)) {
            return if (l != null && l in CYRILLIC) Dir(l, "ru") else Dir("ru", "en")
        }
        return if (l != null && l in LATIN) Dir(l, "ru") else Dir("", "ru")
    }
}
