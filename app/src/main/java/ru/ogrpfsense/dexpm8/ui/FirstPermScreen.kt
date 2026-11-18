package ru.ogrpfsense.dexpm8.ui

import android.content.Intent
import android.graphics.Canvas
import android.provider.Settings

/**
 * B8 · Первый запуск · шаг 3 · разрешения (п. 13). Числа — из design/v5/B8.json.
 *
 *   шаг / доступ к файлам    43.9,479 1316.2×255.9 — вдавленный, «Готово» + `check`, статус справа
 *   шаг / системные настройки 43.9,764.2 — так же
 *   шаг / администратор       41.1,1046.6 1320.8×266 — выпуклый, круглая клавиша `lock`,
 *                             кнопка «Разрешить» 999.1,1123.4 326.3×112.4
 *   шаг / главный экран       43.9,1334.5 1316.2×255.9 — плоский G13 с рамкой G11/1.5, текст G8/G9
 *   «Пропустить и настроить позже»  51.2,1638.8 (38/800 G2 + `arrowR` 659.2)
 *   подсказка                 51.2,1708.1 1279.7×92, 32/600 G5
 *
 * Что требует макет и что есть: «Доступ к файлам» — опасное право, диалог системы
 * (`requestStorage`); «Изменение системных настроек» — WRITE_SETTINGS, системный экран
 * (`withWriteSettings`); «Администратор устройства» — `requestAdmin` (нужен «Сну» на B5);
 * «Главный экран по умолчанию» — `HomeAlias` в манифесте, зовём системный выбор лаунчера.
 *
 * **Отступление от макета (2026-11-18):** на B8 нет ни кнопки «Далее», ни нижнего ряда кнопок —
 * только «Пропустить и настроить позже». Иначе с шага 3 некуда идти: эта строка ведёт на шаг 4,
 * а мастер помечается пройденным на B63. Прогресс на B8 в макете равен половине (648.1), хотя
 * подпись «Шаг 3 из 4» — рисуем 3/4 (`FirstRunKit.progress`).
 */
class FirstPermScreen(act: EinkActivity) : Screen(act) {

    private val status = StatusBar(act, this, ink, icons)
    private val first = FirstRunKit(ink, icons)

    private val storageListener: (Boolean) -> Unit = { if (parent != null) redraw() }

    /** Шторку B5 на шаге мастера не открываем: панель управления здесь ни к чему. */
    override val shadeZone: Float get() = 0f

    override fun render(c: Canvas) {
        ink.rect(c, 0f, 0f, W, H, T.BG)
        status.draw(c)
        first.header(c, 3, "Настроим книгу",
            "Оболочке нужны четыре разрешения. Каждое выдаётся один раз в системных настройках — " +
                "мы откроем нужный экран.")

        // 1. Доступ к файлам — выдано, если право на чтение памяти уже есть.
        granted(c, 479f, "Доступ к файлам", "Чтобы найти книги и аудиокниги в памяти", act.hasStorage())
        // 2. Изменение системных настроек — WRITE_SETTINGS (яркость, Wi-Fi, поворот).
        granted(c, 764.2f, "Изменение системных настроек",
            "Яркость, Wi-Fi и поворот экрана из нашей панели", act.sys.canWrite())
        // 3. Администратор устройства — выпуклая карточка с кнопкой «Разрешить».
        admin(c, act.sys.adminActive())
        // 4. Главный экран по умолчанию — плашка-подсказка, как в макете (неактивна).
        home(c)

        // «Пропустить и настроить позже» — 38/800 G2 + стрелка; ведёт на шаг 4.
        ink.text(c, "Пропустить и настроить позже", 51.2f,
            T.base(1638.8f, T.TS_LABEL_L, 1.25f), T.TS_LABEL_L, 800, T.G2)
        icons.draw(c, "arrowR@40.2x42", 659.2f, 1641.8f, 40.2f, 42f)
        ink.paragraph(c, "Без этого разрешения кнопки «Сон» и «Блокировка» в панели будут " +
            "недоступны. Всё остальное работает.", 51.2f, 1708.1f, 1279.7f, T.TS_BODY_S, 600, T.G5, 3f)
    }

    /**
     * Выданное право: вдавленная карточка 43.9,y 1316.2×255.9 с кругом 102.4 (Δy 76.7), `check`
     * 51.2 по центру круга, название от x 226.7, пояснение на +134.4, статус справа до 1316.3.
     */
    private fun granted(c: Canvas, y: Float, title: String, why: String, ok: Boolean) {
        ink.inset(c, 43.9f, y, 1316.2f, 255.9f, T.R_TILE, T.G11)
        if (ok) {
            ink.ellipsoid(c, 87.8f, y + 76.7f, 102.4f, 102.4f, T.G3)
            icons.draw(c, "check@51.2x51.2", 113.3f, y + 101.8f, 51.2f, 51.2f)
        } else {
            ink.ellipsoid(c, 87.8f, y + 76.7f, 102.4f, 102.4f, T.G10)
            icons.draw(c, "lock@51.2x51.2", 113.3f, y + 101.8f, 51.2f, 51.2f)
        }
        ink.text(c, title, 226.7f, T.base(y + 78.4f, T.TS_ACTION, 1.25f), T.TS_ACTION, 700, T.G1)
        ink.text(c, ink.ellipsize(why, 840f, T.TS_BODY, 600), 226.7f,
            T.base(y + 134.4f, T.TS_BODY, 1.25f), T.TS_BODY, 600, T.G4)
        val label = if (ok) "Выдано" else "Не выдано"
        val w = ink.measure(label, T.TS_BODY, 700)
        ink.text(c, label, 1316.3f - w, T.base(y + 107.6f, T.TS_BODY, 1.25f), T.TS_BODY, 700, T.G4)
    }

