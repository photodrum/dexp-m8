package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas

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
 * Замечание 22 (2026-11-19): список соседей вернулся, и живёт он в общем слое [WifiPanel] —
 * тот же, что на B38 (настройки · сеть). Здесь только числа: первая строка 752.9, низ 1580
 * (5 соседей под текущей сетью, 6 без неё).
 *
 * Касание по защищённой сети открывает лист B47 ([WifiPasswordSheet]) и подключает по паролю;
 * открытая сеть подключается сразу. Подключение — `core/WifiScan`, в фоновом потоке.
 */
class FirstWifiScreen(act: EinkActivity) : Screen(act) {

    private companion object {
        /**
         * Низ места под строки сетей. Панель растянута до кнопок (решение 2026-11-19): её низ —
         * [PANEL_BOTTOM], у последней строки снизу запас ≥ 60 до края поверхности.
         */
        const val LIST_BOTTOM = 1580f

        /** Панель 41,528 до 1653.6 — на 40 выше кнопок 1693.6 (в макете панель 870, низ 1398). */
        const val PANEL_BOTTOM = 1653.6f
    }

    private val status = StatusBar(act, this, ink, icons)
    private val kit = SettingsKit(ink, icons)
    private val first = FirstRunKit(ink, icons)

    /** Список сетей и лист B47; числа B61: первая строка 752.9, низ 1580. */
    private val panel = WifiPanel(this, act, ink, icons, 752.9f, LIST_BOTTOM, "first wifi")

    /** Шторку B5 на шаге мастера не открываем: панель управления здесь ни к чему. */
    override val shadeZone: Float get() = 0f

    override fun render(c: Canvas) {
        // Под листом B47 экран затемнён (замечание 15), статус-бар и сам лист — нет.
        val dim = if (panel.sheetOpen) dimBegin(c) else -1
        ink.rect(c, 0f, 0f, W, H, T.BG)
        first.header(c, 2, "Подключим Wi-Fi",
            "Нужен для каталогов, загрузки книг и точного времени. Можно пропустить и подключить позже.")

        kit.section(c, "WI-FI", 480f)
        // Панель 870 по макету, когда Wi-Fi включён: ниже строки Wi-Fi всегда есть строки
        // (сети или «Показать сети рядом»). Выключен — 420, как в первой версии экрана.
        val h = if (panel.wifiOn) PANEL_BOTTOM - 528f else 420f
        kit.panel(c, 528f, h)
        kit.key(c, 88f, 562.5f)
        icons.draw(c, "wifi@33.1x30.6", 129.4f, 606.3f, 33.1f, 30.6f)
        kit.title(c, "Wi-Fi", 572f, 237f)
        kit.desc(c, panel.text(), 625f, 237f)
        kit.toggle(c, 584f, panel.wifiOn, 1174f)
        kit.divider(c, 710f, 237f, 1076f)
        panel.draw(c)

        first.buttonLight(c, "Пропустить")
        first.buttonDarkRight(c, "Далее")
        if (dim >= 0) dimEnd(c, dim)
        status.draw(c)
        panel.drawSheet(c)
    }

    // ------------------------------------------------------------ касания

    override fun onUp(x: Float, y: Float) {
        // Лист B47 и строки сетей забирают касание первыми.
        if (panel.touch(x, y)) return
        when {
            kit.inRow(x, y, 528f, 182f) -> panel.toggleWifi()
            first.isLightButton(x, y) -> act.go(EinkActivity.SCR_FIRST_PERM)
            first.isDarkRight(x, y) -> act.go(EinkActivity.SCR_FIRST_PERM)
        }
    }

    override fun backPressed(): Boolean = panel.back()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
        panel.attach()
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
