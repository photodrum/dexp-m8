import ru.ogrpfsense.dexpm8.reader.DjvuAuto

/**
 * Хостовая проверка «Авто» обрезки (R2.5): `reader/DjvuAuto.kt` без Android.
 *
 *   export JAVA_HOME=$HOME/.local/jdk PATH=$HOME/.local/jdk/bin:$PATH
 *   kotlinc app/src/main/java/ru/ogrpfsense/dexpm8/reader/DjvuAuto.kt \
 *           app/src/main/cpp/test/AutoTest.kt -include-runtime -d /tmp/auto.jar
 *   java -jar /tmp/auto.jar
 *
 * Проверяет то, ради чего режим и делался: поля скана режутся, содержимое не отрезается,
 * пылинка строку содержимым не делает, залитый лист и пустая страница не режутся вовсе.
 */
private var fails = 0
private var checks = 0

private fun chk(ok: Boolean, what: String, got: Any? = null) {
    checks++
    if (!ok) {
        fails++
        println("FAIL $what -> $got")
    }
}

/** Кадр W×H, где всё поле 0 (бумага), а рамка закрашена 255 (штрих). */
private fun page(x0: Int, y0: Int, x1: Int, y1: Int, w: Int = 600, h: Int = 820): ByteArray {
    val g = ByteArray(w * h)
    for (y in y0 until y1) {
        val base = y * w
        for (x in x0 until x1) g[base + x] = 255.toByte()
    }
    return g
}

private fun pm(v: Int, total: Int) = (v * 1000 + total / 2) / total

fun main() {
    val w = 600
    val h = 820

    // 1. Пустая страница (бумага) — резать нечего.
    chk(DjvuAuto.measure(ByteArray(w * h), w, h) == null, "пустая страница")

    // 2. Поля 100/200 слева-сверху и 100/220 справа-снизу — режем ровно их с отступом 20 ‰.
    val g = page(100, 200, 500, 600)
    val r = DjvuAuto.measure(g, w, h)
    chk(r != null, "поля найдены", r)
    if (r != null) {
        val exp = intArrayOf(
            Math.max(0, pm(100, w) - DjvuAuto.PAD),
            Math.max(0, pm(200, h) - DjvuAuto.PAD),
            Math.max(0, 1000 - pm(500, w) - DjvuAuto.PAD),
            Math.max(0, 1000 - pm(600, h) - DjvuAuto.PAD),
        )
        chk(r.contentEquals(exp), "точные промилле", r.joinToString(",") + " / " + exp.joinToString(","))
        // Содержимое обязано остаться внутри: 1000 − обрезка покрывает рамку.
        chk(r[0] <= pm(100, w) && r[1] <= pm(200, h), "текст не отрезан слева/сверху", r.joinToString(","))
        chk(1000 - r[2] >= pm(500, w) && 1000 - r[3] >= pm(600, h), "текст не отрезан справа/снизу", r.joinToString(","))
    }

    // 3. Залитый лист — обрезки нет (иначе срежем край кадра).
    chk(DjvuAuto.measure(page(0, 0, w, h), w, h) == null, "залитый лист")

    // 4. Одна пылинка — не содержимое.
    val dust = ByteArray(w * h)
    dust[5 * w + 5] = 255.toByte()
    chk(DjvuAuto.measure(dust, w, h) == null, "пылинка")

    // 5. Почти во весь лист (поля 2 %) — отступа хватает, режем немного.
    val full2 = page(12, 16, 588, 804)
    val r5 = DjvuAuto.measure(full2, w, h)
    chk(r5 == null || (r5[0] < 40 && r5[1] < 40), "поля 2 % не режем сильно", r5?.joinToString(","))

    // 6. Узкая колонка по центру (20 % ширины) — столько не режем: остаток < KEEP_MIN.
    chk(DjvuAuto.measure(page(240, 100, 360, 700), w, h) == null, "узкая колонка не режется")

    // 7. Текст во всю ширину, поля сверху-снизу — режем только вертикаль.
    val rows = page(0, 100, w, 700)
    val r7 = DjvuAuto.measure(rows, w, h)
    chk(r7 != null && r7[0] == 0 && r7[2] == 0 && r7[1] > 100, "только вертикаль", r7?.joinToString(","))

    // 8. Битые размеры не роняют.
    chk(DjvuAuto.measure(ByteArray(10), 0, 0) == null, "нулевые размеры")
    chk(DjvuAuto.measure(ByteArray(10), 600, 820) == null, "короткий буфер")

    println("проверок $checks, провалов $fails")
    if (fails > 0) System.exit(1)
}