package ru.ogrpfsense.dexpm8.ui

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.os.SystemClock
import ru.ogrpfsense.dexpm8.core.BtDevices

/**
 * B74 · Настройки · Bluetooth-устройства (п. 13). Числа — из design/v5/B74.json; дверь — строка
 * «Сопряжённые устройства» на B38. Вызовы системы — [BtDevices].
 *
 *   панель «Bluetooth»   41,250 1321×160: название 88,279, описание 88,332.5, тумблер 1177,292.4
 *   «СОПРЯЖЁННЫЕ»        подпись 59,450; панель 41,498
 *   «ДОСТУПНЫЕ»          подпись на 40 ниже панели сопряжённых, панель ещё на 48 ниже
 *   строка устройства    шаг 152: название +29, описание +82.5, разделитель 88,+153 1228×2;
 *                        `check` 42×43.9 на 1282.7,+56.1; `next` на 1305,+66.1;
 *                        `refresh@35.7x40` на 1280.3,+57.5. Панель из n строк — n·152 + 8.
 *
 * Касания: тумблер — Bluetooth вкл/выкл; остальная верхняя панель — «видна другим» на 2 мин;
 * сопряжённое — подключить/отключить; доступное — сопрячь и сразу подключить; последняя строка —
 * искать снова (без права на местоположение сначала спрашивает его: на 8.1 без него
 * `ACTION_FOUND` не приходит).
 *
 * Отступления от макета (в макете таких состояний нет):
 *  - Bluetooth выключен — вместо разделов абзац «Включите Bluetooth…»;
 *  - сопряжённых нет — одна строка «Нет сопряжённых устройств»;
 *  - без прокрутки влезает 8 строк (низ 1818): сопряжённых — до [MAX_PAIRED], доступных — сколько
 *    осталось (с именем, по силе сигнала), остальные — «ещё N» в строке поиска;
 *  - «Забыть устройство» не сделано: стрелка `next` у сопряжённого, как в макете, но экрана
 *    устройства в макете нет — вопрос в docs/DESIGN_QUESTIONS.md.
 *
 * Перерисовка: только по событиям Bluetooth и раз в секунду, пока идёт поиск, подключение или
 * видимость, — и лишь если изменился какой-то текст ([snapshot]).
 */
class SetBtScreen(act: EinkActivity) : Screen(act) {

    private companion object {
        const val PANEL_TOP = 250f
        const val PANEL_H = 160f
        const val PAIRED_TOP = 498f
        const val ROW = 152f

        /** Подпись раздела «ДОСТУПНЫЕ» — на 40 ниже низа панели, панель — ещё на 48. */
        const val GAP_LABEL = 40f
        const val GAP_PANEL = 48f

        /** Строк на экране всего (низ 498 + 8·152 + 104 = 1818 при высоте 1872). */
        const val MAX_ROWS = 8
        const val MAX_PAIRED = 5

        const val TICK_MS = 1000L

        /** Подключение или сопряжение без ответа столько — «Не удалось…». */
        const val WAIT_MS = 25_000L

        /** Тумблер: всё правее этого x в верхней панели — Bluetooth вкл/выкл. */
        const val TOGGLE_X = 1100f

        const val OFF_HINT = "Включите Bluetooth, чтобы подключить наушники, колонку " +
            "или пульт перелистывания."
    }

    private val status = StatusBar(act, this, ink, icons)
    private val kit = SettingsKit(ink, icons)
    private val bt = BtDevices(act)

    /**
     * Строка списка: что рисовать и куда относить касание. [type] — [Line.PAIRED], [Line.FOUND],
     * [Line.EMPTY], [Line.SEARCH]; [icon] — [Line.NONE], [Line.CHECK], [Line.NEXT], [Line.REFRESH].
     */
    private class Line(val top: Float, val type: Int, val dev: BluetoothDevice?, val name: String,
                       val desc: String, val icon: Int, val last: Boolean) {
        companion object {
            const val PAIRED = 0
            const val FOUND = 1
            const val EMPTY = 2
            const val SEARCH = 3

            const val NONE = 0
            const val CHECK = 1
            const val NEXT = 2
            const val REFRESH = 3
        }
    }

