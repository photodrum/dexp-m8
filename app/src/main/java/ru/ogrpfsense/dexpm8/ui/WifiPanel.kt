package ru.ogrpfsense.dexpm8.ui

import android.content.Context
import android.graphics.Canvas
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import ru.ogrpfsense.dexpm8.core.WifiScan

/**
 * Панель сетей Wi-Fi: строка текущей сети, соседи, строка «Показать сети рядом», лист B47.
 * Одна на B61 (мастер) и B38 (настройки · сеть) — замечание 22: второй копии этой логики
 * (скан, подключение, неверный пароль, право на местоположение) быть не должно.
 *
 * Отличаются только числа: у B61 список начинается с 752.9 и доходит до 1580 (5 соседей под
 * текущей сетью), у B38 — с 522.9 до 1094.4 (3 соседа, как в макете). Всё остальное в макетах
 * совпадает: строка 1266 шириной от x 66, текущая сеть 150, сосед 131 с разделителем 2,
 * `wifi` слева и `lock`/`check` справа в 47.6 от края.
 *
 * Экран сам рисует строку «Wi-Fi» с тумблером (она на разных высотах) и зовёт [toggleWifi];
 * панель рисует [draw] и забирает касания [touch]. Лист B47 живёт здесь: пока он открыт,
 * [sheetOpen] = true, экран затемняет кадр ([Screen.dimBegin]) и зовёт [drawSheet].
 *
 * Требование Android 8.1 к `getScanResults()`: выдано право на местоположение **и** включено
 * определение местоположения. Нет одного из двух — на месте списка строка «Показать сети рядом»
 * с пояснением, почему (в макетах такого состояния нет — отступление, как и на B61).
 */
