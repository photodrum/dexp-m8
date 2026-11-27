// Хост-проверка core/TrDir.kt (направление перевода, R5, решение 2026-11-27): русское слово → en,
// любое другое → ru; язык книги — вместо автоопределения. Запуск — docs/CHECKS.md, «Направление перевода на хосте».
import ru.ogrpfsense.dexpm8.core.TrDir

private var fails = 0

private fun check(name: String, word: String, lang: String?, api: String, label: String) {
    val d = TrDir.pair(word, lang)
    if (d.api() == api && d.label() == label) println("OK   $name")
    else { fails++; println("FAIL $name\n  ждали: $api / $label\n  вышло: ${d.api()} / ${d.label()}") }
}

private fun lang(name: String, raw: String?, want: String?) {
    val got = TrDir.lang(raw)
    if (got == want) println("OK   $name")
    else { fails++; println("FAIL $name\n  ждали: $want\n  вышло: $got") }
}

fun main() {
    // 1. Русское слово: на английский, язык книги не мешает (русская, без языка, английская).
    check("рус, книга ru", "дом", "ru", "ru|en", "ru → en")
    check("рус, язык неизвестен", "дом", null, "ru|en", "ru → en")
    check("рус, пустой язык", "дом", "", "ru|en", "ru → en")
    check("рус в английской книге", "дом", "en", "ru|en", "ru → en")
    check("рус, ru-RU", "Дом", "ru-RU", "ru|en", "ru → en")
    // 2. Книга на другом кириллическом языке: язык книги → ru.
    check("укр книга", "дім", "uk", "uk|ru", "uk → ru")
    check("болг книга", "къща", "bg", "bg|ru", "bg → ru")
    // 3. Латиница, язык книги латинский: язык книги → ru.
    check("англ книга", "house", "en", "en|ru", "en → ru")
    check("нем книга", "Haus", "de", "de|ru", "de → ru")
    check("фр книга, fr_FR", "maison", "fr_FR", "fr|ru", "fr → ru")
    check("англ, en-GB в верхнем регистре", "house", "EN-gb", "en|ru", "en → ru")
    // 4. Латиница без языка книги, русская книга или нелатинский язык: автоопределение.
    check("лат, язык неизвестен", "house", null, "Autodetect|ru", "авто → ru")
    check("лат в русской книге", "weekend", "ru", "Autodetect|ru", "авто → ru")
    check("лат в украинской книге", "weekend", "uk", "Autodetect|ru", "авто → ru")
    check("лат в японской книге", "house", "ja", "Autodetect|ru", "авто → ru")
    check("лат, мусор вместо языка", "house", "??", "Autodetect|ru", "авто → ru")
    // 4а. Трёхбуквенные коды EPUB (`dc:language`: rus, eng, deu).
    check("англ книга, eng", "house", "eng", "en|ru", "en → ru")
    check("нем книга, deu", "Haus", "deu", "de|ru", "de → ru")
    check("рус книга, rus, лат слово", "weekend", "rus", "Autodetect|ru", "авто → ru")
    check("укр книга, ukr", "дім", "ukr", "uk|ru", "uk → ru")

    // 5. Слово со смесью алфавитов: кириллица побеждает.
    check("смесь, книга en", "дом2x", "en", "ru|en", "ru → en")
    // 6. Разбор кода языка.
    lang("lang ru-RU", "ru-RU", "ru")
    lang("lang en_US", " en_US ", "en")
    lang("lang RU", "RU", "ru")
    lang("lang eng → en", "eng", "en")
    lang("lang RUS → ru", "RUS", "ru")
    lang("lang deu/ger → de", "ger", "de")
    lang("lang неизвестный xyz", "xyz", "xyz")
    lang("lang пусто", "", null)
    lang("lang null", null, null)
    lang("lang цифры", "x1", null)
    lang("lang длинное", "english", null)

    if (fails == 0) println("=== ВСЁ ОК") else { println("=== ОШИБОК: $fails"); System.exit(1) }
}
