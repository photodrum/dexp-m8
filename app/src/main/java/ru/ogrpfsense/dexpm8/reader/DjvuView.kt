package ru.ogrpfsense.dexpm8.reader

/**
 * Настройки страницы DjVu — лист B31 «Масштаб и поля» (R2.4/R2.5).
 *
 * Числа и умолчания — из ответа дизайна 2026-11-14 (`docs/DESIGN_QUESTIONS.md`, B31):
 * масштаб по умолчанию «По странице», «Свой» — шаг 10 % в диапазоне 50…300 %,
 * обрезка — «Авто / Вручную / Без обрезки», контраст — тумблер «Усилить контраст скана».
 *
 * Хранится это в базе на книгу (R2.5, колонка `view TEXT`, база v9), поэтому класс —
 * простые числа и строковое представление:
 * `zoom:cropL,cropT,cropR,cropB:panX:panY:contrast:cropMode:self`,
 * разбор не падает на мусоре — берёт умолчания (битые настройки не должны мешать читать).
 * Поля после `contrast` добавлены в R2.5: строка без них читается как «Авто» и 140 %.
 *
 * [zoom] — то, что уходит в движок: 0 «по странице», −1 «по ширине», 50…300 «Свой».
 * [self] — запомненный процент «Своего»: сегмент «Свой» возвращает его, «−/+» его же
 * и меняет. Так переключение сегментов не теряет своё значение (макет `B31б`).
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
    /** Как обрезать поля: [CROP_AUTO] (по превью), [CROP_MANUAL] (ручками), [CROP_NONE]. */
    val cropMode: Int = CROP_AUTO,
    /** Процент «Своего» масштаба, который вернёт сегмент «Свой · N %». */
    val self: Int = SELF_DEFAULT,
) {

    /** Обрезка массивом для JNI; null — нечего резать (лишней работы не будет). */
    fun crop(): IntArray? =
        if (cropL == 0 && cropT == 0 && cropR == 0 && cropB == 0) null
        else intArrayOf(cropL, cropT, cropR, cropB)

    /**
     * Обрезка, которая действительно уходит в движок с учётом режима. [auto] — промилле,
     * посчитанные по превью страницы ([DjvuAuto]); для [CROP_AUTO] они и берутся.
     * null — «Без обрезки» или авто ещё не посчитана.
     */
    fun cropFor(auto: IntArray?): IntArray? = when (cropMode) {
        CROP_NONE -> null
        CROP_AUTO -> auto
        else -> crop()
    }

    /** Нажата кнопка «Свой»: масштаб [self] (запомненный процент, по умолчанию 140 %). */
    val isSelf: Boolean get() = zoom >= ZOOM_MIN

    /** Активный сегмент желоба масштаба: 0 «По ширине», 1 «По странице», 2 «Свой». */
    fun zoomSegment(): Int = when {
        zoom == ZOOM_WIDTH -> 0
        zoom >= ZOOM_MIN -> 2
        else -> 1
    }

    /** Активный сегмент желоба обрезки: 0 «Авто», 1 «Вручную», 2 «Без обрезки». */
    fun cropSegment(): Int = when (cropMode) {
        CROP_NONE -> 2
        CROP_MANUAL -> 1
        else -> 0
    }

    /** «Свой · 140 %» — подпись третьего сегмента масштаба (макет `B31`). */
    fun selfLabel(): String = "Свой · $self%"

    /** Есть ли что сдвигать: 0 — страница целиком видна. */
    val hasPan: Boolean get() = panX != 0 || panY != 0

    /** Строка для базы; разбор — [parse]. */
    fun encode(): String =
        "$zoom:$cropL,$cropT,$cropR,$cropB:$panX:$panY:${if (contrast) 1 else 0}:$cropMode:$self"

    /** Сменить сдвиг, не трогая остальное (сдвиг третями, R2.5). */
    fun pan(x: Int, y: Int): DjvuView = copy(panX = x, panY = y)

    /** Шаг «Своего» масштаба: 10 % (решение дизайна); запоминается для сегмента «Свой». */
    fun zoomSelf(percent: Int): DjvuView {
        val p = percent.coerceIn(ZOOM_MIN, ZOOM_MAX)
        return copy(zoom = p, self = p, panX = 0, panY = 0)
    }

    /** Смена масштаба/обрезки/контраста сбрасывает сдвиг: страница раскладывается заново. */
    fun mode(zoom: Int = this.zoom, mode: Int = cropMode): DjvuView {
        val p = if (zoom >= ZOOM_MIN) copy(self = zoom.coerceIn(ZOOM_MIN, ZOOM_MAX)) else this
        return p.copy(zoom = zoom, cropMode = mode, panX = 0, panY = 0)
    }

    companion object {
        /** 0 «по странице» / −1 «по ширине» / 50…300 % — как в `djvu_layout.h`. */
        const val ZOOM_PAGE = 0
        const val ZOOM_WIDTH = -1
        const val ZOOM_MIN = 50
        const val ZOOM_MAX = 300
        const val ZOOM_STEP = 10

        /** «Свой» по умолчанию — 140 % (подпись сегмента в макете B31). */
        const val SELF_DEFAULT = 140

        /** Режимы обрезки: порядок совпадает с сегментами желоба B31. */
        const val CROP_AUTO = 0
        const val CROP_MANUAL = 1
        const val CROP_NONE = 2

        /** Обрезка по умолчанию — «Авто» (R2.5, последний пункт желобов). */
        val DEFAULT = DjvuView()

        /** Разобрать строку из базы; мусор и пустое — [DEFAULT]. */
        fun parse(s: String?): DjvuView {
            if (s.isNullOrEmpty()) return DEFAULT
            // zoom : cropL,cropT,cropR,cropB : panX : panY : contrast [: cropMode : self]
            val f = s.split(':')
            if (f.size < 5) return DEFAULT
            val zoom = f[0].toIntOrNull() ?: ZOOM_PAGE
            val c = f[1].split(',')
            if (c.size != 4) return DEFAULT
            val n = IntArray(4) { c[it].toIntOrNull() ?: 0 }
            val px = f[2].toIntOrNull() ?: 0
            val py = f[3].toIntOrNull() ?: 0
            val ct = f[4].toIntOrNull() ?: 0
            val mode = if (f.size > 5) (f[5].toIntOrNull() ?: CROP_AUTO).coerceIn(CROP_AUTO, CROP_NONE) else CROP_AUTO
            val self = (if (f.size > 6) f[6].toIntOrNull() ?: SELF_DEFAULT else SELF_DEFAULT)
                .coerceIn(ZOOM_MIN, ZOOM_MAX)
            return DjvuView(zoom, n[0], n[1], n[2], n[3], px, py, ct != 0, mode, self)
        }
    }
}
