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


## Аннотация EPUB на хосте

`core/EpubMeta.kt` (`dc:description` из OPF) не зависит от Android — собирается вместе с тестом
`tools/epub_meta_test.kt`, который строит EPUB в памяти (zip в `/tmp`, без файлов в репозитории):

```bash
export JAVA_HOME=$HOME/.local/jdk PATH=$HOME/.local/jdk/bin:$PATH JAVA_OPTS=-Xmx1200m
$HOME/.local/kotlinc/bin/kotlinc -d /tmp/epubtest/out \
    app/src/main/java/ru/ogrpfsense/dexpm8/core/EpubMeta.kt tools/epub_meta_test.kt
java -Dfile.encoding=UTF-8 -cp "/tmp/epubtest/out:$HOME/.local/kotlinc/lib/kotlin-stdlib.jar" Epub_meta_testKt
```

Ожидается 22 строки `OK` и `=== ВСЁ ОК`: Calibre (HTML, экранированный как XML), обычный текст
с жёсткими переносами, CDATA, вложенные теги, пустое/самозакрытое/второе описание, описание из
одной точки или «…» (null), путь с `%20`,
windows-1251 и BOM, предел 2000 знаков без разрыва символа, не-zip и битые пути (null).

## Направление перевода на хосте

`core/TrDir.kt` (русское слово → en, любое другое → ru; язык книги или автоопределение MyMemory) —
чистый Kotlin, собирается вместе с `tools/tr_dir_test.kt`:

```bash
export JAVA_HOME=$HOME/.local/jdk PATH=$HOME/.local/jdk/bin:$PATH JAVA_OPTS=-Xmx1200m
$HOME/.local/kotlinc/bin/kotlinc -d /tmp/trtest/out \
    app/src/main/java/ru/ogrpfsense/dexpm8/core/TrDir.kt tools/tr_dir_test.kt
java -Dfile.encoding=UTF-8 -cp "/tmp/trtest/out:$HOME/.local/kotlinc/lib/kotlin-stdlib.jar" Tr_dir_testKt
```

Ожидается 25 строк `OK` и `=== ВСЁ ОК`.

**На книге** (после `assembleDebug`, слово → «Словарь» → вкладка «Перевод»): русская книга, русское
слово — «ru → en»; английская книга — «en → ru»; латинское слово в русской книге — «авто → ru»
и перевод на русский.

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

## Главы аудиокниг (п. 12)

Байтовые парсеры встроенных глав (`core/Id3Chapters.kt` — ID3 `CHAP`, `core/Mp4Chapters.kt` —
MP4 `chpl`) проверяются на JVM, без Android и без Gradle. Файлы синтетические, их пишет сам тест.

```bash
cd ~/projects/dexp-m8
~/.local/kotlinc/bin/kotlinc -d /tmp/audiocheck \
    app/src/main/java/ru/ogrpfsense/dexpm8/core/Id3Chapters.kt \
    app/src/main/java/ru/ogrpfsense/dexpm8/core/Mp4Chapters.kt tools/audio_chapters_test.kt
java -Dfile.encoding=UTF-8 -cp "/tmp/audiocheck:$HOME/.local/kotlinc/lib/kotlin-stdlib.jar" \
    Audio_chapters_testKt
```

Ожидаемый вывод: 11 строк `ok`, последняя — `all good`. Проверяются: три главы из `CHAP`
с кириллическими названиями (кодировка `TIT2` = 3, UTF-8), смещения и длительности; три главы
из `chpl` в двух вариантах заголовка — с резервным полем и без (редакторы пишут по-разному).

**Грабля:** первый вариант парсера читал `TIT2` с кодировкой 3 как ISO-8859-1 и ломал
кириллицу — тест это поймал, кодировка теперь разбирается по байту (0 — ISO-8859-1,
1 — UTF-16, 2 — UTF-16BE, 3 — UTF-8).

### Тестовое аудио для книги

`ffmpeg` на книге и на хосте нет, mp3 в системе тоже. Годится синтетика: кадры тишины
MPEG-1 Layer III 128 кбит/с, 44.1 кГц, моно + тег `ID3v2.4` с главами `CHAP`:

```python
FRAME = 144 * 128000 // 44100                 # 417 байт
head = b"\xff\xfb\x90\xc0"                    # MPEG1 L3, 128k, 44.1k, mono
audio = (head + bytes(FRAME - 4)) * 919       # ~24 с
# глава: b"CHAP" + syncsafe(len) + b"\x00\x00" + id + b"\x00" + be32(start) + be32(end)
#        + be32(0) + be32(0) + b"TIT2" + syncsafe(n) + b"\x00\x00" + b"\x03" + title.encode()
```