    /** Кадр целиком: [snapshot] — все тексты подряд, по нему решаем, перерисовывать ли. */
    private class Model(val topDesc: String, val toggleOn: Boolean, val on: Boolean,
                        val lines: List<Line>, val pairedH: Float, val labelTop: Float,
                        val foundTop: Float, val foundH: Float, val snapshot: String)

    /** Строки последнего кадра — по ним [onUp] находит, куда пришло касание. */
    private var rows: List<Line> = emptyList()

    /** Найденные поиском: адрес → устройство и сила сигнала. */
    private class Found(val dev: BluetoothDevice, var rssi: Int)
    private val found = LinkedHashMap<String, Found>()

    /** Ждём подключения / отключения / сопряжения: адрес → момент запроса. */
    private val connecting = HashMap<String, Long>()
    private val disconnecting = HashMap<String, Long>()
    private val bonding = HashMap<String, Long>()

    /** Неудачи по устройству: адрес → текст вместо описания (до следующего касания). */
    private val notes = HashMap<String, String>()

    /** Подпись верхней панели вместо обычной (отказ системы); пусто — обычная. */
    private var topNote = ""

    /** Подпись строки поиска вместо обычной (нет права, поиск не запустился); пусто — обычная. */
    private var searchNote = ""

    private var searchStarted = 0L
    private var visibleUntil = 0L
    private var lastSnapshot = ""
    private var listening = false

    private val tick = Runnable { expire(); update(false) }

    private fun now() = SystemClock.uptimeMillis()

    // ------------------------------------------------------------ модель кадра

    /** Всё, что видно на экране, — из системы и ожиданий экрана. Зовётся из [render] и [update]. */
    private fun build(): Model {
        val st = bt.state()
        val on = st == BluetoothAdapter.STATE_ON
        val toggleOn = on || st == BluetoothAdapter.STATE_TURNING_ON
        val topDesc = when {
            topNote.isNotEmpty() -> topNote
            st == BluetoothAdapter.STATE_TURNING_ON -> "Включается…"
            st == BluetoothAdapter.STATE_TURNING_OFF -> "Выключается…"
            !on -> "Выключен"
            bt.visible() -> visibleText()
            else -> "Не видна · коснитесь, чтобы показать на 2 мин"
        }
        if (!on) {
            val snap = "$topDesc|$toggleOn|off"
            return Model(topDesc, toggleOn, false, emptyList(), 0f, 0f, 0f, 0f, snap)
        }

        val lines = ArrayList<Line>(MAX_ROWS)
        val paired = bt.bonded().sortedWith(compareBy({ !bt.connected(it) }, { bt.name(it).lowercase() }))
            .take(MAX_PAIRED)
        if (paired.isEmpty()) {
            lines.add(Line(PAIRED_TOP, Line.EMPTY, null, "Нет сопряжённых устройств",
                "Найдите устройство ниже и коснитесь его", Line.NONE, true))
        } else {
            for (i in paired.indices) {
                val d = paired[i]
                val conn = bt.connected(d)
                lines.add(Line(PAIRED_TOP + ROW * i, Line.PAIRED, d, bt.name(d), pairedDesc(d, conn),
                    if (conn) Line.CHECK else Line.NEXT, i == paired.size - 1))
            }
        }
        val pairedH = lines.size * ROW + 8f
        val labelTop = PAIRED_TOP + pairedH + GAP_LABEL
        val foundTop = labelTop + GAP_PANEL

        // Доступные: с именем, ещё не сопряжённые, сильный сигнал первым; место — до MAX_ROWS.
        val room = MAX_ROWS - lines.size - 1
        val cand = found.values.filter { bt.hasName(it.dev) && bt.bondState(it.dev) != BluetoothDevice.BOND_BONDED }
            .sortedByDescending { it.rssi }
        val shown = cand.take(room)
        for (i in shown.indices) {
            val d = shown[i].dev
            lines.add(Line(foundTop + ROW * i, Line.FOUND, d, bt.name(d), foundDesc(d), Line.NONE, false))
        }
        val more = cand.size - shown.size
        lines.add(Line(foundTop + ROW * shown.size, Line.SEARCH, null,
            if (bt.discovering()) "Поиск устройств…" else "Искать устройства",
            searchDesc(shown.isEmpty()) + (if (more > 0) " · ещё $more" else ""), Line.REFRESH, true))
        val foundH = (shown.size + 1) * ROW + 8f

        val sb = StringBuilder(256).append(topDesc).append('|').append(toggleOn)
        for (l in lines) sb.append('|').append(l.name).append('/').append(l.desc).append('/').append(l.icon)
        return Model(topDesc, toggleOn, true, lines, pairedH, labelTop, foundTop, foundH, sb.toString())
    }

