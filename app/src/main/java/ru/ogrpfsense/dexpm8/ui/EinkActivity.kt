package ru.ogrpfsense.dexpm8.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import ru.ogrpfsense.dexpm8.core.LibraryDb
import ru.ogrpfsense.dexpm8.core.Prefs
import ru.ogrpfsense.dexpm8.core.Sys

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
        const val SCR_BOOK = 6

        // Разделы B13 — экраны B37–B43.
        const val SCR_SET_SCREEN = 10
        const val SCR_SET_NET = 11
        const val SCR_SET_READ = 12
        const val SCR_SET_KEYS = 13
        const val SCR_SET_SLEEP = 14
        const val SCR_SET_LANG = 15
        const val SCR_SET_STORAGE = 16
        const val SCR_SET_ABOUT = 17

        private const val REQ_STORAGE = 1
        private const val REQ_WRITE_SETTINGS = 2
        private const val REQ_ADMIN = 3

        /**
         * Сколько держать белый кадр после системного диалога. Полное обновление Carta —
         * ~0,45 с; если снять кадр раньше, контроллер может склеить его со следующим.
         * Подбирается на книге (шаг 3.10).
         */
        private const val CLEAN_MS = 300L

        /** Одна группа STORAGE: система показывает один диалог на оба. */
        private val STORAGE_PERMS = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        )

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

    /** База библиотеки — одна на приложение; экраны берут её отсюда. */
    val db: LibraryDb by lazy { LibraryDb(this) }

    /** Книга для экрана B12 «О книге» (`_id`); ставится перед `go(SCR_BOOK)`. */
    var bookId = -1L

    /** Системные настройки (B37–B42, B5) и свои настройки оболочки. */
    val sys: Sys by lazy { Sys(this) }
    val prefs: Prefs by lazy { Prefs(this) }

    // Особые права через системные экраны (WRITE_SETTINGS, device admin). Итог — в onActivityResult:
    // экран WRITE_SETTINGS ничего не возвращает, поэтому право проверяется заново.
    private var writeWaiter: ((Boolean) -> Unit)? = null
    private var adminWaiter: ((Boolean) -> Unit)? = null

    private lateinit var host: android.widget.FrameLayout
    private val stack = ArrayList<Int>(8)
    private val screens = HashMap<Int, Screen>(8)
    private val ui = Handler(Looper.getMainLooper())

    // Разрешение на чтение памяти. Всё — только в UI-потоке.
    private var storageOk = false
    private var storageAsking = false
    private val storageWaiters = ArrayList<(Boolean) -> Unit>(2)
    private val storageListeners = ArrayList<(Boolean) -> Unit>(2)

    // Очистка E-Ink после системного диалога (см. [cleanRefresh]).
    private var cleanOnFocus = false
    private var cleanedWhileAsking = false
    private var blank: View? = null
    private val unblank = Runnable { endClean() }

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
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(T.BG))

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
        host.setBackgroundColor(T.BG)
        setContentView(host)
        Trace.step("setContentView ok")

        if (startupError != null) {
            host.addView(CrashScreen(this, startupError))
            return
        }

        try {
            stack.add(SCR_HOME)
            changeTo(SCR_HOME)
            Trace.step("HomeScreen attached")
        } catch (t: Throwable) {
            Trace.step("changeTo(SCR_HOME) FAIL: ${t.javaClass.simpleName}: ${t.message}")
            showCrashSafely(t)
            return
        }

        // Спрашиваем один раз при холодном старте, а не при каждом пересоздании.
        // После «Больше не спрашивать» система отвечает отказом сразу, без диалога.
        storageOk = hasStorage()
        Trace.step("storage: ${if (storageOk) "granted" else "missing"}")
        if (!storageOk && savedInstanceState == null) requestStorage()
    }

    // ------------------------------------------------------------ память

    /** Есть ли право читать внешнее хранилище (книги). */
    fun hasStorage(): Boolean =
        checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Запросить право на чтение памяти. [done] вызывается в UI-потоке с итогом;
     * если право уже есть — сразу. Повторный вызов, пока диалог открыт, только
     * добавляет [done] в очередь: второй `requestPermissions` система бы отменила.
     */
    fun requestStorage(done: ((Boolean) -> Unit)? = null) {
        if (hasStorage()) {
            if (!storageOk) storageChanged(true)
            done?.invoke(true)
            return
        }
        if (done != null) storageWaiters.add(done)
        if (storageAsking) return
        storageAsking = true
        Trace.step("storage: request")
        requestPermissions(STORAGE_PERMS, REQ_STORAGE)
    }

    /**
     * Подписка на смену права (выдали в диалоге или в «Настройках», пока оболочка
     * была в фоне). Экран подписывается при показе и отписывается при уходе.
     */
    fun addStorageListener(l: (Boolean) -> Unit) {
        if (l !in storageListeners) storageListeners.add(l)
    }

    fun removeStorageListener(l: (Boolean) -> Unit) {
        storageListeners.remove(l)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_STORAGE) return
        storageAsking = false
        // grantResults пуст, если диалог прервали; верим фактическому состоянию.
        val ok = hasStorage()
        Trace.step("storage: ${if (ok) "granted" else "denied"}")
        // Ответ приходит после закрытия диалога. На холодном старте окно до диалога фокуса
        // не получало, onWindowFocusChanged(false) не было и cleanOnFocus не ставился —
        // поэтому очистку заказываем и здесь (замечание 1). Если фокус уже вернулся и
        // очистка по нему прошла (cleanedWhileAsking) — второй белый кадр не нужен.
        when {
            cleanedWhileAsking -> cleanedWhileAsking = false
            cleanOnFocus -> {}
            hasWindowFocus() -> cleanRefresh()
            else -> cleanOnFocus = true
        }
        if (ok != storageOk) storageChanged(ok)
        if (storageWaiters.isEmpty()) return
        val waiters = ArrayList(storageWaiters)
        storageWaiters.clear()
        for (w in waiters) w(ok)
    }

    // ------------------------------------------------------------ особые права (шаг 4.6)

    /**
     * Выполнить [action], когда есть WRITE_SETTINGS. Права нет — открывается системный экран
     * «Изменение системных настроек» для оболочки; [action] выполнится после возврата, если
     * право выдали. Действие с неудачей не повторяется: пользователь нажмёт ещё раз.
     */
    fun withWriteSettings(action: () -> Unit) {
        if (sys.canWrite()) {
            action()
            return
        }
        writeWaiter = { ok -> if (ok) action() }
        Trace.step("write_settings: request")
        try {
            startActivityForResult(android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
                android.net.Uri.parse("package:$packageName")), REQ_WRITE_SETTINGS)
            cleanOnFocus = true
        } catch (t: Throwable) {
            Trace.step("write_settings: no screen ${t.javaClass.simpleName}")
            writeWaiter = null
        }
    }

    /**
     * Включить администратора устройства (нужен только для сна по кнопке, B5). [done] получает итог.
     */
    fun requestAdmin(done: (Boolean) -> Unit) {
        if (sys.adminActive()) {
            done(true)
            return
        }
        adminWaiter = done
        Trace.step("admin: request")
        try {
            val i = android.content.Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, sys.adminComponent)
                .putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Нужно только для кнопки «Сон»: оболочка сможет выключать экран.")
            startActivityForResult(i, REQ_ADMIN)
            cleanOnFocus = true
        } catch (t: Throwable) {
            Trace.step("admin: no screen ${t.javaClass.simpleName}")
            adminWaiter = null
            done(false)
        }
    }

    /** Сон сейчас: админ есть — экран гаснет; нет — сначала системный запрос. */
    fun sleepNow() {
        if (sys.lockNow()) return
        requestAdmin { ok -> if (ok) sys.lockNow() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_WRITE_SETTINGS -> {
                // Экран ничего не возвращает: проверяем само право.
                val w = writeWaiter
                writeWaiter = null
                val ok = sys.canWrite()
                Trace.step("write_settings: ${if (ok) "granted" else "denied"}")
                w?.invoke(ok)
            }
            REQ_ADMIN -> {
                val w = adminWaiter
                adminWaiter = null
                val ok = sys.adminActive()
                Trace.step("admin: ${if (ok) "granted" else "denied"}")
                w?.invoke(ok)
            }
        }
    }

    private fun storageChanged(ok: Boolean) {
        storageOk = ok
        for (l in ArrayList(storageListeners)) l(ok)
    }

    /** Возвращает true, если стек удалось показать на экране. */
    private fun showCrashSafely(error: Throwable): Boolean {
        return try {
            if (!this::host.isInitialized) {
                host = android.widget.FrameLayout(this)
                host.setBackgroundColor(T.BG)
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
        // Право могли выдать в «Настройках», пока оболочка была в фоне.
        // Отзыв там же система делает убийством процесса, так что сюда придёт только выдача.
        // Пока открыт системный диалог — ответ придёт в onRequestPermissionsResult.
        if (this::host.isInitialized && !storageAsking) {
            val ok = hasStorage()
            if (ok != storageOk) {
                Trace.step("storage: changed outside -> $ok")
                storageChanged(ok)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) {
            // Фокус ушёл во время запроса права — значит, поверх нас системный диалог.
            if (storageAsking) cleanOnFocus = true
            return
        }
        applyImmersive()
        if (cleanOnFocus) {
            cleanOnFocus = false
            if (storageAsking) cleanedWhileAsking = true
            cleanRefresh()
        }
    }

    /**
     * Полная очистка панели после системного диалога права. Окно под диалогом не
     * перерисовывается: система возвращает старый кадр, а контроллер E-Ink быстро
     * обновляет только место диалога — его текст («ОТКЛОНИТЬ») остаётся следом.
     * Обычный invalidate() даёт тот же кадр и следа не снимает. Поэтому один белый
     * кадр G15 на весь экран, через [CLEAN_MS] — снова экран: два мигания, только здесь.
     */
    private fun cleanRefresh() {
        if (!this::host.isInitialized) return
        val v = blank ?: View(this).apply {
            setBackgroundColor(T.G15)
            isClickable = true          // касания в белом кадре никуда не уходят
        }.also { blank = it }
        ui.removeCallbacks(unblank)
        if (v.parent == null) {
            host.addView(v, android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }
        Trace.step("eink: clean after dialog")
        ui.postDelayed(unblank, CLEAN_MS)
    }

    private fun endClean() {
        blank?.let { if (it.parent != null) host.removeView(it) }
        // Экран рисуется заново с текущими данными (право могли выдать, пока висел кадр).
        for (i in 0 until host.childCount) host.getChildAt(i).invalidate()
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
            SCR_SETTINGS -> SettingsScreen(this)
            SCR_BOOK -> BookScreen(this)
            SCR_SET_SCREEN -> SetScreenScreen(this)
            SCR_SET_NET -> SetNetScreen(this)
            SCR_SET_READ, SCR_SET_KEYS -> SetReadScreen(this)
            SCR_SET_SLEEP -> SetSleepScreen(this)
            SCR_SET_LANG -> SetLangScreen(this)
            SCR_SET_STORAGE -> SetStorageScreen(this)
            SCR_SET_ABOUT -> SetAboutScreen(this)
            SCR_READER -> SimpleScreen(this, "Чтение", "ядро на C — следующий этап", "library")
            else -> HomeScreen(this)
        }
        screens[screenId] = s
        return s
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        storageWaiters.clear()
        storageListeners.clear()
        writeWaiter = null
        adminWaiter = null
        screens.clear()
        if (lastInstance === this) lastInstance = null
        super.onDestroy()
    }
}
