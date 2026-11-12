package ru.ogrpfsense.dexpm8.ui

import android.content.Context
import android.view.MotionEvent
import android.view.View

/**
 * Базовый экран. Один View = один экран, без фрагментов и без навигационных
 * библиотек: на 1 ГБ ОЗУ стек экранов — это массив ссылок, а не FragmentManager
 * с восстановлением состояния.
 *
 * Контракт для наследников:
 *  - [layout] — пересчитать геометрию в дизайн-px (вызывается при смене размера);
 *  - [render] — нарисовать в дизайн-px, Canvas уже отмасштабирован;
 *  - [onDown] — вернуть true, если касание «съедено» (тогда [onUp] придёт туда же).
 *
 * Никаких invalidate() по таймеру: экран перерисовывается только при действии
 * пользователя или смене данных — это главный способ не мерцать на E-Ink.
 */
abstract class Screen(protected val act: EinkActivity) : View(act) {

    protected val ink = Ink(act)
    protected val icons = act.icons

    /** Дизайн-ширина и высота текущего экрана. */
    protected var W = 1404f
    protected var H = 1872f

    /**
     * Нижняя граница зоны открытия шторки B5 в центральной трети экрана (x 468…936), дизайн-px.
     * Оболочка — полоса статус-бара (там ничего не нажимается), читалка — верх ≈27% страницы;
     * 0 — экран шторку не открывает. Решение 2026-11-12, ROADMAP «Жесты шторок».
     */
    protected open val shadeZone: Float get() = ShadeScreen.ZONE_SHELL

    /**
     * Экран под открытой шторкой: рисуется темнее (G15 → G10, фон G12 → ≈G8, текст G1 остаётся;
     * замечание 15, вариант Б, 2026-11-12). Не прозрачность: цвета пересчитываются одним фильтром.
     */
    var dimmed = false
        set(v) {
            if (field == v) return
            field = v
            invalidate()
        }

    private var downX = 0f
    private var downY = 0f
    private var pressed = false

    init {
        setBackgroundColor(T.BG)    // совпадает с фоном темы: без «прыжка» при старте
        isClickable = true
        isFocusable = false
    }

    /** Пересчёт геометрии — вызывается один раз на экран и при смене размера. */
    open fun layout(w: Float, h: Float) {
        W = w
        H = h
    }

    /** Отрисовка содержимого в дизайн-px. */
    abstract fun render(c: android.graphics.Canvas)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layout(w / D.u, h / D.u)
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        // Слой с фильтром живёт только на время кадра: отдельного буфера под экран не держим.
        val layer = if (dimmed) canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), DIM) else -1
        val saved = D.enter(canvas)
        try {
            render(canvas)
        } catch (t: Throwable) {
            // Падение при отрисовке раньше уходило в системный диалог без следов.
            // Теперь видно, какой экран и на каком шаге упал.
            Trace.step("onDraw FAIL в ${this.javaClass.simpleName}: " +
                "${t.javaClass.simpleName}: ${t.message}")
            val sw = java.io.StringWriter()
            t.printStackTrace(java.io.PrintWriter(sw))
            Trace.step(sw.toString().take(1500))
            throw t
        } finally {
            D.leave(canvas, saved)
            if (layer >= 0) canvas.restoreToCount(layer)
        }
    }

    /** Кадр экрана без затемнения — для «Снимка» (B5). [c] — в реальных px. */
    fun capture(c: android.graphics.Canvas) {
        val saved = D.enter(c)
        try {
            render(c)
        } finally {
            D.leave(c, saved)
        }
    }

    /**
     * Касание. На E-Ink палец неизбежно «уезжает», поэтому pinch/scroll
     * намеренно не распознаём: только нажатие → отпускание в той же зоне.
     */
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x / D.u
        val y = e.y / D.u
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; pressed = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (pressed && (Math.abs(x - downX) > 40f || Math.abs(y - downY) > 40f)) pressed = false
                return true
            }
            MotionEvent.ACTION_UP -> {
                val ok = pressed
                pressed = false
                if (!ok) return true
                // Шторка B5 — раньше элементов экрана: в зоне шторки у экранов ничего не нажимается.
                if (downY < shadeZone && downX >= ShadeScreen.ZONE_X0 && downX <= ShadeScreen.ZONE_X1) {
                    act.openShade()
                    return true
                }
                onUp(downX, downY)
                return true
            }
            MotionEvent.ACTION_CANCEL -> { pressed = false; return true }
        }
        return super.onTouchEvent(e)
    }

    /** Обработка завершённого нажатия в дизайн-координатах. */
    protected open fun onUp(x: Float, y: Float) {}

    /** Полная перерисовка экрана — единственный «анимационный» приём, который допустим. */
    fun redraw() = invalidate()

    /**
     * Затемнить часть кадра под своим листом (B36 и др.): всё, что нарисовано между [dimBegin] и
     * [dimEnd], проходит через тот же фильтр, что и экран под шторкой. [c] — в дизайн-px.
     */
    protected fun dimBegin(c: android.graphics.Canvas): Int = c.saveLayer(0f, 0f, W, H, DIM)

    protected fun dimEnd(c: android.graphics.Canvas, layer: Int) = c.restoreToCount(layer)

    private companion object {
        /**
         * Затемнение под шторкой и листами (замечание 15, вариант Б): out = a·in + b, a = 153/238,
         * b = 17·(1 − a). G15 (255) → 170 = G10, G12 (204) → 137 ≈ G8, G1 (17) → 17. Альфа не трогается.
         */
        val DIM = android.graphics.Paint().apply {
            val a = 153f / 238f
            val b = 17f * (1f - a)
            colorFilter = android.graphics.ColorMatrixColorFilter(floatArrayOf(
                a, 0f, 0f, 0f, b,
                0f, a, 0f, 0f, b,
                0f, 0f, a, 0f, b,
                0f, 0f, 0f, 1f, 0f,
            ))
        }
    }
}
