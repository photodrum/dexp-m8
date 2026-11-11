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
}
