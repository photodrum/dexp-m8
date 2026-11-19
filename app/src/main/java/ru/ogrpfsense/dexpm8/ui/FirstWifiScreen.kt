package ru.ogrpfsense.dexpm8.ui

import android.content.Context
import android.graphics.Canvas
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import ru.ogrpfsense.dexpm8.core.WifiScan

/**
 * B61 · Первый запуск · шаг 2 · Wi-Fi (п. 13). Числа — из design/v5/B61.json.
 *
 *   раздел «WI-FI»   59,480; панель 41,528 1321×870 в макете — у нас растянута до 1653.6, на 40
 *                    выше кнопок (решение 2026-11-19): влезает 5 соседей под текущей сетью
 *   «Wi-Fi»          88,562.5: круглая клавиша, заголовок с x 237, описание 625, тумблер 1174,584;
 *                    разделитель 237,710 1076
 *   сеть             строка 66,752.9 1266×150 (вдавленная, «Подключена»), ниже соседи по 131
 *                    с разделителем 2 px между ними; имя 42/700 от x 190, «Защищена паролем» /
 *                    «Открытая сеть» 32/600 G4. Сетка выровнена по центру строк (правка
 *                    2026-11-19) — см. комментарий у `rowTop`.
 *   кнопки           «Пропустить» 41.1,1693.6 553; «Далее» 633.4 728.5
 *
 * Замечание 22 (2026-11-19): список соседей вернулся. Он есть, когда выдано право на
 * местоположение **и** включено определение местоположения — требование Android 8.1 к
 * `getScanResults()`. Нет права или локации — на месте списка одна строка «Показать сети рядом»
 * с пояснением, почему: касание спрашивает право, при выключенной локации открывает системный
 * экран. В макете такого состояния нет — это отступление, как и сжатая панель 420 при
 * выключенном Wi-Fi.
 *
 * Касание по защищённой сети открывает лист B47 ([WifiPasswordSheet]) и подключает по паролю;
 * открытая сеть подключается сразу. Подключение — `core/WifiScan`, в фоновом потоке.
 */
class FirstWifiScreen(act: EinkActivity) : Screen(act) {

    private companion object {
        /** Переключение ключа асинхронное: через столько экран один раз сверяется с системой. */
        const val SETTLE_MS = 2500L

        /** Пауза после `startScan` перед чтением результатов: на книге список готов за ~2 с. */
        const val SCAN_MS = 2000L

        /**
         * Низ места под строки сетей. Панель растянута до кнопок (решение 2026-11-19): её низ —
         * [PANEL_BOTTOM], у последней строки снизу запас ≥ 60 до края поверхности. Сколько
         * соседей влезает, считает [capacity]: под текущей сетью 5, без неё 6.
         */
        const val LIST_BOTTOM = 1580f

        /** Панель 41,528 до 1653.6 — на 40 выше кнопок 1693.6 (в макете панель 870, низ 1398). */
        const val PANEL_BOTTOM = 1653.6f

        /** Ответа о подключении нет столько — снимаем «Подключение к …» (на книге ~10 с). */
        const val CONNECT_MS = 30_000L

        // Сетка списка сетей — см. комментарий у [rowTop].
        const val LIST_TOP = 752.9f
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

        /** Пояснение под строкой «Показать сети рядом»: зачем местоположение. */
        const val WHY = "Android 8.1 показывает сети рядом, только когда разрешено и включено " +
            "определение местоположения. Оболочка само местоположение не читает."
    }

    private val status = StatusBar(act, this, ink, icons)
    private val kit = SettingsKit(ink, icons)
    private val first = FirstRunKit(ink, icons)
    private val wifi = WifiScan(act)
    private val sheet = WifiPasswordSheet(ink, act)

    private var wifiOn = false
    private var ssid = ""
    private var linked = false

    /** Соседи: заполняются, только когда есть право и включена локация. */
    /** Все найденные сети по силе сигнала; сколько показать — решает [visibleNets]. */
    private val nets = ArrayList<WifiScan.Net>(16)

    /** Можно ли сканировать: право выдано и определение местоположения включено. */
    private var canScan = false
    private var scanning = false
    private var note = ""

    /** Лист B47 поверх экрана: null — закрыт. */
    private var sheetFor: WifiScan.Net? = null

