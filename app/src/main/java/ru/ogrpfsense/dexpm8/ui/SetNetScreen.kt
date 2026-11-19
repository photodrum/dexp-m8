package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas

/**
 * B38 · Настройки · Сеть. Числа — из design/v5/B38.json.
 *
 *   раздел «WI-FI»        59,250; панель 41,298 1321×870 (низ 1168 — в макете)
 *   строка «Wi-Fi»        88,332.5: круглая клавиша, заголовок с x 237, описание 395, тумблер
 *                         1174,354; разделитель 237,480 1076
 *   сети                  522.9: текущая 1266×150 (вдавленная, с `check`), ниже соседи по 131
 *                         с разделителем 2 — ровно как в макете, до 1094.4 (3 соседа)
 *   раздел «BLUETOOTH»    59,1200; панель 41,1248 1321×340
 *   строка «Bluetooth»    88,1282.5; тумблер 1177,1304; разделитель 237,1430 1079
 *   «Сопряжённые…»        88,1479.5 — дверь в B74 (ещё не сделана, п. 13 хвост)
 *   «Выключать Wi-Fi…»    59,1646; тумблер 1206,1658
 *
 * Список сетей, подключение и лист B47 — общий слой [WifiPanel] (замечание 22): на B61 та же
 * панель, отличаются только `listTop`/`listBottom`. Право на местоположение спрашивает то же
 * место, что и в мастере.
 *
 * Отступление от макета: когда сети не найдены или нет права/локации, на месте списка встаёт
 * строка «Показать сети рядом» — в макете такого состояния нет (как и на B61).
 *
 * «Выключать Wi-Fi во сне» хранится в `act.prefs` (исполнится вместе со сном, п. 8).
 * Строка «Сопряжённые устройства» пока только рисуется: дверь — п. 13, B74.
 */
class SetNetScreen(act: EinkActivity) : Screen(act) {

    private companion object {
        /**
         * Низ места под строки сетей: панель в макете кончается на 1168, последняя строка
         * (сосед 1020.5, поле 129) — до 1149.5. До 1094.4 влезает ровно 3 соседа под текущей
         * сетью, как нарисовано в макете.
         */
        const val LIST_BOTTOM = 1094.4f
    }

    private val status = StatusBar(act, this, ink, icons)
    private val kit = SettingsKit(ink, icons)

    /** Список сетей и лист B47; числа B38: первая строка 522.9, низ 1094.4. */
    private val panel = WifiPanel(this, act, ink, icons, 522.9f, LIST_BOTTOM, "settings net")

    private var btOn = false

    override fun render(c: Canvas) {
        // Под листом B47 экран затемнён (замечание 15), статус-бар и сам лист — нет.
        val dim = if (panel.sheetOpen) dimBegin(c) else -1
        ink.rect(c, 0f, 0f, W, H, T.BG)
        kit.header(c, "Сеть", "Wi-Fi и Bluetooth")

        kit.section(c, "WI-FI", 250f)
        kit.panel(c, 298f, 870f)
        kit.key(c, 88f, 332.5f)
        icons.draw(c, "wifi@33.1x30.6", 129.4f, 376.3f, 33.1f, 30.6f)
        kit.title(c, "Wi-Fi", 342f, 237f)
        kit.desc(c, panel.text(), 395f, 237f)
        kit.toggle(c, 354f, panel.wifiOn, 1174f)
        kit.divider(c, 480f, 237f, 1076f)
        panel.draw(c)

        kit.section(c, "BLUETOOTH", 1200f)
        kit.panel(c, 1248f, 340f)
        kit.key(c, 88f, 1282.5f)
        icons.draw(c, "bluetooth", 135f, 1320.6f, 22.1f, 42f)
        kit.title(c, "Bluetooth", 1292f, 237f)
        kit.desc(c, "Для наушников и колонок", 1345f, 237f)
        kit.toggle(c, 1304f, btOn, 1177f)
        kit.divider(c, 1430f, 237f, 1079f)
        kit.title(c, "Сопряжённые устройства", 1479.5f, 88f)
        kit.valueRow(c, btDevices(), 1487.5f)
        kit.next(c, 1495.5f)

        kit.title(c, "Выключать Wi-Fi во сне", 1646f, 59f)
        kit.desc(c, "Бережёт заряд, при пробуждении подключится снова", 1699f, 59f)
        kit.toggle(c, 1658f, act.prefs.wifiSleepOff, 1206f)

        if (dim >= 0) dimEnd(c, dim)
        status.draw(c)
        panel.drawSheet(c)
    }

    /**
     * Значение строки «Сопряжённые устройства»: в макете «JBL Go 3». Настоящий список требует
     * системных прав (B74, п. 13) — пока показываем только включённый Bluetooth, иначе прочерк.
     */
    private fun btDevices(): String = if (!btOn) "—" else "Нет устройств"

    override fun onUp(x: Float, y: Float) {
        // Лист B47 и строки сетей забирают касание первыми.
        if (panel.touch(x, y)) return
        if (kit.isBack(x, y)) { act.pop(); return }
        when {
            kit.inRow(x, y, 298f, 190f) -> panel.toggleWifi()
            kit.inRow(x, y, 1248f, 190f) -> {
                if (act.sys.setBt(!btOn)) { btOn = !btOn; redraw() }
            }
            kit.inRow(x, y, 1646f, 150f) -> {
                act.prefs.wifiSleepOff = !act.prefs.wifiSleepOff
                redraw()
            }
        }
    }

    override fun backPressed(): Boolean = panel.back()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
        panel.attach()
        btOn = act.sys.btOn()
    }

    /** Возврат из системного экрана локации: право могли выдать, локацию включить. */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus || !isAttachedToWindow) return
        panel.onFocus()
    }

    override fun onDetachedFromWindow() {
        panel.detach()
        status.detach()
        super.onDetachedFromWindow()
    }
}