Тег `ID3\x04\x00\x00` + syncsafe(длина) + кадры `CHAP`, затем кадры звука. Проверено:
книга из трёх таких файлов в `/sdcard/Audio/<папка>` сканируется, длительности и главы верные.

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

## Шахматы на хосте

Пакет `chess/` (п. 25) не зависит от Android — собирается вместе с тестом `tools/chess_test.kt`:

```bash
export JAVA_HOME=$HOME/.local/jdk PATH=$HOME/.local/jdk/bin:$PATH JAVA_OPTS=-Xmx1200m
$HOME/.local/kotlinc/bin/kotlinc -d /tmp/chesstest/out \
    app/src/main/java/ru/ogrpfsense/dexpm8/chess/*.kt tools/chess_test.kt tools/chess_fics_test.kt
java -Dfile.encoding=UTF-8 -cp "/tmp/chesstest/out:$HOME/.local/kotlinc/lib/kotlin-stdlib.jar" Chess_testKt
```

С 25.3 — `chess_fics_test.kt` (FICS без сети: строки, `<12>`, концы партии, команды, `NetGame`,
часы; строки дословно с сервера). Живая проверка с сервером (нужна сеть, ~40 с; два гостя играют
друг с другом через `match`, чужих не трогают) — ждём `=== ВСЁ ОК (23 проверок)`:

```bash
$HOME/.local/kotlinc/bin/kotlinc -d /tmp/ficslive/out \
    app/src/main/java/ru/ogrpfsense/dexpm8/chess/*.kt tools/fics_live.kt
java -Dfile.encoding=UTF-8 -cp "/tmp/ficslive/out:$HOME/.local/kotlinc/lib/kotlin-stdlib.jar" Fics_liveKt
```

Ожидается `=== ВСЁ ОК (265 проверок)` (25.3; 25.2 — 203), строка `perft: … узлов за … мс` — на хосте
~31,7 млн узлов за 3–4 с — и две строки `search: …` (начальная, глубина 5 — ~188 тыс. узлов
за ~75 мс; Kiwipete, глубина 4 — ~55 тыс. за ~30 мс). Что проверяется: perft эталонных позиций chessprogramming.org
(«Perft Results», позиции 1–6) и набора Седлака (взятие на проходе со вскрытым шахом, рокировки
под боем, превращения, пат и мат); FEN туда и обратно с нормализацией; отказ на битых FEN; ключ
Zobrist после хода и отмены на дереве глубины 3. С 25.1б — SAN (запись с уточнениями, разбор с
допусками и отказами, туда-обратно на дереве глубины 2) и `Game`: мат, пат, 50/75 ходов,
повторы 3/5 (связанное взятие на проходе не мешает повтору), мёртвый материал. С 25.1в — оценка
(симметрия), поиск (маты в 1 и 2, висящий ферзь, пределы глубины/узлов/времени, `stop()` из
другого потока, `Position.copy()`), уровни и «неточность». Проверки времени (300 мс ± 150) на
загруженном хосте могут промахнуться — перезапустить. С 25.2 — `RuSan` (12 записей: Кр Ф Л С К,
превращение, рокировка) и `GameSave` (строка, туда-обратно, 8 битых строк, проигрывание, невозможный ход). Расхождение perft искать через
`Perft.divide(pos, depth)` — по первому ходу, сверяя с любым UCI-движком (`go perft N`).

## Шахматы на книге

Замер скорости `chess/` на PX30 (25.1г) — без APK: `tools/chess_bench.kt` вместе с `chess/` в dex
и запуск через `app_process` (ART тот же, JIT включён). Сборка (с тем же `export`, что выше):

```bash
mkdir -p /tmp/chessbench/out
$HOME/.local/kotlinc/bin/kotlinc -d /tmp/chessbench/out \
    app/src/main/java/ru/ogrpfsense/dexpm8/chess/*.kt tools/chess_bench.kt
(cd /tmp/chessbench/out && jar cf ../classes.jar .)
$HOME/.local/android-sdk/build-tools/34.0.0/d8 --release --min-api 27 --output /tmp/chessbench \
    /tmp/chessbench/classes.jar $HOME/.local/kotlinc/lib/kotlin-stdlib.jar
```

Запуск (`ADB=~/.local/android-sdk/platform-tools/adb`; экран на время замера не гаснет — после
вернуть прежнее `settings get global stay_on_while_plugged_in`, на книге было 0):

```bash
$ADB push /tmp/chessbench/classes.dex /data/local/tmp/chess_bench.dex
$ADB shell input keyevent KEYCODE_WAKEUP; $ADB shell svc power stayon usb
$ADB shell 'CLASSPATH=/data/local/tmp/chess_bench.dex app_process /data/local/tmp Chess_benchKt levels'
$ADB shell settings put global stay_on_while_plugged_in 0; $ADB shell rm /data/local/tmp/chess_bench.dex
```

