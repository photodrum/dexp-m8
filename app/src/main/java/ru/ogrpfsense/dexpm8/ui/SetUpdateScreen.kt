package ru.ogrpfsense.dexpm8.ui

import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.os.BatteryManager
import java.io.File
import ru.ogrpfsense.dexpm8.core.ApkInstall
import ru.ogrpfsense.dexpm8.core.UpdateCheck

/**
 * B76 · Настройки · Обновления (п. 13). Числа — из design/v5/B76.json.
 *
 *   шапка        «Назад» 41,113.5 (107×112); «Обновления» 176,105 60/700; «Об устройстве» 176,181 32/600 G4
 *   панель       41,250 1321×440 (выпуклая, r R_TILE)
 *   гнездо       88,286.8 140×140 (вдавленное, r 70); иконка download 133,330.3 49.9×52
 *   заголовок    268,300 48/700 G1; подзаголовок 268,368 32/600 G4
 *   «что нового» 88,470, ширина 1228, 36/500, межстрочный 1.5, не больше трёх строк
 *   «НАСТРОЙКИ»  59,730 30/700 G5; панель 41,778 1321×282
 *   тумблеры     1177,820.4 и 1177,957.5; разделитель 88,931 1228×2 G11
 *   кнопки       «Позже» 41.1,1693.6 553×141.7 (светлая), «Обновить» 633.4,1693.6 728.5×141.7
 *                (тёмная, иконка download 34.6×34.6 правым краем)
 *
 * **Как идёт обновление.** Вход — строка «Обновления» на B43. Экран сам проверяет манифест
 * ([UpdateCheck]), показывает версию и «что нового»; кнопка «Обновить» просит право на установку
 * из неизвестных источников (`EinkActivity.withInstallPermission`), качает APK в кэш, сверяет
 * `sha256` и отдаёт `PackageInstaller` ([ApkInstall]). Система показывает своё подтверждение,
 * итог возвращается в приёмник и появляется здесь же строкой.
 *
 * **Прогресс без отдельной полосы:** в макете её нет, поэтому проценты идут в заголовок панели
 * («Скачиваем 40 %»). Перерисовка — только при смене десятка процентов: на E-Ink чаще нельзя.
 *
 * **Тумблеры** («Проверять автоматически», «Скачивать только при зарядке») хранятся в `Prefs`.
 * Автопроверка появится вместе с первым запуском (п. 13); второй тумблер уже работает — при
 * выключенной зарядке скачивание не начинается, экран просит поставить книгу на зарядку.
 */
class SetUpdateScreen(act: EinkActivity) : Screen(act) {

    private val status = StatusBar(act, this, ink, icons)
    private val kit = SettingsKit(ink, icons)
    private val upd = UpdateCheck(act)
    private val dir = UpdateCheck.dirOf(act)

    private var found: UpdateCheck.Found? = null
    private var state = S_CHECK
    private var pct = 0

    /** Пояснение под заголовком панели: причина отказа или подсказка по ходу. */
    private var note = ""
    private var installedName = ""
    private var installedCode = 0L

    private var busy = false

