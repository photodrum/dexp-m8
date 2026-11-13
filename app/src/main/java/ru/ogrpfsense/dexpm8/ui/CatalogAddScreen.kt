package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import ru.ogrpfsense.dexpm8.core.LibraryDb
import ru.ogrpfsense.dexpm8.core.Opds

/**
 * B15 · Добавить каталог / B49 · Адрес каталога (символы). Числа — design/v5/B15.json, B49.json.
 *
 *   шапка      «Назад» 41.1,112.9 106.9×112.4; «Новый каталог» 175.5,105 60/700;
 *              «Сетевые каталоги» 175.5,181 32/600 G4
 *   поля       метка 32/700 G4 в 43.9,y; углубление 43.9,y+49.4 1316.2×102.4;
 *              значение 38/600 в 80.4,y+74.4; фокус — обводка G2 2 px
 *   вход       выпуклый 41.1,640.8 1320.8×170.9: «lock» 87.8,695.6 51.2×53.3,
 *              «Требуется вход» 175.5,678 40/700, подпись 175.5,726.5 32/600 G4,
 *              тумблер 1177.3,687.4 138.9×73.1
 *   проверка   вдавленная 43.9,1074.9 1316.2×204.8: круг 87.8,1139 73.1×73.1 (G3),
 *              «check» 102.4,1153.2; «Каталог найден» 197.4,1106.1 40/800;
 *              результат 197.4,1160.1 34/600 G4
 *   кнопки     «Проверить» 41.1,1693.6 553×141.7; «Добавить каталог» 633.4,1693.6 728.5×141.7
 *
 * Клавиатура — [Keyboard] с раскладкой символов (B49): по нажатию на поле внизу открывается
 * лист, как у полок (B48). Поля ввода: адрес, название, логин, пароль. Проверка делает
 * [Opds.probe] в фоновом потоке — сеть на E-Ink не должна вешать экран.
 */
class CatalogAddScreen(act: EinkActivity) : Screen(act) {

    private val status = StatusBar(act, this, ink, icons)
    private val opds = Opds(act)
    private val db = act.db
    private val kb = Keyboard(ink)

    private var url = "http://"
    private var name = ""
    private var user = ""
    private var pass = ""
    private var needAuth = false
    private var field = F_URL
    private var keyboard = false
    private var checking = false
    private var result = ""
    private var resultOk = false
    private var error = ""

    override fun render(c: Canvas) {
        ink.rect(c, 0f, 0f, W, H, T.BG)
        status.draw(c)
        header(c)
        fieldBox(c, F_URL, "Адрес каталога", url, 250.4f, if (url == "http://") "http://example.org/opds" else null)
        fieldBox(c, F_NAME, "Название", name, 440.6f, "Будет взято из каталога")
        authRow(c)
        if (needAuth) {
            fieldBox(c, F_USER, "Логин", user, 842.7f, null, w = 636.2f)
            fieldBox(c, F_PASS, "Пароль", stars(), 842.7f, null, x = 723.9f, w = 636.2f)
        }
        probeBox(c)
        buttons(c)
        if (keyboard) {
            ink.convex(c, -3f, SHEET_TOP, 1409f, 640f, 66f)
            ink.roundRect(c, 644f, 905f, 117f, 11f, 5f, T.G11)
            kb.draw(c)
        }
    }

    private fun header(c: Canvas) {
        ink.convex(c, 41.1f, 112.9f, 106.9f, 112.4f, R_BACK)
        icons.draw(c, "back", 71.3f, 143f, 47.5f, 49.5f)
        ink.text(c, "Новый каталог", 175.5f, T.base(105f, T.TS_TITLE, 1.2f), T.TS_TITLE, 700, T.G1)
        ink.text(c, "Сетевые каталоги", 175.5f, T.base(181f, T.TS_BODY_S, 1.25f),
            T.TS_BODY_S, 600, T.G4)
    }

