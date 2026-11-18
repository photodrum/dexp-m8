package ru.ogrpfsense.dexpm8.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.net.wifi.WifiManager
import android.os.Build
import ru.ogrpfsense.dexpm8.R
import ru.ogrpfsense.dexpm8.reader.Engine

/**
 * B63 · Первый запуск · готово (п. 13). Числа — из design/v5/B63.json.
 *
 *   шапка      «Готово» 51.2,110 (вместо «Шаг N из 4»), заголовок «Можно читать», пояснение,
 *              полоса прогресса во всю длину
 *   панель     41,480 1321×810: корпус книги 88,522 175×236 (тёмный, экран G13),
 *              модель 300,540 60/700, версия 300,620, система 300,670
 *   сведения   «Разрешения / Wi-Fi / Шрифт / Зоны касаний» — 88,848 шагом 120, значения до 1316
 *   кнопки     «Библиотека» 41.1,1693.6 553; «Начать чтение» 633.4 728.5
 *
 * Версия — `PackageInfo` («Оболочка 0.3.0 · сборка 3»), как на B43; имени автора здесь нет:
 * в макете строка короче (594 px). Обе кнопки ведут в библиотеку — книг на первом запуске ещё
 * нет, а «Продолжить» на B1 без книги ведёт туда же.
 */
class FirstDoneScreen(act: EinkActivity) : Screen(act) {

    private val status = StatusBar(act, this, ink, icons)
    private val first = FirstRunKit(ink, icons)
    private val kit = SettingsKit(ink, icons)

    private val name = act.getString(R.string.app_name)
    private var version = ""
    private var android = "Android ${Build.VERSION.RELEASE}"
    private var grantedCount = 0
    private var wifi = ""

    /** Шторку B5 на шаге мастера не открываем: панель управления здесь ни к чему. */
    override val shadeZone: Float get() = 0f

    override fun render(c: Canvas) {
        ink.rect(c, 0f, 0f, W, H, T.BG)
        status.draw(c)
        first.header(c, 0, "Можно читать",
            "Настройка завершена. Всё, что выбрано, меняется в «Настройках» в любой момент.")

        kit.panel(c, 480f, 810f)
        ink.convexDark(c, 88f, 522f, 175f, 236f, 22f)
        ink.roundRect(c, 110f, 544f, 132f, 168f, 8f, T.G13)
        ink.text(c, name, 300f, T.base(540f, T.TS_TITLE, 1.25f), T.TS_TITLE, 700, T.G1)
        ink.text(c, version, 300f, T.base(620f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        ink.text(c, android, 300f, T.base(670f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)

        info(c, "Разрешения", "$grantedCount из 4 выданы", 848f, true)
        info(c, "Wi-Fi", wifi, 968f, true)
        info(c, "Шрифт", faceText(), 1088f, true)
        info(c, "Зоны касаний", zoneText(), 1202f, false)

        first.buttonLight(c, "Библиотека")
        first.buttonDarkRight(c, "Начать чтение")
    }

    /** Строка сведений: название 42/700, значение 34/600 G4 до 1316, разделитель на +86. */
    private fun info(c: Canvas, title: String, value: String, y: Float, divider: Boolean) {
        ink.text(c, title, 88f, T.base(y, T.TS_ACTION, 1.25f), T.TS_ACTION, 700, T.G1)
        val v = ink.ellipsize(value, 500f, T.TS_BODY, 600)
        val w = ink.measure(v, T.TS_BODY, 600)
        ink.text(c, v, 1316f - w, T.base(y + 8f, T.TS_BODY, 1.25f), T.TS_BODY, 600, T.G4)
        if (divider) ink.rect(c, 88f, y + 86f, 1228f, 2f, T.G11)
    }

    /** «Литерата · 32» — как на B62/B39. */
    private fun faceText(): String {
        val p = act.prefs
        val n = when (p.textFace) {
            Engine.FACE_SANS -> "Manrope"
            Engine.FACE_MONO -> "Моно"
            Engine.FACE_PTSERIF -> "PT Serif"
            Engine.FACE_SERIF -> "Литерата"
            else -> "Свой шрифт"
        }
        return "$n · ${p.textSize}"
    }

    /** Подпись «Зоны касаний»: как в макете — «Классические». */
    private fun zoneText(): String = when (act.prefs.tapZones) {
        1 -> "Две половины"
        2 -> "Только вперёд"
        else -> "Классические"
    }

    private fun refresh() {
        val info = try {
            act.packageManager.getPackageInfo(act.packageName, 0)
        } catch (t: Throwable) {
            null
        }
        val vn = info?.versionName ?: ""
        val code = if (info == null) 0L
        else if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        version = "Оболочка " + vn + (if (code > 0L) " · сборка $code" else "")
        android = "Android ${Build.VERSION.RELEASE}"
        grantedCount = (if (act.hasStorage()) 1 else 0) + (if (act.sys.canWrite()) 1 else 0) +
            (if (act.sys.adminActive()) 1 else 0) + (if (homeIsUs()) 1 else 0)
        wifi = currentSsid() ?: if (act.sys.wifiOn()) "Не подключено" else "Выключено"
    }

    /** Мы ли домашний экран: разрешить HOME и сравнить пакет. */
    private fun homeIsUs(): Boolean = try {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        act.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName == act.packageName
    } catch (t: Throwable) {
        false
    }

    @Suppress("DEPRECATION")
    private fun currentSsid(): String? = try {
        val wm = act.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val s = wm.connectionInfo?.ssid ?: ""
        if (s.isEmpty() || s == "<unknown ssid>") null else s.trim('"')
    } catch (t: Throwable) {
        null
    }

    override fun onUp(x: Float, y: Float) {
        // Обе кнопки ведут в библиотеку: на первом запуске книг ещё нет.
        if (first.isLightButton(x, y) || first.isDarkRight(x, y)) {
            act.finishFirstRun(EinkActivity.SCR_LIBRARY)
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
