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

    /**
     * Долгое нажатие уже отдано экрану ([onLongPress]): `ACTION_UP` после него не доходит ни до
     * [onUp], ни до шторки B5 — иначе одно удержание дало бы ещё и обычное нажатие (меню B9,
     * переворот страницы, открытие шторки).
     */
    private var longFired = false

    /**
     * Отложенный вызов [onLongPress] на время удержания. Снимается при уезде пальца
     * (`MOVE_SLOP`), отпускании и отмене касания — короткое нажатие долгим не считается.
     */
    private val longPress = Runnable {
        // Экран успели отвязать или палец уже уехал/отпущен — жест не наш.
        if (!pressed || !isAttachedToWindow) return@Runnable
        // Шторка B5 открывается отпусканием в своей зоне: удержание там ничего не выделяет.
        if (inShadeZone()) return@Runnable
        if (onLongPress(downX, downY)) longFired = true
    }

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
     * Касание. На E-Ink палец неизбежно «уезжает», поэтому pinch/scroll не распознаём:
     * только нажатие → отпускание в той же зоне. Жестов два, и оба объявляет сам экран:
     * свайп ([onSwipe]) — палец уехал, удержание ([onLongPress]) — палец стоит на месте
     * дольше [LONG_MS].
     */
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x / D.u
        val y = e.y / D.u
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; pressed = true; longFired = false
                removeCallbacks(longPress)
                postDelayed(longPress, LONG_MS)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (pressed && (Math.abs(x - downX) > MOVE_SLOP || Math.abs(y - downY) > MOVE_SLOP)) {
                    pressed = false
                    removeCallbacks(longPress)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                // Удержание уже отдано экрану: отпускание — только конец жеста.
                if (longFired) {
                    longFired = false
                    pressed = false
                    return true
                }
                val ok = pressed
                pressed = false
                if (!ok) {
                    // Палец уехал — это не нажатие, и [onUp] уже не будет. Экран получает
                    // жест целиком и сам решает, свайп ли это (у остальных экранов — нет).
                    onSwipe(downX, downY, x, y)
                    return true
                }
                // Шторка B5 — раньше элементов экрана: в зоне шторки у экранов ничего не нажимается.
                // Экран может уступить свою зону, если на этом месте лежит его элемент (замечание 20).
                if (inShadeZone() && shadeAllowed(downX, downY)) {
                    act.openShade()
                    return true
                }
                onUp(downX, downY)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = false
                longFired = false
                removeCallbacks(longPress)
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    /** Точка нажатия — в зоне открытия шторки B5 (там у экранов ничего не нажимается). */
    private fun inShadeZone() =
        downY < shadeZone && downX >= ShadeScreen.ZONE_X0 && downX <= ShadeScreen.ZONE_X1

    /**
     * Разрешает шторке B5 открыться в своей зоне (x 468…936, y < [shadeZone]). По умолчанию — да:
     * в зоне шторки у экранов ничего не нажимается, и отпускание открывает шторку.
     *
     * `false` — экран уступает эту точку себе: касание доходит до [onUp], как у любой другой точки
     * вне зоны. Так делает читалка, когда меню B50 встало в верхнюю треть страницы (замечание 20,
     * 2026-11-17). Удержание зону шторки уважает по-прежнему — см. [longPress].
     */
    protected open fun shadeAllowed(x: Float, y: Float): Boolean = true

    /** Обработка завершённого нажатия в дизайн-координатах. */
    protected open fun onUp(x: Float, y: Float) {}

    /**
     * Удержание: палец стоит на месте дольше [LONG_MS] (дизайн-px; [onUp] после такого жеста
     * не придёт). По умолчанию жеста нет — правило 3 `docs/ARCHITECTURE.md`. Сейчас объявляет
     * только читалка (`ReaderScreen.onLongPress`, B50 «выделение слова»); шторка B5 и уезд
     * пальца удержанием не считаются.
     *
     * true — жест обработан, обычное нажатие по этой же точке не случится.
     */
    protected open fun onLongPress(x: Float, y: Float): Boolean = false


    /**
     * Жест: палец уехал от точки нажатия и [onUp] не придёт. По умолчанию жестов нет —
     * правило 3 `docs/ARCHITECTURE.md`: скролл и pinch не распознаются. Свайп листания
     * объявляет только читалка (`ReaderScreen.onSwipe`, настройка B39 «Листать свайпом»).
     * true — жест обработан. Координаты — дизайн-px.
     */
    protected open fun onSwipe(x0: Float, y0: Float, x1: Float, y1: Float): Boolean = false

    /**
     * Кнопка «Назад» системы. true — экран сам её обработал (закрыл свой лист или диалог),
     * и уходить со экрана не нужно. По умолчанию не перехватывает: `EinkActivity` снимает экран.
     * Порядок: сначала экран (лист поверх него), потом шторка B5, потом стек экранов.
     */
    fun onBack(): Boolean = backPressed()

    protected open fun backPressed(): Boolean = false

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
         * Касание «нажал и отпустил»: `ACTION_MOVE` дальше этого отменяет нажатие (дизайн-px,
         * ARCHITECTURE, правило 3). Отменённое касание отдаётся в [onSwipe].
         */
        const val MOVE_SLOP = 40f

        /**
         * Порог удержания, мс ([onLongPress]). На E-Ink палец держат дольше, чем на телефоне
         * (600 вместо привычных 400–500): листание третями — это короткое нажатие, и слишком
         * ранний порог превращал бы его в выделение.
         */
        const val LONG_MS = 600L

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
