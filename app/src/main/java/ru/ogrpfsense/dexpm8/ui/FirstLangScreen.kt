package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import android.provider.Settings
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ru.ogrpfsense.dexpm8.core.DictOnline
import ru.ogrpfsense.dexpm8.core.Zones

/**
 * B60 · Первый запуск · шаг 1 · язык и время (п. 13). Числа — из design/v5/B60.json.
 *
 *   раздел «ВРЕМЯ»        59,480; панель 41,528 1321×480
 *   «Время по сети»       88,572 / 625, тумблер 1177,584; разделитель 88,710
 *   «Часовой пояс»        88,761, значение до 1267.6, `next` 1305,777; разделитель 88,864
 *   «24-часовой формат»   88,906.5, тумблер 1177,897
 *   раздел «ЯЗЫК И ВВОД»  59,1040; панель 41,1088 1321×470
 *   «Язык оболочки», «Раскладки клавиатуры», «Словари» — как на B41
 *   кнопка «Далее»         41.1,1693.6 1320.8×141.7
 *
 * Пишется только «24-часовой формат» (`Settings.System.TIME_12_24`, право WRITE_SETTINGS) —
 * как на B41. «Время по сети» — `Settings.Global`, обычному приложению только чтение.
 * «Часовой пояс» ведёт на B71, «Словари» на B73, «Язык оболочки» и «Раскладки» — на B41:
 * своих экранов у них нет, а шаг не должен упираться в тупик.
 */
class FirstLangScreen(act: EinkActivity) : Screen(act) {

    private val status = StatusBar(act, this, ink, icons)
    private val kit = SettingsKit(ink, icons)
    private val first = FirstRunKit(ink, icons)

    private var autoTime = true

    /** Сеть для строки «Словари»: словарь онлайн, без сети он не откроется (B73). */
    private var online = false

    /** Шторку B5 на шаге мастера не открываем: панель управления здесь ни к чему. */
    override val shadeZone: Float get() = 0f

    override fun render(c: Canvas) {
        val now = Date()
        val is24 = DateFormat.is24HourFormat(act)
        val l = Locale.getDefault()

        ink.rect(c, 0f, 0f, W, H, T.BG)
        status.draw(c)
        first.header(c, 1, "Добро пожаловать",
            "Четыре коротких шага — и можно читать. Всё это потом меняется в «Настройках».")

        kit.section(c, "ВРЕМЯ", 480f)
        kit.panel(c, 528f, 480f)
        kit.title(c, "Время по сети", 572f)
        kit.desc(c, "Сверяется при подключении к Wi-Fi", 625f)
        kit.toggle(c, 584f, autoTime)
        kit.divider(c, 710f)

        kit.title(c, "Часовой пояс", 761f)
        kit.valueRow(c, Zones.label(act.sys.zone(), now), 769f)
        kit.next(c, 777f)
        kit.divider(c, 864f)

        kit.title(c, "24-часовой формат", 906.5f)
        kit.toggle(c, 897f, is24)

        kit.section(c, "ЯЗЫК И ВВОД", 1040f)
        kit.panel(c, 1088f, 470f)
        kit.title(c, "Язык оболочки", 1153.5f)
        kit.valueRow(c, SettingsData.langName(), 1161.5f)
        kit.next(c, 1169.5f)
        kit.divider(c, 1270f)

        kit.title(c, "Раскладки клавиатуры", 1316f)
        kit.valueRow(c, "Русская, английская", 1324f)
        kit.next(c, 1332f)
        kit.divider(c, 1414f)

        kit.title(c, "Словари", 1456.5f)
        kit.valueRow(c, if (online) "по сети" else "нет сети", 1464.5f)
        kit.next(c, 1472.5f)

        first.buttonDark(c, "Далее")
    }

    private fun refresh() {
        autoTime = Settings.Global.getInt(act.contentResolver, "auto_time", 1) != 0
        online = DictOnline(act).online()
    }

    override fun onUp(x: Float, y: Float) {
        when {
            // «Раскладки» (B72) не нажимаются: своего экрана в коде нет. «Часовой пояс» ведёт
            // на B71, «Язык оболочки» — на B41: своих экранов у них нет, а шаг не должен
            // упираться в тупик.
            kit.inRow(x, y, 710f, 154f) -> act.go(EinkActivity.SCR_SET_ZONE)
            kit.inRow(x, y, 864f, 144f) -> {
                val want = !DateFormat.is24HourFormat(act)
                act.withWriteSettings {
                    act.sys.setTime24(want)
                    redraw()
                }
            }
            kit.inRow(x, y, 1470f, 142f) -> act.go(EinkActivity.SCR_SET_DICT)
            first.isDarkFull(x, y) -> act.go(EinkActivity.SCR_FIRST_WIFI)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
        refresh()
    }

    override fun onDetachedFromWindow() {
        status.detach()
        super.onDetachedFromWindow()
    }
}
