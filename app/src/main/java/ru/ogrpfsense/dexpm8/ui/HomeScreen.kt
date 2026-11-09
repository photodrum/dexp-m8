package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * B1 · Главный экран. Геометрия 1:1 с борда «B1 · Главный экран · Пластик».
 *
 *   статус-бар   51,26   1302×46
 *   дата         51,111  34/600 G4
 *   приветствие  51,150  80/300 G1
 *   карточка     41,275  1321×741 (выпуклая, r=51.19)
 *   кнопка       85,816  1233×142 (тёмная, r=65.81)
 *   плитки       41/721 × 1072/1438, 641×332
 *   статистика   0,1804  30/600 G4
 */
class HomeScreen(act: EinkActivity) : Screen(act) {

    private val dateFmt = SimpleDateFormat("EEEE, d MMMM", Locale("ru"))

    private var date = ""

    private val status = StatusBar(act, this, ink, icons)

    private val hits = ArrayList<Hit>(8)

    private class Hit(val id: Int, val x: Float, val y: Float, val w: Float, val h: Float)

    init {
        refreshDate()
    }

    private fun refreshDate() {
        val now = Date()
        val d = dateFmt.format(now)
        date = d.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("ru")) else it.toString() }
    }

    override fun layout(w: Float, h: Float) {
        super.layout(w, h)
        hits.clear()
        hits.add(Hit(EinkActivity.SCR_LIBRARY, 41f, 1072f, 641f, 332f))
        hits.add(Hit(EinkActivity.SCR_LIBRARY, 721f, 1072f, 641f, 332f))
        hits.add(Hit(EinkActivity.SCR_APPS, 41f, 1438f, 641f, 332f))
        hits.add(Hit(EinkActivity.SCR_SETTINGS, 721f, 1438f, 641f, 332f))
        hits.add(Hit(EinkActivity.SCR_READER, 85f, 816f, 1233f, 142f))
    }

    override fun render(c: Canvas) {
        ink.rect(c, 0f, 0f, W, H, T.BG)
        status.draw(c)
        header(c)
        card(c)
        continueButton(c)
        tiles(c)
        statistics(c)
    }

    private fun header(c: Canvas) {
        ink.text(c, date, 51f, 111f + 34f, T.TS_BODY, 600, T.G4)
        ink.text(c, "Добрый день", 51f, 150f + 78f, T.TS_GREETING, 300, T.G1)
    }

    // --------------------------------------------------------------- карточка

    private fun card(c: Canvas) {
        val x = 41f
        val y = 275f
        val w = 1321f
        val h = 741f
        ink.convex(c, x, y, w, h, T.R_TILE, T.G14, T.G15, T.G10)

        // Гнездо обложки — вдавленная форма 293×424
        val sx = 88f
        val sy = 322f
        ink.inset(c, sx, sy, 293f, 424f, T.R_COVER_SEAT, T.G12)
        ink.roundRect(c, sx + 18f, sy + 18f, 252f, 384f, T.R_COVER, T.G14)

        ink.paragraph(c, "Мастер\nи Маргарита", sx + 44f, sy + 55f, 222f,
            T.TS_META, 600, T.G1, 6f)
        ink.text(c, "М. Булгаков", sx + 44f, sy + 137f + 24f, T.TS_CAPTION, 600, T.G4)

        val tx = 431f
        ink.text(c, "Сейчас читаю", tx, 327f + 30f, T.TS_BODY_S, 600, T.G4)
        ink.text(c, "Мастер и Маргарита", tx, 364f + 56f, T.TS_TITLE, 500, T.G1)
        ink.text(c, "Михаил Булгаков · глава 13 из 32", tx, 440f + 36f, T.TS_LABEL, 600, T.G4)

        ink.text(c, "45", tx, 517f + 150f, T.TS_HUGE, 200, T.G1)
        ink.text(c, "%", 614f, 542f + 56f, T.TS_PERCENT, 400, T.G1)
        ink.paragraph(c, "прочитано\n≈ 3 ч 20 мин до конца", 695f, 578f + 20f, 333f,
            T.TS_BODY_S, 600, T.G4, 8f)

        // Полоса прогресса: желоб вдавленный, заполнение тёмное, без градиента
        val py = 717f
        ink.inset(c, tx, py, 885f, 29f, 14f, T.G12)
        ink.roundRect(c, tx + 4f, py + 5f, (885f - 8f) * 0.45f, 19f, 9f, T.G3)

        icons.draw(c, "arrowR", 1221f, 863f, 44f, 44f)
    }

    private fun continueButton(c: Canvas) {
        val x = 85f
        val y = 816f
        val w = 1233f
        val h = 142f
        ink.convexDark(c, x, y, w, h, T.R_BUTTON)
        ink.text(c, "Продолжить чтение", 146f,
            ink.baselineIn(y, h, T.TS_ACTION, 700), T.TS_ACTION, 700, T.G15)
    }

    // --------------------------------------------------------------- плитки

    private class Tile(
        val label: String, val count: String, val icon: String,
        val x: Float, val y: Float
    )

    private val tiles = arrayOf(
        Tile("Библиотека", "128 книг", "library", 41f, 1072f),
        Tile("Аудиокниги", "12 книг", "audio", 721f, 1072f),
        Tile("Приложения", "9 приложений", "apps", 41f, 1438f),
        Tile("Настройки", "устройство", "settings", 721f, 1438f)
    )

    private fun tiles(c: Canvas) {
        val tw = 641f
        val th = 332f
        for (t in tiles) {
            ink.convex(c, t.x, t.y, tw, th, T.R_TILE)
            val ix = t.x + 44f
            val iy = t.y + 46f
            ink.convex(c, ix, iy, 132f, 132f, 66f)
            icons.draw(c, t.icon, ix + 29f, iy + 28f, 73f, 76f)
            ink.text(c, t.label, t.x + 212f, t.y + 120f, T.TS_BODY_S, 700, T.G1)
            ink.text(c, t.count, t.x + 212f, t.y + 178f, T.TS_META, 600, T.G4)
        }
    }

    private fun statistics(c: Canvas) {
        ink.textCenter(c, "Сегодня 42 мин · 31 стр. · синхронизировано",
            0f, W, H - 22f, T.TS_META, 600, T.G4)
    }

    // --------------------------------------------------------------- ввод

    override fun onUp(x: Float, y: Float) {
        for (h in hits) {
            if (x >= h.x && x <= h.x + h.w && y >= h.y && y <= h.y + h.h) {
                act.go(h.id)
                return
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
        refreshDate()
    }

    override fun onDetachedFromWindow() {
        status.detach()
        super.onDetachedFromWindow()
    }
}
