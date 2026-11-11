package ru.ogrpfsense.dexpm8.ui

import android.view.View

/**
 * Режим обновления панели E-Ink через скрытый API прошивки Rockchip (разведка 6.0, DEVLOG
 * 2026-11-12): `View.requestEpdMode(View.EINK_MODE, force)`. Режим одноразовый — действует
 * на один кадр окна, затем прошивка возвращает частичный `EPD_PART`.
 *
 * Метод и значения enum ищутся один раз через рефлексию (на Android 8.1 скрытый API не закрыт).
 * Если метода нет (другая прошивка) — [ok] false, и вызывающий берёт запасной путь: белый кадр G15.
 */
object Epd {

    /** Значения `View.EINK_MODE.getValue()`. */
    const val FULL = 1
    const val FULL_GL16 = 21

    /**
     * Режим полной перерисовки при смене экрана и после системного диалога. Выбран на книге
     * (6.1): `EPD_FULL_GL16` чистит так же, как `EPD_FULL`, но мигает мягче, скорость та же.
     */
    const val CLEAN = FULL_GL16

    private var looked = false
    private var method: java.lang.reflect.Method? = null
    private val modes = HashMap<Int, Any>(32)

    /** Есть ли на прошивке `requestEpdMode`. */
    val ok: Boolean
        get() {
            look()
            return method != null
        }

    /**
     * Заказать режим [mode] для окна, в котором лежит [v]. [force] false — режим уйдёт со
     * следующим кадром (нужен `invalidate` или смена View); true — прошивка применит его сразу
     * к уже нарисованному кадру. Возвращает ответ прошивки; false — метода нет, режима нет,
     * окно не готово или вызов упал.
     */
    fun request(v: View, mode: Int, force: Boolean): Boolean {
        look()
        val m = method ?: return false
        val e = modes[mode] ?: return false
        return try {
            m.invoke(v, e, force) as? Boolean ?: true
        } catch (t: Throwable) {
            Trace.step("epd: request FAIL ${t.javaClass.simpleName}: ${t.cause ?: t.message}")
            false
        }
    }

    private fun look() {
        if (looked) return
        looked = true
        try {
            val cls = Class.forName("android.view.View\$EINK_MODE")
            val value = cls.getMethod("getValue")
            for (c in cls.enumConstants ?: emptyArray()) {
                modes[value.invoke(c) as Int] = c
            }
            method = View::class.java.getMethod("requestEpdMode", cls, java.lang.Boolean.TYPE)
            Trace.step("epd: requestEpdMode ok, modes=${modes.size}")
        } catch (t: Throwable) {
            method = null
            Trace.step("epd: no requestEpdMode (${t.javaClass.simpleName}) — fallback white frame")
        }
    }
}
