package ru.ogrpfsense.dexpm8.core

import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Context
import android.net.wifi.WifiManager
import android.provider.Settings
import ru.ogrpfsense.dexpm8.ui.Trace

/**
 * Системные настройки — один слой для B37–B42 и панели управления B5 (п. 8).
 * Только вызовы системы; права запрашивает `EinkActivity` (`withWriteSettings`, `sleepNow`).
 *
 *   яркость, тайм-аут сна, 12/24 ч   Settings.System — особое право WRITE_SETTINGS
 *   Wi-Fi                            WifiManager — CHANGE_WIFI_STATE (обычное)
 *   Bluetooth                        BluetoothAdapter — BLUETOOTH_ADMIN (обычное)
 *   сон по кнопке                    DevicePolicyManager.lockNow — device admin
 *
 * «Время по сети» (`auto_time`) и порог экономии — Settings.Global, нужен
 * WRITE_SECURE_SETTINGS (только системным приложениям), поэтому только чтение.
 */
class Sys(ctx: Context) {

    private val app = ctx.applicationContext
    private val cr = app.contentResolver

    val adminComponent = ComponentName(app, AdminReceiver::class.java)

    // ------------------------------------------------------------ WRITE_SETTINGS

    fun canWrite(): Boolean = Settings.System.canWrite(app)

