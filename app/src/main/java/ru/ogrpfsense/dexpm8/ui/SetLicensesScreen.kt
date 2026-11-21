package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas

/**
 * B75 · Настройки · Лицензии. Числа — из design/v5/B75.json.
 *
 *   шапка        «Назад» 41,113.5 (107×112); «Лицензии» 176,105 60/700;
 *                подзаголовок 176,181 32/600 G4
 *   раздел       «ОТКРЫТЫЕ КОМПОНЕНТЫ» 59,250 30/700 G5
 *   панель       41,298 1321×1072 (r R_TILE)
 *   строка       название 88,y 42/700 G1; описание 88,y+53.5 32/600 G4;
 *                разделитель 88,y+124 1228×2; шаг строк 152, первая на 327
 *   пагинация    желоб 614.3,1766 175.5×29.2, сегмент G3 (деталь B6)
 *
 * Данные — `docs/LICENSES.md`: один компонент на строку, колонки «название · лицензия ·
 * назначение». Версии не выводятся: на E-Ink они не нужны, а сверить их можно по
 * `git submodule status`.
 *
 * **Отступления от макета (решение 2026-11-19, вариант «а»):**
 * 1. Строк 16, а в макете 7 мест — список листается страницами по 7, пагинация взята
 *    с B6 (на B75 её нет, но и места под неё макет не занимает). Одна строка = один
 *    компонент: в сгруппированной строке читатель сам разбирался бы, какая лицензия
 *    к чему, а экран для того и нужен, чтобы это было видно без разбирательства.
 * 2. Из тем макета убраны `MuPDF` (AGPL-3.0, удалён в 7.3б — PDF нет), `Hunspell`
 *    (в сборку не входит, переносы — `hyphman` внутри crengine) и `NanoHTTPD`
 *    (загрузка по Wi-Fi — свой `core/WifiShare.kt` на `ServerSocket`). Вместо них —
 *    то, что действительно линкуется: antiword, FreeType, HarfBuzz, FriBidi,
 *    libjpeg-turbo, libpng, libunibreak, utf8proc, xxHash, zlib.
 * 3. Строки «Викисловарь» и «MyMemory» добавлены по замечанию из `DESIGN_QUESTIONS`
 *    (п. 34 `LICENSES.md`): данные приходят по сети и в APK не входят.
 * 4. Подзаголовок шапки — «GPL-2.0 · открытые исходники» вместо макетного «Об устройстве»:
 *    так итог лицензии проекта виден сразу, без прокрутки до последней страницы
 *    (тот же приём, что на B73 с «Викисловарь · MyMemory»).
 * 5. Шевронов `next` нет: в макете они ведут к тексту лицензии, но текстов в APK нет
 *    (`assets/` отсутствует), борда-просмотрщика тоже. Строки только показывают сведения.
 * 6. Под панелью — подпись «Исходники — …» с адресом репозитория (GPL-2.0 §3: получателю APK
 *    нужно видеть, где взять код). Место свободное: панель кончается на 1370, пагинация —
 *    с 1766. Подпись не нажимается: браузера на книге может не быть, адрес читается глазами
 *    и повторён в `docs/LICENSES.md`. Видна на всех страницах — она вне списка.
 *
 * Строка «Literata» рисуется своей гарнитурой, как в макете (Literata 600), остальные —
 * Manrope; «Manrope» — Manrope 700, как все строки.
 *
 * Состояние — номер страницы, как на B6: это положение списка, а не настройка, поэтому
 * в `Prefs` не пишется и при новом входе список открывается с первой страницы.
 */
class SetLicensesScreen(act: EinkActivity) : Screen(act) {

    private val status = StatusBar(act, this, ink, icons)
    private val kit = SettingsKit(ink, icons)

    private var page = 0

    override fun render(c: Canvas) {
        ink.rect(c, 0f, 0f, W, H, T.BG)
        kit.header(c, "Лицензии", "GPL-2.0 · открытые исходники")

        kit.section(c, "ОТКРЫТЫЕ КОМПОНЕНТЫ", 250f)
        kit.panel(c, 298f, PANEL_H)

        val first = page * PER_PAGE
        val last = Math.min(ITEMS.size, first + PER_PAGE)
        for (i in first until last) {
            // Разделитель — только между строками: под последней строкой страницы его нет,
            // как и в макете (шесть линий на семь строк, 451…1211).
            row(c, ITEMS[i], ROW_Y + (i - first) * ROW_STEP, i < last - 1)
        }
        pager(c)
        // GPL-2.0 §3, подпись под панелью: адрес исходников виден с любой страницы списка.
        ink.text(c, SOURCES, 41f, T.base(SOURCE_Y, T.TS_META, 1.25f), T.TS_META, 600, T.G5)
    }