class WifiPanel(
    private val view: Screen,
    private val act: EinkActivity,
    private val ink: Ink,
    private val icons: Icons,
    /** Верх первой строки списка (у B61 752.9, у B38 522.9). */
    private val listTop: Float,
    /** Низ места под строки; сколько соседей влезает — считает [capacity]. */
    private val listBottom: Float,
    /** Префикс трассировки: по нему в логе видно, с какого экрана шла попытка. */
    private val log: String
) {

    private companion object {
        /** Переключение ключа асинхронное: через столько панель один раз сверяется с системой. */
        const val SETTLE_MS = 2500L

        /** Пауза после `startScan` перед чтением результатов: на книге список готов за ~2 с. */
        const val SCAN_MS = 2000L

        /** Ответа о подключении нет столько — снимаем «Подключение к …» (на книге ~10 с). */
        const val CONNECT_MS = 30_000L

        // Сетка списка — см. комментарий у [rowTop].
        const val LINKED_H = 150f
        const val ROW_H = 131f
        const val DIVIDER = 2f
        const val ROW_X = 66f
        const val ROW_W = 1266f

        /** Отступ иконок от края строки: `wifi` в макете 113.6 − 66 = 47.6, справа — так же. */
        const val ICON_PAD = 47.6f

        /** Подпись под именем: рамка имени 42·1.25 = 52.5 + 3.5 (в макете 833.9 − 777.9). */
        const val NOTE_DY = 56f

        /** Блок «имя + подпись»: 56 + рамка подписи 32·1.25 = 40. */
        const val TEXT_BLOCK = 96f

        /** Строка «Показать сети рядом»: та же плашка, что у сети, высота 150. */
        const val HINT_H = 150f

        /** Пояснение под строкой «Показать сети рядом»: зачем местоположение. */
        const val WHY = "Android 8.1 показывает сети рядом, только когда разрешено и включено " +
            "определение местоположения. Оболочка само местоположение не читает."
    }

    private val kit = SettingsKit(ink, icons)
    private val wifi = WifiScan(act)
    private val sheet = WifiPasswordSheet(ink, act)

    /** Включён ли Wi-Fi по системе (тумблер строки «Wi-Fi» рисует экран). */
    var wifiOn = false
        private set

    /** Имя текущей сети; пусто — системы его не отдала (нет права на местоположение). */
    var ssid = ""
        private set

    /** Есть ли подключение по Wi-Fi прямо сейчас. */
    var linked = false
        private set

    /** Можно ли сканировать: право выдано и определение местоположения включено. */
    var canScan = false
        private set

    /** Идёт поиск сетей — в строке подсказки «Ищу сети…». */
    var scanning = false
        private set

    /** Строка-подпись под «Показать сети рядом»: что случилось. */
    var note = ""
        private set

    /** Лист B47 поверх экрана: null — закрыт. */
    var sheetFor: WifiScan.Net? = null
        private set

    /** Сеть, к которой идёт подключение (до сверки состояния). */
    private var connecting = ""

    /**
     * Попытка подключения, ждущая ответа системы: сеть и id её записи. Неверный пароль система
     * сообщает не в `addNetwork`, а позже — событием супликанта с `ERROR_AUTHENTICATING`.
     */
    private var pending: WifiScan.Net? = null
    private var pendingId = -1

    /** Все найденные сети по силе сигнала; сколько показать — решает [visibleNets]. */
    private val nets = ArrayList<WifiScan.Net>(16)

    /** Пароль не подошёл и система молчит — через столько снимаем «Подключение…». */
    private val giveUp = Runnable {
        if (pending != null) {
            Trace.step("$log: connect timeout '${pending?.ssid}'")
            pending = null
            connecting = ""
            refresh()
            view.redraw()
        }
    }

    /**
     * События Wi-Fi, пока экран на виду: смена подключения (книга могла сама вернуться к прежней
     * сети — на книге это ~20 с после неверного пароля) и отказ по паролю для [pending].
     */
    private val events = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context?, i: android.content.Intent?) {
            i ?: return
            when (i.action) {
                WifiManager.SUPPLICANT_STATE_CHANGED_ACTION -> {
                    val err = i.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, -1)
                    if (err == WifiManager.ERROR_AUTHENTICATING) wrongPassword()
                }
                WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                    val wasLinked = linked
                    val wasSsid = ssid
                    refresh()
                    // В момент события ConnectivityManager ещё не считает сеть активной (на книге
                    // экран оставался «Не подключено» после возврата к прежней сети) — состояние
                    // берём из самого события, а через SETTLE_MS сверяемся ещё раз.
                    @Suppress("DEPRECATION")
                    val ni = i.getParcelableExtra<android.net.NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
                    if (ni != null) linked = ni.isConnected
                    val p = pending
                    if (p != null && linked && ssid == p.ssid) {
                        Trace.step("$log: connected '${p.ssid}'")
                        pending = null
                        connecting = ""
                        view.removeCallbacks(giveUp)
                    }
                    settleLater()
                    if (linked != wasLinked || ssid != wasSsid) view.redraw()
                }
            }
        }
    }
    private var listening = false

    private val settle = Runnable { refresh(); view.redraw() }
    private val afterScan = Runnable { scanning = false; readNets(); view.redraw() }

    // ------------------------------------------------------------ жизненный цикл

    /** Экран показан: подписка на события, состояние из системы, первый поиск сетей. */
    fun attach() {
        if (!listening) {
            val f = android.content.IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            f.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION)
            act.registerReceiver(events, f)
            listening = true
        }
        refresh()
        if (canScan) startScan()
    }

    fun detach() {
        view.removeCallbacks(settle)
        view.removeCallbacks(afterScan)
        view.removeCallbacks(giveUp)
        if (listening) {
            try {
                act.unregisterReceiver(events)
            } catch (ignored: IllegalArgumentException) {
            }
            listening = false
        }
    }

    /** Возврат из системного экрана локации: право могли выдать, локацию включить. */
    fun onFocus() {
        val was = canScan
        refresh()
        if (canScan && (!was || nets.isEmpty())) startScan() else view.redraw()
    }

    // ------------------------------------------------------------ данные

    /** Строка экрана: сеть и её подпись. [net] = null — это строка подключённой сети. */
    private class Row(val name: String, val note: String, val secured: Boolean,
                      val linkedRow: Boolean, val net: WifiScan.Net?)

    /**
     * Сколько соседей влезает между первой строкой ([listTop]) и [listBottom]: шаг [ROW_H],
     * у последней строки нет разделителя. Есть строка текущей сети ([linked]) — на одну меньше.
     */
    private fun capacity(): Int {
        val room = listBottom - listTop - (if (linked) LINKED_H else 0f)
        return Math.max(0, (room / ROW_H).toInt())
    }

    /**
     * Что рисуем ниже строки Wi-Fi: подключённая сеть (если система её знает) и столько соседей,
     * сколько влезает в панель ([capacity]) — по силе сигнала, текущая сеть пропускается
     * (она уже первой строкой). Пусто — сканирование недоступно, тогда [hintRow].
     */
    private fun visibleNets(): Array<Row> {
        if (!wifiOn) return emptyArray()
        val out = ArrayList<Row>(capacity() + 1)
        if (linked) {
            out.add(Row(ssid.ifEmpty { "Текущая сеть" },
                if (ssid.isEmpty()) "Подключено · имя скрыто системой" else "Подключено",
                false, true, null))
        }
        // Сначала исключаем текущую сеть, потом берём первые N: иначе она занимала бы место соседа
        // (ошибка, найденная на книге 2026-11-19 — показывались два соседа вместо трёх).
        val room = capacity()
        var n = 0
        for (net in nets) {
            if (n >= room) break
            if (linked && net.ssid == ssid) continue
            out.add(Row(net.ssid, if (net.secured) "Защищена паролем" else "Открытая сеть",
                net.secured, false, net))
            n++
        }
        return out.toTypedArray()
    }

    /** Подпись под строкой «Wi-Fi»: «Подключено к «…»», «Выключено» и т. д. */
    fun text(): String = when {
        !wifiOn -> "Выключено"
        linked -> if (ssid.isEmpty()) "Подключено" else "Подключено к «$ssid»"
        connecting.isNotEmpty() -> "Подключение к «$connecting»"
        else -> "Не подключено"
    }

    /**
     * Состояние из системы: включён ли Wi-Fi, есть ли связь и её SSID, можно ли сканировать.
     * `connectionInfo.ssid` на 8.1 отдаёт `<unknown ssid>` без права на местоположение — тогда
     * имя остаётся пустым, и строка подключённой сети честно говорит, что система его скрыла.
     */
    @Suppress("DEPRECATION")
    fun refresh() {
        wifiOn = act.sys.wifiOn()
        linked = false
        ssid = ""
        canScan = act.hasLocation() && wifi.locationOn()
        try {
            val wm = act.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val s = wm.connectionInfo?.ssid ?: ""
            // Android отдаёт SSID в кавычках; <unknown ssid> — сети нет.
            if (s.isNotEmpty() && s != "<unknown ssid>") ssid = s.trim('"')
            val cm = act.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val ni = cm.activeNetworkInfo
            linked = ni != null && ni.isConnected && ni.type == ConnectivityManager.TYPE_WIFI
        } catch (t: Throwable) {
            Trace.step("$log FAIL: ${t.javaClass.simpleName}: ${t.message}")
        }
        if (!canScan) {
            nets.clear()
            note = if (!act.hasLocation()) "Нужно разрешение на местоположение"
            else "Включите определение местоположения"
        }
    }

    /**
     * Тумблер строки «Wi-Fi»: переключение в системе и сверка через [SETTLE_MS]. `false` —
     * система не дала (тогда тумблер не двигаем).
     */
    fun toggleWifi(): Boolean {
        if (!act.sys.setWifi(!wifiOn)) {
            view.redraw()
            return false
        }
        wifiOn = !wifiOn
        linked = false
        nets.clear()
        view.redraw()
        settleLater()
        return true
    }

    private fun settleLater() {
        view.removeCallbacks(settle)
        view.postDelayed(settle, SETTLE_MS)
    }

    /**
     * Сканирование в фоновом потоке: `startScan` принят — через [SCAN_MS] читаем список и
     * перерисовываем один раз. Вызывается при показе экрана и по строке «Показать сети рядом».
     */
    private fun startScan() {
        if (!wifiOn || scanning || !canScan) return
        scanning = true
        note = ""
        view.redraw()
        Thread({
            wifi.scan()
            try {
                Thread.sleep(SCAN_MS)
            } catch (t: InterruptedException) {
                return@Thread
            }
            view.post(afterScan)
        }, "wifi-scan").start()
    }

    // ------------------------------------------------------------ сетка и рисование

    /*
     * Сетка списка — одна для рисования ([networkList]) и касаний ([rowTapped]), правка
     * 2026-11-19 по снимку с книги. Было: у соседа имя прижато к верху строки 131, разделитель
     * на +129 — над названием 2 px от линии, под подписью 33 px.
     *
     *   строка текущей сети  [listTop], высота 150, вдавленная (как в макете)
     *   строка соседа        131 = поле 129 + разделитель 2 снизу; первая — сразу под текущей
     *                        (+150) или с [listTop], если подключения нет
     *   текст                имя 42/700 (рамка 52.5) + 3.5 + подпись 32/600 (рамка 40) = блок 96,
     *                        по центру поля: у соседа отступы 16.5 сверху и снизу, у текущей 27
     *                        (в макете 25 — на 2 px ниже, ради той же системы)
     *   иконки               по центру того же блока; слева `wifi` в 113.6 — 47.6 от края строки
     *                        (66), справа `lock`/`check` с тем же 47.6 от правого края (1332)
     *   разделитель          только между соседями: под последним нет (как в макете)
     */

    /** Чтение результатов в UI-потоке: их немного, а `redraw` должен идти отсюда. */
    private fun readNets() {
        nets.clear()
        for (n in wifi.results()) nets.add(n)
        if (nets.isEmpty()) note = "Сетей не найдено — проверьте, что Wi-Fi включён"
        Trace.step("$log: сетей ${nets.size}")
    }

    /**
     * Строка нужна, когда сканировать нельзя или соседей нет (идёт первый поиск или он ничего не
     * дал). При повторном поиске со списком на экране строку не рисуем: она легла бы на соседа.
     */
    private fun hintShown(): Boolean = !canScan || nets.isEmpty()

    /**
     * Верх строки «Показать сети рядом»: под строкой текущей сети с зазором 16.1 (это отдельная
     * выпуклая плашка, вплотную к вдавленной строке она слипается), иначе на её месте.
     */
    fun hintTop(): Float = if (linked) listTop + LINKED_H + 16.1f else listTop

    /** Что рисуем ниже строки «Wi-Fi»: список сетей или строка подсказки. */
    fun draw(c: Canvas) {
        if (!wifiOn) return
        val rows = visibleNets()
        if (rows.isNotEmpty()) networkList(c, rows)
        if (hintShown()) hintRow(c, hintTop())
    }

    /** Лист B47 открыт — экран затемняет кадр и рисует лист поверх. */
    val sheetOpen: Boolean get() = sheetFor != null

    /** Лист B47 — после статус-бара: он перекрывает всё, что ниже 840. */
    fun drawSheet(c: Canvas) {
        if (sheetFor != null) sheet.draw(c)
    }

    /** Верх строки [i] в списке [rows] (первая может быть строкой текущей сети). */
    private fun rowTop(rows: Array<Row>, i: Int): Float {
        if (!rows[0].linkedRow) return listTop + ROW_H * i
        return if (i == 0) listTop else listTop + LINKED_H + ROW_H * (i - 1)
    }

    private fun rowH(r: Row): Float = if (r.linkedRow) LINKED_H else ROW_H

    /** Список: подключённая сеть первой (вдавленная, с галочкой), за ней соседи с разделителями. */
    private fun networkList(c: Canvas, rows: Array<Row>) {
        for (i in rows.indices) networkRow(c, rows[i], rowTop(rows, i), i == rows.size - 1)
    }

    /**
     * Строка сети: имя 42/700, подпись 32/600 G4, «wifi» слева и `lock`/`check` справа — всё по
     * центру поля строки (сетка выше). Подложка только у текущей сети; у соседей — разделитель.
     */
    private fun networkRow(c: Canvas, n: Row, top: Float, last: Boolean) {
        val inset = n.linkedRow
        if (inset) ink.inset(c, ROW_X, top, ROW_W, LINKED_H, 40f, T.G12)
        // Поле строки: у соседа без разделителя 2 px снизу.
        val field = if (inset) LINKED_H else ROW_H - DIVIDER
        val nameTop = top + (field - TEXT_BLOCK) / 2f
        val cy = nameTop + TEXT_BLOCK / 2f
        icons.draw(c, "wifi@28.8x26.6", ROW_X + ICON_PAD, cy - 13.3f, 28.8f, 26.6f)
        ink.text(c, ink.ellipsize(n.name, 1000f, T.TS_ACTION, 700), 190f,
            T.base(nameTop, T.TS_ACTION, 1.25f), T.TS_ACTION, 700, T.G1)
        ink.text(c, n.note, 190f,
            T.base(nameTop + NOTE_DY, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        val right = ROW_X + ROW_W - ICON_PAD
        when {
            n.linkedRow -> icons.draw(c, "check@25.7x19.3", right - 25.7f, cy - 9.65f, 25.7f, 19.3f)
            // Варианта lock@27.5x35.8 в IconSpecs нет — базовая `lock` того же размера.
            n.secured -> icons.draw(c, "lock", right - 27.5f, cy - 17.9f, 27.5f, 35.8f)
        }
        if (!inset && !last) kit.divider(c, top + ROW_H - DIVIDER, 190f, 1123f)
    }

    /**
     * «Показать сети рядом» [ROW_W]×150 — геометрия строки сети из макета: имя 42/700 и короткая
     * подпись 32/600 G4 ([note]) в строке, пояснение «почему» — абзацем под ней. Когда
     * сканирование недоступно, касание спрашивает право, затем включает локацию.
     */
    private fun hintRow(c: Canvas, top: Float) {
        ink.convex(c, ROW_X, top, ROW_W, HINT_H, 40f)
        icons.draw(c, "wifi@28.8x26.6", ROW_X + ICON_PAD, top + 62.7f, 28.8f, 26.6f)
        val title = when {
            scanning -> "Ищу сети…"
            canScan -> "Обновить список"
            else -> "Показать сети рядом"
        }
        ink.text(c, title, 190f, T.base(top + 25f, T.TS_ACTION, 1.25f), T.TS_ACTION, 700, T.G1)
        if (note.isNotEmpty()) {
            ink.text(c, ink.ellipsize(note, 1070f, T.TS_BODY_S, 600), 190f,
                T.base(top + 81f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        }
        if (!canScan) {
            ink.paragraph(c, WHY, ROW_X + ICON_PAD, top + 190f, 1200f, T.TS_BODY_S, 600, T.G4, 3f)
        }
    }

    // ------------------------------------------------------------ касания

    /** «Назад» системы при открытом листе закрывает лист. `true` — экран уходить не должен. */
    fun back(): Boolean {
        if (sheetFor == null) return false
        sheetFor = null
        view.redraw()
        return true
    }

    /**
     * Касание ниже строки «Wi-Fi»: лист B47 (пока открыт — забирает всё), строка «Показать сети
     * рядом», строки сетей. `true` — касание наше, экрану его обрабатывать не нужно.
     */
    fun touch(x: Float, y: Float): Boolean {
        val forNet = sheetFor
        if (forNet != null) {
            when (sheet.hit(x, y)) {
                WifiPasswordSheet.ACT_CANCEL -> { sheetFor = null; view.redraw() }
                WifiPasswordSheet.ACT_CHANGED -> view.redraw()
                WifiPasswordSheet.ACT_CONNECT -> connect(forNet, sheet.text, sheet.auto)
            }
            return true
        }
        if (!wifiOn) return false
        if (hintShown() && y >= hintTop() && y < hintTop() + HINT_H) {
            if (canScan) startScan() else askScan()
            return true
        }
        if (y >= listTop && y < listBottom) return rowTapped(x, y)
        return false
    }

    /**
     * Право на местоположение, затем включение локации: обязательны оба — без права система
     * не отдаёт список, без включённого определения тоже. При возврате из системного экрана
     * экран пересканирует (`onWindowFocusChanged` → [onFocus]).
     */
    private fun askScan() {
        if (!act.hasLocation()) {
            act.requestLocation { ok ->
                if (ok) {
                    refresh()
                    if (canScan) startScan() else openLocation()
                } else {
                    note = "Без разрешения сети рядом не показать — можно подключить в настройках системы"
                    view.redraw()
                }
            }
            return
        }
        openLocation()
    }

    private fun openLocation() {
        if (!act.openLocationSettings()) {
            note = "Системный экран местоположения не открылся"
            view.redraw()
        }
    }

    /**
     * Касание по строке списка: открытая сеть подключается сразу, защищённая — лист B47.
     * Геометрия — та же сетка, что у [networkList] ([rowTop], [rowH]).
     */
    private fun rowTapped(x: Float, y: Float): Boolean {
        if (x < ROW_X || x > ROW_X + ROW_W) return false
        val rows = visibleNets()
        for (i in rows.indices) {
            val r = rows[i]
            val top = rowTop(rows, i)
            if (y < top || y >= top + rowH(r)) continue
            if (r.linkedRow) return true
            val net = r.net ?: return true
            if (net.secured) {
                sheetFor = net
                sheet.open(net.ssid, "Защищена паролем")
                view.redraw()
            } else {
                connect(net, "")
            }
            return true
        }
        return false
    }

    /**
     * Подключение в фоновом потоке. Отказ сразу (система не сохранила сеть) — лист B47
     * открывается снова с причиной. Принят — ждём событий [events]: подключились, неверный
     * пароль ([wrongPassword]) или [CONNECT_MS] без ответа ([giveUp]).
     */
    private fun connect(net: WifiScan.Net, password: String, auto: Boolean = true) {
        sheetFor = null
        connecting = net.ssid
        view.redraw()
        Trace.step("$log: connect '${net.ssid}'")
        Thread({
            val a = wifi.connect(net, password, auto)
            view.post {
                val err = a.error
                if (err != null) {
                    connecting = ""
                    Trace.step("$log: connect FAIL '$err'")
                    if (net.secured) reopen(net, err) else note = err
                } else {
                    pending = net
                    pendingId = a.id
                    view.removeCallbacks(giveUp)
                    view.postDelayed(giveUp, CONNECT_MS)
                }
                view.redraw()
            }
        }, "wifi-connect").start()
    }

    /**
     * Супликант отказал по паролю. Свою запись сети удаляем (иначе в системе остаётся сеть
     * «отключена: неверный пароль»), лист B47 открываем снова с ошибкой. Книга сама вернётся
     * к прежней сети — это видно по [events].
     */
    private fun wrongPassword() {
        val p = pending ?: return
        Trace.step("$log: wrong password '${p.ssid}' id=$pendingId")
        pending = null
        connecting = ""
        view.removeCallbacks(giveUp)
        val id = pendingId
        pendingId = -1
        Thread({ wifi.forget(id) }, "wifi-forget").start()
        reopen(p, "Неверный пароль")
        view.redraw()
    }

    private fun reopen(net: WifiScan.Net, err: String) {
        sheetFor = net
        sheet.open(net.ssid, "Защищена паролем")
        sheet.error = err
    }
}
