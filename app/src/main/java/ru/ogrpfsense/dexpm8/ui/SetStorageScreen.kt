package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import android.os.Environment
import android.os.StatFs
import java.util.Locale

/**
 * B42 · Настройки · Память. Числа — из design/v5/B42.json.
 *
 * Читается: объём и свободное место — `StatFs` общего хранилища; «Книги» — сумма размеров файлов
 * из `LibraryDb`; «Приложения» — APK и каталог данных; «Кэш» — внутренний и внешний кэш;
 * «Система» — остаток. «Аудиокниги» — всегда 0 (аудио в оболочке пока нет).
 * Не рисуется (данных нет): «Страницы PDF и DjVu» — описание, движков ещё нет.
 * «Файлы загрузок» — значение по умолчанию из макета. Ввод не обрабатывается, работает только
 * «Назад» (кнопки «Очистить» — шаг 4.6).
 */
class SetStorageScreen(act: EinkActivity) : Screen(act) {

    private val status = StatusBar(act, this, ink, icons)
    private val kit = SettingsKit(ink, icons)

    private var total = 0L
    private var free = 0L
    private var books = 0L
    private var apps = 0L
    private var cache = 0L
    private var avgBook = 0L
    private val deleteArchives = true

    override fun render(c: Canvas) {
        val used = (total - free).coerceAtLeast(0L)
        val audio = 0L
        val system = (used - books - apps - cache - audio).coerceAtLeast(0L)

        ink.rect(c, 0f, 0f, W, H, T.BG)
        status.draw(c)
        kit.header(c, "Память", "Место и кэш")

        kit.panel(c, 250f, 640f)
        val big = SettingsData.gb1(used)
        ink.text(c, big, 88f, T.base(280f, 120f, 1.1f), 120f, 700, T.G1)
        val bigW = ink.measure(big, 120f, 700)
        ink.text(c, "из ${SettingsData.capacityGb(total)} ГБ занято", 88f + bigW + 20f,
            T.base(349.2f, T.TS_ACTION, 1.25f), T.TS_ACTION, 700, T.G1)
        var left = "Свободно ${SettingsData.size(free)}"
        if (avgBook > 0L) left += " — примерно ${thousands(free / avgBook)} книг в FB2"
        ink.text(c, left, 88f, T.base(430f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)

        ink.inset(c, 88f, 500f, 1221f, 60f, 30f, T.G12)
        bar(c, used, longArrayOf(system, books, audio, apps, cache))

        legend(c, 88f, 610f, T.G3, "Система", system)
        legend(c, 708f, 610f, T.G6, "Книги", books)
        legend(c, 88f, 700f, T.G8, "Аудиокниги", audio)
        legend(c, 708f, 700f, T.G9, "Приложения", apps)
        legend(c, 88f, 790f, T.G10, "Кэш", cache)

        kit.section(c, "ОЧИСТКА", 930f)
        kit.panel(c, 978f, 510f)

        kit.key(c, 88f, 1026f)
        icons.draw(c, "gallery", 126.1f, 1067.4f, 39.8f, 35.4f)
        kit.title(c, "Кэш обложек", 1035.5f, 237f)
        kit.desc(c, SettingsData.size(cache), 1088.5f, 237f)
        kit.divider(c, 1160f, 237f, 1076f)
        clearButton(c, 1034.5f, 1059.8f)

        kit.key(c, 88f, 1180f)
        icons.draw(c, "files@42x37.6", 125f, 1220.3f, 42f, 37.6f)
        kit.title(c, "Страницы PDF и DjVu", 1189.5f, 237f)
        kit.divider(c, 1314f, 237f, 1076f)
        clearButton(c, 1188.5f, 1213.8f)

        kit.key(c, 88f, 1340.5f)
        icons.draw(c, "download", 128.3f, 1379.2f, 35.3f, 40.9f)
        kit.title(c, "Файлы загрузок", 1350f, 237f)
        kit.desc(c, "Удалять архивы после добавления", 1403f, 237f)
        kit.toggle(c, 1362f, deleteArchives, 1174f)
    }

    /** Шкала: сегменты по долям [parts] от занятого, ширина дорожки 1213 при заполнении `used / total`. */
    private fun bar(c: Canvas, used: Long, parts: LongArray) {
        if (total <= 0L || used <= 0L) return
        val colors = intArrayOf(T.G3, T.G6, T.G8, T.G9, T.G10)
        val full = 1213f * used / total
        var x = 92f
        var first = true
        for (i in parts.indices) {
            val w = full * parts[i] / used
            if (w < 1f) continue
            ink.roundRect(c, x, 506f, w, 48f, if (first) 24f else 4f, colors[i])
            first = false
            x += w
        }
    }

    /** Подпись категории: цвет 36×36, название слева, объём справа до x + 550. */
    private fun legend(c: Canvas, x: Float, y: Float, color: Int, name: String, bytes: Long) {
        ink.roundRect(c, x, y + 6f, 36f, 36f, 8f, color)
        ink.text(c, name, x + 56f, T.base(y, T.TS_LABEL, 1.25f), T.TS_LABEL, 600, T.G1)
        val v = SettingsData.size(bytes)
        val w = ink.measure(v, T.TS_LABEL, 700)
        ink.text(c, v, x + 550f - w, T.base(y, T.TS_LABEL, 1.25f), T.TS_LABEL, 700, T.G1)
    }

    private fun clearButton(c: Canvas, y: Float, labelTop: Float) {
        ink.convex(c, 1059f, y, 255f, 102f, 46f)
        ink.textCenter(c, "Очистить", 1062f, 250f, T.base(labelTop, T.TS_LABEL_L, 1.25f),
            T.TS_LABEL_L, 700, T.G1)
    }

    /** Число книг с пробелом-разделителем тысяч: «4 000». */
    private fun thousands(n: Long): String = String.format(Locale("ru"), "%,d", n).replace('\u00a0', ' ')

    private fun refresh() {
        try {
            val st = StatFs(Environment.getExternalStorageDirectory().path)
            total = st.blockCountLong * st.blockSizeLong
            free = st.availableBlocksLong * st.blockSizeLong
        } catch (t: Throwable) {
            Trace.step("settings storage FAIL: ${t.javaClass.simpleName}: ${t.message}")
        }
        books = act.db.totalSize()
        val n = act.db.count()
        avgBook = if (n > 0) books / n else 0L
        apps = java.io.File(act.applicationInfo.sourceDir).length() +
            SettingsData.dirSize(java.io.File(act.applicationInfo.dataDir))
        cache = SettingsData.dirSize(act.cacheDir) + SettingsData.dirSize(act.externalCacheDir)
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
