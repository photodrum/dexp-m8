package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
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
 *   РАЗМЕР     подпись 59,1264; «−» 44,1312 145×120 r 50 (с тенью); поле 213,1325 979×92
 *              (вдавленное); значение 48/700 по центру; «+» 1220,1312 145×120 — светлая, как «−»
 *              (замечание 2026-11-26: в коде была тёмная, а в макете светлая)
 *
 * Отступления от макета (решение пользователя 2026-11-15, подробности — `docs/ROADMAP.md` п. 16):
 *  - шрифтов **четыре**: три из макета (Литерата, PT Serif, Manrope) и «Моно» из листа B4 —
 *    иначе выбор, сделанный в B4, в B70 не показать: гарнитура одна на книгу (`Prefs.textFace`);
 *  - «Открыть шрифт…» открывает лист [FontPicker] с файлами `.ttf`/`.otf` из папки `Fonts`;
 *    регистрация — в рантайме нативом (`Engine.registerFont`), код гарнитуры `FACE_USER + n`;
 *  - в строке своего шрифта справа надпись «удалить» 32/700 G3 (в макете её нет): шрифт уходит
 *    из списка и из `Prefs.userFontPath`, выбор возвращается на Литерату; файл в `Fonts` остаётся;
 *  - образец и название своего шрифта рисуются его собственным начертанием
 *    (`Typeface.createFromFile`); если в файле нет кириллицы, на образце будут «пустые» глифы.
 *
 * Кегль. В макете поле показывает «20 pt», но движок верстает в дизайн-px (8…72, умолчание
 * `Engine.FONT_SIZE_DEFAULT` = 32) — пересчёт `pt` в px потянул бы плотность экрана и разошёлся
 * бы с B4. Поэтому в поле — кегль движка; вопрос дизайну записан в `docs/DESIGN_QUESTIONS.md`.
 * Образец рисуется тем же кеглем (8…72); сколько строк влезает в панель, считается по метрикам.
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
     * Шаг строк панели. В макете он 152 при четырёх строках. Строк больше (свой шрифт, «Моно») —
     * панель растёт на шаг за каждую лишнюю строку, а блок «РАЗМЕР» уходит вниз на [shift];
     * шаг сжимается, только если сдвиг упёрся в [MAX_SHIFT] (нижний отступ экрана).
     */
    private var rowStep = ROW_STEP

    /** На сколько панель «ШРИФТ» выросла, а блок «РАЗМЕР» опустился относительно макета. */
    private var shift = 0f

    /** Открыт лист выбора файла шрифта. */
    private var pickerOpen = false

    /** Свои шрифты, уже зарегистрированные в этом сеансе: код гарнитуры → короткое имя. */
    private val userFonts = LinkedHashMap<Int, String>()

    /** Начертания своих шрифтов для образца: код гарнитуры → [Typeface] из файла. */
    private val userFaces = HashMap<Int, Typeface>()

    /** Метрики образца: высота строки зависит от гарнитуры и кегля. */
    private val metrics = Paint()

    private fun loadFace(path: String): Typeface? = try {
        Typeface.createFromFile(path)
    } catch (t: Throwable) {
        null
    }

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
            val face = if (code != null) loadFace(path) else null
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
                if (face != null) userFaces[code] = face
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
        kit.panel(c, 608f, PANEL_H + shift)
        val delX = deleteX()
        // Шаг макета (152) — строки как в B70. Сжатый шаг (редкий случай, сдвиг упёрся в низ экрана):
        // описание ближе к названию, разделитель посередине зазора (иначе он ложится на описание).
        val compact = rowStep < ROW_STEP
        val descDy = if (compact) DESC_DY_COMPACT else DESC_DY
        val bodyH = descDy + 40f                         // название + описание: до низа описания
        val k = bodyH / (DESC_DY + 40f)                  // во сколько сжат состав строки
        for (i in faces.indices) {
            val f = faces[i]
            val y = ROW_Y0 + i * rowStep
            // У своего шрифта справа «удалить», галочка встаёт левее неё; имя обрезается до галочки.
            val checkX = if (f.deletable) delX - DEL_GAP - CHECK_W else 1282.7f
            val titleW = if (f.deletable) checkX - DEL_GAP - 88f else 1228f
            val font = faceFont(f.code)
            val weight = if (f.code == p.textFace) 700 else 600
            ink.text(c, if (f.deletable) ink.ellipsize(f.title, titleW, 42f, weight, font) else f.title,
                88f, T.base(y, 42f, 1.25f), 42f, weight, T.G1, font)
            ink.text(c, f.desc, 88f, T.base(y + descDy, T.TS_BODY_S, 1.25f),
                T.TS_BODY_S, 600, T.G4)
            if (f.code == p.textFace) icons.draw(c, ICON_CHECK, checkX, y + 27.1f * k, CHECK_W, 43.9f)
            if (f.deletable) {
                ink.text(c, DEL_TEXT, delX, ink.baselineIn(y, 98f * k, T.TS_BODY_S, 700),
                    T.TS_BODY_S, 700, T.G3)
            }
            if (!f.open) {
                val sepY = if (compact) y + bodyH + (rowStep - bodyH) / 2f - 1f else y + rowStep - 28f
                ink.rect(c, 88f, sepY, 1228f, 2f, T.G11)
            }
        }
        // Блок «РАЗМЕР» едет вниз вместе с краем панели: подпись, клавиши и поле — одним сдвигом.
        c.save()
        c.translate(0f, shift)
        kit.section(c, "РАЗМЕР", 1264f)
        size(c, p.textSize)
        c.restore()
        if (dim >= 0) dimEnd(c, dim)
        if (pickerOpen) picker.draw(c)
    }

    /**
     * Образец: гарнитура и кегль выбранные (кегль движка 8…72, как в книге). Строк столько, сколько
     * влезает в панель по метрикам гарнитуры: на крупном кегле одна-две, в последней «…».
     */
    private fun sample(c: Canvas, face: Int, size: Int) {
        ink.convex(c, 41f, 250f, 1321f, 250f, T.R_TILE)
        val fs = size.toFloat()
        val font = faceFont(face)
        metrics.typeface = font
        metrics.textSize = fs
        val fm = metrics.fontMetrics
        val gap = fs * 0.25f
        val step = fm.descent - fm.ascent + gap
        val lines = ((SAMPLE_BOTTOM - SAMPLE_TOP + gap) / step).toInt().coerceAtLeast(1)
        ink.paragraph(c, SAMPLE_TEXT, 88f, SAMPLE_TOP, 1228f, fs, 400, T.G1,
            lineGap = gap, font = font, maxLines = lines)
    }

    /**
     * «−», поле с кеглем, «+». Клавиши 145×120 вместе с тенью (r 50), обе светлые; значки —
     * по оптическому центру кнопки: «−» иконкой `minus`, «+» двумя штрихами (иконка `plus`
     * в наборе только белая, для тёмной кнопки) — тёмный штрих и белый блик на 1.9 ниже, как у `minus`.
     */
    private fun size(c: Canvas, size: Int) {
        ink.convex(c, KEY_L_X, KEY_Y, KEY_W, KEY_H, 50f)
        icons.drawRound(c, "minus", KEY_L_X, KEY_Y, KEY_W, KEY_H, 45.7f, 47.6f)
        ink.inset(c, 213f, 1325f, 979f, 92f, 45f, T.G12)
        ink.textCenter(c, "$size pt", 213f, 979f, T.base(1337f, 48f, 1.25f), 48f, 700, T.G1)
        ink.convex(c, KEY_R_X, KEY_Y, KEY_W, KEY_H, 50f)
        val cx = KEY_R_X + ((KEY_W - T.SHADOW_DX) + T.SURFACE_DX) / 2f
        val cy = KEY_Y + ((KEY_H - T.SHADOW_DY) + T.SURFACE_DY) / 2f
        plusCross(c, cx, cy + 0.95f, T.G15)
        plusCross(c, cx, cy - 0.95f, T.G1)
    }

    /** Крест «+» 25.6×25.6 из двух штрихов 2.6 с круглыми концами вокруг ([cx], [cy]). */
    private fun plusCross(c: Canvas, cx: Float, cy: Float, color: Int) {
        ink.line(c, cx - 12.8f, cy, cx + 12.8f, cy, 2.6f, color)
        ink.line(c, cx, cy - 12.8f, cx, cy + 12.8f, 2.6f, color)
    }

    /** Левый край надписи «удалить» (правый край — 1316, как у разделителя строки). */
    private fun deleteX(): Float = DEL_RIGHT - ink.measure(DEL_TEXT, T.TS_BODY_S, 700)

    /** Строка панели «ШРИФТ»: код гарнитуры, подписи, признаки «Открыть шрифт…» и «можно удалить». */
    private class Face(val code: Int, val title: String, val desc: String, val open: Boolean,
                       val deletable: Boolean = false)

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
            faces.add(Face(code, name, "Свой шрифт из папки ${FontPicker.DIR}", false, true))
            if (++n >= USER_MAX) break
        }
        faces.add(Face(FACE_OPEN, "Открыть шрифт…", "TTF или OTF из папки ${FontPicker.DIR}", true))
        // Четыре строки — макет (панель 616, шаг 152). Каждая лишняя строка растит панель на шаг,
        // «РАЗМЕР» опускается на столько же, пока не дойдёт до нижнего отступа ([MAX_SHIFT]);
        // дальше (в списке больше шести строк) — сжимаем шаг. Последняя строка короче остальных:
        // у неё нет разделителя, поэтому высота строк = (n − 1) шагов + [LAST_ROW_H].
        val count = faces.size
        val want = Math.max(0, count - 4) * ROW_STEP
        shift = Math.min(want, MAX_SHIFT)
        rowStep = Math.min(ROW_STEP, (PANEL_INNER_H + shift - LAST_ROW_H) / (count - 1))
    }

    /** Шрифт строки и образца: у каждой гарнитуры своё начертание. */
    private fun faceFont(code: Int) = when (code) {
        Engine.FACE_SANS -> T.manrope(ink.res, 500)
        Engine.FACE_MONO -> android.graphics.Typeface.MONOSPACE
        Engine.FACE_PTSERIF -> T.ptserif(ink.res, 400)
        else -> userFaces[code] ?: T.literata(ink.res, 400)   // свой шрифт — своим начертанием
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
        if (y >= 1300f + shift && y <= 1434f + shift) {
            if (x in 41f..190f) { setSize(p.textSize - TypographySheet.SIZE_STEP); return }
            if (x in 1216f..1370f) { setSize(p.textSize + TypographySheet.SIZE_STEP); return }
        }
        // Строки «ШРИФТ»: касание всей строки, вместе с подписями.
        if (y >= ROW_Y0 - HIT_PAD && y < ROW_Y0 + faces.size * rowStep) {
            val i = ((y - ROW_Y0 + HIT_PAD) / rowStep).toInt().coerceIn(0, faces.size - 1)
            val f = faces[i]
            if (f.open) { openPicker(); return }
            // «удалить» справа: зона шире надписи на DEL_HIT, чтобы попадать пальцем.
            if (f.deletable && x >= deleteX() - DEL_HIT) { removeUserFont(f.code); return }
            if (f.code != p.textFace) { p.textFace = f.code; redraw() }
        }
    }

    /**
     * «удалить»: свой шрифт уходит из списка и из `Prefs`; если он был выбран — Литерата.
     * Файл в `Fonts` не трогаем (можно снова открыть). В движке шрифт остаётся зарегистрированным
     * до перезапуска — ничему не мешает, повторная регистрация того же файла даст тот же код.
     */
    private fun removeUserFont(code: Int) {
        val p = act.prefs
        userFonts.remove(code)
        userFaces.remove(code)
        p.userFontPath = ""
        if (p.textFace == code) p.textFace = Engine.FACE_SERIF
        redraw()
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
            val face = loadFace(path)
            post {
                if (!isAttachedToWindow) return@post
                userFonts[code] = name.substringBeforeLast('.')
                if (face != null) userFaces[code] = face
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
        /** Панель «ШРИФТ» в макете (четыре строки). */
        private const val PANEL_H = 616f
        /** Высота последней строки панели без разделителя: 587 − 3 × 152. */
        private const val LAST_ROW_H = 131f
        /** Предел сдвига «РАЗМЕРа»: клавиши (низ 1432) остаются выше 1835, как нижние кнопки экранов. */
        private const val MAX_SHIFT = 400f
        private const val HIT_PAD = 8f
        /** Описание под названием: в макете +53.5, в сжатой панели (5+ строк) +48. */
        private const val DESC_DY = 53.5f
        private const val DESC_DY_COMPACT = 48f

        /** Образец: верх текста и нижняя граница в панели 253…493 (кегль 8…72, строки — по метрикам). */
        private const val SAMPLE_TOP = 306f
        private const val SAMPLE_BOTTOM = 495f

        /** Клавиши «−» и «+»: габарит вместе с тенью 145×120 (макет B70). */
        private const val KEY_L_X = 44f
        private const val KEY_R_X = 1220f
        private const val KEY_Y = 1312f
        private const val KEY_W = 145f
        private const val KEY_H = 120f

        /** «удалить»: правый край, зазор до галочки и имени, расширение зоны касания, ширина галочки. */
        private const val DEL_TEXT = "удалить"
        private const val DEL_RIGHT = 1316f
        private const val DEL_GAP = 24f
        private const val DEL_HIT = 30f
        private const val CHECK_W = 42f

        /** Код строки «Открыть шрифт…» — не гарнитура, в `Prefs` не пишется. */
        private const val FACE_OPEN = -1
        /** Сколько своих шрифтов показывать: панель 616 px — 4 строки по 152 плюс «Открыть». */
        private const val USER_MAX = 1
    }
}
