# 2026-11-19 — правка crengine больше не висит в подмодуле (замечание 19)

Замечание 19 закрыто 2026-11-18 (`docs/archive/DEVLOG-2026-11-18-z19.md`), но в `docs/CONTEXT.md`
остался долг: «правка `include/lvfntman.h` не закоммичена в подмодуле — потеряется при
`git submodule update`». Разбирались, что из этого правда.

## Что оказалось

- Правка **уже** не потеряется: `tools/patches/crengine-19-glyph-align.patch` совпадает с рабочим
  диффом подмодуля байт в байт (`git diff` в подмодуле → `diff` с патчем → «ИДЕНТИЧНО»), а
  `engines/cre.cmake` накладывает его при каждой конфигурации. Сам подмодуль не наш (koreader),
  коммитить в него нечего и некуда — патч и есть способ хранить правку.
- Настоящая дыра была в другом: `git apply` стоял **только** в блоке конфигурации. Если откатить
  подмодуль (`git submodule update`, `git checkout` внутри него) при живом кеше `app/.cxx`,
  CMake не перезапускается, патч не накладывается — натив молча собирается с исходным `malloc`,
  то есть с SIGBUS на кегле 8…12. Воспроизведено: `checkout` → `assembleDebug` → `allocItem=0`.

## Что сделано

- `engines/cre.cmake`: `set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
  ${CRE_DIR}/crengine/include/lvfntman.h)` — файл подмодуля стал зависимостью конфигурации.
  Откат меняет ему время, CMake перезапускается и накладывает патч заново.
- `.gitmodules`: `ignore = dirty` у `third_party/crengine` — грязь подмодуля больше не висит
  в `git status` родителя, хотя патч в рабочей копии лежит (иначе `assembleDebug` каждый раз
  показывал ` m third_party/crengine`).

## Проверки

| Сценарий | Было | Стало |
|---|---|---|
| кеш `.cxx` есть, подмодуль откатили | `allocItem=0` (баг в нативе) | `allocItem=3`, предупреждения нет |
| `gradle clean` + `rm -rf app/.cxx` | патч накладывается | `allocItem=3` |
| повторная сборка без правок | — | 565 мс, `configure` один раз, цикла нет |
| `git submodule update --init --depth 1` | грязь видна | патч на месте, `status` чист |

`assembleDebug` — BUILD SUCCESSFUL. Правка нативной сборки, на книге проверять нечего.

## Грабли

- `skip-worktree` на файле подмодуля **не годится**: он же прячет файл от `git apply`
  (`pathspec did not match any file(s) known to git`) — патч перестаёт накладываться. Откатан.
- `CMAKE_CONFIGURE_DEPENDS` на файл, который сам патч же и меняет, цикла не даёт: CMake
  перезапускается один раз, второй проход видит патч на месте (`--reverse --check` → пропуск).
