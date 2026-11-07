# Ядро разбора книг (C99).

Собирается двумя способами:

* **на хосте** — обычным `gcc`, для тестов логики без устройства:
  ```
  gcc -std=gnu99 -O1 -g -Wall -Wextra -Icore -fsanitize=address,undefined \
      -o /tmp/test_core core/book.c test/test_core.c
  cd test && /tmp/test_core testdata/sample.fb2
  ```
* **под Android** — через CMake из NDK, подключается в `app/build.gradle.kts`.

## Почему свой парсер, а не libxml2/expat

* libxml2 — это +1.5–2 МБ в APK и C++-зависимости, которые на PX30 с 1 ГБ ОЗУ
  ещё и фрагментируют кучу.
* FB2 — плоский XML с известным набором тегов; нам нужны `<p>`, `<v>`,
  `<title>`, `<subtitle>`, `<epigraph>`, `<poem>`, `<image>`, метаданные.
* Разбор потоковый, один проход, текстовая арена одна на документ.
  Книга на 5 МБ даёт ~1.5–2 МБ блоков, а не 50 МБ объектов DOM.

## Что отдаётся наружу

`book_parse_fb2()` заполняет `book`: массив `blk` (тип + указатель в арену)
и метаданные (`title`, `author`, `lang`, `cover_href`).
Через JNI это превращается в `Array<NativeBook.Block>` — плоский список,
который UI листает постранично.
