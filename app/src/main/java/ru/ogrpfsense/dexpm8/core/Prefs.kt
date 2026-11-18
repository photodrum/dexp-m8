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
    /** 0 — «Чёткий», 1 — «Быстрый» (A2-режим внутри экрана, п. 8.2), 2 — «Без мерцания» (пока как «Чёткий»). */
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
    /**
     * Последнее выставленное деление контраста (B5/B37, 0…15). Само значение живёт в системном
     * свойстве `sys.gray.gammalevel`, а `sys.*` сбрасываются при каждой загрузке книги — здесь
     * же оно переживает перезагрузку и остаётся запасным, пока шторка не прочитала систему.
     * Умолчание — деление 7 (47 %), как «50 %» в макете B05.
     */
    var gammaLevel: Int
        get() = sp.getInt("gamma_level", 7)
        set(v) = sp.edit().putInt("gamma_level", v.coerceIn(0, 15)).apply()

    // B38 «Сеть»
    var wifiSleepOff: Boolean
        get() = sp.getBoolean("wifi_sleep_off", true)
        set(v) = sp.edit().putBoolean("wifi_sleep_off", v).apply()

    // B4 «Типографика» / B39 «Чтение» — одни и те же значения (READER_SPEC: настройки общие).
    // Гарнитура — коды натива `Engine.FACE_SERIF/SANS/MONO`: читалка берёт их отсюда при
    // открытии книги и пишет сюда из листа B4 (R3.5). Умолчания — макет B3/B4 и умолчание
    // движка: антиква, кегль 32, интервал «Обычный», поля «Узкие» (вопрос 3 в DESIGN_QUESTIONS).
    /** Гарнитура: 0 «Антиква», 1 «Гротекс», 2 «Моно». */
    var textFace: Int
        get() = sp.getInt("text_face", 0)
        set(v) = sp.edit().putInt("text_face", v).apply()
    /** Кегль книги в дизайн-px (`TypographySheet.SIZE_MIN`…`SIZE_MAX`). */
    var textSize: Int
        get() = sp.getInt("text_size", 32)
        set(v) = sp.edit().putInt("text_size", v).apply()
    /** Интервал: 0 «Плотный», 1 «Обычный», 2 «Свободный» (`TypographySheet.INTERLINE`). */
    var textSpacing: Int
        get() = sp.getInt("text_spacing", 1)
        set(v) = sp.edit().putInt("text_spacing", v).apply()
    /** Поля: 0 «Узкие», 1 «Средние», 2 «Широкие» (`TypographySheet.MARGIN`). */
    var textMargins: Int
        get() = sp.getInt("text_margins", 0)
        set(v) = sp.edit().putInt("text_margins", v).apply()
    /**
     * Файл своего шрифта (B70 «Открыть шрифт…»), код гарнитуры `Engine.FACE_USER + n`.
     * Код живёт только в процессе, путь — здесь: при следующем старте шрифт регистрируется
     * заново, и `textFace` снова значит то же самое. Пусто — своего шрифта нет.
     */
    var userFontPath: String
        get() = sp.getString("user_font_path", "") ?: ""
        set(v) = sp.edit().putString("user_font_path", v).apply()

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
    /**
     * Что делают клавиши громкости (`adc-keys`) в читалке: 0 [VOLUME_PAGING] — листают,
     * 1 [VOLUME_SOUND] — громкость. Клавиши страниц (`gpio-keys`, `KEY_PAGEUP`/`KEY_PAGEDOWN`)
     * листают всегда: другой роли у них нет.
     */
    var volumeKeys: Int
        get() = sp.getInt("volume_keys", VOLUME_PAGING)
        set(v) = sp.edit().putInt("volume_keys", v).apply()

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

    // B2/B22 «Библиотека»: сортировка и фильтры
    /** Поле сортировки — `LibraryDb.SORT_*`. */
    var libSort: Int
        get() = sp.getInt("lib_sort", 1)
        set(v) = sp.edit().putInt("lib_sort", v).apply()
    /** Обратный порядок («Сначала старые»). */
    var libReverse: Boolean
        get() = sp.getBoolean("lib_reverse", false)
        set(v) = sp.edit().putBoolean("lib_reverse", v).apply()
    /** Маска форматов (`LibraryDb.FORMATS`), 0 — любые. */
    var libFormats: Int
        get() = sp.getInt("lib_formats", 0)
        set(v) = sp.edit().putInt("lib_formats", v).apply()
    /** Маска состояний (`1 shl STATUS_*`), 0 — любые. */
    var libStates: Int
        get() = sp.getInt("lib_states", 0)
        set(v) = sp.edit().putInt("lib_states", v).apply()
    var libHideRead: Boolean
        get() = sp.getBoolean("lib_hide_read", false)
        set(v) = sp.edit().putBoolean("lib_hide_read", v).apply()

    // B25 «Файлы»: 0 — по имени, 1 — по дате (новые сверху). Клавиша «сортировка» переключает.
    var filesSort: Int
        get() = sp.getInt("files_sort", 0)
        set(v) = sp.edit().putInt("files_sort", v).apply()

    // B6/B36 «Приложения»
    /** Пакеты, скрытые из списка («Скрыть из списка»); возврат — подзаголовок «скрыто N» на B6 или сброс настроек. */
    var hiddenApps: Set<String>
        get() = HashSet<String>(sp.getStringSet("hidden_apps", null) ?: emptySet<String>())
 set(v) = sp.edit().putStringSet("hidden_apps", HashSet<String>(v)).apply()
    /** Пакет, закреплённый на B1 вместо плитки «Аудиокниги»; пусто — не закреплено. */
    var pinnedApp: String
        get() = sp.getString("pinned_app", "") ?: ""
        set(v) = sp.edit().putString("pinned_app", v).apply()

    /** Когда последний раз проверяли обновления (мс от эпохи); 0 — ещё не проверяли (B43). */
    var updateCheckedAt: Long
        get() = sp.getLong("update_checked_at", 0L)
        set(v) = sp.edit().putLong("update_checked_at", v).apply()
    /** Результат последней проверки обновлений одной строкой для B43; пусто — нет данных. */
    var updateResult: String
        get() = sp.getString("update_result", "") ?: ""
        set(v) = sp.edit().putString("update_result", v).apply()

    // B76 «Обновления»
    /** «Проверять автоматически · раз в неделю при Wi-Fi»: пока только хранится (проверка — по кнопке). */
    var updateAuto: Boolean
        get() = sp.getBoolean("update_auto", true)
        set(v) = sp.edit().putBoolean("update_auto", v).apply()
    /** «Скачивать только при зарядке»: с зарядкой не спешим, но и не качаем на батарее. */
    var updateChargeOnly: Boolean
        get() = sp.getBoolean("update_charge_only", false)
        set(v) = sp.edit().putBoolean("update_charge_only", v).apply()
    /** `versionCode`, который человек отложил кнопкой «Позже»; 0 — ничего не отложено. */
    var updateSkipCode: Long
        get() = sp.getLong("update_skip_code", 0L)
        set(v) = sp.edit().putLong("update_skip_code", v).apply()



    /** Сброс к значениям макета (B43 «Сбросить настройки оболочки» — после диалога подтверждения). */
    fun reset() = sp.edit().clear().apply()

    companion object {
        /** Клавиши громкости листают страницы (умолчание: до R3 они только это и делали). */
        const val VOLUME_PAGING = 0
        /** Клавиши громкости отданы системе — звук, не листание. */
        const val VOLUME_SOUND = 1
        /** Подписи значения строки «Кнопки громкости» на B39 — по индексу [volumeKeys]. */
        val VOLUME_LABELS = arrayOf("Листание", "Громкость")
    }
}
