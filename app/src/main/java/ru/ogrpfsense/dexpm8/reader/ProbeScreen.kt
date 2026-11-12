package ru.ogrpfsense.dexpm8.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import ru.ogrpfsense.dexpm8.ui.D
import ru.ogrpfsense.dexpm8.ui.EinkActivity
import ru.ogrpfsense.dexpm8.ui.Epd
import ru.ogrpfsense.dexpm8.ui.Screen
import ru.ogrpfsense.dexpm8.ui.T
import ru.ogrpfsense.dexpm8.ui.Trace
import java.nio.ByteBuffer

/**
 * Временный отладочный экран R0 (шаг 7.2), стоит вместо B3 на `SCR_READER`: первая страница
 * книги `act.bookId` через движок по формату. Сверху полоса со «Назад» и строкой
 * «движок · мс · код», ниже — страница 1:1 в реальных px. Уберётся в R1.
 *
 * Память: один `ALPHA_8` на весь экран (≈2,4 МБ) и один `ByteArray` того же размера;
 * пересоздаются только при смене размера.
 */
class ProbeScreen(act: EinkActivity) : Screen(act) {

    private var bmp: Bitmap? = null
    private var buf: ByteArray? = null
    private var pw = 0
    private var ph = 0

    private val pagePaint = Paint().apply {
        color = T.G0          // альфа из буфера: 0 → G15 фона, 255 → G0
        isFilterBitmap = false
        isDither = false
    }
    private val dst = RectF()

    private var line1 = ""
    private var line2 = ""
    private var gen = 0
    private var attached = false

    override fun layout(w: Float, h: Float) {
        super.layout(w, h)
        dst.set(0f, TOP, W, H)
    }

    override fun render(c: Canvas) {
        ink.rect(c, 0f, 0f, W, TOP, T.G14)
        ink.rect(c, 0f, TOP - 2f, W, 2f, T.G4)
        ink.rect(c, 0f, TOP, W, H - TOP, T.G15)
        ink.text(c, "Назад", 41f, 72f, T.TS_LABEL, 700, T.G1)   // шрифты — subset, «‹» в нём может не быть
        ink.text(c, ink.ellipsize(line1, W - 330f, T.TS_BODY_S, 600), 290f, 72f, T.TS_BODY_S, 600, T.G1)
        ink.text(c, ink.ellipsize(line2, W - 82f, T.TS_META, 600), 41f, 140f, T.TS_META, 600, T.G4)
        val b = bmp
        if (b != null) c.drawBitmap(b, null, dst, pagePaint)
    }

    override fun onUp(x: Float, y: Float) {
        // Центр полосы (x 468…936) открывает шторку B5 (Screen.onTouchEvent), «Назад» — по бокам.
        if (y <= TOP) act.pop()
    }

    /** Отладочный экран стоит на месте читалки: зона шторки — как у страницы книги. */
    override val shadeZone: Float get() = ru.ogrpfsense.dexpm8.ui.ShadeScreen.ZONE_READER

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        start()
    }

    override fun onDetachedFromWindow() {
        attached = false
        gen++                 // результат уже идущей отрисовки выбрасываем
        super.onDetachedFromWindow()
    }

    private fun start() {
        val dbg = debugPath
        debugPath = null
        val e = if (dbg != null) null else try {
            act.db.get(act.bookId) ?: act.db.lastOpened()
        } catch (t: Throwable) {
            Trace.step("probe: db FAIL ${t.javaClass.simpleName}: ${t.message}")
            null
        }
        bmp?.eraseColor(0)
        val path = dbg ?: e?.path
        if (path == null) {
            line1 = "нет книги"
            line2 = ""
            redraw()
            return
        }
        val format = e?.format ?: formatOf(path)
        val engine = EngineProbe.engineFor(format)
        line1 = "$format · ${engine ?: "—"} · …"
        line2 = path
        redraw()
        if (engine == null) {
            line1 = "$format: движка нет"
            redraw()
            return
        }

        // Страница — в реальных px под полосой, 1:1 с панелью.
        val w = D.px(W)
        val h = D.px(H - TOP)
        if (w <= 0 || h <= 0) return
        if (bmp == null || pw != w || ph != h) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            buf = ByteArray(w * h)
            pw = w
            ph = h
        }
        val out = buf!!
        val my = ++gen
        Trace.step("probe: start $engine $format ${w}x$h $path")
        EngineProbe.worker.execute {
            val loadErr = EngineProbe.load(engine)
            val t0 = SystemClock.uptimeMillis()
            val rc = if (loadErr != null) EngineProbe.E_LOAD else
                try {
                    EngineProbe.render(engine, path, w, h, out)
                } catch (t: Throwable) {
                    Trace.step("probe: render FAIL ${t.javaClass.simpleName}: ${t.message}")
                    -4
                }
            val ms = SystemClock.uptimeMillis() - t0
            val fonts = if (engine == EngineProbe.CRE) notoSerif() else ""
            Trace.step("probe: $engine rc=$rc ${ms}ms${if (loadErr != null) " load=$loadErr" else ""} $fonts")
            post {
                if (!attached || my != gen) return@post
                val b = bmp ?: return@post
                if (rc == EngineProbe.OK || rc == -4) b.copyPixelsFromBuffer(ByteBuffer.wrap(out, 0, pw * ph))
                line1 = "$format · $engine · $ms мс · ${EngineProbe.codeText(rc)}"
                line2 = if (loadErr != null) loadErr else "$path $fonts"
                if (Epd.ok) Epd.request(this, Epd.CLEAN, false)   // страница целиком — без следов
                redraw()
            }
        }
    }

    /** Есть ли «Noto Serif» в /system/fonts (гарнитура по умолчанию в пробе crengine). */
    private fun notoSerif(): String {
        val names = try {
            java.io.File("/system/fonts").list()
        } catch (t: Throwable) {
            null
        } ?: return "fonts: ?"
        val n = names.count { it.startsWith("NotoSerif-") }
        return "NotoSerif: $n из ${names.size}"
    }

    companion object {
        /** Высота полосы сверху, дизайн-px. Зона «Назад» — вся полоса (≥ 96 px). */
        private const val TOP = 180f

        /** Файл из debug-интента (`--es probe <путь>`, EinkActivity); берётся один раз. */
        @Volatile
        var debugPath: String? = null

        private fun formatOf(path: String): String {
            val l = path.lowercase(java.util.Locale.ROOT)
            if (l.endsWith(".fb2.zip")) return "fb2.zip"
            return l.substringAfterLast('.', "")
        }
    }
}