    /** Яркость подсветки 0…100 (система хранит 0…255). */
    fun brightness(): Int = try {
        (Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS) * 100 + 127) / 255
    } catch (t: Throwable) {
        fail("brightness", t); -1
    }

    /** Ставит ручной режим и яркость. false — нет права или система отказала. */
    fun setBrightness(pct: Int): Boolean = write("brightness") {
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (pct.coerceIn(0, 100) * 255 + 50) / 100)
    }

    /** Тайм-аут сна, мс; −1 — не прочитан. */
    fun sleepTimeout(): Int = try {
        Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT)
    } catch (t: Throwable) {
        fail("timeout", t); -1
    }

    /** [ms] = Int.MAX_VALUE — «Никогда». */
    fun setSleepTimeout(ms: Int): Boolean = write("timeout") {
        Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, ms)
    }

    fun setTime24(on: Boolean): Boolean = write("time_12_24") {
        Settings.System.putString(cr, Settings.System.TIME_12_24, if (on) "24" else "12")
    }

    /**
     * «Звук нажатия» (B72): звук клавиши просит `EinkActivity.keyPressed` через
     * `AudioManager.playSoundEffect(CLICK)`, но система играет его только при включённом
     * `Settings.System.SOUND_EFFECTS_ENABLED` (иначе вызов молча ничего не делает). Поэтому при
     * включении тумблера ставим и системное значение — это `Settings.System`, право WRITE_SETTINGS.
     * При выключении систему не трогаем: остальные звуки книги — не наше дело.
     * false — нет права WRITE_SETTINGS или система отказала (своя настройка при этом цела).
     */
    fun setSoundKey(on: Boolean): Boolean = write("sound_key") {
        Settings.System.putInt(cr, Settings.System.SOUND_EFFECTS_ENABLED, if (on) 1 else 0)
    }

    private inline fun write(what: String, block: () -> Boolean): Boolean {
        if (!canWrite()) return false
        return try {
            block()
        } catch (t: Throwable) {
            fail(what, t); false
        }
    }

    // ------------------------------------------------------------ радио

    private val wifi: WifiManager? get() = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    fun wifiOn(): Boolean = try {
        wifi?.isWifiEnabled ?: false
    } catch (t: Throwable) {
        fail("wifi", t); false
    }

    /** Включение асинхронное: итог придёт в WIFI_STATE_CHANGED_ACTION. */
    fun setWifi(on: Boolean): Boolean = try {
        wifi?.setWifiEnabled(on) ?: false
    } catch (t: Throwable) {
        fail("wifi set", t); false
    }

    fun btOn(): Boolean = try {
        BluetoothAdapter.getDefaultAdapter()?.isEnabled ?: false
    } catch (t: Throwable) {
        fail("bt", t); false
    }

    /** Асинхронно: итог придёт в BluetoothAdapter.ACTION_STATE_CHANGED. */
    fun setBt(on: Boolean): Boolean = try {
        val a = BluetoothAdapter.getDefaultAdapter()
        if (a == null) false else if (on) a.enable() else a.disable()
    } catch (t: Throwable) {
        fail("bt set", t); false
    }

    // ------------------------------------------------------------ device admin

    private val dpm: DevicePolicyManager?
        get() = app.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager

    fun adminActive(): Boolean = dpm?.isAdminActive(adminComponent) ?: false

    /** Выключить экран. false — админ не включён. */
    fun lockNow(): Boolean {
        val d = dpm ?: return false
        if (!d.isAdminActive(adminComponent)) return false
        return try {
            d.lockNow(); true
        } catch (t: Throwable) {
            fail("lockNow", t); false
        }
    }

    // ------------------------------------------------------------ контраст

    /**
     * Контраст панели — системное свойство `sys.gray.gammalevel` (0…80, 16 делений; найдено на
     * книге 2026-11-11). Через `adb shell setprop` работает, и из обычного приложения тоже:
     * запись проверена шторкой B5 (2026-11-12) и ползунком B37 (2026-11-18). Доступ — рефлексией
     * к скрытому `android.os.SystemProperties`. Ползунок B37: `SetScreenScreen`, `SettingsKit.sliderContrast`.
     * −1 — не прочитано.
     */
    fun gamma(): Int = try {
        val v = Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java).invoke(null, GAMMA) as String
        v.trim().toIntOrNull() ?: -1
    } catch (t: Throwable) {
        fail("gamma", t); -1
    }

    /** false — система отказала (на 8.1 отказ приходит исключением) или значение не легло. */
    fun setGamma(v: Int): Boolean = try {
        Class.forName("android.os.SystemProperties")
            .getMethod("set", String::class.java, String::class.java).invoke(null, GAMMA, v.toString())
        gamma() == v
    } catch (t: Throwable) {
        val c = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
        fail("gamma set", c); false
    }

    // ------------------------------------------------------------ часовой пояс (B71)

    /**
     * Текущий часовой пояс системы («Europe/Moscow»). Читается свойство, а не
     * `TimeZone.getDefault()`: кеш зоны в процессе обновляется не сразу после записи.
     * `null` — свойство не прочитано (тогда экран берёт пояс из `TimeZone.getDefault()`).
     */
    fun zone(): String? = try {
        val v = (Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java).invoke(null, TZ) as String).trim()
        v.ifEmpty { null }
    } catch (t: Throwable) {
        fail("zone get", t); null
    }

    /**
     * «Определять по сети» (`Settings.Global.auto_time_zone`). Только чтение: таблица Global
     * пишется с WRITE_SECURE_SETTINGS, которого у обычного приложения нет — на книге это
     * проверено (`settings put` из процесса оболочки отказывает). `null` — не прочитано.
     */
    fun autoZone(): Boolean? = try {
        Settings.Global.getInt(cr, "auto_time_zone") != 0
    } catch (t: Throwable) {
        fail("auto_time_zone", t); null
    }

    /**
     * Поставить часовой пояс системы («Europe/Moscow») — свойство `persist.sys.timezone`.
     * Возвращает то, что прочитано обратно после записи; `null` — записать не удалось.
     *
     * Почему свойство, а не `AlarmManager.setTimeZone`: тот требует `SET_TIME_ZONE`, право
     * уровня подписи платформы, обычному приложению его не выдают. Свойство же пишется тем же
     * вызовом, что и `setprop` в оболочке adb. Проверено на книге 2026-11-19 из процесса
     * оболочки (uid приложения): время переключается сразу, значение ложится в
     * `/data/property/persist.sys.timezone` и переживает перезагрузку. Доступ — рефлексией
     * к скрытому `android.os.SystemProperties`, как у контраста ([gamma]).
     */
    fun setZone(id: String): String? = try {
        val c = Class.forName("android.os.SystemProperties")
        c.getMethod("set", String::class.java, String::class.java).invoke(null, TZ, id)
        val back = (c.getMethod("get", String::class.java).invoke(null, TZ) as String).trim()
        // Пояс процесса закеширован в `TimeZone.getDefault()`, а рассылки TIMEZONE_CHANGED
        // после `setprop` нет (её шлёт только AlarmManager, и она защищённая — самим не послать).
        // Сброс заставляет libcore перечитать `persist.sys.timezone`: без него часы оболочки
        // оставались в старом поясе до перезапуска процесса (проверено на книге 2026-11-19).
        java.util.TimeZone.setDefault(null)
        Trace.step("sys zone: $id -> '$back', default=${java.util.TimeZone.getDefault().id}")
        back
    } catch (t: Throwable) {
        val c = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
        fail("zone set", c); null
    }

    // ------------------------------------------------------------ память

    /** Удалить содержимое внутреннего и внешнего кэша оболочки (сами каталоги остаются). */
    fun clearCache() {
        clearDir(app.cacheDir)
        clearDir(app.externalCacheDir)
    }

    private fun clearDir(d: java.io.File?) {
        d?.listFiles()?.forEach { if (!it.deleteRecursively()) Trace.step("cache: не удалён ${it.name}") }
    }

    private fun fail(what: String, t: Throwable) =
        Trace.step("sys $what FAIL: ${t.javaClass.simpleName}: ${t.message}")

    companion object {
        private const val GAMMA = "sys.gray.gammalevel"

        /** Свойство часового пояса системы (B71); его пишет и `AlarmManager.setTimeZone`. */
        private const val TZ = "persist.sys.timezone"


        /** 16 делений контраста (`sys.gray.gammalevel`), найдены на книге 2026-11-11. */
        val GAMMA_STEPS = intArrayOf(0, 6, 11, 16, 21, 26, 31, 36, 41, 46, 51, 56, 61, 66, 71, 80)

        /** Номер ближайшего деления к значению свойства [v]. */
        fun gammaIndex(v: Int): Int {
            var best = 0
            for (i in GAMMA_STEPS.indices) {
                if (Math.abs(GAMMA_STEPS[i] - v) < Math.abs(GAMMA_STEPS[best] - v)) best = i
            }
            return best
        }
    }
}