    /** «Видна другим устройствам 2 мин» (макет); последнюю минуту — секундами. */
    private fun visibleText(): String {
        val left = ((visibleUntil - now() + 999) / 1000).toInt()
        if (visibleUntil == 0L || left <= 0) return "Видна другим устройствам"
        return if (left >= 60) "Видна другим устройствам ${(left + 59) / 60} мин"
        else "Видна другим устройствам $left с"
    }


    /** «Подключены · заряд 80%», «Сопряжён · не подключён» — род по виду устройства. */
    private fun pairedDesc(d: BluetoothDevice, conn: Boolean): String {
        val a = d.address
        notes[a]?.let { return it }
        if (connecting.containsKey(a)) return "Подключение…"
        if (disconnecting.containsKey(a)) return "Отключение…"
        val end = bt.kind(d).end
        if (conn) {
            val b = bt.battery(d)
            return "Подключен$end" + (if (b >= 0) " · заряд $b%" else "")
        }
        // «Сопряжён», но «Сопряжены/-на/-но»: ударение уходит с «ё».
        val paired = if (end.isEmpty()) "Сопряжён" else "Сопряжен$end"
        val off = if (end.isEmpty()) "не подключён" else "не подключен$end"
        return "$paired · $off"
    }

    private fun foundDesc(d: BluetoothDevice): String {
        notes[d.address]?.let { return it }
        if (bonding.containsKey(d.address)) return "Сопряжение…"
        return bt.kind(d).label
    }

    private fun searchDesc(none: Boolean): String = when {
        searchNote.isNotEmpty() -> searchNote
        bt.discovering() -> "Идёт поиск · ${((now() - searchStarted) / 1000).coerceAtLeast(0)} с"
        !act.hasLocation() -> "Коснитесь — Android попросит доступ к местоположению"
        searchStarted == 0L -> "Коснитесь, чтобы найти устройства рядом"
        none -> "Ничего не найдено · коснитесь, чтобы повторить"
        else -> "Коснитесь, чтобы искать снова"
    }

    // ------------------------------------------------------------ рисование

    override fun render(c: Canvas) {
        val m = build()
        lastSnapshot = m.snapshot
        rows = m.lines
        ink.rect(c, 0f, 0f, W, H, T.BG)
        kit.header(c, "Bluetooth", "Сеть")

        kit.panel(c, PANEL_TOP, PANEL_H)
        kit.title(c, "Bluetooth", 279f)
        kit.desc(c, ink.ellipsize(m.topDesc, 1060f, T.TS_BODY_S, 600), 332.5f)
        kit.toggle(c, 292.4f, m.toggleOn, 1177f)

        if (!m.on) {
            ink.paragraph(c, OFF_HINT, 59f, 450f, 1280f, T.TS_BODY_S, 600, T.G4, 3f)
        } else {
            kit.section(c, "СОПРЯЖЁННЫЕ", 450f)
            kit.panel(c, PAIRED_TOP, m.pairedH)
            kit.section(c, "ДОСТУПНЫЕ", m.labelTop)
            kit.panel(c, m.foundTop, m.foundH)
            for (l in m.lines) line(c, l)
        }
        status.draw(c)
    }

