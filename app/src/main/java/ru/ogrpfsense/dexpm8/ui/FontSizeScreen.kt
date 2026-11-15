package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import java.io.File
import ru.ogrpfsense.dexpm8.reader.Engine
import ru.ogrpfsense.dexpm8.reader.TypographySheet

/**
 * B70 · Настройки · «Шрифт и размер» (R3, продолжение после B4). Числа — `design/v5/B70.json`.
 *
 *   шапка      «Назад» 41,113.5 107×112; «Шрифт и размер» 176,105 60/700;
 *              «Чтение по умолчанию» 176,181 32/600 G4
 *   образец    панель 41,250 1321×250 (выпуклая r 51); текст 88,306 1228×128 — гарнитурой
 *              и кеглем выбранного (в макете Literata/40)
 *   ШРИФТ      подпись 59,560 30/700 G5; панель 41,608 1321×616; строки: название 42/600
 *              в 88,637 (+152), описание 32/600 G4 (+53.5), `check` 42×43.9 в 1282.7,
 *              разделитель 88,y+124 1228×2 G11; «Открыть шрифт…» — последняя строка
 *   РАЗМЕР     подпись 59,1264; «−» 47,1315 140×110 r 50; поле 213,1325 979×92 (вдавленное);
 *              значение 48/700 по центру; «+» 1223,1315 — тёмная
 *
 * Отступления от макета (решение пользователя 2026-11-15, подробности — `docs/ROADMAP.md` п. 16):
 *  - шрифтов **четыре**: три из макета (Литерата, PT Serif, Manrope) и «Моно» из листа B4 —
 *    иначе выбор, сделанный в B4, в B70 не показать: гарнитура одна на книгу (`Prefs.textFace`);
 *  - «Открыть шрифт…» открывает лист [FontPicker] с файлами `.ttf`/`.otf` из папки `Fonts`;
 *    регистрация — в рантайме нативом (`Engine.registerFont`), код гарнитуры `FACE_USER + n`.
 *
 * Кегль. В макете поле показывает «20 pt», но движок верстает в дизайн-px (8…72, умолчание
 * `Engine.FONT_SIZE_DEFAULT` = 32) — пересчёт `pt` в px потянул бы плотность экрана и разошёлся
 * бы с B4. Поэтому в поле — кегль движка; вопрос дизайну записан в `docs/DESIGN_QUESTIONS.md`.
 *
 * Значения общие с B4 и B39 (`Prefs`, R3.5): экран меняет их сразу, «Применить» нет.
 * Дверь — строка «Шрифт и размер» на B39.
 */
class FontSizeScreen(act: EinkActivity) : Screen(act) {

    private val status = StatusBar(act, this, ink, icons)
    private val kit = SettingsKit(ink, icons)
    private val picker = FontPicker(ink, icons)

    /** Строки панели «ШРИФТ» на этот кадр. */
    private var faces = ArrayList<Face>()

    /**
     * Шаг строк панели. В макете он 152 при четырёх строках; своих шрифтов и «Моно» может
     * стать больше, и тогда 5 × 152 = 760 не влезает в панель 616 — шаг подстраивается.
     */
    private var rowStep = ROW_STEP

    /** Открыт лист выбора файла шрифта. */
    private var pickerOpen = false

    /** Свои шрифты, уже зарегистрированные в этом сеансе: код гарнитуры → короткое имя. */
    private val userFonts = LinkedHashMap<Int, String>()

    /** Идёт ли восстановление своего шрифта из `Prefs` (после старта приложения). */
    private var restoring = false

    /**
     * Код своего шрифта (`FACE_USER + n`) живёт только в процессе: после старта приложения
     * `textFace` указывает на ничто, и книга верстается Литератой. Здесь путь из `Prefs`
     * регистрируется заново — код получается прежним, если файл тот же.
     */
    private fun restoreUserFont() {
        if (restoring || userFonts.isNotEmpty()) return
        val p = act.prefs
        val path = p.userFontPath
        if (path.isEmpty()) return
        restoring = true
        Engine.worker.execute {
            val code = Engine.registerFont(path)
            post {
                restoring = false
                if (!isAttachedToWindow) return@post
                if (code == null) {
                    // Файл убрали с карты — возвращаемся к Литерате, иначе текст не отрисуется.
                    p.userFontPath = ""
                    if (p.textFace >= Engine.FACE_USER) p.textFace = Engine.FACE_SERIF
                    redraw()
                    return@post
                }
                userFonts[code] = java.io.File(path).name.substringBeforeLast('.')
                if (p.textFace >= Engine.FACE_USER && code != p.textFace) p.textFace = code
                redraw()
            }
        }
    }

