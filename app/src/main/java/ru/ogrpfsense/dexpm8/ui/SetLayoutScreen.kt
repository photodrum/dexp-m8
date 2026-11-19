package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas

/**
 * B72 · Настройки · раскладки клавиатуры. Числа — из design/v5/B72.json:
 *
 *   шапка      «Назад» 41,113.5 (107×112); «Раскладки клавиатуры» 176,105 60/700;
 *              подзаголовок 176,181 32/600 G4 — «Дата, время, язык»
 *   раздел     «РАСКЛАДКИ» 59,250 30/700 G5
 *   панель     41,298 1321×768 — пять строк шагом 152: название 88,y 42/700 G1,
 *              описание 88,y+53.5 32/600 G4, тумблер 1177,y+13.4 139×73; разделитель 88,y+124
 *   раздел     «КЛАВИАТУРА» 59,1106; панель 41,1154 1321×404
 *   строка     «Переключение» 88,1211.3 + значение «Кнопка RU/EN» до 1267.6 + `next` 1305,1226.4;
 *              разделитель 88,1277; «Звук нажатия» 88,1312, тумблер 1177,1303.5;
 *              разделитель 88,1399; «Обновлять экран при вводе» 88,1412.6 + описание 88,1466.1,
 *              тумблер 1177,1426.1
 *
 * Тумблеры пишут `Prefs.layouts` (бит = `Keyboard.LANG_*`), `Prefs.keySound` и `Prefs.keyClean`;
 * их читает своя клавиатура ([Keyboard]) в момент нажатия, поэтому смена видна сразу на всех
 * листах и экранах ввода. Последнюю раскладку выключить нельзя: набирать стало бы нечем —
 * касание по её тумблеру ничего не делает (отступление от макета, в нём все пять включены).
 *
 * «Кнопка RU/EN» и `next` — не дверь, а подпись: клавиша одна на всех, и она уже подписана кодом
 * текущей раскладки («RU», «УК», «DE» …). Отдельного экрана переключения в макете нет.
 *
 * «Звук нажатия» кроме `Prefs.keySound` пишет системный `Settings.System.SOUND_EFFECTS_ENABLED`
 * (`Sys.setSoundKey`, право WRITE_SETTINGS): без него `AudioManager.playSoundEffect` молчит.
 * Системный «клик» при выключении не трогаем — это чужие звуки. Экран не показывает, выдано ли
 * право: тумблер остаётся включённым, звук идёт через ту же систему, но без права его не будет —
 * решается на книге.
 */
class SetLayoutScreen(act: EinkActivity) : Screen(act) {

    private val status = StatusBar(act, this, ink, icons)
    private val kit = SettingsKit(ink, icons)

    /** Верх строк «РАСКЛАДКИ»: панель 298 + отступ 29, шаг 152 (как у пяти строк макета). */
    private val rowY = FloatArray(Keyboard.LAYOUTS.size) { FIRST + it * STEP }

    override fun render(c: Canvas) {
        val mask = act.prefs.layouts
        ink.rect(c, 0f, 0f, W, H, T.BG)
        status.draw(c)
        kit.header(c, "Раскладки клавиатуры", "Дата, время, язык")

        kit.section(c, "РАСКЛАДКИ", 250f)
        kit.panel(c, 298f, 768f)
        for (i in Keyboard.LAYOUTS.indices) {
            val l = Keyboard.LAYOUTS[i]
            val y = rowY[i]
            kit.title(c, l.name, y)
            kit.desc(c, l.desc, y + DESC_DY)
            kit.toggle(c, y + TOGGLE_DY, on(mask, i))
            // Под последней строкой разделителя нет — как в макете.
            if (i < Keyboard.LAYOUTS.size - 1) kit.divider(c, y + DIVIDER_DY)
        }

        kit.section(c, "КЛАВИАТУРА", 1106f)
        kit.panel(c, 1154f, 404f)
        kit.title(c, "Переключение", 1211.3f)
        kit.valueRow(c, "Кнопка RU/EN", 1219.4f)
        kit.next(c, 1226.4f)
        kit.divider(c, 1277f)

        kit.title(c, "Звук нажатия", 1312f)
        kit.toggle(c, 1303.5f, act.prefs.keySound)
        kit.divider(c, 1399f)

        kit.title(c, "Обновлять экран при вводе", 1412.6f)
        kit.desc(c, "Чётче, но медленнее", 1466.1f)
        kit.toggle(c, 1426.1f, act.prefs.keyClean)
    }

    override fun onUp(x: Float, y: Float) {
        if (kit.isBack(x, y)) {
            act.pop()
            return
        }
        val p = act.prefs
        for (i in Keyboard.LAYOUTS.indices) {
            if (!kit.inRow(x, y, rowY[i], STEP)) continue
            val mask = p.layouts
            if (on(mask, i)) {
                // Последнюю не выключаем: клавиатуре нужна хотя бы одна буквенная раскладка.
                if (mask == 1 shl i) {
                    Trace.step("B72: последняя раскладка оставлена")
                    return
                }
                p.layouts = mask and (1 shl i).inv()
                Trace.step("B72: layout $i off -> ${p.layouts}")
            } else {
                p.layouts = mask or (1 shl i)
                Trace.step("B72: layout $i on -> ${p.layouts}")
            }
            redraw()
            return
        }
        if (kit.inRow(x, y, 1277f, 122f)) {
            p.keySound = !p.keySound
            // Включили — система должна разрешить звук; выключили — её тумблер не трогаем.
            if (p.keySound) act.withWriteSettings { act.sys.setSoundKey(true) }
            Trace.step("B72: key sound ${p.keySound}")
            redraw()
            return
        }
        if (kit.inRow(x, y, 1399f, 159f)) {
            p.keyClean = !p.keyClean
            Trace.step("B72: key clean ${p.keyClean}")
            redraw()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
    }

    override fun onDetachedFromWindow() {
        status.detach()
        super.onDetachedFromWindow()
    }

    private fun on(mask: Int, i: Int) = (mask shr i) and 1 == 1

    private companion object {
        /** Первая строка: панель 298 + 29 (327 в макете). */
        const val FIRST = 327f
        /** Шаг строк: 327 / 479 / 631 / 783 / 935 в макете — ровно 152. */
        const val STEP = 152f
        /** Описание ниже названия: 380.5 − 327. */
        const val DESC_DY = 53.5f
        /** Тумблер ниже названия: 340.4 − 327. */
        const val TOGGLE_DY = 13.4f
        /** Разделитель ниже названия: 451 − 327. */
        const val DIVIDER_DY = 124f
    }
}
