## 2026-11-22 — README: снимки веб-сервера, открытой книги и «Об устройстве» (Sonnet)
- В `docs/img` добавлены `webserver.png` (B26), `reader.png` (страница книги) и `about.png` («Об устройстве»), 1404×1872, `adb exec-out screencap -p`; в таблицу README — вторая строка.
- **Грабля:** `dumpsys window`/`activity` всегда показывает `HomeAlias` — экраны рисуются внутри одной активности, по нему текущий экран не определить. Сверять кадр: md5 и грубая сетка яркости (PIL, `tesseract` нет). Перед снимком — `adb kill-server && start-server`, если `devices` пуст.