    override fun render(c: Canvas) {
        ink.rect(c, 0f, 0f, W, H, T.BG)
        status.draw(c)
        kit.header(c, "Обновления", "Об устройстве")

        kit.panel(c, 250f, 440f)
        ink.inset(c, 88f, 286.8f, 140f, 140f, 70f, T.G12)
        icons.draw(c, "download@49.9x52", 133f, 330.3f, 49.9f, 52f)
        ink.text(c, head(), 268f, T.base(300f, 48f, 1.25f), 48f, 700, T.G1)
        ink.text(c, ink.ellipsize(sub(), 1030f, T.TS_BODY_S, 600), 268f,
            T.base(368f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        ink.paragraph(c, body(), 88f, 470f, 1228f, T.TS_LABEL, 500, T.G1, 5f, maxLines = 3)

        kit.section(c, "НАСТРОЙКИ", 730f)
        kit.panel(c, 778f, 282f)
        kit.title(c, "Проверять автоматически", 807f)
        kit.desc(c, "Раз в неделю при Wi-Fi", 860.5f)
        kit.toggle(c, 820.4f, act.prefs.updateAuto, 1177.1f)
        kit.divider(c, 931f)
        kit.title(c, "Скачивать только при зарядке", 966f)
        kit.toggle(c, 957.5f, act.prefs.updateChargeOnly, 1177f)

        buttons(c)
    }

    // ---------------------------------------------------------------- кнопки

    /**
     * «Позже» по макету светлая, «Обновить» тёмная. Нечего ставить — тёмная гаснет до дна
     * углубления (`convex` с G12), подпись — G5: на E-Ink нет «прозрачности», есть только
     * смена ступени палитры. Нажатие всё равно проверяется в [onUp].
     */
    private fun buttons(c: Canvas) {
        val on = canAct()
        ink.convex(c, 41.1f, 1693.6f, 553f, 141.7f, T.R_BUTTON)
        ink.textCenter(c, "Позже", 43.9f, 548.4f, T.base(1736.2f, T.TS_ACTION, 1.25f),
            T.TS_ACTION, 700, T.G1)

        if (on) {
            ink.convexDark(c, 633.4f, 1693.6f, 728.5f, 141.7f, T.R_BUTTON)
        } else {
            ink.convex(c, 633.4f, 1693.6f, 728.5f, 141.7f, T.R_BUTTON, T.G12, T.G13, T.G10)
        }
        val label = when (state) {
            S_DOWN -> "Скачиваем $pct %"
            S_INSTALL -> "Устанавливаем…"
            S_DONE -> "Готово"
            else -> "Обновить"
        }
        ink.text(c, label, 687.4f, T.base(1736.2f, T.TS_ACTION, 1.25f), T.TS_ACTION, 700,
            if (on) T.G15 else T.G5)
        icons.draw(c, "download@34.6x34.6", 1269.7f, 1744.2f, 34.6f, 34.6f)
    }

    /** Кнопка «Обновить» жива: есть что ставить, ничего не идёт и право уже выдано (или не нужно). */
    private fun canAct(): Boolean = when (state) {
        S_NEW, S_FAIL_DL -> found != null && !busy
        else -> false
    }

    // ---------------------------------------------------------------- тексты

    private fun head(): String = when (state) {
        S_CHECK -> "Проверяем…"
        S_NONE -> "Обновлений нет"
        S_ERR -> "Не удалось проверить"
        S_NEW, S_DOWN, S_INSTALL, S_DONE, S_FAIL_DL -> "Доступна версия " + (found?.versionName ?: "")
        else -> "Обновления"
    }

    /** Подзаголовок: «Сборка 21 · 14 МБ · сейчас 0.1 (14)» по макету. */
    private fun sub(): String {
        val f = found
        if (state == S_NONE || state == S_CHECK) return "Сейчас " + nowText()
        if (f == null) return nowText()
        val parts = ArrayList<String>(3)
        parts.add("Сборка ${f.versionCode}")
        val mb = sizeMb(f)
        if (mb > 0) parts.add("$mb МБ")
        parts.add("сейчас " + nowText())
        return parts.joinToString(" · ")
    }

    private fun nowText(): String = if (installedName.isEmpty()) "без данных"
        else "$installedName (${installedCode})"

    /** Размер APK: из манифеста, а если его нет — по уже скачанному файлу. */
    private fun sizeMb(f: UpdateCheck.Found): Long {
        var b = f.size
        if (b <= 0L) {
            val file = File(dir, "update-${f.versionCode}.apk")
            if (file.isFile) b = file.length()
        }
        if (b <= 0L) return 0L
        return Math.max(1L, Math.round(b / 1e6))
    }

    /** Текст «что нового»: строки манифеста, при отсутствии — что показывает состояние. */
    private fun body(): String {
        if (note.isNotEmpty()) return note
        val f = found
        return when (state) {
            S_CHECK -> "Смотрим манифест обновления. Это несколько секунд."
            S_NONE -> "Установлена последняя версия. Проверка повторится сама, если включена."
            S_ERR -> "Сервер не ответил. Попробуйте позже или проверьте Wi-Fi."
            S_DONE -> "Оболочка обновится при следующем запуске."
            S_INSTALL -> "Подтвердите установку в системном окне."
            S_DOWN -> "Скачиваем и сверяем контрольную сумму."
            S_FAIL_DL -> "Прервалось. Нажмите «Обновить», чтобы попробовать снова."
            else -> f?.notes?.takeIf { it.isNotBlank() }
                ?: "Что нового — напишем в описании версии."
        }
    }

    // ---------------------------------------------------------------- данные и работа

    private fun refresh() {
        try {
            val pi = act.packageManager.getPackageInfo(act.packageName, 0)
            installedName = pi.versionName ?: ""
            installedCode = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode
            else @Suppress("DEPRECATION") pi.versionCode.toLong()
        } catch (t: Throwable) {
            installedName = ""
            installedCode = 0L
        }
        check()
    }

    /**
     * Проверка манифеста в фоне. Сеть в главном потоке на Android 8.1 — `NetworkOnMainThreadException`.
     * Пока идёт — [busy], кнопки неактивны; по итогу один `redraw()`.
     */
    private fun check() {
        if (busy) return
        busy = true
        state = S_CHECK
        note = ""
        redraw()
        Thread({
            val f = upd.fetch()
            val newer = f != null && upd.isNewer(f)
            val why = upd.why
            post {
                busy = false
                if (f == null) {
                    state = S_ERR
                    act.prefs.updateResult = why ?: "нет ответа"
                } else if (newer) {
                    found = f
                    state = S_NEW
                    act.prefs.updateCheckedAt = System.currentTimeMillis()
                    act.prefs.updateResult = "есть версия ${f.versionName}"
                } else {
                    found = null
                    state = S_NONE
                    act.prefs.updateCheckedAt = System.currentTimeMillis()
                    act.prefs.updateResult = "обновлений нет"
                }
                redraw()
            }
        }, "update-b76").start()
    }

    /** «Обновить»: право на установку → скачивание в фоне → установка. */
    private fun update() {
        val f = found ?: return
        if (busy) return
        act.withInstallPermission { start(f) }
    }

    private fun start(f: UpdateCheck.Found) {
        if (busy) return
        // «Скачивать только при зарядке» — честная проверка, иначе тумблер был бы обманом.
        if (act.prefs.updateChargeOnly && !charging()) {
            state = S_FAIL_DL
            note = "Книга на батарее. Поставьте на зарядку или выключите «только при зарядке»."
            redraw()
            return
        }
        busy = true
        state = S_DOWN
        pct = 0
        note = ""
        redraw()
        Trace.step("update: качаем ${f.versionName} (${f.url})")
        Thread({
            val file = upd.download(f, dir) { p ->
                post {
                    if (state == S_DOWN && p != pct) {
                        pct = p
                        redraw()
                    }
                }
            }
            val why = upd.why
            post {
                if (file == null) {
                    busy = false
                    state = S_FAIL_DL
                    note = "Не скачалось: " + (why ?: "неизвестная причина") + "."
                    act.prefs.updateResult = why ?: "не скачалось"
                    redraw()
                } else {
                    install(file)
                }
            }
        }, "update-dl").start()
    }

    private fun install(file: File) {
        state = S_INSTALL
        note = ""
        redraw()
        ApkInstall.setWaiter { ok, msg -> post { finish(ok, msg) } }
        if (!ApkInstall.install(act, file)) {
            ApkInstall.clearWaiter()
            busy = false
            state = S_FAIL_DL
            note = "Система не приняла файл обновления."
            act.prefs.updateResult = "установка не началась"
            redraw()
        }
    }

    private fun finish(ok: Boolean, msg: String) {
        busy = false
        state = if (ok) S_DONE else S_FAIL_DL
        note = if (ok) "" else ApkInstall.describe(msg) + "."
        if (ok) {
            // Файл больше не нужен: следующая проверка скачает заново.
            try {
                File(dir, "update-${found?.versionCode}.apk").delete()
            } catch (ignored: Throwable) {
            }
            act.prefs.updateResult = "обновление установлено"
        }
        Trace.step("update: итог установки " + (if (ok) "ок" else "нет"))
        redraw()
    }

    /** Идёт ли зарядка — `ACTION_BATTERY_CHANGED` липкое, читаем как в `StatusBar`. */
    private fun charging(): Boolean = try {
        val i = act.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
    } catch (t: Throwable) {
        false
    }

    // ---------------------------------------------------------------- касания

    override fun onUp(x: Float, y: Float) {
        if (kit.isBack(x, y)) {
            act.pop()
            return
        }
        if (kit.inRow(x, y, 790f, 150f)) {
            // Первая строка панели «НАСТРОЙКИ» вместе с тумблером; разделитель на 931.
            act.prefs.updateAuto = !act.prefs.updateAuto
            redraw()
            return
        }
        if (kit.inRow(x, y, 940f, 120f)) {
            act.prefs.updateChargeOnly = !act.prefs.updateChargeOnly
            redraw()
            return
        }
        if (inRect(x, y, 41.1f, 1693.6f, 553f, 141.7f)) {
            skip()
            return
        }
        if (inRect(x, y, 633.4f, 1693.6f, 728.5f, 141.7f)) {
            // Пока идёт скачивание или установка, кнопка молчит; а когда ставить нечего — проверяем снова.
            if (busy) return
            if (state == S_NONE || state == S_ERR || state == S_DONE) check() else update()
        }
    }

    /**
     * «Позже»: версию запоминаем в `Prefs.updateSkipCode` — её пропустит автопроверка при
     * старте (п. 13) — и уходим назад. Без запоминания кнопка значила бы только «назад».
     */
    private fun skip() {
        val f = found
        if (f != null && (state == S_NEW || state == S_FAIL_DL)) act.prefs.updateSkipCode = f.versionCode
        act.pop()
    }

    private fun inRect(x: Float, y: Float, rx: Float, ry: Float, rw: Float, rh: Float) =
        x >= rx && x <= rx + rw && y >= ry && y <= ry + rh

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
        // Отладка: адрес манифеста из intent (`--es update http://192.168.50.12:8000/update.json`).
        val it = act.intent
        if (ru.ogrpfsense.dexpm8.BuildConfig.DEBUG && it != null) {
            val u = it.getStringExtra("update")
            if (!u.isNullOrEmpty()) {
                it.removeExtra("update")
                upd.devManifest = u
                Trace.step("update: отладочный манифест $u")
            }
        }
        refresh()
    }

    override fun onDetachedFromWindow() {
        ApkInstall.clearWaiter()
        status.detach()
        super.onDetachedFromWindow()
    }

    private companion object {
        /** 0 — проверяем; 1 — есть новая; 2 — обновлений нет; 3 — проверка не удалась. */
        const val S_CHECK = 0
        const val S_NEW = 1
        const val S_NONE = 2
        const val S_ERR = 3
        /** 4 — скачиваем; 5 — отдано системе; 6 — поставлено; 7 — скачать/поставить не вышло. */
        const val S_DOWN = 4
        const val S_INSTALL = 5
        const val S_DONE = 6
        const val S_FAIL_DL = 7
    }
}
