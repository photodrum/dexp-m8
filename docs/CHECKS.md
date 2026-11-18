# Проверки на хосте

> Вынесено из `docs/BUILD.md` 2026-11-18: тесты C-ядра, разбор OPDS, срезы каталога,
> шифрование пароля каталога. Окружение, сборка, установка на книгу, git, подпись
> и публикация — `docs/BUILD.md`.

## Тесты C-ядра на хосте

Без NDK и без устройства — обычным `gcc`:

```bash
cd ~/projects/dexp-m8/app/src/main/cpp
gcc -std=gnu99 -O1 -g -Wall -Wextra -Icore -fsanitize=address,undefined \
    -o /tmp/test_core core/book.c test/test_core.c
cd test && setarch -R /tmp/test_core testdata/sample.fb2
```

Ожидаемый вывод: `parse rc=0`, `paras=4 titles=3 poems=2`, `meta rc=0 … complete=1`, `all good`.
`setarch -R` (без ASLR) обязателен: ASan из gcc 12 на ядре хоста (7.0, высокая энтропия mmap)
примерно в 1 запуске из 7 падает до `main` с `AddressSanitizer:DEADLYSIGNAL` — и на старом коде тоже.

### Раскладка DjVu (R2.4)

`engines/djvu_layout.h` — чистая арифметика без DjVuLibre, поэтому проверяется `g++`
без NDK:

```bash
cd ~/projects/dexp-m8
g++ -std=c++17 -O1 -Wall -Wextra -Iapp/src/main/cpp/engines \
    -o /tmp/test_djvu_layout app/src/main/cpp/test/test_djvu_layout.cpp
/tmp/test_djvu_layout          # «140 проверок, 0 провалов», «all good»
```

Стенд на настоящем файле (`test/host_djvu_view.cpp`) требует хостовой libdjvu; рецепт —
`app/src/main/cpp/README.md`, раздел «Раскладка DjVu». В APK он не входит.


## Проверка разбора OPDS на хосте

Ядро сетевых каталогов (`core/Opds.kt`) разбирается на JVM без устройства и без Gradle:
`parse` и `parseTemplate` — чистые функции в `companion object`.

```bash
export JAVA_HOME=$HOME/.local/jdk PATH=$HOME/.local/jdk/bin:$PATH
KX=~/.gradle/caches/modules-2/files-2.1/net.sf.kxml/kxml2/2.3.0/ccbc77a5fd907ef863c29f3596c6f54ffa4e9442/kxml2-2.3.0.jar
# стабы android.content.Context / ConnectivityManager / Base64 / ui.Trace — в /tmp/optest/stub
# companion object в Opds — private, поэтому для теста копия без слова private:
sed 's/    private companion object {/    companion object {/' \
    app/src/main/java/ru/ogrpfsense/dexpm8/core/Opds.kt > /tmp/optest/h/Opds.kt
$HOME/.local/kotlinc/bin/kotlinc -cp $KX -d /tmp/optest/out \
    /tmp/optest/stub/*.kt /tmp/optest/h/Opds.kt /tmp/optest/TplTest.kt
java -Dfile.encoding=UTF-8 -cp "/tmp/optest/out:$KX:$HOME/.local/kotlinc/lib/kotlin-stdlib.jar" TplTestKt
```

Проверяет то, что живёт в разборе, а не в сети: `rel="search"` из фида, OpenSearch-описание
BookLore → абсолютный шаблон, выбор `<Url kind="acquisition">`, шаблон без `{searchTerms}`,
снятие лишних подстановок (`{startIndex?}`, `{language}`), шаблон Calibre. Успех — строки `OK…`
и `=== ВСЁ ОК`; `FAIL` в выводе — падение разбора.

## Проверка срезов каталога на хосте

`sliceHref`/`findSlice` (`core/Opds.kt`) — чистая функция, проверяется без устройства: BookLore
отдаёт срезы записями-навигациями, Calibre — названиями «By Author»/«Tags», часть каталогов —
только ссылками `rel="subsection"` фида. Харнесс — тот же, что у поиска (стабы и `kxml2`),
файл теста `SliceTest.kt`:

```bash
# в харнессе /tmp/optest (см. выше), реальные фиды: /tmp/opds/f5b1d4f9d.xml — корень,
# /tmp/authors.xml — /api/v1/opds/authors, /tmp/series.xml — /api/v1/opds/series
$HOME/.local/kotlinc/bin/kotlinc -cp $KX -d /tmp/optest/out2 \
    /tmp/optest/stub/*.kt /tmp/optest/h/Opds.kt /tmp/optest/SliceTest.kt
java -Dfile.encoding=UTF-8 -cp "/tmp/optest/out2:$KX:$HOME/.local/kotlinc/lib/kotlin-stdlib.jar" SliceTestKt
```

19 проверок: срезы BookLore (`authors`/`series`, жанров нет), 442 автора и 147 серий, ссылки
записей (`catalog?author=`, `catalog?series=`), поиск по названию («By Author», «Tags»,
«Авторы/Серии/Жанры»), срез только ссылкой фида, `null` для отсутствующего среза. Успех —
`=== ВСЁ ОК`.

**Грабли харнесса:** `Opds.slice(...)` в тесте не компилируется — `slice` перехватывает
стандартное расширение `List.slice(indices: IntRange)`; метод называется `findSlice`, в тесте
вызывать через экземпляр (`opds.findSlice(feed, kind)`). Фикстуры `<feed>` не должны начинаться
с `<?xml …?>` после `trimIndent()` — `KXmlParser` на это падает (`PI must not start with xml`).

## Проверка шифрования пароля каталога

Пароль OPDS в базе — шифртекст (`core/Pass.kt`, `v1:` + Base64). Проверяется только на книге:
`AndroidKeyStore` на JVM нет. После установки APK и одного запуска:

```bash
adb shell 'run-as ru.ogrpfsense.dexpm8 sqlite3 databases/library.db \
  "pragma user_version; select substr(pass,1,16) from catalogs"'
# ожидается: 7   и   v1:BUt/ZJpRRCRbPrY

adb shell 'run-as ru.ogrpfsense.dexpm8 sh -c \
  "grep -c <старый-пароль> databases/library.db databases/library.db-wal"'
# ожидается: 0 в обоих файлах — иначе уборка (VACUUM) не прошла
```

В трассе (`files/DexpM8-trace.txt`) должны быть строки «pass: перешифровано N паролей каталогов»,
«db v7: открыта, версия 7» и «db v7: уборка после перешифрования готова». Вход в каталог после
обновления должен работать без повторного ввода пароля — это и есть проверка, что ключ прочитан.