    /** Строка устройства по сетке B74: название +29, описание +82.5, разделитель +153. */
    private fun line(c: Canvas, l: Line) {
        kit.title(c, ink.ellipsize(l.name, 1170f, T.TS_ACTION, 700), l.top + 29f)
        kit.desc(c, ink.ellipsize(l.desc, 1170f, T.TS_BODY_S, 600), l.top + 82.5f)
        when (l.icon) {
            Line.CHECK -> icons.draw(c, "check", 1282.7f, l.top + 56.1f, 42f, 43.9f)
            Line.NEXT -> kit.next(c, l.top + 66.1f)
            Line.REFRESH -> icons.draw(c, "refresh@35.7x40", 1280.3f, l.top + 57.5f, 35.7f, 40f)
        }
        if (!l.last) kit.divider(c, l.top + 153f)
    }


    // ------------------------------------------------------------ касания

    override fun onUp(x: Float, y: Float) {
        if (kit.isBack(x, y)) { act.pop(); return }
        if (kit.inRow(x, y, PANEL_TOP, PANEL_H)) {
            topNote = ""
            if (x >= TOGGLE_X || !bt.on()) toggleBt() else toggleVisible()
            return
        }
        if (!bt.on()) return
        val l = rows.firstOrNull { y >= it.top && y < it.top + ROW && x >= 41f && x <= 1362f } ?: return
        val d = l.dev
        if (d != null) notes.remove(d.address)
        searchNote = ""
        when (l.type) {
            Line.PAIRED -> if (d != null) pairedTapped(d)
            Line.FOUND -> if (d != null) foundTapped(d)
            Line.SEARCH -> search()
            else -> return
        }
        update(true)
    }

    private fun toggleBt() {
        val want = !bt.on()
        if (!act.sys.setBt(want)) topNote = "Система не дала ${if (want) "включить" else "выключить"} Bluetooth"
        update(true)
    }

    private fun toggleVisible() {
        val want = !bt.visible()
        if (bt.setVisible(want)) {
            visibleUntil = if (want) now() + BtDevices.DISCOVERABLE_S * 1000L else 0L
        } else {
            topNote = "Прошивка не даёт сделать книгу видимой"
        }
        update(true)
    }

    /** Сопряжённое: подключено — отключить, нет — подключить. */
    private fun pairedTapped(d: BluetoothDevice) {
        val a = d.address
        if (connecting.containsKey(a) || disconnecting.containsKey(a)) return
        if (bt.connected(d)) {
            if (bt.disconnect(d)) disconnecting[a] = now() else notes[a] = "Не удалось отключить"
        } else {
            if (bt.connect(d)) connecting[a] = now() else notes[a] = "Не удалось подключить"
        }
    }

    /** Доступное: сопрячь; когда сопряжётся — сразу подключить ([onBond]). */
    private fun foundTapped(d: BluetoothDevice) {
        val a = d.address
        if (bonding.containsKey(a)) return
        if (bt.bond(d)) bonding[a] = now() else notes[a] = "Не удалось начать сопряжение"
    }

    /** Поиск заново; без права на местоположение `ACTION_FOUND` не придёт — сначала право. */
    private fun search() {
        if (!act.hasLocation()) {
            act.requestLocation { ok ->
                if (ok) startSearch() else searchNote = "Без доступа к местоположению Android не покажет устройства"
                update(true)
            }
            return
        }
        startSearch()
    }

    private fun startSearch() {
        found.clear()
        if (bt.discover()) searchStarted = now() else searchNote = "Поиск не запустился"
    }


    // ------------------------------------------------------------ события и таймер

    /**
     * Ожидания, вышедшие по времени: подключение и сопряжение без ответа [WAIT_MS] — «Не удалось…»;
     * видимость кончилась. Ответ пришёл (устройство подключено/отключено) — ожидание снимается.
     */
    private fun expire() {
        val t = now()
        settle(connecting, t, "Не удалось подключить") { bt.connected(it) }
        settle(disconnecting, t, "Не удалось отключить") { !bt.connected(it) }
        settle(bonding, t, "Сопряжение не завершено") { bt.bondState(it) == BluetoothDevice.BOND_BONDED }
        if (visibleUntil != 0L && (t >= visibleUntil || !bt.visible())) visibleUntil = 0L
    }

