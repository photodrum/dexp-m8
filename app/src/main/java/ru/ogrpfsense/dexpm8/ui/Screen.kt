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
                if (ok) onUp(downX, downY)
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
}
