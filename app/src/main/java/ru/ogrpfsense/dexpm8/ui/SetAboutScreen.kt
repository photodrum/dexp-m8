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

/**
 * B43 · Настройки · Об устройстве. Числа — из design/v5/B43.json.
 *
 * Читается: версия и номер сборки оболочки — `PackageInfo`, Android — `Build.VERSION`, число ядер —
 * `Runtime`, ОЗУ — `ActivityManager.MemoryInfo`, накопитель — `StatFs`.
 * Из макета без чтения: «Экран» («7,8″ · 1404×1872 · 300 ppi») и название процессора — модель одна.
 * Не рисуются: строка «Серийный номер» (скрыта по решению — личные данные) и дата сборки в строке
 * версии (её нет в манифесте).
 *
 * **Отступление от макета (решение 2026-11-18):** нижняя тёмная кнопка — «Автор» с чашкой кофе,
 * а не «Проверить обновления». Касание открывает экран «Автор» ([SetAuthorScreen]) с QR-кодом
 * на Бусти: приложение бесплатное, автора можно угостить чашкой кофе. Проверка обновлений
 * осталась доступной — её делает B76 ([SetUpdateScreen]) сам при открытии, дверь к нему —
 * строка «Обновления» в «ОБСЛУЖИВАНИИ». Поэтому состояние проверки (`checking`, `check`,
 * `UpdateCheck`) с этого экрана убрано: строка «Последняя проверка — …» приходит из `Prefs`.
 *
 * Строка версии: «Оболочка 0.2.0 · сборка 2, автор Денис Лампусов» — имя автора вписано по
 * просьбе человека, отдельной строки «Автор» в панели устройства нет.
 *
 * Остальной ввод: «Назад» и полоса «Сбросить настройки оболочки» (лицензии — позже, B75).
 */
class SetAboutScreen(act: EinkActivity) : Screen(act) {

    private val status = StatusBar(act, this, ink, icons)
    private val kit = SettingsKit(ink, icons)

    private val NAME = act.getString(R.string.app_name)

    private var version = ""
    private var android = "Android ${Build.VERSION.RELEASE}"
    private var cpu = "Rockchip PX30"
    private var mem = ""
    private var reset = 0   // 0 — обычно, 1 — ждём второго нажатия, 2 — сброшено

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
        // Панель выросла с 350 до 525: в макете две строки, у нас три — «Обновления» (B76)
        // добавлена решением 2026-11-18, шаг строк 175 как в макете.
        kit.panel(c, 1138f, 525f)

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
        kit.divider(c, 1491f, 237f, 1076f)

        // Строка B76: строка итога проверки из макета (слой «Проверка», y 1520) переехала сюда
        // описанием — отдельной строки под панелью больше нет, иначе она налезала на новую.
        kit.key(c, 88f, 1514.5f)
        icons.draw(c, "download", 128.9f, 1553.4f, 35.3f, 40.9f)
        kit.title(c, "Обновления", 1524f, 237f)
        kit.desc(c, ink.ellipsize(check.ifEmpty { "Проверить и установить новую версию" },
            1020f, T.TS_BODY_S, 600), 1577f, 237f)
        icons.draw(c, "next@11x23.8", 1302f, 1561.5f, 11f, 23.8f)

        // Кнопка «Автор» (решение 2026-11-18): QR-код на Бусти. Проверка обновлений — на B76.
        // Чашка вдвое крупнее иконки макета (60 вместо 30 по просьбе человека 2026-11-18):
        // центр остался на прежнем месте — 122.2, 1764.2 (середина кнопки по вертикали),
        // подпись сдвинулась на разницу 15 → 30, зазор иконка→текст прежний, 32.8.
        ink.convexDark(c, 41f, 1693f, 1321f, 142f, 66f)
        icons.draw(c, "cup", 92.2f, 1734.2f, 60f, 60f)
        ink.text(c, "Автор", 185f, T.base(1735.7f, T.TS_ACTION, 1.25f), T.TS_ACTION, 700, T.G15)
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
        // «Оболочка 0.2.0 · сборка 2, автор Денис Лампусов» — имя автора по просьбе человека.
        version = "Оболочка " + name + (if (code > 0L) " · сборка $code" else "") + ", автор " + AUTHOR
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
     * Пишет её B76 при каждой проверке; ширина в описании строки ограничена 1020 px.
     * Сама проверка здесь больше не запускается (решение 2026-11-18): кнопка внизу — «Автор».
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
            // Нижняя кнопка «Автор» (решение 2026-11-18): QR-код на Бусти.
            reset = 0
            act.go(EinkActivity.SCR_SET_AUTHOR)
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
            return
        }
        if (y >= 1491f && y <= 1660f) {
            // Строка B76 «Обновления»: проверка и установка живут на своём экране.
            reset = 0
            act.go(EinkActivity.SCR_SET_UPDATES)
            return
        }
        if (reset == 1) {
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

    private companion object {
        /** Автор оболочки — в строке версии: «… сборка 2, автор Денис Лампусов». */
        const val AUTHOR = "Денис Лампусов"
    }
}