    private inline fun settle(m: HashMap<String, Long>, t: Long, fail: String, done: (BluetoothDevice) -> Boolean) {
        val it = m.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val d = bt.device(e.key)
            when {
                d != null && done(d) -> it.remove()
                t - e.value >= WAIT_MS -> { notes[e.key] = fail; it.remove() }
            }
        }
    }

    /** Нужен ли таймер: идёт поиск, ждём ответа или книга видима (счётчик в подписи). */
    private fun busy(): Boolean = bt.discovering() || connecting.isNotEmpty() ||
        disconnecting.isNotEmpty() || bonding.isNotEmpty() || visibleUntil != 0L

    /**
     * Перерисовать, если изменился хоть один текст ([force] — всегда), и завести таймер, пока
     * [busy]. Так «Идёт поиск · N с» обновляется раз в секунду, а простой экран не мигает.
     */
    private fun update(force: Boolean) {
        if (!isAttachedToWindow) return
        expire()
        if (force || build().snapshot != lastSnapshot) redraw()
        removeCallbacks(tick)
        if (busy()) postDelayed(tick, TICK_MS)
    }

    /** Сопряжение завершилось: удачно — сразу подключаем, сорвалось — пишем в строке. */
    private fun onBond(d: BluetoothDevice, state: Int) {
        val a = d.address
        if (!bonding.containsKey(a)) return
        when (state) {
            BluetoothDevice.BOND_BONDED -> {
                bonding.remove(a)
                found.remove(a)
                Trace.step("bt: bonded $a")
                if (bt.connect(d)) connecting[a] = now()
            }
            BluetoothDevice.BOND_NONE -> {
                bonding.remove(a)
                notes[a] = "Сопряжение отменено"
            }
        }
    }


    private val events = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context?, i: Intent?) {
            i ?: return
            when (i.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    val rssi = i.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    val f = found[d.address]
                    if (f == null) found[d.address] = Found(d, rssi) else if (rssi > f.rssi) f.rssi = rssi
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    onBond(d, i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE))
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val st = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF)
                    if (st == BluetoothAdapter.STATE_ON) bt.open()
                    if (st != BluetoothAdapter.STATE_ON) {
                        found.clear(); connecting.clear(); disconnecting.clear(); bonding.clear()
                        visibleUntil = 0L
                    }
                }
            }
            // ACL, профили, заряд, видимость, начало и конец поиска — просто перечитать состояние.
            update(false)
        }
    }

    // ------------------------------------------------------------ жизненный цикл

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
        if (!listening) {
            val f = IntentFilter(BluetoothDevice.ACTION_FOUND)
            f.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            f.addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED)
            f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            f.addAction(BluetoothDevice.ACTION_NAME_CHANGED)
            f.addAction(BluetoothDevice.ACTION_CLASS_CHANGED)
            f.addAction(android.bluetooth.BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            f.addAction(android.bluetooth.BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            f.addAction(BtDevices.ACTION_INPUT_STATE)
            f.addAction(BtDevices.ACTION_BATTERY)
            act.registerReceiver(events, f)
            listening = true
        }
        bt.onProfiles = { update(false) }
        bt.open()
        // Первый поиск — сразу при входе, как сети на B38: в макете «Идёт поиск» уже идёт.
        if (bt.on() && act.hasLocation() && !bt.discovering()) startSearch()
        else if (bt.discovering()) searchStarted = now()
        update(true)
    }

    /** Возврат из системного диалога (PIN сопряжения, право) — состояние могло смениться. */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && isAttachedToWindow) update(false)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        bt.stopDiscovery()
        bt.onProfiles = null
        bt.close()
        if (listening) {
            try {
                act.unregisterReceiver(events)
            } catch (ignored: IllegalArgumentException) {
            }
            listening = false
        }
        status.detach()
        super.onDetachedFromWindow()
    }
}

