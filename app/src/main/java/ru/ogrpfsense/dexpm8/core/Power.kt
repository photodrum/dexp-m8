package ru.ogrpfsense.dexpm8.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings
import ru.ogrpfsense.dexpm8.ui.Trace

/**
 * Заряд книги: чтение состояния, оценка «сколько ещё читать», режим энергосбережения и порог
 * выключения (п. 13, B20 «Низкий заряд»).
 *
 * Заряд и зарядка — липкое `ACTION_BATTERY_CHANGED`, право не нужно (как в `StatusBar`).
 * Порог, при котором система сама включает экономию, — `Settings.Global.low_power_trigger_level`
 * (`Settings.Global` закрыт для записи обычным приложениям, поэтому только чтение; если пусто —
 * [DEFAULT_SAVER_AT], столько же в подписи B40 «Включится при 15 %»).
 *
 * Экономия — **не настройка системы**, а два своих действия (так она описана на B40 и в макете
 * B20: «Без Wi-Fi и подсветки до зарядки»): Wi-Fi выключается, подсветка ставится в 0. Что было,
 * запоминается в `Prefs` и возвращается при зарядке ([saverRelease]). Своих профилей питания
 * (PowerManager) не трогаем: на прошивке книги они не проверены.
 */
class Power(ctx: Context, private val prefs: Prefs, private val sys: Sys) {

    private val app = ctx.applicationContext

    /** Заряд, % и идёт ли зарядка. */
    data class Read(val level: Int, val charging: Boolean)

    /**
     * Снимок состояния. Приёмник не зарегистрирован (сбой системы, редкий случай) — значения
     * по умолчанию, как у `StatusBar`: 78 % и не заряжается.
     */
    fun read(): Read = try {
        val i = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (i == null) {
            Read(DEFAULT_LEVEL, false)
        } else {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            Read(
                if (level < 0 || scale <= 0) DEFAULT_LEVEL else (level * 100 / scale).coerceIn(0, 100),
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL || plugged != 0,
            )
        }
    } catch (t: Throwable) {
        Trace.step("power: read FAIL ${t.javaClass.simpleName}: ${t.message}")
        Read(DEFAULT_LEVEL, false)
    }

    /** Порог экономии, %: системный, а если его нет — [DEFAULT_SAVER_AT]. */
    fun saverAt(): Int = try {
        val v = Settings.Global.getInt(app.contentResolver, "low_power_trigger_level", 0)
        if (v in 1..99) v else DEFAULT_SAVER_AT
    } catch (t: Throwable) {
        Trace.step("power: trigger FAIL ${t.javaClass.simpleName}: ${t.message}")
        DEFAULT_SAVER_AT
    }

    /**
     * Сколько ещё примерно читать при запасе [level], %: «примерно 2 часа чтения», как на B20.
     * Оценка от полного заряда в [FULL_MINUTES] минут чтения — та же арифметика, что у макета
     * (9 % → 2 часа), ниже получаса — «меньше часа».
     */
    fun hoursText(level: Int): String {
        val minutes = FULL_MINUTES * level.coerceIn(0, 100) / 100
        if (minutes < 45) return "меньше часа"
        val h = (minutes + 30) / 60
        val word = when {
            h % 10 == 1 && h % 100 != 11 -> "час"
            h % 10 in 2..4 && h % 100 !in 12..14 -> "часа"
            else -> "часов"
        }
        return "примерно $h $word чтения"
    }

    /**
     * Включить экономию: запомнить, что было, и снять Wi-Fi с подсветкой. false — менять нечего
     * (уже включена, идёт зарядка или система ничего не дала изменить). Подсветка меняется только
     * при WRITE_SETTINGS — без права гасится один Wi-Fi, это тоже экономия.
     */
    fun saverApply(): Boolean {
        if (prefs.saverApplied || read().charging) return false
        var changed = false
        val wifi = sys.wifiOn()
        prefs.saverWifi = wifi
        if (wifi && sys.setWifi(false)) changed = true
        val bright = sys.brightness()
        prefs.saverBright = if (bright > 0) bright else -1
        if (bright > 0 && sys.setBrightness(0)) changed = true
        prefs.saverApplied = changed
        if (changed) Trace.step("power: saver on wifi=$wifi bright=$bright")
        return changed
    }

    /** Вернуть Wi-Fi и подсветку, как было до экономии (зарядка или отключение режима). */
    fun saverRelease() {
        if (!prefs.saverApplied) return
        prefs.saverApplied = false
        val wifi = prefs.saverWifi
        if (wifi) sys.setWifi(true)
        val bright = prefs.saverBright
        prefs.saverBright = -1
        if (bright > 0) sys.setBrightness(bright)
        Trace.step("power: saver off wifi=$wifi bright=$bright")
    }

    companion object {
        /** Ниже этого заряда оболочка сохраняет место чтения и выключает книгу (макет B20). */
        const val OFF_AT = 3

        /** Порог экономии, если система своего не отдала (столько же в подписи B40). */
        const val DEFAULT_SAVER_AT = 15

        /** Заряд, который показываем, когда системы не спросить (как в `StatusBar`). */
        private const val DEFAULT_LEVEL = 78

        /**
         * Сколько читает полностью заряженная книга, минуты. Не измерено, оценка: 20 часов при
         * 9 % → «примерно 2 часа» на B20. Уточнить замером на книге, когда будет заряд в 100 % и
         * известное время чтения; число одно, правится здесь.
         */
        private const val FULL_MINUTES = 20 * 60
    }
}