Без `levels` — ещё perft и поиск на глубину (~3 мин), с `levels` — только уровни 1–8 (~1,5 мин).
Ожидается (2026-11-26): perft ~410 узлов/мс, поиск в миттельшпиле ~80 узлов/мс; уровни 1–6 —
своя глубина за ≤ 2,5 с, 7 — глубина 5 за ~4,7 с, 8 — до 20 с. Тот же класс на хосте — для
сравнения (`java … Chess_benchKt`, perft ~10 500 узлов/мс). Числа — `docs/archive/DEVLOG-2026-11-26-25.1g.md`.

### Экраны шахмат (25.2) — проверка человеком на книге

`gradle assembleDebug` (не `tools/build.sh`), установка, дальше по порядку; подтверждать после каждого пункта:

1. B1 → «Приложения»: плитка «Шахматы» (иконка `chess`) стоит по алфавиту, касание открывает B77.
2. B77а (партии нет): одна строка «С компьютером», пояснение под панелью. «Назад» → B6.
3. B78: «слабее» / «сильнее» меняют цифру и описание (1…8), цвет Белые/Чёрные/Случайно, «Начать партию».
4. B79 белыми: фигуры как на B79, касание своей фигуры — рамка и точки ходов, второе касание —
   ход; «Думает…» на полосе компьютера, ответ на уровне 1 за ≤ 2 с; последний ход обведён, внизу — запись ходов.
5. Превращение: довести пешку до последнего ряда — лист B80, выбор фигуры, «Отмена».
6. Шах (кольцо на короле, «Шах · ваш ход»), мат и пат — диалог B83; «Смотреть ходы» → B81, «Назад» → диалог снова.
7. «Отменить ход» (и в меню B82): откатывает свой ход и ответ; без своих ходов кнопка серая.
8. B82: «Предложить ничью» (отказ — «Компьютер отказался»), «Перевернуть доску», «Сдаться» → B84 → B83.
9. Чёрными: доска повёрнута сама (B79б), компьютер ходит первым.
10. Выход «Назад» посреди партии → B77 с «Продолжить партию · уровень N · ход M · сегодня, ЧЧ:ММ»; продолжение
    возвращает на то же место (а если ход компьютера — он думает заново). Перезапуск оболочки в партии — то же.
11. Уровень 8: время ответа (открыто с 25.1г — до 20 с); «Назад» во время «Думает…» не вешает книгу.

### Партия по сети (25.3б) — проверка человеком на книге

Сборка и установка — как выше. Соперник: второй гость с хоста — `telnet freechess.org 5000`, логин
`guest`, затем `sought` (список объявлений) и `play <номер>`; свой ход — `e2e4`. Подтверждать после каждого пункта:

1. B77: строка «По сети» (иконка `wifi`) под «С компьютером», подзаголовок «С компьютером или по сети»; касание → B85.
2. B85: сегменты «15 + 10 / 30 + 0 / 10 + 5» переключаются касанием; «Назад» → B77.
3. Wi-Fi выключен → «Искать соперника» → B87 «Нет подключения»; «Настройки Wi-Fi» открывает системный экран,
   по возврату экран чистый; «Повторить» при включённом Wi-Fi — поиск (B85а).
4. B85а: «Ищем соперника…» и счётчик «0:01, 0:02…» — перерисовывается только он (без мигания всего экрана);
   «Отмена» → B85; книга не засыпает за время поиска дольше «Засыпать через» (B40).
5. Соперник принял объявление → B86: белыми внизу, чёрными — доска повёрнута сама; часы идут у стороны,
   чей ход (тёмная плашка); имя соперника сверху, «Вы · GuestXXXX» снизу.
6. Ход касанием (выбор → точки → ход), ход соперника, взятые фигуры у полос, последние ходы справа внизу;
   превращение — лист B80.
7. Часы: «11:58» меняются раз в секунду, остальной экран не мигает; меньше минуты — рамка (B86а).
8. «Ничья»: подзаголовок «Ничья предложена»; отказ соперника — «Соперник отклонил ничью»; предложение
   соперника — B87б, «Принять» → B83 «Ничья», «Отклонить» → партия идёт.
9. «Сдаться» и «Назад» посреди партии → B84; «Сдаться» → B83; «Смотреть ходы» → B81 («По сети · 15 + 10 · N ходов»).
10. Соперник вышел из партии (`quit` у второго гостя) → B87а; «Новая партия» → B85, «В меню» → B77.
11. Мат, флаг (часы до нуля), сдача соперника — B83 с верным заголовком и победителем.
12. Wi-Fi выключить посреди партии → через ≤ 2,5 мин B87; «Повторить» → B85.
13. «Домой» посреди партии → «Приложения» → «Шахматы»: на B77 «Вернуться к партии» → B86 с текущей позицией.

