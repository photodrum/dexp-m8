# DEXP M8 Prudentia — кастомная оболочка

Полная замена родного софта книги DEXP M8 Prudentia: лаунчер, библиотека,
читалка, плеер. Дизайн — страница **v5** файла «UI» в Penpot (76 бордов) и спецификация
на странице «v5 дизайн-система». Код — Kotlin без AndroidX и Compose, разбор метаданных
книг — C99 через JNI; страницы книг (план) — CREngine, MuPDF, DjVuLibre.
Лицензия — AGPL-3.0.

## Документация

| Файл | О чём |
|---|---|
| [`docs/CONTEXT.md`](docs/CONTEXT.md) | **начинать отсюда**: состояние проекта, правила работы, следующий шаг |
| [`docs/DEVLOG.md`](docs/DEVLOG.md) | журнал по датам: что сделано, какие баги найдены |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | как устроен рендер, дизайн-px, слои объёма, типографика |
| [`docs/DESIGN.md`](docs/DESIGN.md) | токены из Penpot: палитра, радиусы, кегли, геометрия B1/B2 |
| [`docs/DESIGN_USAGE.md`](docs/DESIGN_USAGE.md) | как пользоваться макетами «v5» и «v5 дизайн-система», карта экранов |
| [`docs/READER_SPEC.md`](docs/READER_SPEC.md) | ТЗ читалки: движки, `ReaderEngine`, этапы R0–R6, приёмка |
| [`docs/BUILD.md`](docs/BUILD.md) | сборка, установка на книгу, git и доступы, частые ошибки |
| [`docs/COMPARISON.md`](docs/COMPARISON.md) | чем своё ядро отличается от Book's Story и KOReader |
| [`app/src/main/cpp/README.md`](app/src/main/cpp/README.md) | C-ядро: типы блоков, тесты, грабли |

## Железо и ограничения

| | |
|---|---|
| SoC | Rockchip PX30 (Cortex-A35, 4 ядра) |
| ОЗУ / Flash | 1 ГБ / 16 ГБ |
| ОС | Android 8.1, API 27, без Google Play |
| Экран | 7.8″ E-Ink Carta, **1404×1872 px**, 300 ppi, 16 градаций |
| Плотность | ориентировочно 2.0 → 702×936 dp |

Правила, которые нельзя нарушать:

* **никаких анимаций и переходов** — смена экрана = полная перерисовка;
* **никаких градиентов, полупрозрачности, размытия** — только 16 ступеней
  серого с шагом 17 (`G0 #000000` … `G15 #FFFFFF`);
* **никакого скролла** — строгая постраничность;
* касательные зоны **≥ 48 dp** (при density 2.0 это ≥ 96 дизайн-px);
* диалоги и листы без затемнения фона — всё под ними перерисовывается на 4–5 ступеней
  светлее (текст G1 → G8), статус-бар не трогается.

## Почему дизайн-px, а не dp

Макет нарисован в пикселях панели: 1404×1872. Числа в нём — это не dp и не sp.
Чтобы перенести их в код без пересчёта, все View рисуют в «дизайн-px»,
а `Canvas` масштабируется один раз (`D.u = screenWidth / 1404f`).

* на панели 1404 px `u = 1.0`, числа совпадают с макетом один-в-один;
* на другой ревизии экрана всё масштабируется линейно и пропорции не плывут.

Проверить фактическую плотность на устройстве:
```
adb shell getprop ro.sf.lcd_density
```

## Структура

```
app/src/main/java/ru/ogrpfsense/dexpm8/
  ui/
    EinkActivity.kt   единственная Activity, стек экранов, immersive, без анимаций
    Screen.kt         базовый View-экран: layout/render/onUp, касания без скролла
    Ink.kt            рисование: convex / convexDark / inset, текст, абзацы
    Tokens.kt         палитра G0..G15, радиусы, кегли, кэш Typeface
    D.kt              дизайн-пространство, масштаб, проверка dp
    Icons.kt          отрисовка иконок из геометрии
    IconSpecs.kt      геометрия 24 иконок, снятая с Penpot
    HomeScreen.kt     B1 · Главный экран
    LibraryScreen.kt  B2 · Библиотека (список)
    SimpleScreen.kt   заглушка раздела для проверки навигации
  core/
    NativeBook.kt     JNI-мост к C-ядру
app/src/main/cpp/
  core/book.c,.h      потоковый разбор FB2
  jni_bridge.c        JNI
  test/               хост-тесты (gcc, без NDK)
tools/
  build.sh            сборка APK без Gradle (aapt2 + kotlinc + d8 + apksigner)
  typecheck.sh        проверка Kotlin против android.jar
  fonts/              статические начертания Manrope/Literata (subset)
```

## Сборка

Окружение развёрнуто в `~/.local` (JDK 17, cmdline-tools, kotlinc 1.9.24, Gradle 8.7, NDK r26d).