    /** Сеть, к которой идёт подключение (до сверки состояния). */
    private var connecting = ""

    /**
     * Попытка подключения, ждущая ответа системы: сеть и id её записи. Неверный пароль система
     * сообщает не в `addNetwork`, а позже — событием супликанта с `ERROR_AUTHENTICATING`.
     */
    private var pending: WifiScan.Net? = null
    private var pendingId = -1

    /** Пароль не подошёл и система молчит — через столько снимаем «Подключение…». */
    private val giveUp = Runnable {
        if (pending != null) {
            Trace.step("first wifi: connect timeout '${pending?.ssid}'")
            pending = null
            connecting = ""
            refresh()
            redraw()
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
                        Trace.step("first wifi: connected '${p.ssid}'")
                        pending = null
                        connecting = ""
                        removeCallbacks(giveUp)
                    }
                    settleLater()
                    if (linked != wasLinked || ssid != wasSsid) redraw()
                }
            }
        }
    }
    private var listening = false

    private val settle = Runnable { if (parent != null) { refresh(); redraw() } }
    private val afterScan = Runnable { scanning = false; readNets(); redraw() }

    /** Шторку B5 на шаге мастера не открываем: панель управления здесь ни к чему. */
    override val shadeZone: Float get() = 0f

    override fun render(c: Canvas) {
        // Под листом B47 экран затемнён (замечание 15), статус-бар и сам лист — нет.
        val dim = if (sheetFor != null) dimBegin(c) else -1
        ink.rect(c, 0f, 0f, W, H, T.BG)
        first.header(c, 2, "Подключим Wi-Fi",
            "Нужен для каталогов, загрузки книг и точного времени. Можно пропустить и подключить позже.")

        kit.section(c, "WI-FI", 480f)
        val rows = visibleNets()
        // Панель 870 по макету, когда Wi-Fi включён: ниже строки Wi-Fi всегда есть строки
        // (сети или «Показать сети рядом»). Выключен — 420, как в первой версии экрана.
        val h = if (wifiOn) PANEL_BOTTOM - 528f else 420f
        kit.panel(c, 528f, h)
        kit.key(c, 88f, 562.5f)
        icons.draw(c, "wifi@33.1x30.6", 129.4f, 606.3f, 33.1f, 30.6f)
        kit.title(c, "Wi-Fi", 572f, 237f)
        kit.desc(c, wifiText(), 625f, 237f)
        kit.toggle(c, 584f, wifiOn, 1174f)
        kit.divider(c, 710f, 237f, 1076f)

        if (wifiOn) {
            if (rows.isNotEmpty()) networkList(c, rows)
            // Сканирование недоступно (нет права или локации) — строка «Показать сети рядом»
            // встаёт под текущей сетью; если сети нет — на её место.
            if (hintShown()) hintRow(c, hintTop())
        }

        first.buttonLight(c, "Пропустить")
        first.buttonDarkRight(c, "Далее")
        if (dim >= 0) dimEnd(c, dim)
        status.draw(c)
        if (sheetFor != null) sheet.draw(c)
    }

    // ------------------------------------------------------------ строки списка

    /*
     * Сетка списка — одна для рисования ([networkList]) и касаний ([rowTapped]), правка
     * 2026-11-19 по снимку с книги. Было: у соседа имя прижато к верху строки 131, разделитель
     * на +129 — над названием 2 px от линии, под подписью 33 px.
     *
     *   строка текущей сети  752.9, высота 150, вдавленная (как в макете)
     *   строка соседа         131 = поле 129 + разделитель 2 снизу; первая — сразу под текущей
     *                         (902.9) или с 752.9, если подключения нет
     *   текст                 имя 42/700 (рамка 52.5) + 3.5 + подпись 32/600 (рамка 40) = блок 96,
     *                         по центру поля: у соседа отступы 16.5 сверху и снизу, у текущей 27
     *                         (в макете 25 — на 2 px ниже, ради той же системы)
     *   иконки                по центру того же блока; слева `wifi` в 113.6 — 47.6 от края строки
     *                         (66), справа `lock`/`check` с тем же 47.6 от правого края (1332)
     *   разделитель           только между соседями: под последним нет (как в макете)
     */

    /** Верх строки [i] в списке [rows] (первая может быть строкой текущей сети). */
    private fun rowTop(rows: Array<Row>, i: Int): Float {
        if (!rows[0].linkedRow) return LIST_TOP + ROW_H * i
        return if (i == 0) LIST_TOP else LIST_TOP + LINKED_H + ROW_H * (i - 1)
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
     * Верх строки «Показать сети рядом»: под строкой текущей сети с зазором 16.1 (это отдельная
     * выпуклая плашка, вплотную к вдавленной строке она слипается), иначе на её месте.
     */
    private fun hintTop(): Float = if (linked) LIST_TOP + LINKED_H + 16.1f else LIST_TOP

    /**
     * Строка нужна, когда сканировать нельзя или соседей нет (идёт первый поиск или он ничего не
     * дал). При повторном поиске со списком на экране строку не рисуем: она легла бы на соседа.
     */
    private fun hintShown(): Boolean = !canScan || nets.isEmpty()

    /**
     * «Показать сети рядом» 1266×150 — геометрия строки сети из макета: имя 42/700 и короткая
     * подпись 32/600 G4 ([note]) в строке, пояснение «почему» — абзацем под ней. Когда
     * сканирование недоступно, касание спрашивает право, затем включает локацию.
     */
    private fun hintRow(c: Canvas, top: Float) {
        ink.convex(c, 66f, top, 1266f, 150f, 40f)
        icons.draw(c, "wifi@28.8x26.6", 113.6f, top + 62.7f, 28.8f, 26.6f)
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
            ink.paragraph(c, WHY, 113.6f, top + 190f, 1200f, T.TS_BODY_S, 600, T.G4, 3f)
        }
    }


    // ------------------------------------------------------------ данные

    /** Строка экрана: сеть и её подпись. [net] = null — это строка подключённой сети. */
    private class Row(val name: String, val note: String, val secured: Boolean,
                      val linkedRow: Boolean, val net: WifiScan.Net?)

    /**
     * Сколько соседей влезает между первой строкой ([LIST_TOP]) и [LIST_BOTTOM]: шаг [ROW_H],
     * у последней строки нет разделителя. Есть строка текущей сети ([linked]) — на одну меньше.
     */
    private fun capacity(): Int {
        val room = LIST_BOTTOM - LIST_TOP - (if (linked) LINKED_H else 0f)
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

    private fun wifiText() = when {
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
    private fun refresh() {
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
            Trace.step("first wifi FAIL: ${t.javaClass.simpleName}: ${t.message}")
        }
        if (!canScan) {
            nets.clear()
            note = if (!act.hasLocation()) "Нужно разрешение на местоположение"
            else "Включите определение местоположения"
        }
    }

    /**
     * Сканирование в фоновом потоке: `startScan` принят — через [SCAN_MS] читаем список и
     * перерисовываем один раз. Вызывается при показе экрана и по строке «Показать сети рядом».
     */
    private fun startScan() {
        if (!wifiOn || scanning || !canScan) return
        scanning = true
        note = ""
        redraw()
        Thread({
            wifi.scan()
            try {
                Thread.sleep(SCAN_MS)
            } catch (t: InterruptedException) {
                return@Thread
            }
            post(afterScan)
        }, "wifi-scan").start()
    }

    /** Чтение результатов в UI-потоке: их немного, а `redraw` должен идти отсюда. */
    private fun readNets() {
        nets.clear()
        for (n in wifi.results()) nets.add(n)
        if (nets.isEmpty()) note = "Сетей не найдено — проверьте, что Wi-Fi включён"
        Trace.step("first wifi: сетей ${nets.size}")
    }


    // ------------------------------------------------------------ касания

    override fun onUp(x: Float, y: Float) {
        val forNet = sheetFor
        if (forNet != null) {
            when (sheet.hit(x, y)) {
                WifiPasswordSheet.ACT_CANCEL -> { sheetFor = null; redraw() }
                WifiPasswordSheet.ACT_CHANGED -> redraw()
                WifiPasswordSheet.ACT_CONNECT -> connect(forNet, sheet.text, sheet.auto)
            }
            return
        }
        when {
            kit.inRow(x, y, 528f, 182f) -> {
                if (act.sys.setWifi(!wifiOn)) {
                    wifiOn = !wifiOn
                    linked = false
                    nets.clear()
                    settleLater()
                }
                redraw()
            }
            // Строка «Показать сети рядом» / «Обновить список» — под текущей сетью или на её месте.
            wifiOn && hintShown() && kit.inRow(x, y, hintTop(), 150f) ->
                if (canScan) startScan() else askScan()
            wifiOn && kit.inRow(x, y, LIST_TOP, LIST_BOTTOM - LIST_TOP) -> rowTapped(x, y)
            first.isLightButton(x, y) -> act.go(EinkActivity.SCR_FIRST_PERM)
            first.isDarkRight(x, y) -> act.go(EinkActivity.SCR_FIRST_PERM)
        }
    }

    /**
     * Право на местоположение, затем включение локации: обязательны оба — без права система
     * не отдаёт список, без включённого определения тоже. При возврате из системного экрана
     * экран пересканирует (`onWindowFocusChanged` → `refresh`).
     */
    private fun askScan() {
        if (!act.hasLocation()) {
            act.requestLocation { ok ->
                if (ok) {
                    refresh()
                    if (canScan) startScan() else openLocation()
                } else {
                    note = "Без разрешения сети рядом не показать — можно подключить в настройках системы"
                    redraw()
                }
            }
            return
        }
        openLocation()
    }

    private fun openLocation() {
        if (!act.openLocationSettings()) {
            note = "Системный экран местоположения не открылся"
            redraw()
        }
    }

    /**
     * Касание по строке списка: открытая сеть подключается сразу, защищённая — лист B47.
     * Геометрия — та же сетка, что у [networkList] ([rowTop], [rowH]).
     */
    private fun rowTapped(x: Float, y: Float) {
        if (x < ROW_X || x > ROW_X + ROW_W) return
        val rows = visibleNets()
        for (i in rows.indices) {
            val r = rows[i]
            val top = rowTop(rows, i)
            if (y < top || y >= top + rowH(r)) continue
            if (r.linkedRow) return
            val net = r.net ?: return
            if (net.secured) {
                sheetFor = net
                sheet.open(net.ssid, "Защищена паролем")
                redraw()
            } else {
                connect(net, "")
            }
            return
        }
    }

    /**
     * Подключение в фоновом потоке. Отказ сразу (система не сохранила сеть) — лист B47
     * открывается снова с причиной. Принят — ждём событий [events]: подключились, неверный
     * пароль ([wrongPassword]) или [CONNECT_MS] без ответа ([giveUp]).
     */
    private fun connect(net: WifiScan.Net, password: String, auto: Boolean = true) {
        sheetFor = null
        connecting = net.ssid
        redraw()
        Trace.step("first wifi: connect '${net.ssid}'")
        Thread({
            val a = wifi.connect(net, password, auto)
            post {
                val err = a.error
                if (err != null) {
                    connecting = ""
                    Trace.step("first wifi: connect FAIL '$err'")
                    if (net.secured) reopen(net, err) else note = err
                } else {
                    pending = net
                    pendingId = a.id
                    removeCallbacks(giveUp)
                    postDelayed(giveUp, CONNECT_MS)
                }
                redraw()
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
        Trace.step("first wifi: wrong password '${p.ssid}' id=$pendingId")
        pending = null
        connecting = ""
        removeCallbacks(giveUp)
        val id = pendingId
        pendingId = -1
        Thread({ wifi.forget(id) }, "wifi-forget").start()
        reopen(p, "Неверный пароль")
        redraw()
    }

    private fun reopen(net: WifiScan.Net, err: String) {
        sheetFor = net
        sheet.open(net.ssid, "Защищена паролем")
        sheet.error = err
    }

    private fun settleLater() {
        removeCallbacks(settle)
        postDelayed(settle, SETTLE_MS)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
        if (!listening) {
            val f = android.content.IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            f.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION)
            act.registerReceiver(events, f)
            listening = true
        }
        refresh()
        if (canScan) startScan()
    }

    /** Возврат из системного экрана локации: право могли выдать, локацию включить. */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus || !isAttachedToWindow) return
        val was = canScan
        refresh()
        if (canScan && (!was || nets.isEmpty())) startScan() else redraw()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(settle)
        removeCallbacks(afterScan)
        removeCallbacks(giveUp)
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

