## 2026-11-21 — Шахматы: план и решения, кода нет (Plan → Act, Sonnet)

- Решения человека: шахматы внутри оболочки, в её стиле; игра с компьютером без сети и по сети без ручного токена.
- lichess-org/mobile не берём: Flutter, Firebase, AndroidX, GPL-3.0 — несовместимо с GPL-2.0-only. Ядро — Pulse (MIT) переписать на Kotlin; chesslib (Apache-2.0) и Stockfish (GPL-3.0) нельзя.
- Сеть: FICS гостем (`freechess.org:5000`, проверено с хоста: вход `guest` проходит, без токена); lichess по OAuth PKCE — позже. Оценка APK: +50–80 КБ из 4,48 МБ (не замер).

- Записано: п. 25 в ROADMAP, вопрос про борды в `DESIGN_QUESTIONS.md`, подробно — `docs/archive/CHESS-2026-11-21-plan.md`. Архивирована запись B5 → `DEVLOG-2026-11-21-b5.md`.



- Первая `assembleRelease` ок (1 мин 51 с), подпись `dexp-m8` (`9edff2d1…7317fc`, `apksigner verify` ок). `lintVitalRelease` печатает `ExpiredTargetSdkVersion` (targetSdk 27) — сборку не роняет, на книгу и обновления через GitLab не влияет.
- Поверх debug: данные целы, старт ~1,7 с, падений нет, `run-as` → `not debuggable`, отладочный `--es screen read` не срабатывает (`BuildConfig.DEBUG = false`). Человек проверил экраны на книге — ок.
- **Выпуск 1.0.0 (код 4)**: версия поднята в `app/build.gradle.kts` и `tools/build.sh` (было 0.3.0 / 3: тот же код у новой сборки не дал бы B43 увидеть обновление). APK `c9f31d18…`, 4 478 840 б; `gl_publish_apk.py` → 201, скачанный файл совпал по сумме; `update.json` → 1.0.0; книга обновлена до 1.0.0.
- `PowerService` после `install -r` + `force-stop` не поднимается, пока не записать настройки заново с `--user 0` (после `adb reboot` и после переустановки) — грабля из BUILD, не баг релиза.
