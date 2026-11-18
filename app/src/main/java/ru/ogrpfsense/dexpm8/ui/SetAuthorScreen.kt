package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas

/**
 * «Автор» — экран с QR-кодом страницы на Бусти (решение человека 2026-11-18).
 *
 * Дверь — нижняя тёмная кнопка на B43 «Об устройстве»: раньше там была «Проверить обновления»,
 * теперь «Автор» с чашкой кофе. Проверка обновлений от этого не потерялась: строка «Обновления»
 * в «ОБСЛУЖИВАНИИ» ведёт на B76, а тот проверяет манифест сам при открытии.
 *
 * **Своего борда в макете нет.** Экран собран из готовых деталей B43/B76, как требует
 * `docs/DESIGN_USAGE.md`: шапка `SettingsKit.header`, панель `SettingsKit.panel`, гнездо
 * `Ink.inset` с иконкой, заголовок 48/700 и подзаголовок 32/600 G4 (как шапка панели на B76),
 * тёмная кнопка внизу 41,1693 1321×142 с подписью по центру (как «Понятно» на B45).
 *
 *   шапка        «Назад» 41,113.5 (107×112); «Автор» 176,105 60/700; «Денис Лампусов» 176,181 32/600 G4
 *   раздел       «ПОДДЕРЖАТЬ АВТОРА» 59,250 30/700 G5
 *   панель       41,298 1321×1180
 *   гнездо       88,334.8 140×140 (вдавленное, r 70); иконка «cup» 56×56 по центру
 *   заголовок    268,348 48/700 «Чашка кофе»; подзаголовок 268,416 32/600 G4 «Приложение бесплатное»
 *   подложка QR  332,520 740×740 (G15, r 40) — тихая зона 4 модуля по 20 px
 *   QR           412,600 580×580 (29 модулей по 20 px, чёрный G0)
 *   пояснение    152,1290 1100, 32/600 G4, по центру, lh 1.4 (две строки до ≈1380)
 *   ссылка       38/700 G1 по центру, рамка 1402 (базовая линия 1420.6)
 *   кнопка       41,1693 1321×142 (тёмная), подпись «Понятно» 42/700 G15 по центру
 *
 * **Почему QR матрицей, а не библиотекой.** AndroidX и сторонних зависимостей в проекте нет
 * (осознанно, см. `app/build.gradle.kts`), а тянуть генератор QR ради одной картинки — лишние
 * сотни килобайт в APK и в PSS. Матрица 29×29 лежит готовой в [AuthorQr] и рисуется квадратами:
 * строки кодируются сериями `1`, поэтому на кадр приходится ~60 `drawRect` вместо 841.
 * Перегенерация (другая ссылка) — `tools/qr_matrix.py`.
 */
class SetAuthorScreen(act: EinkActivity) : Screen(act) {

    private val status = StatusBar(act, this, ink, icons)
    private val kit = SettingsKit(ink, icons)

    override fun render(c: Canvas) {
        ink.rect(c, 0f, 0f, W, H, T.BG)
        status.draw(c)
        kit.header(c, "Автор", AUTHOR)

        kit.section(c, "ПОДДЕРЖАТЬ АВТОРА", 250f)
        kit.panel(c, 298f, 1180f)

        ink.inset(c, 88f, 334.8f, 140f, 140f, 70f, T.G12)
        icons.drawInset(c, "cup", 88f, 334.8f, 140f, 140f, 56f, 56f)
        ink.text(c, "Чашка кофе", 268f, T.base(348f, 48f, 1.25f), 48f, 700, T.G1)
        ink.text(c, "Приложение бесплатное", 268f, T.base(416f, T.TS_BODY_S, 1.25f),
            T.TS_BODY_S, 600, T.G4)

        // Подложка: белый квадрат с тихой зоной в 4 модуля — так код читается камерой телефона.
        // Низ подложки 1260, пояснение с 1290 двумя строками до ≈1380, ссылка — рамка 1402
        // (базовая линия 1420.6), кнопка — с 1693. Между блоками не меньше 18 px.
        ink.flat(c, PLATE_X, PLATE_Y, PLATE, PLATE, 40f, T.G15)
        drawQr(c, PLATE_X + QUIET * MODULE, PLATE_Y + QUIET * MODULE)

        ink.paragraphCenter(c,
            "Если оболочка пригодилась, автора можно угостить чашкой кофе. " +
                "Наведите камеру телефона на код — откроется страница на Бусти.",
            152f, 1290f, 1100f, T.TS_BODY_S, 600, T.G4, 1.4f)

        ink.textCenter(c, AuthorQr.SHORT, 41f, 1321f, T.base(1402f, T.TS_LABEL_L, 1.25f),
            T.TS_LABEL_L, 700, T.G1)

        ink.convexDark(c, 41f, 1693f, 1321f, 142f, T.R_BUTTON)
        ink.textCenter(c, "Понятно", 44f, 1316f, T.base(1735.7f, T.TS_ACTION, 1.25f),
            T.TS_ACTION, 700, T.G15)
    }

    /**
     * Модули кода квадратами. Серии подряд идущих «1» в строке рисуются одним прямоугольником:
     * модуль [MODULE] px, отсчёт от левого верхнего угла кода (тихую зону уже обрезал [render]).
     */
    private fun drawQr(c: Canvas, x: Float, y: Float) {
        val n = AuthorQr.SIZE
        for (r in 0 until n) {
            val row = AuthorQr.ROWS[r]
            var col = 0
            while (col < n) {
                if (row[col] != '1') { col++; continue }
                var end = col
                while (end < n && row[end] == '1') end++
                ink.rect(c, x + col * MODULE, y + r * MODULE, (end - col) * MODULE, MODULE, T.G0)
                col = end
            }
        }
    }

    override fun onUp(x: Float, y: Float) {
        // «Назад» в шапке и кнопка «Понятно» внизу — один и тот же выход.
        if (kit.isBack(x, y) || (y >= 1693f && y <= 1835f)) act.pop()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
    }

    override fun onDetachedFromWindow() {
        status.detach()
        super.onDetachedFromWindow()
    }

    private companion object {
        /** Автор оболочки — подпись в шапке и в строке версии на B43. */
        const val AUTHOR = "Денис Лампусов"

        /** Сторона модуля QR, дизайн-px. 29·20 = 580 — код на экране 1404 px. */
        const val MODULE = 20f

        /** Тихая зона вокруг кода в модулях (стандарт QR — 4). */
        const val QUIET = 4

        /** Сторона белой подложки: код + две тихие зоны. */
        const val PLATE = (29 + 2 * QUIET) * MODULE

        /** Подложка по центру колонки 1321 (41…1362). */
        const val PLATE_X = 41f + (1321f - PLATE) / 2f
        const val PLATE_Y = 520f
    }
}
