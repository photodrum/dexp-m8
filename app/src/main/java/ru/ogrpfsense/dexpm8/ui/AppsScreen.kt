package ru.ogrpfsense.dexpm8.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import java.io.File
import java.text.Collator
import java.util.Locale

/**
 * B6 · Приложения. Числа — из design/v5/B06.json.
 *
 *   шапка      «Назад» 41.1,112.9; заголовок 175.5,105 60/700; подзаголовок 175.5,181 32/600 G4;
 *       «Поиск» 1255,112.9 (пока не нажимается — поиск в п. 9)
 *   плитка     поверхность 409.5×336.4 r 51.1875; колонки x 51.2 / 489.9 / 928.7;
 *       строки y 263.3 / 628.9 / 994.5 / 1360.1 → 3×4 = 12 на странице
 *   внутри     иконка 98.7×102.8 на +155.4,+73.2; подпись 38/700 на +209; «состояние» 30/600 G4 на +261
 *   нажата     вдавленная форма (дно G11) и «Открывается…»
 *   пагинация  желоб 614.3,1766 175.5×29.2; сегмент 619.7,1771.5 54.8×16.5 G3 r 8.2
 *
 * Список динамический: приложения с LAUNCHER-активностью, кроме самой оболочки.
 * Известные пакеты получают свою иконку из макета ([iconFor]), остальные — общую «apps».
 */
class AppsScreen(act: EinkActivity) : Screen(act) {

    private class App(val label: String, val pkg: String, val cls: String, val icon: String)

    private val status = StatusBar(act, this, ink, icons)
    private var apps: List<App> = emptyList()
    private var sizeBytes = 0L
    private var page = 0
    private var opening = -1    // индекс в apps плитки «Открывается…», -1 — нет

    private fun pages() = Math.max(1, (apps.size + PER_PAGE - 1) / PER_PAGE)

    override fun render(c: Canvas) {
        ink.rect(c, 0f, 0f, W, H, T.BG)
        status.draw(c)
        header(c)
        grid(c)
        pager(c)
    }

    private fun header(c: Canvas) {
        ink.convex(c, 41.1f, 112.9f, 106.9f, 112.4f, 51.2f)
        icons.draw(c, "back", 72.2f, 144.9f, 45.7f, 47.6f)
        ink.text(c, "Приложения", 175.5f, T.base(105f, T.TS_TITLE, 1.2f), T.TS_TITLE, 700, T.G1)
        val sub = if (sizeBytes > 0L) {
            "${apps.size} установлено · ${SettingsData.gb1(sizeBytes)} ГБ"
        } else {
            "${apps.size} установлено"
        }
        ink.text(c, sub, 175.5f, T.base(181f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        ink.convex(c, 1255f, 112.9f, 106.9f, 112.4f, 51.2f)
        icons.draw(c, "searchDark", 1286.1f, 142.3f, 45.7f, 47.6f)
    }

    private fun grid(c: Canvas) {
        val first = page * PER_PAGE
        val last = Math.min(apps.size, first + PER_PAGE)
        for (i in first until last) {
            val k = i - first
            tile(c, apps[i], COL_X[k % COLS], ROW_Y[k / COLS], i == opening)
        }
    }

    /** [x], [y] — поверхность плитки; блик начинается на 2.8 левее и выше. */
    private fun tile(c: Canvas, a: App, x: Float, y: Float, pressed: Boolean) {
        if (pressed) {
            ink.inset(c, x, y, TILE_W, TILE_H, T.R_TILE)
        } else {
            ink.convex(c, x - T.SURFACE_DX, y - T.SURFACE_DY, TILE_W + T.SHADOW_DX,
                TILE_H + T.SHADOW_DY, T.R_TILE)
        }
        if (a.icon == GENERIC) {
            // Общая иконка 73.1×76.2 — по центру места 98.7×102.8.
            icons.draw(c, a.icon, x + 168.2f, y + 86.5f, 73.1f, 76.2f)
        } else {
            icons.draw(c, a.icon, x + 155.4f, y + 73.2f, 98.7f, 102.8f)
        }
        val label = ink.ellipsize(a.label, TILE_W - 2 * LABEL_PAD, T.TS_LABEL_L, 700)
        ink.textCenter(c, label, x, TILE_W, T.base(y + 209f, T.TS_LABEL_L, 1.25f),
            T.TS_LABEL_L, 700, T.G1)
        if (pressed) {
            ink.textCenter(c, "Открывается…", x, TILE_W, T.base(y + 261f, T.TS_META, 1.25f),
                T.TS_META, 600, T.G4)
        }
    }

    private fun pager(c: Canvas) {
        ink.inset(c, 614.3f, 1766f, 175.5f, 29.2f, T.R_TRACK)
        val w = SEG_TOTAL / pages()
        ink.roundRect(c, 619.7f + page * w, 1771.5f, w, 16.5f, 8.2265625f, T.G3)
    }

    // --------------------------------------------------------------- данные

    private fun load() {
        apps = query(act)
        var sum = 0L
        for (a in apps) sum += apkSize(a.pkg)
        sizeBytes = sum
        page = Math.min(page, pages() - 1)
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
        if (x >= 41.1f && x <= 148f && y >= 112.9f && y <= 225.3f) {
            act.pop()
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
                open(first + k)
                return
            }
        }
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
        postDelayed({
            try {
                val it = Intent(Intent.ACTION_MAIN)
                it.addCategory(Intent.CATEGORY_LAUNCHER)
                it.component = ComponentName(a.pkg, a.cls)
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                act.startActivity(it)
            } catch (t: Throwable) {
                Trace.step("apps open FAIL ${a.pkg}: ${t.javaClass.simpleName}: ${t.message}")
                opening = -1
                redraw()
            }
        }, OPEN_DELAY_MS)
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

    /** Вернулись из приложения: снимаем «нажата» и обновляем список (могли установить/удалить). */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && parent != null && opening >= 0) {
            opening = -1
            reload()
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
        private const val GENERIC = "apps"

        private val COL_X = floatArrayOf(51.2f, 489.9f, 928.7f)
        private val ROW_Y = floatArrayOf(263.3f, 628.9f, 994.5f, 1360.1f)

        /** Число приложений — для счётчика плитки на B1. */
        fun count(ctx: Context): Int = try {
            query(ctx).size
        } catch (t: Throwable) {
            0
        }

        private fun query(ctx: Context): List<App> {
            val pm = ctx.packageManager
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            val out = ArrayList<App>(32)
            for (ri in pm.queryIntentActivities(intent, 0)) {
                val ai = ri.activityInfo ?: continue
                if (ai.packageName == ctx.packageName) continue
                var label = ri.loadLabel(pm)?.toString()?.trim().orEmpty()
                if (label.isEmpty()) label = ai.packageName
                out.add(App(label, ai.packageName, ai.name, iconFor(ai.packageName, label)))
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
            arrayOf("vending", "market", "store", "магазин") to "store"
        )
    }
}
