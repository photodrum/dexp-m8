# Ядро разбора книг (C99)

Собирается двумя способами:

* **на хосте** — обычным `gcc`, для тестов логики без устройства;
* **под Android** — через CMake из NDK, подключается в `app/build.gradle.kts`.

## Почему свой парсер, а не libxml2/expat

* libxml2 — это +1.5–2 МБ в APK и C++-зависимости, которые на PX30 с 1 ГБ ОЗУ
  ещё и фрагментируют кучу;
* FB2 — плоский XML с известным набором тегов; нам нужны `<p>`, `<v>`, `<title>`,
  `<subtitle>`, `<epigraph>`, `<poem>`, `<image>` и метаданные;
* разбор потоковый, один проход, текстовая арена одна на документ.
  Книга на 5 МБ даёт ~1.5–2 МБ блоков, а не 50 МБ объектов DOM.

## Что отдаётся наружу

`book_parse_fb2()` заполняет `book`: массив `blk` (тип + указатель в арену)
и метаданные (`title`, `author`, `lang`, `cover_href`).
Через JNI это превращается в `Array<NativeBook.Block>` — плоский список,
который UI листает постранично.

`book_meta_fb2()` — для сканера библиотеки: только `<title-info>` по первым
`BOOK_META_HEAD` (64 КБ) файла, тело не читается. Заполняет `book_meta`: название,
все авторы, части имени первого автора, серия и номер, язык, id обложки, `complete`.
Кодировки UTF-8 / windows-1251 / latin-1; KOI8-R и UTF-16 — ошибка 4.
В Kotlin — `NativeBook.metaFb2(file, buf): Meta?` (JNI отдаёт `String[11]`).

## Типы блоков

| Константа | Значение | Источник в FB2 |
|---|---|---|
| `BLK_PARA` | 0 | `<p>` |
| `BLK_TITLE` | 1 | `<p>` внутри `<title>` |
| `BLK_SUBTITLE` | 2 | `<subtitle>` |
| `BLK_EPIGRAPH` | 3 | `<text-author>`, `<epigraph>` |
| `BLK_POEM` | 4 | `<v>` |
| `BLK_ANNOTATION` | 5 | `<annotation>` |
| `BLK_EMPTY` | 6 | `<empty-line>` |

## Сборка на хосте и тесты

```bash
cd app/src/main/cpp
gcc -std=gnu99 -O1 -g -Wall -Wextra -Icore -fsanitize=address,undefined \
    -o /tmp/test_core core/book.c test/test_core.c
cd test && setarch -R /tmp/test_core testdata/sample.fb2   # без ASLR: см. docs/BUILD.md
```

Тест проверяет: успешный разбор, извлечение названия и автора, количество абзацев и
заголовков, валидность UTF-8, раскрытие сущностей, детерминированность повторного разбора;
для `book_meta_fb2` — все поля, кодировки, обрезку файла в любом месте, ошибки, мусор.
Прогоняется под ASan + UBSan, ожидается 34 `ok` и `all good`.

## Сборка под Android

`CMakeLists.txt` собирает `libdexp_core.so` с флагами:

```
-Os  -ffunction-sections  -fdata-sections  -fvisibility=hidden
-Wl,--gc-sections  -Wl,-z,max-page-size=16384
```

Результат — 12 КБ на `arm64-v8a`. Gradle собирает под `arm64-v8a` и `x86_64`.

## Грабли

1. `strncasecmp` требует `<strings.h>` — на glibc это отдельный заголовок,
   без него `-std=c99` даёт неявное объявление.
2. `buf_normalize` должен сдвигать индекс при **нераспознанной** сущности,
   иначе бесконечный цикл на тексте вида `&foo;`.
3. `<binary>` (base64-обложки) надо пропускать целиком, иначе парсер съест мегабайты
   в текстовые блоки.
4. R8 удаляет JNI-класс, если в ProGuard стоит `-keepclasseswithmembernames`
   вместо `-keep class ... { *; }` — см. `docs/BUILD.md`.
