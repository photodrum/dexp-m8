package ru.ogrpfsense.dexpm8.ui

import android.app.ActivityManager
import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ru.ogrpfsense.dexpm8.R
import ru.ogrpfsense.dexpm8.core.UpdateCheck
import ru.ogrpfsense.dexpm8.ui.Trace

/**
 * B43 · Настройки · Об устройстве. Числа — из design/v5/B43.json.
 *
 * Читается: версия и номер сборки оболочки — `PackageInfo`, Android — `Build.VERSION`, число ядер —
 * `Runtime`, ОЗУ — `ActivityManager.MemoryInfo`, накопитель — `StatFs`.
 * Из макета без чтения: «Экран» («7,8″ · 1404×1872 · 300 ppi») и название процессора — модель одна.
 * Не рисуются: строка «Серийный номер» (скрыта по решению — личные данные) и дата сборки в строке
 * версии (её нет в манифесте).
 *
 * **«Проверить обновления»** (внизу) — [UpdateCheck]: манифест `update.json` сначала со своего
 * GitLab, при недоступности — с GitHub-зеркала. Проверка идёт в фоновом потоке, экран
 * перерисовывается один раз по результату (на E-Ink нельзя дёргать `invalidate` по таймеру).
 * Строка «Последняя проверка обновлений — …» взята из макета: время и итог хранятся в `Prefs`,
 * поэтому видны и после перезапуска оболочки. Сам APK пока не скачивается — установка это п. 13.
 *
 * Остальной ввод: «Назад» и полоса «Сбросить настройки оболочки» (лицензии — позже, B75).
 */
class SetAboutScreen(act: EinkActivity) : Screen(act) {

    private val status = StatusBar(act, this, ink, icons)
    private val kit = SettingsKit(ink, icons)

    private val NAME = act.getString(R.string.app_name)
    private val upd = UpdateCheck(act)

    private var version = ""
    private var android = "Android ${Build.VERSION.RELEASE}"
    private var cpu = "Rockchip PX30"
    private var mem = ""
    private var reset = 0   // 0 — обычно, 1 — ждём второго нажатия, 2 — сброшено

    /** 0 — нет данных, 1 — проверяем, 2 — готово (итог в [check]). */
    private var checking = 0
    /** Строка «Последняя проверка обновлений — …»; пусто — ещё не проверяли. */
    private var check = ""

    override fun render(c: Canvas) {
        ink.rect(c, 0f, 0f, W, H, T.BG)
        status.draw(c)
        kit.header(c, "Об устройстве", "Версия оболочки и сведения")

        kit.panel(c, 250f, 810f)
        ink.convexDark(c, 88f, 292f, 175f, 236f, 22f)
        ink.roundRect(c, 110f, 314f, 132f, 168f, 8f, T.G13)
        ink.text(c, NAME, 300f, T.base(310f, T.TS_TITLE, 1.25f), T.TS_TITLE, 700, T.G1)
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
        kit.desc(c, when (reset) {
            1 -> "Нажмите ещё раз — сбросить"
            2 -> "Сброшено"
            else -> "Книги и заметки останутся"
        }, 1406f, 237f)
        icons.draw(c, "next@11x23.8", 1302f, 1390.5f, 11f, 23.8f)

        // «Последняя проверка обновлений — …»: x 59, y 1520, 32/600 G4 (слой «Проверка» B43).
        // 795 px в макете — авто-ширина той подписи, а не предел: берём всю строку до полей.
        if (check.isNotEmpty()) {
            ink.text(c, ink.ellipsize(check, 1300f, T.TS_BODY_S, 600), 59f,
                T.base(1520f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        }

        ink.convexDark(c, 41f, 1693f, 1321f, 142f, 66f)
        icons.draw(c, "refresh@27.3x28.8", 107.2f, 1747.6f, 27.3f, 28.8f)
        val label = when (checking) {
            1 -> "Проверяем…"
            2 -> "Проверить снова"
            else -> "Проверить обновления"
        }
        ink.text(c, label, 170f, T.base(1735.7f, T.TS_ACTION, 1.25f),
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
        version = "Оболочка " + name + (if (code > 0L) " · сборка $code" else "")
        android = "Android ${Build.VERSION.RELEASE}"
        cpu = "Rockchip PX30 · " + cores(Runtime.getRuntime().availableProcessors())

        val ram = try {
            val mi = ActivityManager.MemoryInfo()
            (act.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
            SettingsData.capacityGb(mi.totalMem).toString() + " ГБ ОЗУ"
        } catch (t: Throwable) {
            ""
        }
        val flash = SettingsData.storage()?.let { SettingsData.capacityGb(it.first).toString() + " ГБ встроенной" } ?: ""
        mem = if (ram.isNotEmpty() && flash.isNotEmpty()) "$ram · $flash" else ram + flash

        check = storedLine()
    }

    // ------------------------------------------------------------- обновления

    /**
     * Строка из `Prefs`: «Последняя проверка — сегодня, 09:12 · обновлений нет».
     * Ширина ограничена 795 px, как в макете B43 (слой «Проверка»), поэтому длинные итоги
     * вроде «есть версия 0.2.0» обрезаются многоточием, а не наезжают на край экрана.
     */
    private fun storedLine(): String {
        val at = act.prefs.updateCheckedAt
        if (at <= 0L) return ""
        val res = act.prefs.updateResult
        val head = "Последняя проверка — " + when {
            isToday(at) -> "сегодня, " + SimpleDateFormat("HH:mm", Locale("ru")).format(Date(at))
            else -> SimpleDateFormat("d MMMM, HH:mm", Locale("ru")).format(Date(at))
        }
        return if (res.isEmpty()) head else "$head · $res"
    }

    private fun isToday(at: Long): Boolean {
        val a = java.util.Calendar.getInstance().apply { timeInMillis = at }
        val b = java.util.Calendar.getInstance()
        return a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) &&
            a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)
    }

    /**
     * Проверка в фоне: сеть в главном потоке на Android 8.1 роняет `NetworkOnMainThreadException`.
     * Пока идёт — кнопка пишет «Проверяем…», после — один `redraw()` с итогом.
     */
    private fun checkUpdates() {
        if (checking == 1) return
        checking = 1
        redraw()
        Thread({
            val f = upd.fetch()
            val res: String
            if (f == null) {
                res = upd.why ?: "нет ответа"
            } else if (upd.isNewer(f)) {
                res = "есть версия ${f.versionName}"
            } else {
                res = "обновлений нет"
            }
            Trace.step("update: $res")
            act.prefs.updateCheckedAt = System.currentTimeMillis()
            act.prefs.updateResult = res
            post {
                checking = 2
                check = storedLine()
                redraw()
            }
        }, "update-check").start()
    }

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
        if (kit.isBack(x, y)) {
            act.pop()
            return
        }
        if (y >= 1693f && y <= 1835f) {
            checkUpdates()
            return
        }
        if (y >= 1320f && y <= 1490f) {
            // Сброс — со вторым нажатием вместо диалога: сбрасываются только настройки оболочки.
            if (reset == 1) {
                act.prefs.reset()
                reset = 2
            } else {
                reset = 1
            }
            redraw()
        } else if (reset == 1) {
            reset = 0
            redraw()
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