    override fun render(c: Canvas) {
        val p = act.prefs
        buildRows(p.textFace)

        ink.rect(c, 0f, 0f, W, H, T.BG)
        status.draw(c)
        // Под листом выбора шрифта экран приглушён — как под [ShelfPicker] на B12.
        val dim = if (pickerOpen) dimBegin(c) else -1
        kit.header(c, "Шрифт и размер", "Чтение по умолчанию")
        sample(c, p.textFace, p.textSize)
        kit.section(c, "ШРИФТ", 560f)
        kit.panel(c, 608f, 616f)
        for (i in faces.indices) {
            val f = faces[i]
            val y = ROW_Y0 + i * rowStep
            ink.text(c, f.title, 88f, T.base(y, 42f, 1.25f), 42f,
                if (f.code == p.textFace) 700 else 600, T.G1, faceFont(f.code))
            ink.text(c, f.desc, 88f, T.base(y + 53.5f, T.TS_BODY_S, 1.25f),
                T.TS_BODY_S, 600, T.G4)
            if (f.code == p.textFace) icons.draw(c, ICON_CHECK, 1282.7f, y + 27.1f, 42f, 43.9f)
            if (!f.open) ink.rect(c, 88f, y + rowStep - 28f, 1228f, 2f, T.G11)
        }
        kit.section(c, "РАЗМЕР", 1264f)
        size(c, p.textSize)
        if (dim >= 0) dimEnd(c, dim)
        if (pickerOpen) picker.draw(c)
    }

    /** Образец: гарнитура и кегль выбранные, не больше трёх строк в панели 250 px. */
    private fun sample(c: Canvas, face: Int, size: Int) {
        ink.convex(c, 41f, 250f, 1321f, 250f, T.R_TILE)
        val fs = size.coerceIn(SAMPLE_MIN, SAMPLE_MAX).toFloat()
        ink.paragraph(c, SAMPLE_TEXT, 88f, 306f, 1228f, fs, 400, T.G1,
            lineGap = fs * 0.25f, font = faceFont(face), maxLines = 3)
    }

    /** «−», поле с кеглем, «+» — 140×110 с радиусом 50, как в макете. */
    private fun size(c: Canvas, size: Int) {
        ink.convex(c, 44f, 1312f, 140f, 110f, 50f)
        ink.textCenter(c, "−", 47f, 140f, T.base(1315f, 56f, 1.25f), 56f, 600, T.G1)
        ink.inset(c, 213f, 1325f, 979f, 92f, 45f, T.G12)
        ink.textCenter(c, "$size pt", 213f, 979f, T.base(1337f, 48f, 1.25f), 48f, 700, T.G1)
        ink.convexDark(c, 1223f, 1312f, 140f, 110f, 50f)
        ink.textCenter(c, "+", 1223f, 140f, T.base(1315f, 56f, 1.25f), 56f, 600, T.G15)
    }

    /** Строка панели «ШРИФТ»: код гарнитуры, подписи и признак «Открыть шрифт…». */
    private class Face(val code: Int, val title: String, val desc: String, val open: Boolean)

    /**
     * Строки панели. Встроенные — в порядке B70 (Литерата, PT Serif, Manrope). «Моно» есть
     * только в листе B4 и в макете B70 его нет — показываем строку лишь когда она выбрана,
     * чтобы список не расходился с макетом (иначе он 5 строк и теснее, чем B70.json).
     * Затем свои шрифты из папки `Fonts`; последняя строка — «Открыть шрифт…».
     */
    private fun buildRows(face: Int) {
        faces = ArrayList(6)
        faces.add(Face(Engine.FACE_SERIF, "Литерата", "С засечками · по умолчанию", false))
        faces.add(Face(Engine.FACE_PTSERIF, "PT Serif", "С засечками", false))
        faces.add(Face(Engine.FACE_SANS, "Manrope", "Без засечек", false))
        if (face == Engine.FACE_MONO) faces.add(Face(Engine.FACE_MONO, "Моно", "Без засечек · для таблиц", false))
        var n = 0
        for ((code, name) in userFonts) {
            faces.add(Face(code, name, "Свой шрифт из папки ${FontPicker.DIR}", false))
            if (++n >= USER_MAX) break
        }
        faces.add(Face(FACE_OPEN, "Открыть шрифт…", "TTF или OTF из папки ${FontPicker.DIR}", true))
        // Четыре строки — шаг макета (152); больше — теснее, иначе последняя наезжает
        // на раздел «РАЗМЕР» (проверено на книге 2026-11-15).
        rowStep = Math.min(ROW_STEP, PANEL_INNER_H / faces.size)
    }