    /** Одно поле B15: метка, углубление 102.4, значение 38/600; фокус — обводка G2. */
    private fun fieldBox(c: Canvas, f: Int, label: String, value: String, top: Float,
                         placeholder: String?, x: Float = 43.9f, w: Float = 1316.2f) {
        ink.text(c, label, x, T.base(top, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 700, T.G4)
        ink.inset(c, x, top + 49.4f, w, 102.4f, 32.9f)
        if (field == f) focusRing(c, x, top + 49.4f, w, 102.4f, 32.9f)
        val shown = if (f == F_PASS) stars() else value
        if (shown.isEmpty() && placeholder != null) {
            ink.text(c, ink.ellipsize(placeholder, w - 80f, T.TS_LABEL_L, 500), x + 36.5f,
                T.base(top + 74.4f, T.TS_LABEL_L, 1.25f), T.TS_LABEL_L, 500, T.G6)
        } else {
            val s = ink.ellipsize(shown, w - 80f, T.TS_LABEL_L, 600)
            ink.text(c, s, x + 36.5f, T.base(top + 74.4f, T.TS_LABEL_L, 1.25f), T.TS_LABEL_L, 600, T.G1)
            if (field == f) {
                ink.rect(c, x + 36.5f + ink.measure(s, T.TS_LABEL_L, 600) + 2f,
                    top + 76.5f, 3.7f, 43.9f, T.G1)
            }
        }
    }

    /** «Требуется вход» с тумблером 138.9×73.1 (включён — дно G3, ручка справа). */
    private fun authRow(c: Canvas) {
        ink.convex(c, 41.1f, 640.8f, 1320.8f, 170.9f, T.R_TILE)
        icons.draw(c, "lock", 87.8f, 695.6f, 51.2f, 53.3f)
        ink.text(c, "Требуется вход", 175.5f, T.base(678f, 40f, 1.25f), 40f, 700, T.G1)
        ink.text(c, "Логин и пароль от сервера", 175.5f, T.base(726.5f, T.TS_BODY_S, 1.25f),
            T.TS_BODY_S, 600, T.G4)
        toggle(c, 1177.3f, 687.4f, needAuth)
    }

    private fun toggle(c: Canvas, x: Float, y: Float, on: Boolean) {
        ink.inset(c, x, y, 138.9f, 73.1f, 36.6f, if (on) T.G3 else T.G12)
        val kx = if (on) x + 70.4f else x + 2.8f
        ink.convex(c, kx, y + 4.5f, 63.1f, 68.6f, 34.3f)
    }

    /**
     * Рамка фокуса поля (B49: обводка G2 2 px по рамке углубления). У [Ink.roundRect] нет
     * обводки, а заводить Paint ради одного экрана незачем — рамка из четырёх полос.
     */
    private fun focusRing(c: Canvas, x: Float, y: Float, w: Float, h: Float, r: Float) {
        ink.roundRect(c, x, y, w, h, r, T.G2)
        ink.roundRect(c, x + RING, y + RING, w - 2 * RING, h - 2 * RING, r - RING, T.G11)
    }

    /** «Результат проверки»: найден каталог и сколько в нём книг, либо причина отказа. */
    private fun probeBox(c: Canvas) {
        if (result.isEmpty() && error.isEmpty() && !checking) return
        ink.inset(c, 43.9f, 1074.9f, 1316.2f, 204.8f, T.R_TILE)
        ink.flat(c, 87.8f, 1139f, 73.1f, 73.1f, 36.6f, T.G3)
        icons.draw(c, if (resultOk) "check@43.9x43.9" else "info@39.8x42", 102.4f, 1153.2f,
            43.9f, 43.9f)
        val head = when {
            checking -> "Проверяем каталог…"
            resultOk -> "Каталог найден"
            else -> "Каталог не ответил"
        }
        ink.text(c, head, 197.4f, T.base(1106.1f, 40f, 1.25f), 40f, 800, T.G1)
        val note = if (resultOk) result else error
        ink.text(c, ink.ellipsize(note, 1120f, T.TS_BODY, 600), 197.4f,
            T.base(1160.1f, T.TS_BODY, 1.25f), T.TS_BODY, 600, T.G4)
    }

    private fun buttons(c: Canvas) {
        ink.convex(c, 41.1f, 1693.6f, 553f, 141.7f, T.R_BUTTON)
        icons.draw(c, "refresh@43.9x45.7", 84.1f, 1739.3f, 43.9f, 45.7f)
        ink.text(c, "Проверить", 146.2f, T.base(1736.2f, T.TS_ACTION, 1.25f), T.TS_ACTION, 700, T.G1)
        ink.convexDark(c, 633.4f, 1693.6f, 728.5f, 141.7f, T.R_BUTTON)
        ink.text(c, "Добавить каталог", 687.4f, T.base(1736.2f, T.TS_ACTION, 1.25f),
            T.TS_ACTION, 700, T.G15)
        icons.draw(c, "check@43.9x43.9", 1265.1f, 1740.2f, 43.9f, 43.9f)
    }

    private fun stars(): String = "*".repeat(pass.length.coerceAtMost(24))

    // ------------------------------------------------------------- касания

    override fun onUp(x: Float, y: Float) {
        if (keyboard) {
            keyUp(x, y)
            return
        }
        if (inRect(x, y, 41.1f, 112.9f, 106.9f, 112.4f)) {
            act.pop()
            return
        }
        if (inRect(x, y, 43.9f, 299.8f, 1316.2f, 102.4f)) { open(F_URL); return }
        if (inRect(x, y, 43.9f, 489.9f, 1316.2f, 102.4f)) { open(F_NAME); return }
        if (inRect(x, y, 1160f, 680f, 180f, 90f)) {
            needAuth = !needAuth
            redraw()
            return
        }
        if (needAuth) {
            if (inRect(x, y, 43.9f, 892.1f, 636.2f, 102.4f)) { open(F_USER); return }
            if (inRect(x, y, 723.9f, 892.1f, 636.2f, 102.4f)) { open(F_PASS); return }
        }
        if (inRect(x, y, 41.1f, 1693.6f, 553f, 141.7f)) { probe(); return }
        if (inRect(x, y, 633.4f, 1693.6f, 728.5f, 141.7f)) { save(); return }
    }

    /** Касание при открытой клавиатуре: клавиши, «Готово», мимо листа — закрыть. */
    private fun keyUp(x: Float, y: Float) {
        if (y < SHEET_TOP) {
            close()
            return
        }
        val k = kb.hit(x, y) ?: return
        when (k.kind) {
            Keyboard.K_DONE -> {
                close()
                return
            }
            Keyboard.K_DEL -> {
                setValue(value().dropLast(1))
            }
            else -> {
                val s = kb.press(k)
                if (s != null) setValue(value() + s)
            }
        }
        redraw()
    }

    private fun open(f: Int) {
        field = f
        keyboard = true
        kb.setAction("Готово")
        redraw()
    }

    private fun close() {
        keyboard = false
        redraw()
    }

    private fun value(): String = when (field) {
        F_NAME -> name
        F_USER -> user
        F_PASS -> pass
        else -> url
    }

    private fun setValue(v: String) {
        when (field) {
            F_NAME -> name = v.take(80)
            F_USER -> user = v.take(60)
            F_PASS -> pass = v.take(60)
            else -> url = v.take(300)
        }
        error = ""
        result = ""
    }

    // ------------------------------------------------------------- проверка

    /** [Opds.probe] в фоне: проверить адрес и, если вышло, подставить название из фида. */
    private fun probe() {
        val u = LibraryDb.normalizeUrl(url)
        if (u.isEmpty() || u == "http://") {
            error = "Введите адрес каталога"
            resultOk = false
            redraw()
            return
        }
        checking = true
        resultOk = false
        error = ""
        result = ""
        redraw()
        val auth = if (needAuth) Opds.Auth(user, pass) else null
        Thread({
            val p = opds.probe(u, auth)
            post {
                checking = false
                if (p != null) {
                    resultOk = true
                    val n = if (p.books > 0) Words.books(p.books) else "книг не видно"
                    result = "«" + p.title + "» · " + n + (if (p.formats.isEmpty()) "" else " · " + p.formats)
                    if (name.isEmpty()) name = p.title
                } else {
                    error = opds.lastWhy ?: "Сервер не ответил"
                }
                redraw()
            }
        }, "opds-probe").start()
    }

    /**
     * Добавить каталог в базу и вернуться к списку B14. Дубликат адреса — сообщение в поле
     * проверки, добавление не делается (адрес в базе уникален).
     */
    private fun save() {
        val u = LibraryDb.normalizeUrl(url)
        if (u.isEmpty() || u == "http://") {
            error = "Введите адрес каталога"
            resultOk = false
            redraw()
            return
        }
        val id = db.addCatalog(u, name, if (needAuth) user else "", if (needAuth) pass else "")
        if (id < 0) {
            error = "Такой каталог уже добавлен"
            resultOk = false
            redraw()
            return
        }
        act.catalogId = id
        act.pop()
    }

    private fun inRect(x: Float, y: Float, rx: Float, ry: Float, rw: Float, rh: Float) =
        x >= rx && x <= rx + rw && y >= ry && y <= ry + rh

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
        // Правка существующего каталога: адрес и название уже есть в базе.
        val c0 = db.catalog(act.catalogId)
        if (c0 != null) {
            url = c0.url
            name = c0.title
            user = c0.user
            pass = c0.pass
            needAuth = c0.user.isNotEmpty() || c0.pass.isNotEmpty()
        }
        redraw()
    }

    override fun onDetachedFromWindow() {
        status.detach()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val R_BACK = 51.2f
        const val SHEET_TOP = 1376f
        const val F_URL = 0
        const val F_NAME = 1
        const val F_USER = 2
        const val F_PASS = 3
        const val RING = 2f
    }
}