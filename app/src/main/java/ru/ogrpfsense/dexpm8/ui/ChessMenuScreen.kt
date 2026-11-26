package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import ru.ogrpfsense.dexpm8.chess.GameSave
import ru.ogrpfsense.dexpm8.chess.SavedGame

/**
 * B77 · Шахматы · меню (и B77а — «нет партии»). Числа — design/v5/B77.json, B77а.json.
 *
 *   шапка      как у настроек: «Назад» 41,113.5; «Шахматы» 176,105 60/700; подзаголовок 176,181
 *   панель     41.1,245.9 1320.8×(212.5 + 175.5·(строк − 1)), радиус R_TILE
 *   строка     клавиша 77.7,y 114.7×119.7; название 226.7,y+9.4 42/700; описание 226.7,y+61.7
 *              32/600 G4; разделитель 226.7,y+153.8 1096.9×1.8 G11; «next» 1295.2,y+35.6 43.9×45.7;
 *              первая строка на y 287.3, шаг 175.5
 *   пояснение  59, 849.4 − 175.5·(3 − строк) 32/600 G4
 *
 * **Отступления от макета (25.2):**
 * 1. Строки «По сети» нет — сети в оболочке ещё нет (п. 25.3); мёртвые клавиши убраны
 *    (решение 2026-11-21), панель короче на шаг строки, пояснение выше на столько же.
 * 2. Подзаголовок — «С компьютером» вместо «С компьютером или по сети» (по той же причине).
 *
 * «Продолжить партию» — только при сохранённой партии (`Prefs.chessSave`), первой строкой.
 */
class ChessMenuScreen(act: EinkActivity) : Screen(act) {

    private val status = StatusBar(act, this, ink, icons)
    private val kit = SettingsKit(ink, icons)
    private var saved: SavedGame? = null

    override fun render(c: Canvas) {
        ink.rect(c, 0f, 0f, W, H, T.BG)
        kit.header(c, "Шахматы", "С компьютером")

        val rows = if (saved != null) 2 else 1
        ink.convex(c, 41.1f, 245.9f, 1320.8f, 212.5f + (rows - 1) * ROW_STEP, T.R_TILE)
        var y = ROW_Y
        val s = saved
        if (s != null) {
            row(c, y, clockIcon(), "Продолжить партию", resumeText(s), true)
            y += ROW_STEP
        }
        row(c, y, "chess", "С компьютером", "Без сети · 8 уровней силы", false)

        ink.text(c, HINT, 59f, T.base(849.4f - (3 - rows) * ROW_STEP, T.TS_BODY_S, 1.25f),
            T.TS_BODY_S, 600, T.G4)
        status.draw(c)
    }

    private fun clockIcon() = if (icons.has("clock")) "clock" else "clock@98.7x102.8"

    private fun row(c: Canvas, y: Float, icon: String, title: String, desc: String, divider: Boolean) {
        ink.convex(c, 77.7f, y, 114.7f, 119.7f, 55f)
        icons.drawRound(c, icon, 77.7f, y, 114.7f, 119.7f, 53f, 55.2f)
        ink.text(c, title, 226.7f, T.base(y + 9.4f, T.TS_ACTION, 1.25f), T.TS_ACTION, 700, T.G1)
        ink.text(c, ink.ellipsize(desc, 987.2f, T.TS_BODY_S, 600), 226.7f,
            T.base(y + 61.7f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        if (divider) ink.rect(c, 226.7f, y + 153.8f, 1096.9f, 1.8f, T.G11)
        icons.draw(c, "next@53x53", 1295.2f, y + 35.6f, 43.9f, 45.7f)
    }

    /** «С компьютером, уровень 4 · ход 13 · сегодня, 14:20». */
    private fun resumeText(s: SavedGame): String =
        "С компьютером, уровень ${s.level} · ход ${s.moves.size / 2 + 1} · ${whenText(s.time)}"

    private fun whenText(ms: Long): String {
        val hm = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(ms))
        val now = Calendar.getInstance()
        val t = Calendar.getInstance().apply { timeInMillis = ms }
        val days = (now.get(Calendar.YEAR) * 366 + now.get(Calendar.DAY_OF_YEAR)) -
            (t.get(Calendar.YEAR) * 366 + t.get(Calendar.DAY_OF_YEAR))
        return when (days) {
            0 -> "сегодня, $hm"
            1 -> "вчера, $hm"
            else -> SimpleDateFormat("d MMM", Locale("ru")).format(Date(ms)).trimEnd('.') + ", " + hm
        }
    }

    override fun onUp(x: Float, y: Float) {
        if (kit.isBack(x, y)) {
            act.pop()
            return
        }
        if (x < 41f || x > 1362f) return
        var top = ROW_Y
        if (saved != null) {
            if (y >= top - 10f && y < top + ROW_STEP - 10f) {
                resume()
                return
            }
            top += ROW_STEP
        }
        if (y >= top - 10f && y < top + ROW_STEP - 10f) act.go(EinkActivity.SCR_CHESS_NEW)
    }

    private fun resume() {
        if (ChessSession.restore(act.prefs.chessSave)) {
            act.go(EinkActivity.SCR_CHESS)
        } else {
            // Сохранение повредилось: убираем его, как будто партии нет.
            Trace.step("chess: save broken -> dropped")
            act.prefs.chessSave = ""
            saved = null
            redraw()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
        saved = GameSave.decode(act.prefs.chessSave)
    }

    override fun onDetachedFromWindow() {
        status.detach()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val ROW_Y = 287.3f
        const val ROW_STEP = 175.5f
        const val HINT = "Партия сохраняется сама: из неё можно выйти в любой момент."
    }
}