    /** Шрифт строки и образца: у каждой гарнитуры своё начертание. */
    private fun faceFont(code: Int) = when (code) {
        Engine.FACE_SANS -> T.manrope(ink.res, 500)
        Engine.FACE_MONO -> android.graphics.Typeface.MONOSPACE
        Engine.FACE_PTSERIF -> T.ptserif(ink.res, 400)
        else -> T.literata(ink.res, 400)          // Литерата и свои шрифты (образец — Литератой)
    }

    override fun onUp(x: Float, y: Float) {
        if (pickerOpen) {
            when (picker.hit(x, y)) {
                FontPicker.ACT_CLOSE -> closePicker()
                FontPicker.ACT_PICK -> register(picker.fileAt(picker.row))
            }
            return
        }
        if (kit.isBack(x, y)) { act.pop(); return }
        val p = act.prefs
        // «−» и «+»: кегль шагом 2 в пределах движка (8…72), как в листе B4.
        if (y >= 1300f && y <= 1434f) {
            if (x in 41f..190f) { setSize(p.textSize - TypographySheet.SIZE_STEP); return }
            if (x in 1216f..1370f) { setSize(p.textSize + TypographySheet.SIZE_STEP); return }
        }
        // Строки «ШРИФТ»: касание всей строки, вместе с подписями.
        if (y >= ROW_Y0 - HIT_PAD && y < ROW_Y0 + faces.size * rowStep) {
            val i = ((y - ROW_Y0 + HIT_PAD) / rowStep).toInt().coerceIn(0, faces.size - 1)
            val f = faces[i]
            if (f.open) { openPicker(); return }
            if (f.code != p.textFace) { p.textFace = f.code; redraw() }
        }
    }

    /** Кегль в пределах движка: та же шкала и шаг, что в листе B4. */
    private fun setSize(v: Int) {
        val n = v.coerceIn(TypographySheet.SIZE_MIN, TypographySheet.SIZE_MAX)
        val p = act.prefs
        if (n == p.textSize) return
        p.textSize = n
        redraw()
    }

    private fun openPicker() {
        picker.open()
        pickerOpen = true
        redraw()
    }

    private fun closePicker() {
        pickerOpen = false
        redraw()
    }

    /**
     * Зарегистрировать выбранный файл шрифта в движке и выбрать его. Регистрация нативная,
     * поэтому идёт в поток движка: `fontMan` живёт там же, где `LVDocView`.
     * Имя строки — из имени файла: семейство из name-таблицы бывает длинным и с суффиксами.
     */
    private fun register(file: File?) {
        pickerOpen = false
        redraw()
        if (file == null) return
        val name = file.name
        val path = file.absolutePath
        Engine.worker.execute {
            val code = Engine.registerFont(path)
            Trace.step("b70: font $name → ${code ?: "FAIL"}")
            if (code == null) return@execute
            post {
                if (!isAttachedToWindow) return@post
                userFonts[code] = name.substringBeforeLast('.')
                act.prefs.userFontPath = path
                act.prefs.textFace = code
                redraw()
            }
        }
    }

    /** Экран ушёл — лист не должен остаться открытым при возврате (экран живёт в кэше). */
    override fun backPressed(): Boolean {
        if (pickerOpen) { closePicker(); return true }
        return false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
        restoreUserFont()
    }

    override fun onDetachedFromWindow() {
        status.detach()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val ICON_CHECK = "check"
        private const val SAMPLE_TEXT =
            "Все счастливые семьи похожи друг на друга, каждая несчастливая семья несчастлива по-своему."

        /** Строки панели «ШРИФТ»: первая название в 88,637, шаг 152 (разделитель в +124). */
        private const val ROW_Y0 = 637f
        private const val ROW_STEP = 152f
        /** Внутренняя высота панели «ШРИФТ» от первой строки до низа (608 + 616 − 637). */
        private const val PANEL_INNER_H = 587f
        private const val HIT_PAD = 8f

        /** Образец: кегль книги 8…72, но в панель 250 px больше трёх строк не влезет. */
        private const val SAMPLE_MIN = 10
        private const val SAMPLE_MAX = 40

        /** Код строки «Открыть шрифт…» — не гарнитура, в `Prefs` не пишется. */
        private const val FACE_OPEN = -1
        /** Сколько своих шрифтов показывать: панель 616 px — 4 строки по 152 плюс «Открыть». */
        private const val USER_MAX = 1
    }
}