### Основной способ — Gradle

```bash
export JAVA_HOME=$HOME/.local/jdk
export ANDROID_HOME=$HOME/.local/android-sdk
export PATH=$JAVA_HOME/bin:$PATH

gradle -p ~/projects/dexp-m8 assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
gradle -p ~/projects/dexp-m8 assembleRelease   # с R8
```

`local.properties` уже указывает на SDK. Gradle сам собирает `.so` через CMake
для `arm64-v8a` и `x86_64`.

Установка на книгу:

```bash
$ANDROID_HOME/platform-tools/adb install -r -d \
  app/build/outputs/apk/debug/app-debug.apk
```

### Быстрая сборка без Gradle

`tools/build.sh` делает то же самое руками (aapt2 → javac → kotlinc → d8 →
zipalign → apksigner) за ~10 секунд, но **не собирает `.so`** и не подхватывает
`.so`/CMake — только Kotlin + ресурсы. Нужен, чтобы быстро проверить логику
экранов, не дожидаясь Gradle.

```bash
./tools/build.sh              # build/app-debug.apk (без нативного ядра)
./tools/build.sh --install
./tools/typecheck.sh          # только проверка типов Kotlin, ~10 сек
```

Проверка C-ядра на хосте (без NDK и устройства):

```bash
cd app/src/main/cpp
gcc -std=gnu99 -O1 -g -Wall -Wextra -Icore -fsanitize=address,undefined \
    -o /tmp/test_core core/book.c test/test_core.c
cd test && /tmp/test_core testdata/sample.fb2
```

## Что уже есть

* B1 «Главный»: карточка «Сейчас читаю», прогресс, тёмная кнопка, 4 плитки,
  статистика, свой статус-бар (время / батарея / wifi).
* B2 «Библиотека»: шапка с «Назад», переключатель вид, поле поиска,
  сегментированная сортировка, список из 6 книг с разделителями,
  постраничность кнопками и номер страницы.
* Навигация: стек экранов, `onBackPressed` → `pop()`, без анимаций.
* C-ядро: разбор FB2 в плоский список блоков + метаданные, собирается NDK
  под `arm64-v8a` и `x86_64`, `.so` 12 КБ.

## Состояние сборки (проверено)

| | |
|---|---|
| `assembleDebug` | ✅ 965 КБ, `libdexp_core.so` внутри |
| `assembleRelease` (R8) | ✅ 145 КБ, JNI-класс сохранён |
| `tools/build.sh` | ✅ 148 КБ, **без** `.so` (только Kotlin) |
| C-ядро, хостовые тесты | ✅ 9/9, ASan+UBSan чисто |

## Грабли, уже набитые

1. **Дефисы в именах ресурсов запрещены.** `res/font/manrope-700.ttf` проходит
   `aapt2` напрямую, но Gradle падает: только `a-z0-9_`. Файлы называются
   `manrope_700.ttf`, а `R.font.manrope_700` получается сам.
2. **R8 удаляет JNI-класс.** `-keepclasseswithmembernames` разрешает shrinking —
   пока `NativeBook` никто не вызывает, он вылетает из APK, и JNI падает на
   `UnsatisfiedLinkError`. Нужен именно `-keep class ... { *; }`.
3. **`R` из пакета `ui` не виден** без `import ru.ogrpfsense.dexpm8.R`.
4. **`aapt2` требует `package`** в манифесте, а AGP 8 его вырезает (берёт из
   `namespace`). В `tools/build.sh` подставляем `sed` в копию.
5. **`d8` — шелл-обёртка**, ищет `java` в `PATH`.
6. `buf_normalize` в `book.c` зацикливался на нераспознанной сущности.

## Дальше по плану

1. Проверить APK на книге: `adb install -r -d app-debug.apk`, посмотреть B1/B2,
   срабатывание тач-зон, читаемость при 16 градациях.
2. `adb shell getprop ro.sf.lcd_density` — подтвердить density (ожидаем 320 → 2.0).
3. B11 «Библиотека · сетка», сканер `/sdcard/Books` + `MediaStore`.
4. B12 «О книге», B13 и B37–B43 «Настройки».
5. E-Ink-слой: политика полной/частичной перерисовки через sysfs контроллера.
6. Проба движков читалки (R0), затем остальная оболочка: панель управления, полки,
   OPDS, плеер B7 / B33–B35, первый запуск.
7. Читалка после оболочки: CREngine + MuPDF + DjVuLibre за интерфейсом `ReaderEngine`,
   этапы R1–R6 — `docs/READER_SPEC.md`.

Полный и актуальный план — раздел «Дальше (roadmap)» в `docs/CONTEXT.md`.

## Лицензия

GNU Affero General Public License v3.0 (AGPL-3.0). Шрифты Manrope и Literata — SIL OFL 1.1
(`tools/fonts/OFL-*.txt`).
