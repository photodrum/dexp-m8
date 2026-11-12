package ru.ogrpfsense.dexpm8.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Canvas
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import java.io.File
import java.text.Collator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Приложение из списка; [icon] — имя иконки из макета (см. [AppsScreen.iconFor]). */
class AppEntry(val label: String, val pkg: String, val cls: String, val icon: String)

/**
 * B6 · Приложения и B36 · Действия с приложением. Числа — из design/v5/B06.json и B36.json.
 *
 *   шапка      «Назад» 41.1,112.9; заголовок 175.5,105 60/700; подзаголовок 175.5,181 32/600 G4;
 *       «Поиск» 1255,112.9 (пока не нажимается — поиск в п. 9)
 *   плитка     поверхность 409.5×336.4 r 51.1875; колонки x 51.2 / 489.9 / 928.7;
 *       строки y 263.3 / 628.9 / 994.5 / 1360.1 → 3×4 = 12 на странице
 *   внутри     иконка 98.7×102.8 на +155.4,+73.2; подпись 38/700 на +209; «состояние» 30/600 G4 на +261
 *   «…» в макете нет; по решению пользователя (2026-11-11) — в правом верхнем углу плитки,
 *       точки вертикальные: горизонтальная `more` повёрнута на 90°; зона нажатия 100×100
 *   нажата     вдавленная форма (дно G11) и «Открывается…»
 *   пагинация  желоб 614.3,1766 175.5×29.2; сегмент 619.7,1771.5 54.8×16.5 G3 r 8.2
 *   лист B36   выпуклый лист с y=670 (захват, гнездо значка 59,730, название 60/700, подробности,
 *       пять строк через 150 px от y=942, кнопка «Удалить приложение» 59,1723); выбранная
 *       плитка нарисована вдавленной
 *
 * Список динамический: приложения с LAUNCHER-активностью, кроме самой оболочки и скрытых.
 * Известные пакеты получают свою иконку из макета ([iconFor]), остальные — общую «apps».
 */
class AppsScreen(act: EinkActivity) : Screen(act) {

    private class Info(val details: String, val system: Boolean, val cacheBytes: Long)

    private val status = StatusBar(act, this, ink, icons)
    private var apps: List<AppEntry> = emptyList()
    private var sizeBytes = 0L
    private var page = 0
    private var hiddenMode = false  // показаны скрытые приложения: нажатие возвращает в список
    private var hiddenCount = 0
    private var opening = -1    // индекс в apps плитки «Открывается…», -1 — нет

    // B36
    private var sheet: AppEntry? = null
    private var info: Info? = null
    private var cleared = false     // «Очищено» вместо «Освободится N» до закрытия листа
    private var away = false        // ушли в системный экран («О приложении», удаление)

    private fun pages() = Math.max(1, (apps.size + PER_PAGE - 1) / PER_PAGE)

    override fun render(c: Canvas) {
        // Под листом B36 экран затемнён (замечание 15), статус-бар и сам лист — нет.
        val dim = if (sheet != null) dimBegin(c) else -1
        ink.rect(c, 0f, 0f, W, H, T.BG)
        header(c)
        grid(c)
        pager(c)
        if (dim >= 0) dimEnd(c, dim)
        status.draw(c)
        if (sheet != null) drawSheet(c)
    }

