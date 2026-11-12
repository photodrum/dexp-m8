package ru.ogrpfsense.dexpm8.ui

/** Слова с числом: «1 книга», «2 книги», «5 книг». */
object Words {

    fun plural(n: Int, one: String, few: String, many: String): String {
        val m100 = n % 100
        val m10 = n % 10
        val w = if (m100 in 11..14) many else when (m10) {
            1 -> one
            2, 3, 4 -> few
            else -> many
        }
        return "$n $w"
    }

    fun books(n: Int) = plural(n, "книга", "книги", "книг")

    fun shelves(n: Int) = plural(n, "полка", "полки", "полок")

    /** «4 файла» — строка папки в B25. */
    fun files(n: Int) = plural(n, "файл", "файла", "файлов")

    /** «2 папки» — подзаголовок B25, когда в папке есть ещё папки. */
    fun folders(n: Int) = plural(n, "папка", "папки", "папок")
}
