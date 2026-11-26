package ru.ogrpfsense.dexpm8.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import ru.ogrpfsense.dexpm8.core.AudioBooks
import ru.ogrpfsense.dexpm8.core.AudioPlayer
import ru.ogrpfsense.dexpm8.core.Downloads
import ru.ogrpfsense.dexpm8.core.LibraryDb
import ru.ogrpfsense.dexpm8.core.Opds
import ru.ogrpfsense.dexpm8.core.Prefs
import ru.ogrpfsense.dexpm8.core.Speech
import ru.ogrpfsense.dexpm8.core.Sys
import ru.ogrpfsense.dexpm8.core.UpdateCheck

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
        const val SCR_SEARCH = 7
        const val SCR_SHELVES = 8
        const val SCR_SHELF = 9

        // Разделы B13 — экраны B37–B43.
        const val SCR_SET_SCREEN = 10
        const val SCR_SET_NET = 11
        const val SCR_SET_READ = 12
        const val SCR_SET_KEYS = 13
        const val SCR_SET_SLEEP = 14
        const val SCR_SET_LANG = 15
        const val SCR_SET_STORAGE = 16
        const val SCR_SET_ABOUT = 17

        /** B25 «Файлы» — обзор папок памяти; открывается синтетической плиткой в B6. */
        const val SCR_FILES = 18

        /** B26 «Загрузка по Wi-Fi» — свой HTTP-сервер; входы — кнопка на пустой библиотеке B44, клавиши в шапках B25 и B14. */
        const val SCR_WIFI = 19

        /** Сетевые каталоги (п. 10): B14 список, B15 добавить, B16 каталог, B17 книга, B18 загрузки. */
        const val SCR_CATALOGS = 20
        const val SCR_CATALOG_ADD = 21
        const val SCR_CATALOG = 22
        const val SCR_CATALOG_BOOK = 23
        const val SCR_DOWNLOADS = 24

        /** B70 «Шрифт и размер» — настройки чтения по умолчанию; дверь — строка на B39. */
        const val SCR_SET_FONT = 25

        /** B73 «Словари» — источники словаря и перевода; дверь — строка на B41. */
        const val SCR_SET_DICT = 26

        /** B76 «Обновления» — скачивание и установка APK (п. 13); дверь — строка на B43. */
        const val SCR_SET_UPDATES = 27

        /**
         * «Автор» — QR-код на Бусти (решение 2026-11-18); дверь — нижняя кнопка B43.
         * Своего борда в макете нет: собран из деталей B43/B76 (см. `SetAuthorScreen`).
         */
        const val SCR_SET_AUTHOR = 28

        /**
         * Мастер первого запуска (п. 13): B60 шаг 1 язык и время, B61 шаг 2 Wi-Fi,
         * B8 шаг 3 разрешения, B62 шаг 4 чтение, B63 «готово». Открывается вместо B1,
         * пока `Prefs.firstRunDone` = false и библиотека пуста.
         */
        const val SCR_FIRST_LANG = 29
        const val SCR_FIRST_WIFI = 30
        const val SCR_FIRST_PERM = 31
        const val SCR_FIRST_READ = 32
        const val SCR_FIRST_DONE = 33

        /** B75 «Лицензии» — открытые компоненты; дверь — строка на B43. */
        const val SCR_SET_LICENSES = 34

        /** B71 «Часовой пояс» — пояса России; дверь — строка на B41 и на B60 (мастер). */
        const val SCR_SET_ZONE = 35

        /** B72 «Раскладки клавиатуры» — настройки ввода; двери — B41 и B60. */
        const val SCR_SET_LAYOUTS = 36

        /** B74 «Bluetooth-устройства» — сопряжённые и доступные; дверь — строка на B38. */
        const val SCR_SET_BT = 37

        /** Плеер (п. 12): B33 список аудиокниг, B7 плеер, B34 главы. */
        const val SCR_AUDIO_LIST = 38
        const val SCR_AUDIO = 39
        const val SCR_AUDIO_CHAPTERS = 40

        /** B32 «Статистика чтения» (п. 22): двери — строка «Сегодня» на B1 и «Ещё» в меню B9. */
        const val SCR_STATS = 41

        /**
         * Шахматы (п. 25.2): B77 меню (дверь — плитка «Шахматы» в B6), B78 новая партия,
         * B79 партия (с листами B80, B82 и диалогами B83, B84), B81 ходы партии.
         */
        const val SCR_CHESS_MENU = 42
        const val SCR_CHESS_NEW = 43
        const val SCR_CHESS = 44
        const val SCR_CHESS_MOVES = 45


        private const val REQ_STORAGE = 1
        private const val REQ_WRITE_SETTINGS = 2
        private const val REQ_ADMIN = 3
        private const val REQ_INSTALL = 4

        /**
         * Право на местоположение — только ради списка сетей Wi-Fi (замечание 22): на 8.1
         * `getScanResults()` без него возвращает пусто. Одно право, без точного.
         */
        private const val REQ_LOCATION = 5

        /**
         * Сколько держать белый кадр после системного диалога. Полное обновление Carta —
         * ~0,45 с; если снять кадр раньше, контроллер может склеить его со следующим.
         * Подбирается на книге (шаг 3.10).
         */
        private const val CLEAN_MS = 300L

        /**
         * За сколько до системного гашения показывать заставку B19. Кадр рисуется на ещё
         * горящей панели и остаётся на выключенном E-Ink; после гашения перерисовать его
         * нельзя. При коротком сне («5 мин») запас урезается до четверти тайм-аута.
         */
        private const val SLEEP_LEAD_MS = 20_000

        /**
         * Пауза между кадром заставки и `lockNow` по кнопке «Сон» на B5: полный кадр Carta идёт
         * ~0,45 с, и выключение подсветки раньше оставило бы на панели прошлый экран.
         */
        private const val LOCK_MS = 700L

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
    val db: LibraryDb by lazy { LibraryDb(this).also { it.open() } }

    /** Книга для экрана B12 «О книге» (`_id`); ставится перед `go(SCR_BOOK)`. */
    var bookId = -1L

    /**
     * B12 «Оглавление»: книгу открыли из оболочки, а не из читалки, — значит после первой страницы
     * нужно показать панель B10. Ставится на B12 перед `go(SCR_READER)`, снимается там же, где
     * читалка открывает книгу (`ReaderScreen.start`): оглавление отдаёт только движок после
     * открытия документа, на B12 его нет.
     */
    var bookToc = false

    /**
     * B25 «Файлы»: папка, в которой открыть экран; `null` — корень памяти. Ставится перед
     * `go(SCR_FILES)` (B12 «…» → «Открыть папку с файлом»), читается и снимается в
     * `FilesScreen.onAttachedToWindow` — следующий вход в B25 снова начинается с корня.
     */
    var filesDir: String? = null

    /** Полка для экрана B24: `_id` своей или `LibraryDb.SHELF_*`; ставится перед `go(SCR_SHELF)`. */
    var shelfId = 0L

    /** Поиск B21: true — открыть с пустым запросом и клавиатурой; при возврате с «О книге» запрос остаётся. */
    var searchFresh = true

    /** Каталог для B16 (п. 10): `_id` в таблице `catalogs`; -1 — новый (B15 «Добавить каталог»). */
    var catalogId = -1L

    /** Запись каталога для B17 «Книга в каталоге» и адрес фида, из которого она пришла. */
    var opdsEntry: Opds.Entry? = null
    var opdsSource = ""

    /** Очередь загрузок каталогов (B18, счётчик на B14) — одна на приложение. */
    val downloads: Downloads by lazy { Downloads(this, db) }

    /** Плеер (п. 12) — один на приложение: уход с B7 на B33 не останавливает звук. */
    val audio: AudioPlayer by lazy { AudioPlayer(db) }

    /**
     * Чтение вслух системным TTS (п. 19, R6) — один на приложение: `TextToSpeech` после
     * `shutdown()` не поднимается, а `init` асинхронный (1–2 с на книге). Поднимается при
     * первом обращении из читалки; на прошивке с движком и русским голосом
     * (`com.google.android.tts` + `ru-ru-x-dfc`) синтез идёт офлайн.
     */
    val speech: Speech by lazy { Speech(this).also { it.init(); speechUsed = true } }

    /** `Speech` уже создан: в `onDestroy` его нужно закрыть, но создавать там нельзя. */
    private var speechUsed = false

    /** Сканер аудиокниг (п. 12): папки Audio и Books. */
    val audioScan: AudioBooks by lazy { AudioBooks(this, db) }

    /** Аудиокнига для B7/B34 (`_id`); ставится перед `go(SCR_AUDIO)`. */
    var audioId = -1L

    /** Запрос поиска для аудиокниг: непустой — B21 ищет по аудио, а не по книгам. */
    var audioQuery: String? = null

    /** Системные настройки (B37–B42, B5) и свои настройки оболочки. */
    val sys: Sys by lazy { Sys(this) }
    val prefs: Prefs by lazy { Prefs(this) }

    /** Заряд, экономия и порог выключения (B20, B40). */
    val power: ru.ogrpfsense.dexpm8.core.Power by lazy {
        ru.ogrpfsense.dexpm8.core.Power(this, prefs, sys)
    }

    // Особые права через системные экраны (WRITE_SETTINGS, device admin). Итог — в onActivityResult:
    // экран WRITE_SETTINGS ничего не возвращает, поэтому право проверяется заново.
    private var writeWaiter: ((Boolean) -> Unit)? = null
    private var adminWaiter: ((Boolean) -> Unit)? = null
    private var installWaiter: ((Boolean) -> Unit)? = null

    private lateinit var host: android.widget.FrameLayout
    private val stack = ArrayList<Int>(8)
    private val screens = HashMap<Int, Screen>(8)
    private val ui = Handler(Looper.getMainLooper())

    // Разрешение на чтение памяти. Всё — только в UI-потоке.
    private var storageOk = false
    private var storageAsking = false
    private val storageWaiters = ArrayList<(Boolean) -> Unit>(2)
    private val storageListeners = ArrayList<(Boolean) -> Unit>(2)

    // Право на местоположение — ради списка сетей Wi-Fi (замечание 22).
    private var locationAsking = false
    private val locationWaiters = ArrayList<(Boolean) -> Unit>(2)

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
        ru.ogrpfsense.dexpm8.reader.Engine.init(this)

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
        host.viewTreeObserver.addOnPreDrawListener(fastFrame)
        applyRefreshMode()
        // Партия по сети (п. 25.3) переживает пересоздание окна: запрет сна — сразу по состоянию.
        ChessNet.onAwake = chessAwake

        if (startupError != null) {
            host.addView(CrashScreen(this, startupError))
            return
        }

        var firstRun = false
        try {
            // Мастер первого запуска (п. 13) — только при пустой библиотеке: после «Сбросить
            // настройки оболочки» (ключ сбрасывается) или переустановки база уже с книгами.
            firstRun = savedInstanceState == null && !prefs.firstRunDone && booksEmpty()
            stack.add(if (firstRun) SCR_FIRST_LANG else SCR_HOME)
            changeTo(stack[stack.size - 1], switch = false)   // первый кадр окна — и так полный
            Trace.step(if (firstRun) "FirstLangScreen attached" else "HomeScreen attached")
        } catch (t: Throwable) {
            Trace.step("changeTo(start) FAIL: ${t.javaClass.simpleName}: ${t.message}")
            showCrashSafely(t)
            return
        }

        // Спрашиваем один раз при холодном старте, а не при каждом пересоздании.
        // После «Больше не спрашивать» система отвечает отказом сразу, без диалога.
        storageOk = hasStorage()
        Trace.step("storage: ${if (storageOk) "granted" else "missing"}")
        if (!storageOk && savedInstanceState == null) requestStorage()
        if (savedInstanceState == null) probeFromIntent(intent)
        if (savedInstanceState == null) autoUpdate(firstRun)
        // Заставка B19 (п. 13): показывается за 20 с до системного гашения. В мастере первого
        // запуска не нужна — человек ещё настраивает книгу.
        if (!firstRun) planSleep()
        // Заряд (B20): при низком — диалог, у нуля — выключение. Первый кадр к этому времени уже
        // на панели, поэтому диалог рисуется поверх готового экрана.
        batteryWatch()
        if (!firstRun) checkBattery()
    }

    /**
     * Автопроверка обновлений при старте (п. 13, тумблер B76 «Проверять автоматически»).
     *
     * Тихо: ни экрана, ни скачивания — итог ложится в `Prefs`, откуда его читают B43 (строка
     * «Последняя проверка — …») и B76 (при открытии проверит заново). Отсюда — в фоновом
     * потоке: сеть в главном на Android 8.1 даёт `NetworkOnMainThreadException`, а первый
     * кадр должен уйти до любого сетевого ожидания.
     *
     * Не проверяем: пока идёт мастер первого запуска (у человека на экране ещё настройка),
     * при выключенном тумблере, без Wi-Fi и пока не прошла неделя с прошлой проверки.
     */
    private fun autoUpdate(firstRun: Boolean) {
        if (firstRun) {
            Trace.step("update auto: мастер первого запуска, пропуск")
            return
        }
        if (!prefs.updateAuto) {
            Trace.step("update auto: выключено на B76")
            return
        }
        Thread({
            try {
                val u = UpdateCheck(this)
                if (!u.dueWeekly()) {
                    Trace.step("update auto: неделя не прошла, пропуск")
                    return@Thread
                }
                if (!u.onWifi()) {
                    Trace.step("update auto: не Wi-Fi, пропуск")
                    return@Thread
                }
                u.autoCheck()
            } catch (t: Throwable) {
                // Автопроверка не имеет права мешать запуску оболочки.
                Trace.step("update auto FAIL ${t.javaClass.simpleName}: ${t.message}")
            }
        }, "update-auto").start()
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

    // ------------------------------------------------------------ местоположение (Wi-Fi, замечание 22)

    /** Выдано ли право на местоположение (нужно списку сетей Wi-Fi на 8.1). */
    fun hasLocation(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Запросить право на местоположение. [done] — в UI-потоке с фактическим итогом; при уже
     * выданном праве вызывается сразу. Повторный вызов, пока диалог открыт, только добавляет
     * [done] в очередь — как у памяти.
     */
    fun requestLocation(done: ((Boolean) -> Unit)? = null) {
        if (hasLocation()) {
            done?.invoke(true)
            return
        }
        if (done != null) locationWaiters.add(done)
        if (locationAsking) return
        locationAsking = true
        Trace.step("location: request")
        requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
    }

    /**
     * Системный экран «Местоположение»: без включённого определения местоположения список сетей
     * всё равно пуст. Очистку после возврата заказывает экран ([markCleanOnFocus]).
     */
    fun openLocationSettings(): Boolean = try {
        startActivity(android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        cleanOnFocus = true
        true
    } catch (t: Throwable) {
        Trace.step("location: no screen ${t.javaClass.simpleName}")
        false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            locationAsking = false
            // grantResults пуст, если диалог прервали; верим фактическому состоянию.
            val ok = hasLocation()
            Trace.step("location: ${if (ok) "granted" else "denied"}")
            cleanAfterDialog()
            val waiters = ArrayList(locationWaiters)
            locationWaiters.clear()
            for (w in waiters) w(ok)
            return
        }
        if (requestCode != REQ_STORAGE) return
        storageAsking = false
        // grantResults пуст, если диалог прервали; верим фактическому состоянию.
        val ok = hasStorage()
        Trace.step("storage: ${if (ok) "granted" else "denied"}")
        cleanAfterDialog()
        if (ok != storageOk) storageChanged(ok)
        if (storageWaiters.isEmpty()) return
        val waiters = ArrayList(storageWaiters)
        storageWaiters.clear()
        for (w in waiters) w(ok)
    }

    /**
     * Очистка кадра после системного диалога. Ответ приходит после закрытия диалога, а на холодном
     * старте окно до диалога фокуса не получало, `onWindowFocusChanged(false)` не было и
     * `cleanOnFocus` не ставился — поэтому заказываем и здесь (замечание 1). Если фокус уже
     * вернулся и очистка по нему прошла (`cleanedWhileAsking`) — второй белый кадр не нужен.
     */
    private fun cleanAfterDialog() {
        when {
            cleanedWhileAsking -> cleanedWhileAsking = false
            cleanOnFocus -> {}
            hasWindowFocus() -> cleanRefresh()
            else -> cleanOnFocus = true
        }
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

    /**
     * Сон сейчас (кнопка «Сон» на B5): сначала заставка B19, потом гашение. Админа нет — сначала
     * системный запрос, как раньше; без него `lockNow` не сработает.
     */
    fun sleepNow() {
        if (sys.adminActive()) {
            sleepWithCurtain()
            return
        }
        requestAdmin { ok -> if (ok) sleepWithCurtain() }
    }

    /** Заставка, затем гашение: кадр должен успеть выйти на панель до выключения подсветки. */
    private fun sleepWithCurtain() {
        curtain()
        ui.postDelayed({ sys.lockNow() }, LOCK_MS)
    }

    // ------------------------------------------------------------ заставка сна (B19)

    /** Заставка B19 поверх экрана; null — не показывалась ни разу. */
    private var sleep: SleepScreen? = null

    /** План показа заставки: `postDelayed` на ([SLEEP_LEAD_MS] до системного гашения). */
    private val sleepAt = Runnable { curtain() }

    /**
     * Показать заставку B19 сейчас: по тайм-ауту сна ([sleepAt]) или по кнопке «Сон» на B5
     * (`sleepNow`). Кадр рисуется полной перерисовкой — за ним уже ничего не меняется.
     */
    private fun curtain() {
        if (!this::host.isInitialized || sleepShown()) return
        Trace.step("sleep: curtain")
        val s = sleep ?: SleepScreen(this).also { sleep = it }
        host.addView(s, android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        ))
        bigFrame("sleep")
    }

    /** Заставка на экране. */
    private fun sleepShown(): Boolean = sleep?.parent != null

    /**
     * Заново назначить показ заставки по системному тайм-ауту сна (B40 «Засыпать через»).
     * Тайм-аут в системе отсчитывается от последнего касания, поэтому и мы считаем так же —
     * [onUserInteraction] и [wakeUp] перезапускают план.
     *
     * Не планируется, когда сна нет («Никогда»), а также если до гашения меньше половины
     * тайм-аута: при коротком сне заставка показалась бы сразу после касания.
     */
    private fun planSleep() {
        ui.removeCallbacks(sleepAt)
        if (awake.isNotEmpty()) return
        val ms = sys.sleepTimeout()
        if (ms <= 0 || ms == Int.MAX_VALUE) return
        val lead = minOf(SLEEP_LEAD_MS, ms / 4)
        val before = ms - lead
        if (before <= 0) return
        ui.postDelayed(sleepAt, before.toLong())
    }

    /**
     * Касание, клавиша или возврат из фона: если заставка показана, убрать её — прежний экран
     * остаётся под ней как был. Время сна отсчитывается заново.
     */
    fun wakeUp() {
        val s = sleep
        if (s != null && s.parent != null) {
            Trace.step("sleep: wake")
            host.removeView(s)
            // Под заставкой на E-Ink остаётся её след: кадр экрана рисуется заново, полной
            // перерисовкой (частичная оставила бы серые пятна от обложки и названия).
            fullOnSwitch()
        }
        planSleep()
    }

    /** Кто сейчас не даёт книге заснуть (п. 25.3: «chess-net» — поиск и партия по сети). */
    private val awake = HashSet<String>(2)

    /** Запрет сна для [ChessNet]; одна ссылка — чтобы в onDestroy снять только своё. */
    private val chessAwake: (Boolean) -> Unit = { on -> holdAwake("chess-net", on) }

    /**
     * Не засыпать, пока [on] хоть у одного [who]: флаг окна `FLAG_KEEP_SCREEN_ON` (система не
     * гасит экран по `SCREEN_OFF_TIMEOUT`; ни права, ни `WakeLock`, снимается с окном) и без
     * заставки B19 ([planSleep]). Кнопка «Сон» на B5 по-прежнему усыпляет — это решение человека.
     */
    fun holdAwake(who: String, on: Boolean) {
        val was = awake.isNotEmpty()
        if (on) awake.add(who) else awake.remove(who)
        val now = awake.isNotEmpty()
        if (was == now) return
        Trace.step("sleep: holdAwake $who=$on")
        if (now) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!sleepShown()) planSleep()
    }

    /** Поле «Засыпать через» на B40 сменили — план пересчитать. */
    fun rescheduleSleep() {
        if (sleepShown()) return
        planSleep()
    }

    /**
     * Касание или клавиша в любом месте окна: план заставки пересчитывается от текущего момента,
     * как и системный тайм-аут сна (`Settings.System.SCREEN_OFF_TIMEOUT`).
     */
    override fun onUserInteraction() {
        super.onUserInteraction()
        planSleep()
        checkBattery()
    }

    // ------------------------------------------------------------ низкий заряд (B20)

    /** Диалог B20; null — не показывался ни разу. */
    private var low: LowBatteryScreen? = null

    /** Заряд, на котором диалог уже показывали в этот разряд; −1 — не показывали. */
    private var lowShownAt = -1

    /** Заряд решается один раз — до следующей зарядки (иначе выключение повторилось бы). */
    private var lowOffDone = false

    /** Экономия уже включалась сама в этот разряд. */
    private var lowSaverDone = false

    /** Приёмник батареи: нас интересует только смена заряда и начало зарядки. */
    private val batteryWatcher = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == android.content.Intent.ACTION_BATTERY_CHANGED) checkBattery()
        }
    }
    private var batteryWatched = false

    private fun batteryWatch() {
        if (batteryWatched) return
        try {
            registerReceiver(batteryWatcher,
                android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            batteryWatched = true
        } catch (t: Throwable) {
            Trace.step("power: battery watch FAIL ${t.javaClass.simpleName}")
        }
    }

    private fun batteryUnwatch() {
        if (!batteryWatched) return
        batteryWatched = false
        try {
            unregisterReceiver(batteryWatcher)
        } catch (ignored: IllegalArgumentException) {
        }
    }

    /**
     * Что делать с текущим зарядом (B20). Три исхода, по убыванию важности:
     *
     *  - **в зарядке** — экономия снимается, всё прежнее возвращается ([Power.saverRelease]);
     *  - **≤ [Power.OFF_AT]** и зарядка не началась — один раз за разряд: сохранить место чтения,
     *    заставка B19 и меню питания (своего выключения из APK нет — решение п. 8). Экономия при
     *    этом уже включена, отдельный диалог человеку ни к чему;
     *  - **≤ порога экономии** — диалог B20 один раз на заряд: «Включить экономию» или «Позже».
     *    Экономия включена вручную на B40 — включается и без диалога (так обещает подпись B40).
     *
     * Заряд ниже −1 (система не отдала) не трогаем вовсе.
     */
    private fun checkBattery() {
        if (!this::host.isInitialized) return
        val r = power.read()
        if (r.level < 0) return

        if (r.charging) {
            // Зарядка снимает экономию: Wi-Fi и подсветка возвращаются (Power.saverRelease).
            // Диалог убираем, счётчики разряда — с нуля: в разряд пошли заново.
            power.saverRelease()
            lowSaverDone = false
            lowOffDone = false
            lowShownAt = -1
            if (lowShown()) closeLow()
            return
        }

        val at = power.saverAt()
        if (r.level <= ru.ogrpfsense.dexpm8.core.Power.OFF_AT) {
            if (lowOffDone) return
            lowOffDone = true
            Trace.step("power: off at ${r.level}%")
            if (prefs.saver) power.saverApply()
            if (lowShown()) closeLow()
            shutdownNow()
            return
        }

        if (r.level > at) return
        if (prefs.saver) {
            // Экономия сама, без вопросов: подпись B40 обещает именно это.
            if (!lowSaverDone && power.saverApply()) lowSaverDone = true
            return
        }
        // Диалог — один раз на разряд: каждый процент зарядки не повод мигать экраном.
        if (lowShownAt >= 0 || lowShown()) return
        showLow(r.level, at)
    }

    /** Диалог B20 поверх текущего экрана: экран затемняется, диалог — второй View в `host`. */
    private fun showLow(level: Int, at: Int) {
        if (!this::host.isInitialized || lowShown()) return
        lowShownAt = level
        val s = low ?: LowBatteryScreen(this).also { low = it }
        s.open(level, power.hoursText(level), saverOn = prefs.saverApplied)
        current()?.dimmed = true
        host.addView(s, android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        ))
        Trace.step("power: low $level% (saver at $at, applied=${prefs.saverApplied})")
        bigFrame("battery")
    }

    private fun lowShown(): Boolean = low?.parent != null

    private fun closeLow() {
        val s = low ?: return
        if (s.parent == null) return
        host.removeView(s)
        current()?.dimmed = false
        Trace.step("power: low closed")
        bigFrame("battery close")
    }

    /** «Позже» в B20 (и «Назад»): диалог уходит, экран остаётся как был. */
    fun lowLater() {
        closeLow()
    }

    /**
     * «Включить экономию» в B20 (и строка режима): Wi-Fi и подсветка снимаются до зарядки.
     * Тумблер B40 не трогаем: он про автоматику, а здесь человек выбрал режим разово.
     */
    fun lowSaver() {
        val ok = power.saverApply()
        Trace.step("power: saver from B20 -> $ok")
        lowSaverDone = true
        closeLow()
    }

    /**
     * Выключение при [ru.ogrpfsense.dexpm8.core.Power.OFF_AT] %.
     *
     * Device admin для этого не нужен, поэтому и `sleepNow` не зовём: он бы выскочил системным
     * запросом права на пустом месте. Порядок: место чтения в базу — сразу (процесс может не
     * дожить), заставка B19 с обложкой (`curtain`), пауза на кадр и системное меню питания.
     * Меню, а не прямое выключение: `GLOBAL_ACTION_POWER_DIALOG` — единственный путь из
     * приложения, проверенный на прошивке (п. 8). Служба не включена — экран гаснет (`lockNow`
     * при admin) либо остаётся заставка: это лучше, чем ничего.
     */
    private fun shutdownNow() {
        (current() as? ru.ogrpfsense.dexpm8.reader.ReaderScreen)?.saveNow()
        curtain()
        if (!powerDialog()) {
            Trace.step("power: off — нет службы питания")
            sys.lockNow()
        }
    }

    /**
     * Выполнить [action], когда разрешена установка из оболочки («Неизвестные источники», B76).
     * Права нет — открывается системный экран для нашего пакета; [action] выполнится после
     * возврата, если право выдали. Как и с WRITE_SETTINGS, действие с неудачей не повторяется.
     */
    fun withInstallPermission(action: () -> Unit) {
        if (ru.ogrpfsense.dexpm8.core.ApkInstall.canInstall(this)) {
            action()
            return
        }
        installWaiter = { ok -> if (ok) action() }
        Trace.step("install_perm: request")
        try {
            startActivityForResult(ru.ogrpfsense.dexpm8.core.ApkInstall.sourcesIntent(this), REQ_INSTALL)
            cleanOnFocus = true
        } catch (t: Throwable) {
            Trace.step("install_perm: no screen ${t.javaClass.simpleName}")
            installWaiter = null
        }
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
            REQ_INSTALL -> {
                // Экран «Неизвестные источники» тоже ничего не возвращает: проверяем само право.
                val w = installWaiter
                installWaiter = null
                val ok = ru.ogrpfsense.dexpm8.core.ApkInstall.canInstall(this)
                Trace.step("install_perm: ${if (ok) "granted" else "denied"}")
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
        // Экран в кеше переживает выключение экрана: дата и приветствие B1 к утру устарели.
        // Перерисовка внутри — только при смене строки.
        (screens[SCR_HOME] as? HomeScreen)?.refreshTime()
        // Читалка вернулась из фона — отсчёт времени чтения продолжается (п. 22, B32).
        currentReader()?.resumeNow()
        // После чтения строка «Сегодня …» на B1 показывает новые минуты (п. 22).
        (screens[SCR_HOME] as? HomeScreen)?.refreshTodayIfChanged()
        // Заставка B19 снимается: будильник и возврат из фона — одно и то же событие,
        // эхо после подъёма ничего не должно оставлять. План сна — от текущего момента.
        // Раньше заряда: если при пробуждении окажется 3 %, заставку поставит уже B20.
        wakeUp()
        // Заряд мог упасть, пока оболочка была в фоне, а могла включиться зарядка — тогда
        // экономия снимается и прежнее возвращается (B20).
        batteryWatch()
        checkBattery()
    }

    override fun onPause() {
        // Читалка уходит в фон (кнопка «Домой», выключение экрана): сохраняем место чтения
        // сейчас, не дожидаясь закрытия экрана — процесс могут убить в фоне (R1.4).
        // Чтение вслух (R6) в фоне прекращается: голос из свёрнутого приложения на E-Ink
        // выглядит как «звук идёт сам по себе», а остановить его нечем — кнопка в книге.
        currentReader()?.stopSpeechNow()
        currentReader()?.saveNow()
        // Время чтения (п. 22, B32): набежавшие минуты — в базу сейчас, процесс могут убить в фоне.
        currentReader()?.pauseNow()
        // В фоне заставка не нужна и план не работает: система сама погасит экран.
        ui.removeCallbacks(sleepAt)
        // Батарею в фоне сторожит система; нам хватит проверки при возврате.
        batteryUnwatch()
        super.onPause()
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
     * Обычный invalidate() частичным режимом даёт тот же кадр и следа не снимает. Поэтому
     * кадр заново, но полным режимом ([Epd], п. 6.1). Если метода прошивки нет — один белый
     * кадр G15 на весь экран, через [CLEAN_MS] — снова экран: два мигания, только здесь.
     */
    private fun cleanRefresh() {
        if (!this::host.isInitialized) return
        if (Epd.ok) {
            val r = Epd.request(host, Epd.CLEAN, false)
            Trace.step("eink: clean after dialog (epd full) -> $r")
            for (i in 0 until host.childCount) host.getChildAt(i).invalidate()
            return
        }
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

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        probeFromIntent(intent)
    }

    /**
     * Только debug, R0 (шаг 7.2): `adb shell am start -n ru.ogrpfsense.dexpm8/.ui.EinkActivity
     * --es probe /sdcard/Books/x.pdf` открывает файл на отладочном экране движков без базы.
     * `--es screen wifi` — экран B26 «Загрузка по Wi-Fi»: кнопка входа есть только на пустой
     * библиотеке, а на книге база не пуста (шаг 9.7, проверка на железе).
     * `--es screen updates` — B76 «Обновления» (п. 13): за ним два уровня стека.
     */
    private fun probeFromIntent(intent: android.content.Intent?) {
        if (!ru.ogrpfsense.dexpm8.BuildConfig.DEBUG) return
        // R1.1: самопроверка сеанса движка без экрана (`--es reader <путь>`, EngineSelfTest).
        val book = intent?.getStringExtra("reader")
        if (book != null && book.isNotEmpty()) {
            intent.removeExtra("reader")
            Trace.step("debug: selftest $book")
            ru.ogrpfsense.dexpm8.reader.EngineSelfTest.run(book)
            return
        }
        val p = intent?.getStringExtra("probe")
        if (p == null) {
            // R1.3: открыть книгу из библиотеки по `_id` без кликов — `--es read 506`.
            val read = intent?.getStringExtra("read")
            if (read != null) {
                intent.removeExtra("read")
                bookId = read.toLongOrNull() ?: -1L
                Trace.step("debug: read $bookId")
                if (stack.isNotEmpty() && stack[stack.size - 1] == SCR_READER) changeTo(SCR_READER)
                else go(SCR_READER)
                return
            }
            val sc = intent?.getStringExtra("screen")
            // `first` — мастер первого запуска на книге с непустой библиотекой (п. 13):
            // ключ `firstRunDone` не трогаем, мастер открывается синтетически.
            if (sc == "first") {
                intent.removeExtra("screen")
                Trace.step("debug: screen first")
                stack.clear()
                stack.add(SCR_FIRST_LANG)
                changeTo(SCR_FIRST_LANG)
                return
            }
            // `first2` — сразу шаг 2 мастера (B61, сети Wi-Fi и лист B47), за ним шаг 1 в стеке.
            if (sc == "first2") {
                intent.removeExtra("screen")
                Trace.step("debug: screen first2")
                stack.clear()
                stack.add(SCR_FIRST_LANG)
                go(SCR_FIRST_WIFI)
                return
            }
            if (sc != "wifi" && sc != "updates" && sc != "licenses" && sc != "zone" &&
                sc != "layouts" && sc != "net" && sc != "bt" && sc != "read" &&
                sc != "keys" && sc != "firstread" && sc != "stats") return
            intent.removeExtra("screen")
            Trace.step("debug: screen $sc")
            val id = when (sc) {
                "updates" -> SCR_SET_UPDATES
                "licenses" -> SCR_SET_LICENSES
                "zone" -> SCR_SET_ZONE
                "layouts" -> SCR_SET_LAYOUTS
                "net" -> SCR_SET_NET
                "bt" -> SCR_SET_BT
                // B32 «Статистика чтения» (п. 22): проверка экрана без кликов по меню.
                "stats" -> SCR_STATS
                // B39 «Настройки · Чтение» и шаг 4 мастера (B62): на книге не пройти кликами —
                // на пути к B39 два уровня списка, а мастер открывается только на первом запуске.
                "read" -> SCR_SET_READ
                // B13 → «Кнопки и жесты» (2026-11-21): тоже два уровня списка.
                "keys" -> SCR_SET_KEYS
                "firstread" -> SCR_FIRST_READ
                else -> SCR_WIFI
            }
            if (stack.isNotEmpty() && stack[stack.size - 1] == id) changeTo(id)
            else go(id)
            return
        }
        intent.removeExtra("probe")
        Trace.step("probe: intent $p")
        ru.ogrpfsense.dexpm8.reader.ProbeScreen.debugPath = p
        if (stack.isNotEmpty() && stack[stack.size - 1] == SCR_READER) changeTo(SCR_READER) else go(SCR_READER)
    }

    /**
     * Физические клавиши книги (R1.3): `gpio-keys` отдаёт PAGE_UP/PAGE_DOWN, `adc-keys` —
     * громкость. На читалке (B3) они листают; в остальной оболочке идут как раньше.
     * `KEYCODE_BACK` не перехватываем никогда — выход с экрана идёт через [onBackPressed].
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val code = event.keyCode
        // Заставка B19: любая клавиша (кроме «Назад» — ею занимается onBackPressed) только будит.
        // Иначе листание вслепую перевернуло бы страницу под заставкой.
        if (sleepShown() && code != android.view.KeyEvent.KEYCODE_BACK) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) wakeUp()
            return true
        }
        if (code != android.view.KeyEvent.KEYCODE_BACK) {
            val top = current()
            if (top is ru.ogrpfsense.dexpm8.reader.ReaderScreen && shade?.parent == null) {
                if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    if (top.key(code)) return true
                } else if (event.action == android.view.KeyEvent.ACTION_UP) {
                    // Листание по нажатию: UP не отдаём системе, иначе громкость «щёлкнет».
                    // Но если читалка клавишу не берёт (B39 «Кнопки громкости» = «Громкость»),
                    // UP идёт дальше — иначе система не увидит нажатия вовсе.
                    if (top.handlesKey(code)) return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() {
        // Заставка перехватывает «Назад»: это пробуждение, а не выход с экрана и не закрытие
        // шторки (шторку под заставкой не открывали).
        if (sleepShown()) {
            wakeUp()
            return
        }
        // Диалог B20 — поверх экрана: «Назад» закрывает его, как «Позже».
        if (lowShown()) {
            lowLater()
            return
        }
        // Сначала экран: у читалки поверх страницы бывает лист B31 — «Назад» закрывает его,
        // а не уводит из книги.
        if (current()?.onBack() == true) return
        if (shadeOpen) {
            closeShade()
            return
        }
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

    /**
     * «Сбросить» в диалоге B56: вернуть системе то, что сняла экономия, стереть настройки оболочки
     * и перезапустить её.
     *
     * Порядок обязателен: сначала `Power.saverRelease` (ему нужны `Prefs.saverWifi`/`saverBright` —
     * после `reset` их уже нет, и Wi-Fi с подсветкой остались бы выключенными навсегда), потом
     * `Prefs.reset`, потом `recreate`.
     *
     * Экономия снимается, даже если сброс случился не из-за неё: настройки оболочки уходят
     * к значениям макета, а книга должна остаться в рабочем виде — с Wi-Fi и подсветкой.
     * Контраст (`sys.gray.gammalevel`) — системное свойство, а не настройка оболочки (в диалоге
     * перечислены шрифт, поля, зоны касаний, кнопки и сон), поэтому его не трогаем.
     *
     * `recreate()` — единственный «перезапуск», доступный из приложения: стек экранов не
     * сохраняется, и `onCreate` начинает с B1. Книги и заметки не затрагиваются: база живёт
     * в `LibraryDb`, а не в `Prefs`. Мастер первого запуска вернётся только при пустой библиотеке.
     */
    fun resetShell() {
        if (prefs.saverApplied) power.saverRelease()
        prefs.reset()
        Trace.step("shell: reset -> recreate")
        recreate()
    }

    /** Пустая ли библиотека: мастер первого запуска показывается только тогда. */
    private fun booksEmpty(): Boolean = try {
        db.count() == 0
    } catch (t: Throwable) {
        Trace.step("first run: db FAIL ${t.javaClass.simpleName}: ${t.message}")
        false
    }

    /**
     * Полная перерисовка после возврата из системного экрана (настройки Wi-Fi, права).
     * Экран делает это сам: [cleanOnFocus] сработает только при следующей смене фокуса.
     */
    fun markCleanOnFocus() {
        cleanOnFocus = true
    }

    /**
     * Мастер первого запуска пройден: ставим `Prefs.firstRunDone` и уводим мастер из стека.
     * [target] — куда идти: [SCR_HOME] или [SCR_LIBRARY]. Стек заменяется целиком, чтобы
     * «Назад» с главного не возвращал в мастер.
     */
    fun finishFirstRun(target: Int) {
        prefs.firstRunDone = true
        Trace.step("first run: done -> screen $target")
        stack.clear()
        stack.add(SCR_HOME)
        if (target != SCR_HOME && target != SCR_LIBRARY) {
            go(target)
            return
        }
        changeTo(SCR_HOME)
        if (target == SCR_LIBRARY) go(SCR_LIBRARY)
    }

    private fun changeTo(screenId: Int, switch: Boolean = true) {
        // Шторка уходит вместе с экраном; затемнение с прежнего экрана снимаем (он в кэше).
        for (i in 0 until host.childCount) (host.getChildAt(i) as? Screen)?.dimmed = false
        // Заставка B19 и диалог B20 тоже уходят: под ними мог остаться экран, с которого ушли.
        sleep?.let { if (it.parent != null) host.removeView(it) }
        low?.let { if (it.parent != null) host.removeView(it) }
        host.removeAllViews()
        host.addView(obtain(screenId))
        if (switch) fullOnSwitch()
    }

    // ------------------------------------------------------------ B5: шторка (п. 8.1)

    private var shade: ShadeScreen? = null

    val shadeOpen: Boolean get() = shade?.parent != null

    /** Экран наверху стека (под шторкой). */
    private fun current(): Screen? = if (stack.isEmpty()) null else screens[stack[stack.size - 1]]

    /** Текущий экран, если это читалка (чтение вслух и место чтения живут в ней). */
    private fun currentReader(): ru.ogrpfsense.dexpm8.reader.ReaderScreen? =
        current() as? ru.ogrpfsense.dexpm8.reader.ReaderScreen

    /**
     * Открыть шторку B5 поверх текущего экрана: экран затемняется ([Screen.dimmed]), шторка —
     * второй View в `host`. Касания под шторкой экрану не доходят: шторка на весь экран.
     */
    fun openShade() {
        if (!this::host.isInitialized || shadeOpen) return
        val s = shade ?: ShadeScreen(this).also { shade = it }
        current()?.dimmed = true
        host.addView(s, android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        ))
        Trace.step("shade: open")
        bigFrame("shade open")
    }

    fun closeShade() {
        val s = shade ?: return
        if (s.parent == null) return
        host.removeView(s)
        current()?.dimmed = false
        Trace.step("shade: close")
        bigFrame("shade close")
    }

    /**
     * «Выключение» (B5): системное меню питания через свою службу спецвозможностей
     * ([ru.ogrpfsense.dexpm8.core.PowerService]). Экрана «Спец. возможности» в прошивке книги нет
     * (проверено 2026-11-12), службу включают один раз через adb — команда в docs/BUILD.md.
     * false — служба не включена или система отказала; шторка тогда пишет «Нужна настройка».
     * После меню питания — очистка E-Ink, как после диалога права.
     */
    fun powerDialog(): Boolean {
        val svc = ru.ogrpfsense.dexpm8.core.PowerService.instance
        if (svc == null) {
            Trace.step("power: service off")
            return false
        }
        val ok = svc.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
        Trace.step("power: dialog -> $ok")
        if (ok) cleanOnFocus = true
        return ok
    }

    /**
     * «Снимок» (B5): текущий экран оболочки без шторки и без затемнения → PNG в
     * `Pictures/Screenshots`. Кадр рисуется в UI-потоке, сжатие и запись — в фоне.
     * [done] — в UI-потоке, true — файл записан.
     */
    fun screenshot(done: (Boolean) -> Unit) {
        val s = current()
        if (s == null || s.width <= 0 || s.height <= 0) { done(false); return }
        requestStorage { granted ->
            if (!granted) { Trace.step("shot: no storage"); done(false); return@requestStorage }
            val bmp = try {
                android.graphics.Bitmap.createBitmap(s.width, s.height, android.graphics.Bitmap.Config.ARGB_8888)
            } catch (t: Throwable) {
                Trace.step("shot: bitmap FAIL ${t.javaClass.simpleName}"); done(false); return@requestStorage
            }
            val c = android.graphics.Canvas(bmp)
            c.drawColor(T.BG)
            try {
                s.capture(c)
            } catch (t: Throwable) {
                Trace.step("shot: render FAIL ${t.javaClass.simpleName}: ${t.message}")
                bmp.recycle(); done(false); return@requestStorage
            }
            val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_PICTURES), "Screenshots")
            val name = "DexpM8_" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT)
                .format(java.util.Date()) + ".png"
            val f = java.io.File(dir, name)
            Thread {
                val ok = try {
                    dir.mkdirs()
                    java.io.FileOutputStream(f).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                } catch (t: Throwable) {
                    Trace.step("shot: write FAIL ${t.javaClass.simpleName}: ${t.message}"); false
                }
                bmp.recycle()
                Trace.step("shot: ${f.path} -> $ok")
                if (ok) android.media.MediaScannerConnection.scanFile(applicationContext,
                    arrayOf(f.path), arrayOf("image/png"), null)
                ui.post { if (!isDestroyed) done(ok) }
            }.start()
        }
    }

    // ------------------------------------------------------------ E-Ink: полная перерисовка (п. 6.1)

    /** Переходов между экранами с последней полной перерисовки. */
    private var switches = 0

    /**
     * Политика при смене экрана. «Очищать при смене экрана» (B37) включено — полная перерисовка
     * на каждом переходе; выключено — на каждом N-м, N = `screen_sync_time` (настройка родного
     * лаунчера, по умолчанию 5). Режим заказывается до кадра нового экрана (`force=false`) и уходит
     * вместе с ним — одно мигание. Внутри экрана ничего не заказываем: прошивка рисует `EPD_PART`.
     */
    private fun fullOnSwitch() {
        switches++
        // «Быстрый» (8.2, вариант А): A2 на большой кадр с серым даёт кашу — смена экрана всегда полная.
        if (!prefs.cleanOnSwitch && fastEpd == 0 && switches < syncEvery()) {
            Trace.step("epd: switch skip $switches/${syncEvery()}")
            return
        }
        switches = 0
        if (!Epd.ok) return
        val r = Epd.request(host, Epd.CLEAN, false)
        Trace.step("epd: switch full -> $r (clean_on_switch=${prefs.cleanOnSwitch})")
    }

    // ------------------------------------------------------------ E-Ink: «Чёткий / Быстрый», «Очистка» (п. 8.2)

    /** Режим «Быстрый» для [Epd.beforeFrame]: значение `EINK_MODE`, 0 — «Чёткий» (`EPD_PART`). */
    private var fastEpd = 0

    /** Каждый кадр окна: режим прошивки одноразовый, «Быстрый» заказываем заново. */
    private val fastFrame = android.view.ViewTreeObserver.OnPreDrawListener {
        Epd.beforeFrame(host, fastEpd)
        true
    }

    /**
     * Применить `Prefs.refreshMode` (B5, B37): 1 — «Быстрый» (A2-режим на мелкие изменения внутри
     * экрана), иначе «Чёткий» (частичный `EPD_PART` по умолчанию прошивки). «Без мерцания» (B37, 2)
     * пока как «Чёткий» — своего режима нет. При выходе из «Быстрого» — одна полная перерисовка:
     * A2 оставляет следы. Большие кадры в «Быстром» — полные ([bigFrame], [fullOnSwitch]).
     */
    fun applyRefreshMode() {
        val was = fastEpd
        fastEpd = if (prefs.refreshMode == 1 && Epd.ok) Epd.A2 else 0
        Trace.step("epd: refresh mode ${prefs.refreshMode} -> epd $fastEpd")
        if (was > 0 && fastEpd == 0) cleanScreen()
    }

    /**
     * Клавиша своей клавиатуры нажата (`Keyboard.onKey`): звук касания (B72) и, если включено
     * «Обновлять экран при вводе», полная перерисовка кадра. Звук просит `AudioManager` —
     * системный «клик» прошивки; без включённых звуков системы вызов молча ничего не делает,
     * поэтому тумблер B72 пишет и `Settings.System.SOUND_EFFECTS_ENABLED` (`Sys.setSoundKey`).
     */
    fun keyPressed() {
        if (prefs.keySound) try {
            (getSystemService(AUDIO_SERVICE) as? android.media.AudioManager)
                ?.playSoundEffect(android.media.AudioManager.FX_KEY_CLICK)
        } catch (t: Throwable) {
            Trace.step("key sound FAIL: ${t.javaClass.simpleName}: ${t.message}")
        }
        if (prefs.keyClean) {
            if (!this::host.isInitialized) return
            val r = Epd.request(host, Epd.CLEAN, false)
            Trace.step("epd: key input full -> $r")
        }
    }

    /**
     * Большой кадр (шторка, лист B36 и т. п.) в «Быстром»: A2 рисует его кашей (проверено на книге
     * 2026-11-12, 8.2), поэтому этот один кадр — полной перерисовкой, одно мигание. В «Чётком»
     * ничего не заказываем: `EPD_PART` такие кадры рисует нормально. Вызывать до кадра.
     */
    fun bigFrame(what: String) {
        if (fastEpd == 0 || !this::host.isInitialized) return
        val r = Epd.request(host, Epd.CLEAN, false)
        Trace.step("epd: big frame ($what) full -> $r")
    }

    /**
     * Разовая полная перерисовка текущего окна («Очистка» в B5, выход из «Быстрого»): тот же
     * путь, что после системного диалога — `Epd.CLEAN` одним кадром, без метода — белый кадр.
     */
    fun cleanScreen() {
        Trace.step("epd: clean now")
        cleanRefresh()
    }

    private fun syncEvery(): Int = try {
        android.provider.Settings.System.getInt(contentResolver, "screen_sync_time", 5).coerceAtLeast(1)
    } catch (t: Throwable) {
        5
    }

    private fun obtain(screenId: Int): Screen {
        val cached = screens[screenId]
        if (cached != null) return cached
        val s: Screen = when (screenId) {
            SCR_HOME -> HomeScreen(this)
            SCR_LIBRARY -> LibraryScreen(this)
            SCR_APPS -> AppsScreen(this)
            SCR_SETTINGS -> SettingsScreen(this)
            SCR_BOOK -> BookScreen(this)
            SCR_SEARCH -> SearchScreen(this)
            SCR_SHELVES -> ShelvesScreen(this)
            SCR_SHELF -> ShelfScreen(this)
            SCR_SET_SCREEN -> SetScreenScreen(this)
            SCR_SET_NET -> SetNetScreen(this)
            SCR_SET_READ -> SetReadScreen(this)
            SCR_SET_KEYS -> SetKeysScreen(this)
            SCR_SET_FONT -> FontSizeScreen(this)
            SCR_SET_SLEEP -> SetSleepScreen(this)
            SCR_SET_LANG -> SetLangScreen(this)
            SCR_SET_DICT -> SetDictScreen(this)
            SCR_SET_UPDATES -> SetUpdateScreen(this)
            SCR_SET_STORAGE -> SetStorageScreen(this)
            SCR_SET_ABOUT -> SetAboutScreen(this)
            SCR_SET_AUTHOR -> SetAuthorScreen(this)
            SCR_SET_LICENSES -> SetLicensesScreen(this)
            SCR_SET_ZONE -> SetTimeZoneScreen(this)
            SCR_SET_LAYOUTS -> SetLayoutScreen(this)
            SCR_SET_BT -> SetBtScreen(this)
            SCR_AUDIO_LIST -> AudioListScreen(this)
            SCR_AUDIO -> AudioScreen(this)
            SCR_AUDIO_CHAPTERS -> AudioChaptersScreen(this)
            SCR_STATS -> StatScreen(this)
            SCR_CHESS_MENU -> ChessMenuScreen(this)
            SCR_CHESS_NEW -> ChessNewScreen(this)
            SCR_CHESS -> ChessScreen(this)
            SCR_CHESS_MOVES -> ChessMovesScreen(this)
            SCR_FIRST_LANG -> FirstLangScreen(this)
            SCR_FIRST_WIFI -> FirstWifiScreen(this)
            SCR_FIRST_PERM -> FirstPermScreen(this)
            SCR_FIRST_READ -> FirstReadScreen(this)
            SCR_FIRST_DONE -> FirstDoneScreen(this)
            SCR_FILES -> FilesScreen(this)
            SCR_WIFI -> WifiUploadScreen(this)
            SCR_CATALOGS -> CatalogsScreen(this)
            SCR_CATALOG_ADD -> CatalogAddScreen(this)
            SCR_CATALOG -> OpdsScreen(this)
            SCR_CATALOG_BOOK -> CatalogBookScreen(this)
            SCR_DOWNLOADS -> DownloadsScreen(this)
            SCR_READER -> ru.ogrpfsense.dexpm8.reader.ReaderScreen(this)   // B3, R1.3
            else -> HomeScreen(this)
        }
        screens[screenId] = s
        return s
    }

    override fun onDestroy() {
        // Новое окно (пересоздание) могло уже поставить своё — снимаем только своё.
        if (ChessNet.onAwake === chessAwake) ChessNet.onAwake = null
        ui.removeCallbacksAndMessages(null)
        batteryUnwatch()
        storageWaiters.clear()
        storageListeners.clear()
        locationWaiters.clear()
        writeWaiter = null
        adminWaiter = null
        installWaiter = null
        // TTS-движок — отдельный процесс-сервис: без shutdown он живёт до смерти процесса.
        if (speechUsed) speech.shutdown()
        screens.clear()
        shade = null
        if (lastInstance === this) lastInstance = null
        super.onDestroy()
    }
}
