package ru.ogrpfsense.dexpm8.core

import android.content.Context

/**
 * Настройки самой оболочки (то, чего нет в системе): `SharedPreferences` «shell».
 * Значения по умолчанию — из макетов B37–B42. Пока часть из них только хранится:
 * тёплый свет, режим обновления и контраст ждут драйвера (п. 8), чтение — читалки,
 * экономия и заставка — B19/B20.
 */
class Prefs(ctx: Context) {

    private val sp = ctx.applicationContext.getSharedPreferences("shell", Context.MODE_PRIVATE)

    // B37 «Экран»
    var warm: Int
        get() = sp.getInt("warm", 30)
        set(v) = sp.edit().putInt("warm", v).apply()
    /** 0 — «Чёткий», 1 — «Быстрый», 2 — «Без мерцания». */
    var refreshMode: Int
        get() = sp.getInt("refresh_mode", 0)
        set(v) = sp.edit().putInt("refresh_mode", v).apply()
    /** Полная перерисовка на каждой смене экрана; выключено — каждые `screen_sync_time` (п. 6.1). */
    var cleanOnSwitch: Boolean
        get() = sp.getBoolean("clean_on_switch", true)
        set(v) = sp.edit().putBoolean("clean_on_switch", v).apply()
    var contrast: Boolean
        get() = sp.getBoolean("contrast", false)
        set(v) = sp.edit().putBoolean("contrast", v).apply()

    // B38 «Сеть»
    var wifiSleepOff: Boolean
        get() = sp.getBoolean("wifi_sleep_off", true)
        set(v) = sp.edit().putBoolean("wifi_sleep_off", v).apply()

    // B39 «Чтение»
    var hyphens: Boolean
        get() = sp.getBoolean("hyphens", true)
        set(v) = sp.edit().putBoolean("hyphens", v).apply()
    /** 0 — «Классическая», 1 — «Две половины», 2 — «Только вперёд». */
    var tapZones: Int
        get() = sp.getInt("tap_zones", 0)
        set(v) = sp.edit().putInt("tap_zones", v).apply()
    var swipe: Boolean
        get() = sp.getBoolean("swipe", true)
        set(v) = sp.edit().putBoolean("swipe", v).apply()

    // B40 «Сон и питание»
    var saver: Boolean
        get() = sp.getBoolean("saver", true)
        set(v) = sp.edit().putBoolean("saver", v).apply()
    var coverCharge: Boolean
        get() = sp.getBoolean("cover_charge", true)
        set(v) = sp.edit().putBoolean("cover_charge", v).apply()

    // B42 «Память»
    var deleteArchives: Boolean
        get() = sp.getBoolean("delete_archives", true)
        set(v) = sp.edit().putBoolean("delete_archives", v).apply()

    // B6/B36 «Приложения»
    /** Пакеты, скрытые из списка («Скрыть из списка»); возврат — подзаголовок «скрыто N» на B6 или сброс настроек. */
    var hiddenApps: Set<String>
        get() = HashSet<String>(sp.getStringSet("hidden_apps", null) ?: emptySet<String>())
 set(v) = sp.edit().putStringSet("hidden_apps", HashSet<String>(v)).apply()
    /** Пакет, закреплённый на B1 вместо плитки «Аудиокниги»; пусто — не закреплено. */
    var pinnedApp: String
        get() = sp.getString("pinned_app", "") ?: ""
        set(v) = sp.edit().putString("pinned_app", v).apply()


    /** Сброс к значениям макета (B43 «Сбросить настройки оболочки» — после диалога подтверждения). */
    fun reset() = sp.edit().clear().apply()
}
