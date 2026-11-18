package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas

/**
 * B20 · Низкий заряд · диалог. Числа — design/v5/B20.json.
 *
 *   диалог       92,520 1225×870 (выпуклая, r 56)
 *   значок гнездо 152,584 140×140 (дно G12) + «battery@53.8x31.2» 195.1,639.8
 *   заголовок    332,590 60/700 lh 1.25 G1 — «Заряд 9%»
 *   подзаголовок 332,672 32/600 lh 1.25 G4 — «Осталось примерно 2 часа чтения»
 *   пояснение    152,790 1100 38/500 lh 1.4 G1, перенос по словам
 *   эконом. режим «Режим энергосбережения» 152,1006 42/700, описание 152,1059 32/600 G4,
 *                тумблер 1113,1018 139×73
 *   кнопки       «Позже» — выпуклая 152,1208 425×112, подпись 38/700 G1 по центру 155,1238.3;
 *                «Включить экономию» — тёмная 612,1193 645×142, подпись 42/700 G15 по центру 615,1235.7
 *
 * Показывается `EinkActivity` поверх затемнённого текущего экрана, когда экономия выключена
 * вручную на B40, а заряд упал до порога (иначе экономия включается сама — так обещает подпись
 * B40 «Включится при 15 %»). Мимо диалога касание ничего не делает: закрыть можно «Позже»,
 * «Назад» или выбором экономии. У самого нуля диалога нет — `EinkActivity` сохраняет страницу
 * и выключает книгу (`Power.OFF_AT`).
 */
class LowBatteryScreen(act: EinkActivity) : Screen(act) {

    private var level = 9
    private var hours = "примерно 2 часа чтения"
    /** Включать экономию нечего: она уже действует (`Prefs.saverApplied`). */
    private var saverOn = false

    /** Шторка B5 на этом диалоге не открывается: он сам закрывается кнопкой или «Назад». */
    override val shadeZone: Float get() = 0f

    init {
        background = null    // ниже диалога виден затемнённый экран
    }

    fun open(level: Int, hours: String, saverOn: Boolean) {
        this.level = level.coerceIn(0, 100)
        this.hours = hours
        this.saverOn = saverOn
    }

    override fun render(c: Canvas) {
        ink.convex(c, DX, DY, DW, DH, R_DIALOG)
        ink.inset(c, 152f, 584f, 140f, 140f, 70f, T.G12)
        icons.draw(c, "battery@53.8x31.2", 195.1f, 639.8f, 53.8f, 31.2f)

        ink.text(c, "Заряд ${level}%", 332f, T.base(590f, T.TS_TITLE, 1.25f),
            T.TS_TITLE, 700, T.G1)
        ink.text(c, ink.ellipsize(hours, 940f, T.TS_BODY_S, 600), 332f,
            T.base(672f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)

        // Межстрочный 1.4 от кегля 38 (макет) — в проекте это lineGap 6f, как в B45/B57.
        ink.paragraph(c, NOTE, 152f, 790f, 1100f, T.TS_LABEL_L, 500, T.G1, 6f)

        ink.text(c, "Режим энергосбережения", 152f, T.base(1006f, T.TS_ACTION, 1.25f),
            T.TS_ACTION, 700, T.G1)
        ink.text(c, "Без Wi-Fi и подсветки до зарядки", 152f, T.base(1059f, T.TS_BODY_S, 1.25f),
            T.TS_BODY_S, 600, T.G4)
        toggle(c, SA_X, SA_Y, saverOn)

        ink.convex(c, 152f, 1208f, 425f, 112f, 51f)
        ink.textCenter(c, LATER, 155f, 420f, T.base(1238.3f, T.TS_LABEL_L, 1.25f),
            T.TS_LABEL_L, 700, T.G1)

        ink.convexDark(c, 612f, 1193f, 645f, 142f, T.R_BUTTON)
        ink.textCenter(c, "Включить экономию", 615f, 640f, T.base(1235.7f, T.TS_ACTION, 1.25f),
            T.TS_ACTION, 700, T.G15)
    }

    /** Тумблер 139×73 в (x, y) — как `SettingsKit.toggle`, но у диалога своя ширина. */
    private fun toggle(c: Canvas, x: Float, y: Float, on: Boolean) {
        ink.inset(c, x, y, 139f, 73f, 37f, if (on) T.G3 else T.G12)
        ink.convex(c, if (on) x + 70f else x + 4f, y + 4f, 63f, 68f, 29f)
    }

    /**
     * Касание: «Позже», «Включить экономию» или строка режима (её тумблер) — то же, что кнопка.
     * Мимо диалога — ничего: он закрывается только кнопкой или «Назад».
     */
    override fun onUp(x: Float, y: Float) {
        when {
            inRect(x, y, 152f, 1208f, 425f, 112f) -> act.lowLater()
            inRect(x, y, 612f, 1193f, 645f, 142f) -> act.lowSaver()
            inRect(x, y, 152f, SA_Y - 20f, 1100f, 130f) -> act.lowSaver()
        }
    }

    /** «Назад» — как «Позже»: диалог закрывается, экран остаётся как был. */
    override fun backPressed(): Boolean {
        act.lowLater()
        return true
    }

    private fun inRect(x: Float, y: Float, rx: Float, ry: Float, rw: Float, rh: Float) =
        x >= rx && x <= rx + rw && y >= ry && y <= ry + rh

    private companion object {
        const val DX = 92f
        const val DY = 520f
        const val DW = 1225f
        const val DH = 870f
        const val R_DIALOG = 56f

        /** Тумблер строки «Режим энергосбережения». */
        const val SA_X = 1113f
        const val SA_Y = 1018f

        const val LATER = "Позже"

        /** Пояснение из макета, с порогом выключения `Power.OFF_AT`. */
        const val NOTE = "Подключите книгу к зарядке через USB-C. " +
            "При 3% оболочка запомнит страницу и выключит устройство."
    }
}
