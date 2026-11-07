package ru.ogrpfsense.dexpm8.ui

import android.graphics.Typeface
import android.content.res.Resources
import ru.ogrpfsense.dexpm8.R

/**
 * Дизайн-токены, снятые живьём со страницы v5 (файл «UI»).
 *
 * Палитра — 16 градаций E-Ink с шагом 17. Никаких полупрозрачностей:
 * альфа-канал всегда 0xFF, иначе контроллер начнёт дизерить и картинка «зашумит».
 */
object T {

    // --- Градации (совпадают с res/values/colors.xml) ---
    const val G0 = 0xFF000000.toInt()
    const val G1 = 0xFF111111.toInt()   // основной текст
    const val G2 = 0xFF222222.toInt()
    const val G3 = 0xFF333333.toInt()   // тёмная клавиша (главная кнопка)
    const val G4 = 0xFF444444.toInt()   // вторичный текст, контур иконок
    const val G5 = 0xFF555555.toInt()   // подписи
    const val G6 = 0xFF666666.toInt()   // плейсхолдер
    const val G7 = 0xFF777777.toInt()
    const val G8 = 0xFF888888.toInt()   // тень тёмной клавиши
    const val G9 = 0xFF999999.toInt()
    const val G10 = 0xFFAAAAAA.toInt()  // тень / внутренняя тень
    const val G11 = 0xFFBBBBBB.toInt()  // обод углубления, разделители
    const val G12 = 0xFFCCCCCC.toInt()  // дно углубления
    const val G13 = 0xFFDDDDDD.toInt()  // фон экрана
    const val G14 = 0xFFEEEEEE.toInt()  // поверхность «пластика»
    const val G15 = 0xFFFFFFFF.toInt()  // блик

    /**
     * Радиусы скруглений из макета. Значения в дизайн-px,
     * они одинаковы на всех бордах, поэтому это константы, а не вычисления.
     */
    const val R_TILE = 51.1875f      // плитка, панель списка, поле
    const val R_BUTTON = 65.8125f    // главная кнопка
    const val R_COVER_SEAT = 32.90625f
    const val R_COVER_INNER = 30.1640625f
    const val R_COVER = 10.96875f

    /**
     * Смещения «ступенчатых» теней. Не размытие, а сдвинутая копия формы —
     * ровно то, что лежит в макете слоями.
     */
    const val SHADOW_DX = 5f
    const val SHADOW_DY = 10f
    const val SURFACE_DX = 3f        // блик 0,0 → поверхность +3,+3
    const val SURFACE_DY = 3f
    const val INSET_DX = 3f          // дно углубления +3,+5
    const val INSET_DY = 5f

    // --- Типографика: кегли из макета, в дизайн-px ---
    const val TS_CAPTION = 28f       // подписи на обложке
    const val TS_META = 30f          // статистика, счётчики
    const val TS_BODY_S = 32f        // вторичный текст, подзаголовки
    const val TS_BODY = 34f          // дата
    const val TS_LABEL = 36f         // автор
    const val TS_LABEL_L = 38f
    const val TS_ACTION = 42f        // подпись главной кнопки
    const val TS_PAGE = 44f          // номер страницы
    const val TS_PERCENT = 52f
    const val TS_TITLE = 60f         // заголовок экрана/книги
    const val TS_GREETING = 80f
    const val TS_HUGE = 150f         // «45 %»

    /** Кеш шрифтов: Resources.getFont дешевле, чем createFromAsset, и не течёт. */
    private val cache = HashMap<Int, Typeface>(6)

    fun manrope(res: Resources, weight: Int): Typeface = cache.getOrPut(weight) {
        when (weight) {
            800 -> res.getFont(R.font.manrope_800)
            700 -> res.getFont(R.font.manrope_700)
            600 -> res.getFont(R.font.manrope_600)
            else -> res.getFont(R.font.manrope_400)
        }
    }

    fun literata(res: Resources): Typeface = cache.getOrPut(900) { res.getFont(R.font.literata_400) }
}
