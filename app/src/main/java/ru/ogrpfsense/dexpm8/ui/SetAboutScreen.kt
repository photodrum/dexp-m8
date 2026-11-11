package ru.ogrpfsense.dexpm8.ui

import android.app.ActivityManager
import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.os.Environment
import android.os.StatFs

/**
 * B43 · Настройки · Об устройстве. Числа — из design/v5/B43.json.
 *
 * Читается: версия и номер сборки оболочки — `PackageInfo`, Android — `Build.VERSION`, число ядер —
 * `Runtime`, ОЗУ — `ActivityManager.MemoryInfo`, накопитель — `StatFs`.
 * Из макета без чтения: «Экран» («7,8″ · 1404×1872 · 300 ppi») и название процессора — модель одна.
 * Не рисуются: строка «Серийный номер» (скрыта по решению — личные данные), дата сборки в строке
 * версии (её нет в манифесте) и «Последняя проверка обновлений» (проверки ещё нет).
 * Ввод не обрабатывается, работает только «Назад» (лицензии, сброс и обновления — позже).
 */
class SetAboutScreen(act: EinkActivity) : Screen(act) {

    private val status = StatusBar(act, this, ink, icons)
    private val kit = SettingsKit(ink, icons)

    private var version = "Оболочка"
    private var android = "Android ${Build.VERSION.RELEASE}"
    private var cpu = "Rockchip PX30"
    private var mem = ""

    override fun render(c: Canvas) {
        ink.rect(c, 0f, 0f, W, H, T.BG)
        status.draw(c)
        kit.header(c, "Об устройстве", "Версия оболочки и сведения")

        kit.panel(c, 250f, 810f)
        ink.convexDark(c, 88f, 292f, 175f, 236f, 22f)
        ink.roundRect(c, 110f, 314f, 132f, 168f, 8f, T.G13)
        ink.text(c, "DEXP M8 Prudentia", 300f, T.base(310f, T.TS_TITLE, 1.25f), T.TS_TITLE, 700, T.G1)
        ink.text(c, version, 300f, T.base(390f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        ink.text(c, android, 300f, T.base(440f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)

        info(c, "Экран", "7,8″ · 1404×1872 · 300 ppi", 618f, true)
        info(c, "Процессор", cpu, 738f, true)
        info(c, "Память", mem, 858f, true)

        kit.section(c, "ОБСЛУЖИВАНИЕ", 1090f)
        kit.panel(c, 1138f, 350f)

        kit.key(c, 88f, 1172.5f)
        icons.draw(c, "info@39.8x42", 126.1f, 1210.6f, 39.8f, 42f)
        kit.title(c, "Лицензии", 1182f, 237f)
        kit.desc(c, "Открытые библиотеки и шрифты", 1235f, 237f)
        icons.draw(c, "next@11x23.8", 1302f, 1219.5f, 11f, 23.8f)
        kit.divider(c, 1320f, 237f, 1076f)

        kit.key(c, 88f, 1343.5f)
        icons.draw(c, "refresh", 130.5f, 1384.2f, 32.9f, 36.9f)
        kit.title(c, "Сбросить настройки оболочки", 1353f, 237f)
        kit.desc(c, "Книги и заметки останутся", 1406f, 237f)
        icons.draw(c, "next@11x23.8", 1302f, 1390.5f, 11f, 23.8f)

        ink.convexDark(c, 41f, 1693f, 1321f, 142f, 66f)
        icons.draw(c, "refresh@27.3x28.8", 107.2f, 1747.6f, 27.3f, 28.8f)
        ink.text(c, "Проверить обновления", 170f, T.base(1735.7f, T.TS_ACTION, 1.25f),
            T.TS_ACTION, 700, T.G15)
    }

    /** Строка сведений: название слева (42/700), значение справа до 1316 (34/600 G4), разделитель на y + 86. */
    private fun info(c: Canvas, name: String, value: String, y: Float, divider: Boolean) {
        kit.title(c, name, y)
        kit.valueInfo(c, value, y + 8f)
        if (divider) kit.divider(c, y + 86f)
    }

    private fun refresh() {
        val info = try {
            act.packageManager.getPackageInfo(act.packageName, 0)
        } catch (t: Throwable) {
            null
        }
        val name = info?.versionName ?: ""
        val code = if (info == null) 0L else if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        version = "Оболочка " + shortVersion(name) + (if (code > 0L) " · сборка $code" else "")
        android = "Android ${Build.VERSION.RELEASE}"
        cpu = "Rockchip PX30 · " + cores(Runtime.getRuntime().availableProcessors())

        val ram = try {
            val mi = ActivityManager.MemoryInfo()
            (act.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
            SettingsData.capacityGb(mi.totalMem).toString() + " ГБ ОЗУ"
        } catch (t: Throwable) {
            ""
        }
        val flash = try {
            val st = StatFs(Environment.getExternalStorageDirectory().path)
            SettingsData.capacityGb(st.blockCountLong * st.blockSizeLong).toString() + " ГБ встроенной"
        } catch (t: Throwable) {
            Trace.step("settings about FAIL: ${t.javaClass.simpleName}: ${t.message}")
            ""
        }
        mem = if (ram.isNotEmpty() && flash.isNotEmpty()) "$ram · $flash" else ram + flash
    }

    /** «0.1.0» → «0.1»: как в макете; остальное оставляем как есть. */
    private fun shortVersion(v: String): String = if (v.endsWith(".0") && v.count { it == '.' } == 2) v.dropLast(2) else v

    private fun cores(n: Int): String {
        val m = n % 100
        val w = when {
            m in 11..14 -> "ядер"
            n % 10 == 1 -> "ядро"
            n % 10 in 2..4 -> "ядра"
            else -> "ядер"
        }
        return "$n $w"
    }

    override fun onUp(x: Float, y: Float) {
        if (kit.isBack(x, y)) act.pop()
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