    /** Администратор устройства: выпуклая карточка, круглая клавиша `lock`, кнопка «Разрешить». */
    private fun admin(c: Canvas, ok: Boolean) {
        val y = 1046.6f
        ink.convex(c, 41.1f, y, 1320.8f, 266f, T.R_TILE)
        if (ok) {
            ink.ellipsoid(c, 85f, y + 73.3f, 102.4f, 102.4f, T.G3)
            icons.draw(c, "check@51.2x51.2", 110.5f, y + 98.4f, 51.2f, 51.2f)
        } else {
            ink.convex(c, 85f, y + 73.3f, 106.9f, 112.4f, 55f)
            icons.draw(c, "lock@53x55.2", 112.4f, y + 101.7f, 53f, 55.2f)
        }
        ink.text(c, "Администратор устройства", 226.7f, T.base(y + 58.5f, T.TS_ACTION, 1.25f),
            T.TS_ACTION, 700, T.G1)
        ink.paragraph(c, "Кнопки «Сон» и «Блокировка» в панели управления", 226.7f, y + 114.5f,
            750f, T.TS_BODY, 600, T.G4, 4f, maxLines = 2)
        if (!ok) {
            ink.convexDark(c, 999.1f, y + 76.8f, 326.3f, 112.4f, 51.2f)
            ink.textCenter(c, "Разрешить", 1001.8f, 321.7f, T.base(y + 105.1f, T.TS_LABEL_L, 1.25f),
                T.TS_LABEL_L, 700, T.G15)
        } else {
            val w = ink.measure("Выдано", T.TS_LABEL_L, 700)
            ink.text(c, "Выдано", 1325.4f - w, T.base(y + 105.1f, T.TS_LABEL_L, 1.25f),
                T.TS_LABEL_L, 700, T.G4)
        }
    }

    /** Главный экран по умолчанию: плоская плашка G13 с рамкой G11 (в макете текст приглушён). */
    private fun home(c: Canvas) {
        val y = 1334.5f
        ink.flat(c, 43.9f, y, 1316.2f, 255.9f, T.R_TILE, T.G13)
        ink.ring(c, 43.9f, y, 1316.2f, 255.9f, T.R_TILE, T.G11, 1.5f)
        ink.convex(c, 85f, y + 59.4f, 106.9f, 112.4f, 55f)
        icons.draw(c, "home@51.2x53.3", 113.3f, y + 87.8f, 51.2f, 53.3f)
        ink.text(c, "Главный экран по умолчанию", 226.7f, T.base(y + 66.2f, T.TS_ACTION, 1.25f),
            T.TS_ACTION, 700, T.G8)
        ink.text(c, "Чтобы кнопка «Домой» открывала оболочку", 226.7f,
            T.base(y + 122.2f, T.TS_BODY, 1.25f), T.TS_BODY, 600, T.G9)
    }

    override fun onUp(x: Float, y: Float) {
        // Карточки-права: x 43.9…1360.1, высота 255.9 — зона шире органов, как требует ARCHITECTURE.
        when {
            y >= 479f && y <= 734.9f -> act.requestStorage { redraw() }
            y >= 764.2f && y <= 1020.1f -> act.withWriteSettings { redraw() }
            // Администратор: дверь в макете — кнопка «Разрешить», а не вся карточка.
            x >= 999.1f && x <= 1325.4f && y >= 1123.4f && y <= 1235.8f ->
                if (!act.sys.adminActive()) act.requestAdmin { redraw() }
            y >= 1334.5f && y <= 1590.4f -> pickHome()
            // «Пропустить и настроить позже» — дальше, на шаг 4.
            x >= 41f && x <= 750f && y >= 1620f && y <= 1700f -> act.go(EinkActivity.SCR_FIRST_READ)
        }
    }

    /** Системный выбор домашнего экрана (activity-alias `HomeAlias` уже в манифесте). */
    private fun pickHome() {
        try {
            act.startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            act.markCleanOnFocus()
        } catch (t: Throwable) {
            // На части прошивок экрана нет — молчим: значение всё равно чужое.
            Trace.step("first perm: home screen ${t.javaClass.simpleName}")
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
        // Право могли выдать в системном окне, пока шаг был на экране.
        act.addStorageListener(storageListener)
    }

    override fun onDetachedFromWindow() {
        act.removeStorageListener(storageListener)
        status.detach()
        super.onDetachedFromWindow()
    }
}
