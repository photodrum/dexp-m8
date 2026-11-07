package ru.ogrpfsense.dexpm8.ui

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager

/**
 * Единственная Activity оболочки. Экраны — это View в стеке, переходы —
 * подмена View и полная перерисовка, без анимаций и без затемнений.
 */
class EinkActivity : Activity() {

    companion object {
        const val SCR_HOME = 1
        const val SCR_LIBRARY = 2
        const val SCR_APPS = 3
        const val SCR_SETTINGS = 4
        const val SCR_READER = 5

        /**
         * Последняя созданная Activity. Нужна обработчику необработанных исключений:
         * он может сработать в момент, когда Activity уже не находится в нормальном
         * состоянии, и тогда единственный способ показать стек — через этот статический
         * доступ. Ссылка обнуляется в onDestroy, чтобы не держать утечку.
         */
        @Volatile
        private var lastInstance: EinkActivity? = null
    }

    lateinit var icons: Icons
        private set

    private lateinit var host: android.widget.FrameLayout
    private val stack = ArrayList<Int>(8)
    private val screens = HashMap<Int, Screen>(8)
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Обработчик ставим ПЕРВЫМ делом и сохраняем ссылку на объект Activity,
        // чтобы показать стек даже если падение случилось внутри самого onCreate.
        lastInstance = this
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val shown = lastInstance?.showCrashSafely(error) ?: false
            if (!shown) prev?.uncaughtException(thread, error)
        }

        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(T.G13))

        Trace.init(this)
        Trace.step("onCreate start")

        try {
            D.init(this)
            Trace.step("D.init ok u=${D.u} w=${D.screenW}x${D.screenH}")
        } catch (t: Throwable) {
            Trace.step("D.init FAIL: ${t.javaClass.simpleName}: ${t.message}")
            throw t
        }

        // Каждый шаг в своём try: если что-то упадёт, мы всё равно дойдём
        // до отрисовки аварийного экрана.
        var startupError: Throwable? = null
        try {
            icons = Icons()
            Trace.step("Icons ok")
        } catch (t: Throwable) {
            Trace.step("Icons FAIL: ${t.javaClass.simpleName}: ${t.message}")
            startupError = t
        }

        host = android.widget.FrameLayout(this)
        host.setBackgroundColor(T.G13)
        setContentView(host)
        Trace.step("setContentView ok")

        if (startupError != null) {
            host.addView(CrashScreen(this, startupError))
            return
        }

        try {
            stack.add(SCR_HOME)
            show(SCR_HOME)
            Trace.step("HomeScreen attached")
        } catch (t: Throwable) {
            Trace.step("show(SCR_HOME) FAIL: ${t.javaClass.simpleName}: ${t.message}")
            showCrashSafely(t)
        }
    }

    /** Возвращает true, если стек удалось показать на экране. */
    private fun showCrashSafely(error: Throwable): Boolean {
        return try {
            if (!this::host.isInitialized) {
                host = android.widget.FrameLayout(this)
                host.setBackgroundColor(T.G13)
                setContentView(host)
            }
            host.removeAllViews()
            host.addView(CrashScreen(this, error), android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            ))
            host.requestLayout()
            host.invalidate()
            true
        } catch (ignored: Throwable) {
            false
        }
    }

    override fun onResume() {
        super.onResume()
        applyImmersive()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    /** Системные бары не рисуем и не показываем — при их перерисовке E-Ink мигает. */
    private fun applyImmersive() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    override fun onBackPressed() {
        if (stack.size > 1) {
            pop()
        } else {
            // Первый экран — некуда назад, значит выход. Не держим процесс зря.
            moveTaskToBack(true)
        }
    }

    // ------------------------------------------------------------ навигация

    fun go(screenId: Int) {
        stack.add(screenId)
        changeTo(screenId)
    }

    fun pop() {
        if (stack.size <= 1) return
        stack.removeAt(stack.size - 1)
        changeTo(stack[stack.size - 1])
    }

    fun popTo(screenId: Int) {
        while (stack.size > 1 && stack[stack.size - 1] != screenId) {
            stack.removeAt(stack.size - 1)
        }
        changeTo(stack[stack.size - 1])
    }

    private fun show(screenId: Int) {
        host.removeAllViews()
        host.addView(obtain(screenId))
    }

    private fun changeTo(screenId: Int) {
        host.removeAllViews()
        host.addView(obtain(screenId))
    }

    private fun obtain(screenId: Int): Screen {
        val cached = screens[screenId]
        if (cached != null) return cached
        val s: Screen = when (screenId) {
            SCR_HOME -> HomeScreen(this)
            SCR_LIBRARY -> LibraryScreen(this)
            SCR_APPS -> SimpleScreen(this, "Приложения", "9 приложений", "apps")
            SCR_SETTINGS -> SimpleScreen(this, "Настройки", "устройство", "settings")
            SCR_READER -> SimpleScreen(this, "Чтение", "ядро на C — следующий этап", "library")
            else -> HomeScreen(this)
        }
        screens[screenId] = s
        return s
    }

    /** Экран отправляет себя на перерисовку. */
    fun repaint(screenId: Int) {
        screens[screenId]?.let { s ->
            if (s.parent != null) s.redraw()
        }
    }

    /** Отложенная перерисовка — для обновлений времени/батареи без мигания на каждый тик. */
    fun schedule(delayMs: Long, what: Runnable) = ui.postDelayed(what, delayMs)

    /**
     * Показ стека на экране (см. [showCrashSafely]). Оставлено как runOnUiThread-обёртка
     * для случаев, когда обработчик вызывается не в UI-потоке.
     */
    private fun showCrash(error: Throwable) {
        runOnUiThread { showCrashSafely(error) }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        screens.clear()
        if (lastInstance === this) lastInstance = null
        super.onDestroy()
    }
}
