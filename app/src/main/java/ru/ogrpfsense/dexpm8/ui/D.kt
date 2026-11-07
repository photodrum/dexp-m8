package ru.ogrpfsense.dexpm8.ui

import android.content.Context
import android.graphics.Canvas

/**
 * Дизайн-пространство.
 *
 * Панель устройства — ровно 1404×1872 физических пикселей, поэтому
 * (при density 2.0) 1404 px = 702 dp. Числа из Penpot — это не dp и не sp,
 * это пиксели макета. Чтобы не пересчитывать каждый отступ, все View рисуют
 * в «дизайн-px», а Canvas один раз масштабируется на [u].
 *
 * Обычно u == 1.0. Коэффициент нужен только если прошивка рапортует
 * другую ширину (например, 1264 px на соседней ревизии).
 */
object D {

    var screenW = 1404
        private set
    var screenH = 1872
        private set
    private var density = 2f

    /** Коэффициент «дизайн-px → реальные px». */
    var u = 1f
        private set

    fun init(ctx: Context) {
        val dm = ctx.resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        density = dm.density
        u = screenW / 1404f
    }

    /** Дизайн-px → пиксели экрана (для тач-хитбоксов и LayoutParams). */
    fun px(v: Float): Int = Math.round(v * u)
    fun px(v: Int): Int = Math.round(v * u)

    /** Дизайн-px → dp (нужно только для проверки «≥48 dp»). */
    fun dp(v: Float): Float = v * u / density

    /**
     * Переход в дизайн-пространство. Возвращает прежнее состояние Canvas,
     * восстановление обязательно в finally.
     */
    fun enter(canvas: Canvas): Int {
        val saved = canvas.save()
        canvas.scale(u, u)
        return saved
    }

    fun leave(canvas: Canvas, saved: Int) = canvas.restoreToCount(saved)
}