    private fun header(c: Canvas) {
        ink.convex(c, 41.1f, 112.9f, 106.9f, 112.4f, 51.2f)
        icons.draw(c, "back", 72.2f, 144.9f, 45.7f, 47.6f)
        ink.text(c, if (hiddenMode) "Скрытые" else "Приложения", 175.5f,
            T.base(105f, T.TS_TITLE, 1.2f), T.TS_TITLE, 700, T.G1)
        var sub = if (hiddenMode) {
            "${apps.size} скрыто · нажмите, чтобы вернуть"
        } else if (sizeBytes > 0L) {
            "${installed()} установлено · ${SettingsData.gb1(sizeBytes)} ГБ"
        } else {
            "${installed()} установлено"
        }
        if (!hiddenMode && hiddenCount > 0) sub += " · скрыто $hiddenCount"
        ink.text(c, sub, 175.5f, T.base(181f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        ink.convex(c, 1255f, 112.9f, 106.9f, 112.4f, 51.2f)
        icons.draw(c, "searchDark", 1286.1f, 142.3f, 45.7f, 47.6f)
    }

    private fun grid(c: Canvas) {
        val first = page * PER_PAGE
 val last = Math.min(apps.size, first + PER_PAGE)
        val sel = sheet?.pkg
        for (i in first until last) {
            val k = i - first
     val a = apps[i]
     tile(c, a, COL_X[k % COLS], ROW_Y[k / COLS], i == opening, a.pkg == sel)
 }
    }

    /** [x], [y] — поверхность плитки; блик начинается на 2.8 левее и выше. */
    private fun tile(c: Canvas, a: AppEntry, x: Float, y: Float, opening: Boolean, selected: Boolean) {
        if (opening || selected) {
     ink.inset(c, x, y, TILE_W, TILE_H, T.R_TILE)
        } else {
     ink.convex(c, x - T.SURFACE_DX, y - T.SURFACE_DY, TILE_W + T.SHADOW_DX,
         TILE_H + T.SHADOW_DY, T.R_TILE)
        }
 if (a.icon in SMALL) {
     // Иконки 73.1×76.2 (общая, настройки, книги) — по центру места 98.7×102.8.
     icons.draw(c, a.icon, x + 168.2f, y + 86.5f, 73.1f, 76.2f)
        } else {
            icons.draw(c, a.icon, x + 155.4f, y + 73.2f, 98.7f, 102.8f)
        }
        val label = ink.ellipsize(a.label, TILE_W - 2 * LABEL_PAD, T.TS_LABEL_L, 700)
        ink.textCenter(c, label, x, TILE_W, T.base(y + 209f, T.TS_LABEL_L, 1.25f),
     T.TS_LABEL_L, 700, T.G1)
 if (opening) {
     ink.textCenter(c, "Открывается…", x, TILE_W, T.base(y + 261f, T.TS_META, 1.25f),
         T.TS_META, 600, T.G4)
        }
        if (!hiddenMode && a.pkg.isNotEmpty()) dots(c, x + TILE_W - 50f, y + 50f)
    }

    /** Вертикальное «…»: горизонтальная иконка `more` против часовой на 90° вокруг центра. */
    private fun dots(c: Canvas, cx: Float, cy: Float) {
        val saved = c.save()
 c.rotate(-90f, cx, cy)
        icons.draw(c, "more@53x55.2", cx - 26.5f, cy - 27.6f, 53f, 55.2f)
        c.restoreToCount(saved)
    }

    private fun pager(c: Canvas) {
        ink.inset(c, 614.3f, 1766f, 175.5f, 29.2f, T.R_TRACK)
        val w = SEG_TOTAL / pages()
 ink.roundRect(c, 619.7f + page * w, 1771.5f, w, 16.5f, 8.2265625f, T.G3)
    }

    // --------------------------------------------------------------- B36 · лист

    private fun drawSheet(c: Canvas) {
        val a = sheet ?: return
 val inf = info ?: return
        ink.convex(c, -3f, 670f, 1409f, 1332f, 66f)
        ink.roundRect(c, 644f, 695f, 117f, 11f, 5f, T.G11)
 ink.inset(c, 59f, 730f, 150f, 150f, 44f, T.G12)
 if (a.icon == "dict") {
     icons.draw(c, "dict@42.5x56.7", 115.8f, 778.1f, 42.5f, 56.7f)
 } else {
     icons.draw(c, a.icon, 102f, 773f, 64f, 64f)
        }
        ink.text(c, ink.ellipsize(a.label, SHEET_TEXT_W, T.TS_TITLE, 700), 249f,
     T.base(742f, T.TS_TITLE, 1.25f), T.TS_TITLE, 700, T.G1)
 ink.text(c, ink.ellipsize(inf.details, SHEET_TEXT_W, T.TS_BODY_S, 600), 249f,
     T.base(818f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)

        row(c, ROW_OPEN, "Открыть", null, true)
 icons.draw(c, "next@13.3x28.7", 111.5f, ROW_OPEN + 44.7f, 13.3f, 28.7f)

        row(c, ROW_PIN, "Закрепить на главном", "Плитка вместо «Аудиокниг»", true)
        icons.draw(c, "home", 99.3f, ROW_PIN + 41.4f, 35.3f, 37.6f)
 toggle(c, ROW_PIN + 21.5f, act.prefs.pinnedApp == a.pkg)

        row(c, ROW_HIDE, "Скрыть из списка", "Останется установленным", true)
        icons.draw(c, "close", 106f, ROW_HIDE + 47f, 22.1f, 24.3f)

        val cache = when {
     cleared -> "Очищено"
     inf.cacheBytes <= 0L -> "Кэш пуст"
     inf.cacheBytes < 1_000_000L -> "Освободится меньше 1 МБ"
     else -> "Освободится ${SettingsData.size(inf.cacheBytes)}"
 }
        row(c, ROW_CACHE, "Очистить кэш", cache, true)
        icons.draw(c, "refresh", 101.5f, ROW_CACHE + 40.7f, 32.9f, 36.9f)

 row(c, ROW_ABOUT, "О приложении", "Разрешения, память, версия", false)
 icons.draw(c, "info@39.8x42", 97.1f, ROW_ABOUT + 38.1f, 39.8f, 42f)

        if (!inf.system) {
     ink.convex(c, 59f, 1723f, 1291f, 112f, 51f)
     icons.draw(c, "trash@28.8x35.1", 112.6f, 1761.7f, 28.8f, 35.1f)
     ink.textCenter(c, "Удалить приложение", 106f, 1242f,
  T.base(1753.3f, T.TS_LABEL_L, 1.25f), T.TS_LABEL_L, 700, T.G1)
        }
    }

    /** Строка листа: круглая клавиша слева, название, описание, разделитель. Иконку рисует вызывающий. */
    private fun row(c: Canvas, top: Float, title: String, desc: String?, divider: Boolean) {
 ink.convex(c, 59f, top, 115f, 120f, 55f)
 val ty = if (desc == null) top + 31f else top + 9.5f
 ink.text(c, title, 208f, T.base(ty, T.TS_ACTION, 1.25f), T.TS_ACTION, 700, T.G1)
        if (desc != null) {
     ink.text(c, desc, 208f, T.base(top + 62.5f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        }
 if (divider) ink.rect(c, 208f, top + 132f, 1137f, 2f, T.G11)
    }

    /** Тумблер 139×73 в (1206, [y]); как в SettingsKit.toggle. */
    private fun toggle(c: Canvas, y: Float, on: Boolean) {
        ink.inset(c, 1206f, y, 139f, 73f, 37f, if (on) T.G3 else T.G12)
 ink.convex(c, if (on) 1276f else 1210f, y + 4f, 63f, 68f, 29f)
    }

    private fun buildInfo(a: AppEntry): Info {
        var ver = ""
 var date = ""
        var system = false
        try {
     val pi = act.packageManager.getPackageInfo(a.pkg, 0)
     ver = pi.versionName ?: ""
     date = SimpleDateFormat("d MMM", Locale("ru")).format(Date(pi.firstInstallTime)).trimEnd('.')
     system = (pi.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (t: Throwable) {
     Trace.step("apps info FAIL ${a.pkg}: ${t.javaClass.simpleName}: ${t.message}")
        }
 val parts = ArrayList<String>(3)
 if (ver.isNotEmpty()) parts.add("Версия $ver")
        parts.add(SettingsData.size(apkSize(a.pkg)))
 if (date.isNotEmpty()) parts.add("установлено $date")
        return Info(parts.joinToString(" · "), system, SettingsData.dirSize(cacheDir(a.pkg)))
    }

    /** Внешний кэш приложения (до Android 11 доступен по праву на файлы). Внутренний кэш чужого приложения недоступен. */
    private fun cacheDir(pkg: String) =
 File(Environment.getExternalStorageDirectory(), "Android/data/$pkg/cache")

    private fun openSheet(a: AppEntry) {
        sheet = a
        cleared = false
 info = buildInfo(a)
        act.bigFrame("sheet open")    // «Быстрый»: большой кадр — полной перерисовкой (8.2)
 redraw()
    }

    private fun closeSheet() {
        sheet = null
        info = null
        cleared = false
        act.bigFrame("sheet close")
        redraw()
    }

    /** После возврата из системного экрана: приложение могли удалить или обновить. */
    private fun refreshSheet() {
        val s = sheet ?: return
        val a = apps.firstOrNull { it.pkg == s.pkg }
 if (a == null) {
     sheet = null
     info = null
 } else {
     sheet = a
     info = buildInfo(a)
        }
    }

    // --------------------------------------------------------------- данные

    private fun load() {
        val hidden = query(act, true)
        hiddenCount = hidden.size
        if (hiddenMode && hidden.isEmpty()) hiddenMode = false
        apps = if (hiddenMode) hidden else withFiles(query(act))
        var sum = 0L
        for (a in apps) sum += apkSize(a.pkg)
        sizeBytes = sum
        page = Math.min(page, pages() - 1)
    }

    /** «N установлено» без синтетической плитки «Файлы»: она не приложение системы. */
    private fun installed(): Int = apps.count { it.pkg.isNotEmpty() }

    /**
     * Добавляет синтетическую плитку «Файлы» (B25) по алфавиту. Своего файлового менеджера в
     * системе может не быть — тогда без неё в «Файлы» не попасть (решение 9.6).
     */
    private fun withFiles(list: List<AppEntry>): List<AppEntry> {
        val out = ArrayList<AppEntry>(list.size + 1)
        val col = Collator.getInstance(Locale("ru"))
        var placed = false
        for (a in list) {
            if (!placed && col.compare(FILES_LABEL, a.label) < 0) {
                out.add(FILES_ENTRY)
                placed = true
            }
            out.add(a)
        }
        if (!placed) out.add(FILES_ENTRY)
        return out
    }

    private fun apkSize(pkg: String): Long = try {
 File(act.packageManager.getApplicationInfo(pkg, 0).sourceDir).length()
    } catch (t: Throwable) {
        0L
    }

    private fun reload() {
        try {
     load()
        } catch (t: Throwable) {
     Trace.step("apps load FAIL: ${t.javaClass.simpleName}: ${t.message}")
        }
    }


    // --------------------------------------------------------------- ввод

    override fun onUp(x: Float, y: Float) {
        if (opening >= 0) return
 if (sheet != null) {
     sheetUp(x, y)
            return
 }
        if (x >= 41.1f && x <= 148f && y >= 112.9f && y <= 225.3f) {
     if (hiddenMode) {
         hiddenMode = false
         page = 0
         reload()
         redraw()
     } else {
         act.pop()
     }
     return
        }
        // Подзаголовок «· скрыто N» открывает список скрытых.
        if (!hiddenMode && hiddenCount > 0 && x >= 175f && x <= 1000f && y >= 170f && y <= 240f) {
            hiddenMode = true
            page = 0
            reload()
            redraw()
            return
        }
        // Пагинация: нижняя полоса — левая половина назад, правая вперёд.
        if (y >= 1730f && y <= 1840f) {
     goPage(if (x < W / 2f) page - 1 else page + 1)
     return
        }
 val first = page * PER_PAGE
        for (k in 0 until Math.min(PER_PAGE, apps.size - first)) {
     val tx = COL_X[k % COLS]
     val ty = ROW_Y[k / COLS]
     if (x >= tx && x <= tx + TILE_W && y >= ty && y <= ty + TILE_H) {
         // Правый верхний угол плитки 100×100 — «…», остальное — запуск.
  if (hiddenMode) {
      val a = apps[first + k]
      act.prefs.hiddenApps = act.prefs.hiddenApps - a.pkg
      reload()
      redraw()
  } else if (apps[first + k].pkg.isEmpty()) {
      // Синтетическая плитка «Файлы» (решение 9.6): открывает B25, листа B36 у неё нет.
      act.go(EinkActivity.SCR_FILES)
  } else if (x >= tx + TILE_W - DOTS_HIT && y <= ty + DOTS_HIT) {
      openSheet(apps[first + k])
         } else {
      open(first + k)
         }
  return
     }
        }
    }

    /** Нажатия в листе B36: строки, «Удалить», вне листа — закрыть. */
    private fun sheetUp(x: Float, y: Float) {
        val a = sheet ?: return
        val inf = info ?: return
        if (y < SHEET_TOP) {
     closeSheet()
            return
 }
        if (y >= ROW_OPEN && y < ROW_OPEN + ROW_STEP) {
     closeSheet()
     launch(a)
        } else if (y >= ROW_PIN && y < ROW_PIN + ROW_STEP) {
     act.prefs.pinnedApp = if (act.prefs.pinnedApp == a.pkg) "" else a.pkg
     redraw()
 } else if (y >= ROW_HIDE && y < ROW_HIDE + ROW_STEP) {
     act.prefs.hiddenApps = act.prefs.hiddenApps + a.pkg
     if (act.prefs.pinnedApp == a.pkg) act.prefs.pinnedApp = ""
     sheet = null
     info = null
     act.bigFrame("sheet hide")
            reload()
     redraw()
        } else if (y >= ROW_CACHE && y < ROW_CACHE + ROW_STEP) {
     clearCache(a)
        } else if (y >= ROW_ABOUT && y < ROW_ABOUT + ROW_STEP) {
            away = true
     system(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, a.pkg)
        } else if (!inf.system && y >= 1723f && y <= 1835f) {
            away = true
     system(Intent.ACTION_DELETE, a.pkg)
        }
    }

    private fun system(action: String, pkg: String) {
        try {
     val it = Intent(action, Uri.parse("package:$pkg"))
     it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
     act.startActivity(it)
 } catch (t: Throwable) {
     Trace.step("apps $action FAIL $pkg: ${t.javaClass.simpleName}: ${t.message}")
            away = false
 }
    }

    /** Чужой внутренний кэш недоступен; чистим внешний (`Android/data/<пакет>/cache`). */
    private fun clearCache(a: AppEntry) {
 try {
            cacheDir(a.pkg).listFiles()?.forEach { it.deleteRecursively() }
        } catch (t: Throwable) {
     Trace.step("apps cache FAIL ${a.pkg}: ${t.javaClass.simpleName}: ${t.message}")
        }
        cleared = true
        info = buildInfo(a)
        redraw()
    }

    private fun goPage(p: Int) {
        val np = Math.max(0, Math.min(pages() - 1, p))
        if (np == page) return
 page = np
        redraw()
    }

    /** Сначала рисуем «нажата / Открывается…», затем запускаем — иначе отклика не видно. */
    private fun open(i: Int) {
        opening = i
        redraw()
        val a = apps[i]
        postDelayed({ launch(a) }, OPEN_DELAY_MS)
    }

    private fun launch(a: AppEntry) {
 try {
     launch(act, a)
 } catch (t: Throwable) {
     Trace.step("apps open FAIL ${a.pkg}: ${t.javaClass.simpleName}: ${t.message}")
     opening = -1
     redraw()
        }
    }

    override fun onAttachedToWindow() {
 super.onAttachedToWindow()
 status.attach()
        opening = -1
        reload()
    }

    override fun onDetachedFromWindow() {
        status.detach()
        super.onDetachedFromWindow()
    }

    /** Вернулись из приложения или системного экрана: снимаем «нажата», обновляем список и лист. */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
 super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && parent != null && (opening >= 0 || away)) {
            opening = -1
     away = false
     reload()
     refreshSheet()
     redraw()
 }
    }


    companion object {
 private const val COLS = 3
 private const val PER_PAGE = 12    // 4 строки: пятая в макете не помещается над пагинацией
        private const val TILE_W = 409.5f
        private const val TILE_H = 336.4f
        private const val LABEL_PAD = 20f
 private const val SEG_TOTAL = 164.6f    // желоб 170 минус зазоры
        private const val OPEN_DELAY_MS = 150L
        private const val DOTS_HIT = 100f
        private const val GENERIC = "apps"
        private val SMALL = setOf("apps", "settings", "library")

        /** Синтетическая плитка «Файлы» (B25): пакета нет, листа B36 у неё нет. */
        private const val FILES_LABEL = "Файлы"
        private val FILES_ENTRY = AppEntry(FILES_LABEL, "", "", "files@98.7x102.8")

        // B36: верх листа и верхи пяти строк (шаг 150)
 private const val SHEET_TOP = 670f
        private const val SHEET_TEXT_W = 1050f
 private const val ROW_STEP = 150f
        private const val ROW_OPEN = 942f
        private const val ROW_PIN = 1092f
        private const val ROW_HIDE = 1242f
        private const val ROW_CACHE = 1392f
        private const val ROW_ABOUT = 1542f

 private val COL_X = floatArrayOf(51.2f, 489.9f, 928.7f)
        private val ROW_Y = floatArrayOf(263.3f, 628.9f, 994.5f, 1360.1f)

        /** Число приложений — для счётчика плитки на B1. */
 fun count(ctx: Context): Int = try {
     query(ctx).size
        } catch (t: Throwable) {
     0
        }

        /** Приложение, закреплённое на B1; null — не закреплено или удалено. */
        fun pinned(ctx: Context, pkg: String): AppEntry? = try {
     if (pkg.isEmpty()) null else query(ctx).firstOrNull { it.pkg == pkg }
        } catch (t: Throwable) {
     null
 }

 /** Запуск приложения по записи — для плитки на B1 и листа B36. */
        fun launch(ctx: Context, a: AppEntry) {
     val it = Intent(Intent.ACTION_MAIN)
     it.addCategory(Intent.CATEGORY_LAUNCHER)
     it.component = ComponentName(a.pkg, a.cls)
     it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
     ctx.startActivity(it)
 }

        private fun query(ctx: Context, hiddenOnly: Boolean = false): List<AppEntry> {
            val pm = ctx.packageManager
     val hidden = (ctx as? EinkActivity)?.prefs?.hiddenApps ?: emptySet<String>()
     val intent = Intent(Intent.ACTION_MAIN)
     intent.addCategory(Intent.CATEGORY_LAUNCHER)
     val out = ArrayList<AppEntry>(32)
     for (ri in pm.queryIntentActivities(intent, 0)) {
         val ai = ri.activityInfo ?: continue
         if (ai.packageName == ctx.packageName) continue
         if ((ai.packageName in hidden) != hiddenOnly) continue
         var label = ri.loadLabel(pm)?.toString()?.trim().orEmpty()
         if (label.isEmpty()) label = ai.packageName
  out.add(AppEntry(label, ai.packageName, ai.name, iconFor(ai.packageName, label)))
            }
     val col = Collator.getInstance(Locale("ru"))
     out.sortWith { a, b -> col.compare(a.label, b.label) }
     return out
 }

 /** Иконка из макета по пакету и названию; нет совпадения — общая «apps». */
        private fun iconFor(pkg: String, label: String): String {
     val s = (pkg + " " + label).lowercase(Locale.ROOT)
            for ((key, icon) in ICONS) {
         for (k in key) if (s.contains(k)) return icon
            }
     return GENERIC
        }

        private val ICONS = arrayOf(
     arrayOf("browser", "chrome", "firefox", "браузер") to "browser",
            arrayOf("calculator", "calc", "калькулятор") to "calc",
            arrayOf("calendar", "календарь") to "calendar",
     arrayOf("deskclock", "clock", "часы") to "clock@98.7x102.8",
     arrayOf("gallery", "photos", "галерея") to "gallery@98.7x102.8",
     arrayOf("notes", "notepad", "заметки") to "notes",
     arrayOf("weather", "погода") to "weather",
     arrayOf("chess", "шахматы") to "chess",
     arrayOf("documentsui", "filemanager", "files", "файлы") to "files@98.7x102.8",
     arrayOf("dict", "словар") to "dict",
     arrayOf("music", "player", "audio", "плеер") to "player",
     arrayOf("vending", "market", "store", "магазин") to "store",
     arrayOf("android.settings", "настройки") to "settings",
            arrayOf("alreader", "neverland", "fbreader", "читалка") to "library"
        )
    }
}

