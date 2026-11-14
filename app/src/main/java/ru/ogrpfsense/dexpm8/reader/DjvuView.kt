package ru.ogrpfsense.dexpm8.reader

/**
 * Настройки страницы DjVu — лист B31 «Масштаб и поля» (R2.4/R2.5).
 *
 * Числа и умолчания — из ответа дизайна 2026-11-14 (`docs/DESIGN_QUESTIONS.md`, B31):
 * масштаб по умолчанию «По странице», «Свой» — шаг 10 % в диапазоне 50…300 %,
 * обрезка — «Авто / Вручную / Без обрезки», контраст — тумблер «Усилить контраст скана».
 *
 * Хранится это в базе на книгу (R2.5, колонка `view TEXT`, база v9), поэтому класс —
 * простые числа и строковое представление: формат `zoom:crop.l,crop.t,crop.r,crop.b:panX:panY:contrast`,
 * разбор не падает на мусоре — берёт умолчания (битые настройки не должны мешать читать).
 */
data class DjvuView(
    /** 0 — «По странице», −1 — «По ширине», 50…300 — «Свой» в процентах. */
    val zoom: Int = ZOOM_PAGE,
    /** Промилле исходной страницы: слева, сверху, справа, снизу (1000 = вся сторона). */
    val cropL: Int = 0,
    val cropT: Int = 0,
    val cropR: Int = 0,
    val cropB: Int = 0,
    /** Сдвиг увеличенной страницы в пикселях буфера; 0 — левый/верхний край. */
    val panX: Int = 0,
    val panY: Int = 0,
    /** «Усилить контраст скана». */
    val contrast: Boolean = false,
) {

    /** Обрезка массивом для JNI; null — все поля нулевые (лишней работы не будет). */
    fun crop(): IntArray? =
        if (cropL == 0 && cropT == 0 && cropR == 0 && cropB == 0) null
        else intArrayOf(cropL, cropT, cropR, cropB)

    /** Есть ли что сдвигать: 0 — страница целиком видна. */
    val hasPan: Boolean get() = panX != 0 || panY != 0

    /** Строка для базы; разбор — [parse]. */
    fun encode(): String =
        "$zoom:$cropL,$cropT,$cropR,$cropB:$panX:$panY:${if (contrast) 1 else 0}"

    /** Шаг «Своего» масштаба: 10 % (решение дизайна). */
    fun zoomSelf(percent: Int): DjvuView =
        copy(zoom = percent.coerceIn(ZOOM_MIN, ZOOM_MAX))

    companion object {
        /** 0 «по странице» / −1 «по ширине» / 50…300 % — как в `djvu_layout.h`. */
        const val ZOOM_PAGE = 0
        const val ZOOM_WIDTH = -1
        const val ZOOM_MIN = 50
        const val ZOOM_MAX = 300
        const val ZOOM_STEP = 10

        /** Обрезка по умолчанию — «Авто» (R2.5, последний пункт желобов). */
        val DEFAULT = DjvuView()

        /** Разобрать строку из базы; мусор и пустое — [DEFAULT]. */
        fun parse(s: String?): DjvuView {
            if (s.isNullOrEmpty()) return DEFAULT
            val f = s.split(':')          // zoom : cropL,cropT,cropR,cropB : panX : panY : contrast
            if (f.size < 5) return DEFAULT
            val zoom = f[0].toIntOrNull() ?: ZOOM_PAGE
            val c = f[1].split(',')
            if (c.size != 4) return DEFAULT
            val n = IntArray(4) { c[it].toIntOrNull() ?: 0 }
            val px = f[2].toIntOrNull() ?: 0
            val py = f[3].toIntOrNull() ?: 0
            val ct = f[4].toIntOrNull() ?: 0
            return DjvuView(zoom, n[0], n[1], n[2], n[3], px, py, ct != 0)
        }
    }
}
