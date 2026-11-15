import ru.ogrpfsense.dexpm8.reader.DjvuView

/**
 * Хостовая проверка рамки «Вручную» (R2.5б): `reader/DjvuView.kt` без Android.
 *
 *   export JAVA_HOME=$HOME/.local/jdk PATH=$HOME/.local/jdk/bin:$PATH
 *   kotlinc app/src/main/java/ru/ogrpfsense/dexpm8/reader/DjvuView.kt \
 *           app/src/main/cpp/test/PlaceTest.kt -include-runtime -d /tmp/place.jar
 *   java -jar /tmp/place.jar
 *
 * Проверяет то, что видно на книге: касание страницы ставит выбранную ручку в это место,
 * рамка не переворачивается, поля не съедают страницу (предел как в `djvu_layout.h`),
 * «Вручную» из «Авто» начинается с рамки макета `B31а`, а строка в базе переживает разбор.
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

private fun s(v: DjvuView) = "${v.cropL},${v.cropT},${v.cropR},${v.cropB}"

fun main() {
    // 1. Левая верхняя ручка: ставится ровно в точку касания.
    val a = DjvuView.DEFAULT.manualDefault.place(DjvuView.HANDLE_LT, 120, 90)
    chk(a.cropL == 120 && a.cropT == 90, "LT ставит левую и верхнюю", s(a))

    // 2. Правая нижняя: касание в 800/700 — правой стороны остаётся 200, нижней 300.
    val b = DjvuView.DEFAULT.manualDefault.place(DjvuView.HANDLE_RB, 800, 700)
    chk(b.cropR == 200 && b.cropB == 300, "RB ставит правую и нижнюю", s(b))
    chk(b.cropL == 78 && b.cropT == 69, "RB не трогает левую и верхнюю", s(b))

    // 3. Правая верхняя и левая нижняя — по одной стороне с каждой оси.
    val c = DjvuView.DEFAULT.manualDefault.place(DjvuView.HANDLE_RT, 700, 50)
    chk(c.cropR == 300 && c.cropT == 50 && c.cropL == 78 && c.cropB == 77, "RT", s(c))
    val d = DjvuView.DEFAULT.manualDefault.place(DjvuView.HANDLE_LB, 200, 900)
    chk(d.cropL == 200 && d.cropB == 100 && d.cropT == 69 && d.cropR == 78, "LB", s(d))

    // 4. За пределы поверхности: точка зажимается в 0…1000.
    val e = DjvuView.DEFAULT.place(DjvuView.HANDLE_LT, -50, 1200)
    chk(e.cropL == 0 && e.cropT == 900, "точка зажата в 0…1000", s(e))

    // 5. Рамка не переворачивается: левую ручку не утащить за правую сторону (остаток 900).
    val f = DjvuView.DEFAULT.manualDefault.place(DjvuView.HANDLE_LT, 980, 980)
    chk(f.cropL == DjvuView.CROP_PAIR_MAX - 78, "левую держит предел с правой", s(f))
    chk(f.cropT == DjvuView.CROP_PAIR_MAX - 77, "верхнюю держит предел с нижней", s(f))
    chk(1000 - f.cropL - f.cropR >= 100, "страницы остаётся не меньше 10 %", 1000 - f.cropL - f.cropR)

    // 6. Смена рамки сбрасывает сдвиг: старый был бы за пределом новой раскладки.
    val g = DjvuView.DEFAULT.zoomSelf(140).pan(120, 40).manualDefault.place(DjvuView.HANDLE_LT, 60, 60)
    chk(g.panX == 0 && g.panY == 0, "сдвиг сброшен", "${g.panX},${g.panY}")
    chk(g.zoom == 140, "масштаб не тронут", g.zoom)

    // 7. Неизвестная ручка — настройки как были.
    val h = DjvuView.DEFAULT.manualDefault
    chk(h.place(-1, 100, 100) == h, "мимо ручки — без изменений")

    // 8. «Вручную» включает режим и повторяет рамку макета `B31а` (452,170 506×700 из 600×820).
    chk(h.cropMode == DjvuView.CROP_MANUAL, "режим «Вручную»", h.cropMode)
    chk(h.cropL == 78 && h.cropT == 69 && h.cropR == 78 && h.cropB == 77, "рамка макета", s(h))
    chk(h.cropSegment() == 1, "желоб показывает «Вручную»", h.cropSegment())

    // 9. Обрезка уходит в движок только когда есть что резать.
    chk(!DjvuView.DEFAULT.hasCrop && DjvuView.DEFAULT.crop() == null, "без полей — null")
    chk(h.hasCrop && h.crop()!!.contentEquals(intArrayOf(78, 69, 78, 77)), "рамка в массив", h.crop()?.joinToString(","))
    chk(h.cropFor(intArrayOf(10, 10, 10, 10))!![0] == 78, "«Вручную» берёт свои промилле, не авто")
    chk(DjvuView.DEFAULT.manualDefault.copy(cropMode = DjvuView.CROP_NONE).cropFor(null) == null,
        "«Без обрезки» режет пусто")

    // 10. Строка в базе: поля и режим переживают запись и разбор.
    val back = DjvuView.parse(h.encode())
    chk(back == h, "encode → parse", "${h.encode()} → ${back.encode()}")

    // 11. Старая строка (без полей режима и «своего» %) читается как «Авто» + 140 %.
    val old = DjvuView.parse("0:0,0,0,0:0:0:0")
    chk(old.cropMode == DjvuView.CROP_AUTO && old.self == 140, "старая строка", old.encode())

    println("проверок $checks, провалов $fails")
    if (fails > 0) System.exit(1)
}
