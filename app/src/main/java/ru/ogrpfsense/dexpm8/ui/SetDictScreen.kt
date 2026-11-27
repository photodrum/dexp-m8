package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import ru.ogrpfsense.dexpm8.core.DictOnline

/**
 * B73 · Настройки · Словари. Числа — из design/v5/B73.json.
 *
 * Решение 2026-11-18 (вариант «одна секция»): офлайн-словарей нет и не будет — словарь
 * в R5.2а сделан **онлайн** (Викисловарь, CC BY-SA), перевод — MyMemory. Поэтому в панели
 * макета остаются две нижние строки шаблона, а секции «Установлены» и «Доступны для загрузки»
 * (Ожегов 58 МБ, Мюллер 110 МБ, Даль 96 МБ, «Добавить из файла…») не рисуются: скачивать
 * нечего (сервера словарей у проекта нет), парсера StarDict/DSL в коде нет.
 *
 *   шапка      «Назад» 41,113.5; заголовок 176,105; подзаголовок 176,181
 *   раздел     «СЛОВАРЬ» 59,250 30/700 G5
 *   панель     41,298 1321×312 (r R_TILE)
 *   строка 1   название 88,327 42/700; описание 88,380.5 32/600 G4
 *   разделитель 88,451
 *   строка 2   название 88,479; значение справа до 1267.6 34/600 G4; описание 88,532.5
 *
 * Настроек нет: сервер один и зашит в [DictOnline] (`MYMEMORY`), направление перевода
 * выбирает код (`core/TrDir`, решение 2026-11-27: русское слово → en, любое другое → ru;
 * язык «откуда» — язык книги или автоопределение) — поэтому строки только показывают источники.
 * Ключи в `Prefs` не заводим, пока не появится второго сервера (иначе мёртвая настройка).
 * Стрелки `next` нет: двери в макете у этих строк не было и экрана за ней тоже.
 *
 * Живое на экране одно — строка «Толковый словарь»: без сети она честно пишет, что словарь
 * не откроется. Сеть проверяется при открытии экрана (таймеров на E-Ink нет).
 */
class SetDictScreen(act: EinkActivity) : Screen(act) {

    private val status = StatusBar(act, this, ink, icons)
    private val kit = SettingsKit(ink, icons)

    private var online = false

    override fun render(c: Canvas) {
        ink.rect(c, 0f, 0f, W, H, T.BG)
        status.draw(c)
        kit.header(c, "Словари", "Викисловарь · MyMemory")

        kit.section(c, "СЛОВАРЬ", 250f)
        kit.panel(c, 298f, 312f)

        kit.title(c, "Толковый словарь", 327f)
        kit.desc(c, if (online) "Викисловарь · по сети · CC BY-SA" else "Нет сети — словарь не откроется",
            380.5f)
        kit.divider(c, 451f)

        kit.title(c, "Сервер перевода", 479f)
        kit.valueRow(c, "MyMemory", 487f)
        kit.desc(c, "Без ключа · направление по тексту слова", 532.5f)
    }

    private fun refresh() {
        online = DictOnline(act).online()
    }

    override fun onUp(x: Float, y: Float) {
        if (kit.isBack(x, y)) act.pop()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
        refresh()
    }

    override fun onDetachedFromWindow() {
        status.detach()
        super.onDetachedFromWindow()
    }
}
