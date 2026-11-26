package ru.ogrpfsense.dexpm8.chess

// Уровни игры компьютера (п. 25.1в) для экрана 25.2: глубина, предел времени и «неточность».
// Числа предварительные: подобраны на хосте, настраиваются по замеру на PX30 (25.1г).
// Предел времени есть у каждого уровня, чтобы на медленной книге ход не затягивался.

object Level {
    const val COUNT = 8

    private val TABLE = arrayOf(
        Limits(depth = 1, timeMs = 1000, noise = 400),
        Limits(depth = 2, timeMs = 1000, noise = 250),
        Limits(depth = 2, timeMs = 2000, noise = 120),
        Limits(depth = 3, timeMs = 2000, noise = 60),
        Limits(depth = 4, timeMs = 3000, noise = 25),
        Limits(depth = 5, timeMs = 4000),
        Limits(timeMs = 6000),
        Limits(timeMs = 10000),
    )

    /** Пределы уровня [n] = 1…[COUNT] (вне границ — ближайший уровень). */
    fun of(n: Int): Limits = TABLE[n.coerceIn(1, COUNT) - 1]
}