    /** Строка: название, описание, разделитель на y + 124 (если [line] — не последняя). */
    private fun row(c: Canvas, e: Item, y: Float, line: Boolean) {
        if (e.literata) {
            ink.text(c, e.name, 88f, T.base(y, T.TS_ACTION, 1.25f, true), T.TS_ACTION, 600, T.G1,
                T.literata(ink.res, 600))
        } else {
            kit.title(c, e.name, y)
        }
        kit.desc(c, ink.ellipsize(e.desc, TEXT_W, T.TS_BODY_S, 600), y + DESC_DY)
        if (line) kit.divider(c, y + DIVIDER_DY)
    }

    /**
     * Пагинация B6: желоб 175.5×29.2 с углублением и сегментом G3 по числу страниц.
     * Пока страница одна, не рисуется.
     */
    private fun pager(c: Canvas) {
        val n = pages()
        if (n < 2) return
        ink.inset(c, SEG_X, SEG_Y, SEG_W, SEG_H, T.R_TRACK)
        val w = SEG_TOTAL / n
        ink.roundRect(c, SEG_FILL_X + page * w, SEG_FILL_Y, w, SEG_FILL_H, 8.2265625f, T.G3)
    }

    private fun pages() = Math.max(1, (ITEMS.size + PER_PAGE - 1) / PER_PAGE)

    private fun goPage(p: Int) {
        val np = Math.max(0, Math.min(pages() - 1, p))
        if (np == page) return
        page = np
        redraw()
    }

    override fun onUp(x: Float, y: Float) {
        if (kit.isBack(x, y)) {
            act.pop()
            return
        }
        // Пагинация: полоса под списком, левая половина — назад, правая — вперёд (как B6).
        if (y >= 1730f && y <= 1840f) {
            goPage(if (x < W / 2f) page - 1 else page + 1)
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

    /** Строка списка: название, «лицензия · назначение», флаг гарнитуры-образца. */
    private class Item(val name: String, val desc: String, val literata: Boolean = false)

    private companion object {
        /** Строк на странице: 7-я на 1239, её описание кончается на 1332 — панель до 1370. */
        private const val PER_PAGE = 7
        private const val PANEL_H = 1072f
        /** Верх названия первой строки. */
        private const val ROW_Y = 327f
        /** Шаг строк: название → название (числа макета 327, 479, 631 …). */
        private const val ROW_STEP = 152f
        /** Описание ниже названия: 380.5 − 327. */
        private const val DESC_DY = 53.5f
        /** Разделитель: блок 0…93.5 по центру ячейки 152 — (152 − 2 + 93.5) / 2 = 121.8. */
        private const val DIVIDER_DY = 121.8f
        /** Ширина описания: колонка до 1305 (там в макете шеврон), минус запас. */
        private const val TEXT_W = 1188f

        /**
         * GPL-2.0 §3: где взять исходники. Подпись под панелью (она кончается на 1370),
         * выше пагинации (1766). Адрес — тот же публичный репозиторий, что и в `update.json`.
         */
        private const val SOURCES = "Исходники — git.ogrpfsense.ru/denisle/dexp-m8"
        private const val SOURCE_Y = 1420f

        // Пагинация — деталь B6: желоб 614.3,1766 175.5×29.2, дно 617,1771.5 170×21,
        // сегмент 619.7,1771.5 высотой 16.5; сумма сегментов — желоб минус зазоры.
        private const val SEG_X = 614.3f
        private const val SEG_Y = 1766f
        private const val SEG_W = 175.5f
        private const val SEG_H = 29.2f
        private const val SEG_FILL_X = 619.7f
        private const val SEG_FILL_Y = 1771.5f
        private const val SEG_FILL_H = 16.5f
        private const val SEG_TOTAL = 164.6f

        /**
         * Список по `docs/LICENSES.md`: движки, библиотеки, шрифты, данные по сети.
         * Один компонент — одна строка: лицензия в описании относится именно к нему.
         */
        private val ITEMS = arrayOf(
            Item("CREngine", "GPL-2.0 · движок EPUB и FB2"),
            Item("DjVuLibre", "GPL-2.0 · движок DjVu"),
            Item("antiword", "GPL-2.0 · чтение DOC"),
            Item("FreeType", "GPL-2.0 · отрисовка шрифтов"),
            Item("HarfBuzz", "MIT · раскладка текста"),
            Item("FriBidi", "LGPL-2.1 · письмо справа налево"),
            Item("libpng", "libpng · картинки PNG"),
            Item("libjpeg-turbo", "IJG + BSD-3 · картинки JPEG"),
            Item("libunibreak", "zlib · разбивка строк"),
            Item("utf8proc", "MIT · нормализация Unicode"),
            Item("xxHash", "BSD-2 · быстрые хеши"),
            Item("zlib", "zlib · сжатие, из NDK"),
            Item("Manrope", "SIL Open Font License 1.1 · шрифт интерфейса"),
            Item("Literata", "SIL Open Font License 1.1 · шрифт книги", true),
            Item("Викисловарь", "CC BY-SA 4.0 · словарь по сети"),
            Item("MyMemory", "Сервис · перевод по сети"),
        )
    }
}
