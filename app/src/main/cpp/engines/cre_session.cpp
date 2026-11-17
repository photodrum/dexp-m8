/*
 * Сеанс crengine для читалки (R1, `docs/READER_SPEC.md` раздел 4).
 *
 * Один открытый документ на процесс: `LVDocView` не потокобезопасен, все вызовы идут
 * из одного фонового потока (Kotlin — `Engine.worker`). Вид живёт между вызовами:
 * страницы, шрифты и разметка не пересчитываются.
 *
 * Чего не было в пробе R0 (`cre_probe.cpp`):
 *  - вид держится между вызовами;
 *  - страница рисуется в буфер вызывающего, без аллокаций на кадр;
 *  - позиция — xpointer, а не номер страницы: переживает смену кегля;
 *  - ошибка возвращается кодом, процесс не падает (READER_SPEC, раздел 4).
 *
 * Коды ошибок — общие `DEXP_PROBE_*` из probe.h плюс свои `DEXP_READER_*`.
 */
#include <android/log.h>
#include <string.h>
#include <time.h>

#include <vector>

/* FreeType уже слинкован в dexp_cre (cre.cmake) — им и читаем имя семейства из файла шрифта. */
#include <ft2build.h>
#include FT_FREETYPE_H
#include FT_SFNT_NAMES_H
#include FT_TRUETYPE_IDS_H

#include "crengine.h"
#include "hyphman.h"
#include "textlang.h"
#include "cre_fonts.h"
#include "cre_gray.h"
#include "probe.h"

#define DEXP_LOG(...) __android_log_print(ANDROID_LOG_INFO, "DexpM8", __VA_ARGS__)

/* Свои коды сеанса (общие `DEXP_PROBE_*` из probe.h не повторяются). */
enum {
    DEXP_READER_ENOSESSION = -10,  /* документ не открыт */
    DEXP_READER_EPOS = -11,        /* позиция не нашлась в тексте */
    DEXP_READER_EFONT = -12        /* шрифтов нет — рисовать нечем */
};

namespace {

/* Кэш разметки: 24 МБ при 16 ГБ flash — хватает на 2–3 книги. */
const lvsize_t CACHE_MAX_BYTES = 24 * 1024 * 1024;

/**
 * Вес загрузки файла в общем прогрессе открытия, проценты. Загрузка 5-МБ FB2 — сотни мс,
 * разметка — секунды, поэтому разметке отдана основная часть шкалы.
 */
const int LOAD_SHARE = 30;

/** Куда писать прогресс (Kotlin, ставит `dexp_reader_set_progress_hook`). */
typedef void (*ProgressHook)(int percent);

ProgressHook g_hook = NULL;
/* Последний отданный процент: движок при загрузке из кэша может шагнуть назад — шкала не должна. */
int g_lastPercent = -1;

/** Единственный вид на процесс; между книгами тот же самый. */
LVDocView *g_view = NULL;
bool g_fontsOk = false;
bool g_cacheOk = false;
int g_pages = 0;

/**
 * Путь к каталогу со своими шрифтами читалки (`Engine.init` распаковывает `res/font`).
 * Регистрация идёт один раз при первом `prepare`: `fontMan` живёт на процесс.
 */
const char *g_appFontDir = NULL;
/* Инициализированы ли переносы (HyphMan::initDictionaries) — один раз на процесс. */
bool g_hyphOk = false;

/**
 * Типографика B4/B70, общая для всех книг (как в макете: это настройки читалки, не книги).
 * Держим и применяем заново при каждом открытии: новый `LVDocView` начинает с умолчаний.
 *
 * Коды гарнитур. 0…3 — встроенные в APK (Literata, Manrope, системный моно) и PT Serif;
 * 100 и выше — шрифт, выбранный пользователем строкой «Открыть шрифт…» в B70: файл лежит
 * в папке `Fonts` на карте, регистрируется в рантайме и нумеруется [g_userFonts] по порядку.
 *
 * Порядок кодов — часть сохранённых настроек (`Prefs.textFace`, B4/B39/B70): новые только
 * дописываются в конец, переставлять нельзя (пользователь уже выбрал «Гротекс» = 1).
 */
enum { FACE_SERIF = 0, FACE_SANS = 1, FACE_MONO = 2, FACE_PTSERIF = 3, FACE_USER = 100 };

struct Typography {
    int size;        /* кегль, дизайн-px макета (20 в B4); 0 — умолчание движка */
    int face;        /* FACE_* */
    int interline;   /* процент 70…200 (100 — обычный) */
    int margin;      /* поля слева и справа, px окна */
    bool hyphens;    /* переносы слов */
};

Typography g_type = { 0, FACE_SERIF, 100, 0, true };

/**
 * Шрифты, добавленные пользователем (строка «Открыть шрифт…» B70), в порядке регистрации:
 * код гарнитуры `FACE_USER + индекс`. [family] — имя из name-таблицы файла (его ищет crengine),
 * [path] — файл на карте: по нему не регистрируем один и тот же шрифт дважды.
 */
struct UserFont {
    lString8 family;
    lString8 path;
};

std::vector<UserFont> g_userFonts;

/** Имена встроенных семейств: порядок = FACE_SERIF, FACE_SANS, FACE_MONO, FACE_PTSERIF. */
static const char *const FACE_NAMES[] = {
    "Literata",        /* FACE_SERIF   — антиква по умолчанию (B4/B70 «Литерата») */
    "Manrope",         /* FACE_SANS    — гротеск B4/B70 */
    /* В макете «Моно» — Martian Mono, файла в проекте нет; берём системный
       (`name` table — именно «Droid Sans Mono», с пробелами). */
    "Droid Sans Mono", /* FACE_MONO */
    "PT Serif"         /* FACE_PTSERIF — вторая антиква B70 (свои начертания в APK) */
};

/** Сколько встроенных гарнитур (коды 0…FACE_PTSERIF); дальше — `FACE_USER + индекс`. */
static const int FACE_COUNT = 4;

/** Имя семейства в crengine для гарнитуры B4/B70 (код или `FACE_USER + индекс`). */
const char *face_name(int face)
{
    if (face >= FACE_USER) {
        int i = face - FACE_USER;
        return (size_t) i < g_userFonts.size() ? g_userFonts[i].family.c_str()
                                               : FACE_NAMES[FACE_SERIF];
    }
    return face >= 0 && face < FACE_COUNT ? FACE_NAMES[face] : FACE_NAMES[FACE_SERIF];
}

void report_progress(int percent)
{
    if (percent < 0)
        percent = 0;
    if (percent > 100)
        percent = 100;
    if (percent <= g_lastPercent)
        return;
    g_lastPercent = percent;
    if (g_hook)
        g_hook(percent);
}

/**
 * Колбэк crengine (`lvtinydom.h`, `LVDocViewCallback`) — экран B58 «Открываем книгу».
 * Движок зовёт его из того же потока, что и `dexp_reader_open` (`Engine.worker`).
 *
 * Прерывания разметки в crengine нет: `Render` — один вызов без флагов остановки,
 * `OnFormatProgress` только сообщает процент. Поэтому отмена в читалке — решение
 * Kotlin: экран уходит, результат открытия выбрасывается, документ закрывается
 * сразу после возврата `dexp_reader_open`.
 */
class ProgressCallback : public LVDocViewCallback {
public:
    void OnLoadFileStart(lString32 /*filename*/) override { report_progress(0); }
    void OnLoadFileProgress(int percent) override { report_progress(percent * LOAD_SHARE / 100); }
    void OnLoadFileEnd() override { report_progress(LOAD_SHARE); }
    void OnFormatStart() override { report_progress(LOAD_SHARE); }
    void OnFormatProgress(int percent) override {
        report_progress(LOAD_SHARE + percent * (100 - LOAD_SHARE) / 100);
    }
    void OnDocumentReady() override { report_progress(100); }
};

ProgressCallback g_progressCallback;

/**
 * Пункт оглавления в том виде, в каком его отдаёт JNI: уже строкой UTF-8.
 * Копия делается один раз на разметку — `LVTocItem` живёт в DOM и меняется
 * при перевёрстке, а номера страниц после `setFont` пересчитываются.
 */
struct TocEntry {
    int level;
    int page;
    lString8 title;
    lString8 pos;
};

std::vector<TocEntry> g_toc;
bool g_tocValid = false;

void drop_toc()
{
    g_toc.clear();
    g_tocValid = false;
}

long now_ms()
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long)(ts.tv_sec * 1000L + ts.tv_nsec / 1000000L);
}

/**
 * Подготовить движок: менеджер шрифтов (раз на процесс), переносы и кэш разметки.
 * Каталог кэша передаёт Kotlin: путь `/data/data/<пакет>` знает только он.
 * [appFontDir] — каталог со своими начертаниями (`res/font` внутри APK, распаковка в Kotlin).
 */
bool prepare(const char *cacheDir, const char *appFontDir)
{
    if (!fontMan) {
        InitFontManager(lString8::empty_str);
        g_appFontDir = appFontDir;
        g_fontsOk = dexp_register_system_fonts(g_appFontDir) > 0;
        DEXP_LOG("reader: fontMan %s (sys + %s)", g_fontsOk ? "ok" : "empty",
                 appFontDir && appFontDir[0] ? appFontDir : "нет своих");
    }
    if (!g_fontsOk)
        return false;
    /*
     * Переносы: без `HyphMan::initDictionaries` список словарей не создан (`_dictList == NULL`),
     * и `getHyphMethodForDictionary` всегда отдаёт NO_HYPH — то есть до R3.4 переносов в
     * читалке не было вовсе. Каталог пустой намеренно: движок заводит встроенные варианты
     * («нет», «алгоритм», «мягкие переносы»), а алгоритм поддерживает кириллицу
     * (`char_props` в lvstring.cpp размечен для 0x0410…0x044F).
     * Своих .pdb-словарей не кладём: Russian_hyphen_(Alan).pdb — 63 КБ в APK ради
     * расстановки переносов, а алгоритм на глаз не хуже (вопрос в DESIGN_QUESTIONS.md).
     */
    if (!g_hyphOk) {
        g_hyphOk = HyphMan::initDictionaries(lString32::empty_str);
        TextLangMan::setMainLang(lString32("ru"));
        DEXP_LOG("reader: hyph %s", g_hyphOk ? "ok" : "алгоритм");
    }
    if (!g_cacheOk && cacheDir && cacheDir[0]) {
        g_cacheOk = ldomDocCache::init(Utf8ToUnicode(cacheDir), CACHE_MAX_BYTES);
        DEXP_LOG("reader: cache %s (%s)", g_cacheOk ? "ok" : "off", cacheDir);
    }
    return true;
}

/**
 * Гарнитура B4 против `font-family` книги (замечание 18, 2026-11-16).
 *
 * `setDefaultFontFace` — только значение по умолчанию (`PROP_FONT_FACE`): `LVDocView::
 * setDefaultFontFace` кладёт имя в `m_defaultFontFace` и зовёт перерисовку, а при разборе
 * CSS имя из книги остаётся в силе. Проверено по коду `LVFontSelector::select`
 * (`lvfntman.cpp:6704`), порядок там такой:
 *
 *   1. каждое имя из `font-family` книги, если семейство зарегистрировано (`findFamily`);
 *   2. `preferred_family` — предпочтение пользователя (`fontMan->SetPrimaryFont`);
 *   3. любое семейство нужного generic-типа;
 *   4. любое зарегистрированное.
 *
 * EPUB со своим `@font-face` регистрирует шрифт документа, поэтому шаг 1 всегда срабатывает
 * и «Гротекс» в B4 не менял ничего (DEVLOG 2026-11-15: 1451 стр. при всех трёх гарнитурах).
 *
 * Два рычага, оба штатные:
 *
 *  - `fontMan->SetPrimaryFont(face)` — заполняет `_preferred_family`, то есть шаг 2. Без него
 *    шаг 2 мёртв: `SetAsPreferredFontWithBias` в движке никто не зовёт (проверено по всему
 *    `third_party/crengine`), и `_preferred_family` остаётся пустым.
 *  - `crengine.font.family.faces` (`PROP_FONT_FAMILY_FACES` → `setFontFamilyFonts`,
 *    `lvtinydom.h:2888`): строка через `|`, где `[0]` непустое значит «имена из книги
 *    отбрасывать», а `[1]…[8]` — имена для восьми generic-семейств в порядке
 *    `css_font_family_t` (`cssdef.h`): serif, sans-serif, cursive, fantasy, monospace,
 *    math, emoji, fangsong. В `lvstsheet.cpp:4349` generic-имя в списке `font-family`
 *    заменяется на наше, а всё левее (имя шрифта книги) вычёркивается — это и нужно для
 *    книг, где `font-family` вообще без generic-имени.
 *
 * Все восемь семейств заполняем одной выбранной гарнитурой: гарнитура в читалке одна, и
 * `font-family: monospace` из стилей книги тоже должен слушаться B4. Встроенные шрифты книги
 * при этом **не** отключаются (`crengine.doc.embedded.fonts.enabled` не трогаем) — синтез
 * начертаний и фолбэк для недостающих символов работают как раньше.
 */
lString8 face_family_faces(const char *face)
{
    lString8 s("1");                  /* [0] непусто → имена книги отбрасывать */
    for (int i = 0; i < 8; i++) {     /* [1]…[8] — восемь generic-семейств */
        s += "|";
        s += face;
    }
    return s;
}

/**
 * Прокинуть выбранную гарнитуру в менеджер шрифтов (шаг 2 в `LVFontSelector::select`).
 * Зовётся при каждой смене гарнитуры и при открытии книги: `_preferred_family` живёт на
 * процесс (`fontMan` один), но полагаться на это не будем — выставим явно.
 */
void apply_preferred_face()
{
    if (fontMan)
        fontMan->SetPrimaryFont(lString8(face_name(g_type.face)));
}

/**
 * Применить типографику B4 к виду. Зовётся после `LoadDocument` (нужен готовый DOM для
 * перевёрстки) и из `dexp_reader_set_typography` при смене настроек.
 *
 * Поля — `setPageMargins`: `Render()` считает ширину как `ширина буфера − left − right`,
 * то есть поля действительно сжимают текст (не только отступ при отрисовке). Буфер
 * экрана — уже область текста B3 (1199×1540), поэтому «Узкие» = 0.
 *
 * Переносы. Тонкость движка (проверено по коду, `textlang.cpp`/`hyphman.cpp`):
 * `TextLangCfg::getHyphMethod()` при `_overridden_hyph_method == false` отдаёт метод
 * по языку, а для «ru» это `Russian.pattern` из списка словарей — у нас его нет,
 * значит NO_HYPH и переносов не будет никогда. Поэтому включаем **алгоритмический**
 * режим (`setHyphenationForceAlgorithmic`): он ставит `_overridden_hyph_method = true`
 * и методом становится встроенный AlgoHyph, размеченный под кириллицу
 * (`char_props` в `lvstring.cpp`: гласные/согласные для 0x0410…0x044F).
 * Выключение — `setHyphenationEnabled(false)`, тогда метод NO_HYPH.
 */
void apply_typography()
{
    if (!g_view)
        return;
    const char *face = face_name(g_type.face);
    g_view->setDefaultFontFace(lString8(face));
    /* Замечание 18: имя из книги сильнее `setDefaultFontFace` — см. face_family_faces(). */
    g_view->propApply(lString8(PROP_FONT_FAMILY_FACES), Utf8ToUnicode(face_family_faces(face)));
    apply_preferred_face();
    if (g_type.size > 0)
        g_view->setFontSize(g_type.size);
    g_view->setDefaultInterlineSpace(g_type.interline);
    int m = g_type.margin > 0 ? g_type.margin : 0;
    g_view->setPageMargins(lvRect(m, 0, m, 0));
    TextLangMan::setHyphenationSoftHyphensOnly(false);
    TextLangMan::setHyphenationForceAlgorithmic(true);
    TextLangMan::setHyphenationEnabled(g_type.hyphens);
}

/* ------------------------------------------------- выделение и поиск (R4.3, B28/B50–B52) */

/**
 * Выделение (B50–B52) живёт на стороне сеанса: [g_sel] — диапазон от начала до конца
 * в xpointer, в порядке текста. Движку свою подсветку НЕ отдаём (`selectRange`/`selectWords`
 * не зовём): страница рисуется в буфер как есть, а плашки G12 оболочка рисует сама по
 * прямоугольникам [dexp_reader_selection_rects] — так требует макет B50 и ROADMAP п. 17
 * («плашкой G12 по рамкам слов от движка, не getRange»).
 *
 * Диапазон держит ссылки на узлы DOM, поэтому освобождается ДО `delete g_view`
 * (см. [drop_document]): указатель в освобождённое дерево — падение процесса.
 */
ldomXRange *g_sel = NULL;

/** Уронить выделение. Звать до уничтожения вида — оно держит xpointer в его DOM. */
void sel_clear()
{
    if (g_sel) {
        delete g_sel;
        g_sel = NULL;
    }
}

/** Поставить выделение по готовому диапазону [range]. */
void sel_set(const ldomXRange &range)
{
    sel_clear();
    g_sel = new ldomXRange(range);
}

/**
 * Результат последнего поиска по книге (B28). Позиция — xpointer, номер страницы — на
 * момент поиска. После перевёрстки копия выбрасывается ([drop_search] из [relayout]):
 * xpointer старого DOM после новой разметки указывает в другое место (DEVLOG 2026-11-13),
 * а номера страниц считаются только по новой.
 */
struct SearchHit {
    int page;
    lString8 pos;
    lString8 text;
};

std::vector<SearchHit> g_search;

/**
 * Сколько совпадений держим и отдаём в B28. 500 строк — это ~125 экранов списка; больше
 * на 1 ГБ ОЗУ бессмысленно, а поиск «и» иначе вернул бы десятки тысяч.
 */
const int SEARCH_MAX_HITS = 500;

void drop_search()
{
    g_search.clear();
}

/**
 * Кусок текста вокруг совпадения для строки B28: «…Все смешалось в доме Облонских. Жена…».
 * Берём текстовый узел, где стоит начало совпадения, отсчитываем [ctx] символов в обе
 * стороны и обрезаем по границам слов. Совпадение, перешедшее в другой узел (переносом
 * внутри абзаца), отдаётся текстом самого диапазона.
 */
lString8 excerpt_of(ldomXRange &range, int ctx)
{
    ldomXPointer &sp = range.getStart();
    ldomXPointer &ep = range.getEnd();
    ldomNode *node = sp.getNode();
    if (!node || !node->isText() || ep.getNode() != node)
        return UnicodeToUtf8(range.getRangeText(' '));
    lString32 txt = node->getText();
    int n = (int) txt.length();
    int a = sp.getOffset();
    int b = ep.getOffset();
    if (a < 0)
        a = 0;
    if (b < a)
        b = a;
    if (b > n)
        b = n;
    int from = a - ctx;
    int to = b + ctx;
    if (from < 0)
        from = 0;
    if (to > n)
        to = n;
    /* Границы — по пробелу, чтобы фрагмент не начинался с половины слова. За пределы
       самого совпадения не выходим: там стоят те же неразрывные буквы. */
    while (from > 0 && from < a && txt[from] != ' ')
        from++;
    while (to > b && to < n && txt[to] != ' ')
        to--;
    while (from < a && txt[from] == ' ')
        from++;
    while (to > b && txt[to - 1] == ' ')
        to--;
    lString32 out;
    if (from > 0)
        out << (lChar32) 0x2026;   /* … */
    out << txt.substr(from, to - from);
    if (to < n)
        out << (lChar32) 0x2026;
    return UnicodeToUtf8(out);
}

/**
 * Скопировать UTF-8 в буфер вызывающего, не разрывая символ: обрыв посреди символа даёт
 * невалидную строку, а `NewStringUTF` на ней абортит процесс (падеж на EPUB 2026-11-13).
 * Возвращает длину или код `< 0`.
 */
int copy_utf8(const lString8 &s, char *out, int outLen)
{
    if (!out || outLen <= 1)
        return DEXP_PROBE_EARG;
    int n = (int) s.length();
    if (n + 1 > outLen) {
        n = outLen - 1;
        while (n > 0 && ((unsigned char) s[n] & 0xC0) == 0x80)
            n--;
    }
    memcpy(out, s.c_str(), (size_t) n);
    out[n] = 0;
    return n;
}

/**
 * Переверстать после смены типографики, удержав место чтения [keepPos] (xpointer).
 * Возвращает число страниц или `< 0`.
 *
 * Позицию ищем строкой ПОСЛЕ перевёрстки: xpointer, созданный на старом DOM, после
 * новой разметки указывает в другое место (проверено на книге 2026-11-13 — место
 * чтения уезжало с 50 % на 61 %). Условие приёмки — «та же фраза на странице», а не
 * её первый символ (R1.2).
 */
int relayout(const char *keepPos)
{
    apply_typography();
    g_view->checkRender();
    g_pages = g_view->getPageCount();
    drop_toc();   // номера страниц пунктов посчитает build_toc на новой разметке
    /* Выделение и результаты поиска — на старом DOM: после перевёрстки их xpointer
       указывают в другое место (DEVLOG 2026-11-13), а номера страниц не совпадают. */
    sel_clear();
    drop_search();
    if (keepPos && keepPos[0]) {
        ldomXPointer pos = g_view->getDocument()->createXPointer(Utf8ToUnicode(keepPos));
        if (!pos.isNull()) {
            g_view->goToBookmark(pos);
            g_view->checkRender();
        }
    }
    return g_pages;
}

/**
 * Закрыть документ. Вид удаляется целиком: деструктор `LVDocView` освобождает DOM и разметку.
 * Пустой `LVStreamRef` в `LoadDocument` не годится — там `stream->GetSize()` без проверки.
 */
void drop_document()
{
    sel_clear();   /* выделение держит xpointer в DOM вида — уронить до delete g_view */
    drop_search();
    if (g_view) {
        delete g_view;
        g_view = NULL;
    }
    g_pages = 0;
    drop_toc();
}

/**
 * Обойти дерево оглавления и сложить пункты плоско, в порядке документа.
 * Уровень берём у движка (`getLevel`, корень — 0), корневой пункт не выводим:
 * у него нет ни заголовка, ни страницы.
 */
void collect_toc(LVTocItem *item)
{
    for (int i = 0; i < item->getChildCount(); i++) {
        LVTocItem *child = item->getChild(i);
        if (child->getLevel() > 0) {
            TocEntry e;
            e.level = child->getLevel();
            e.page = child->getPage();
            e.title = UnicodeToUtf8(child->getName());
            e.pos = UnicodeToUtf8(child->getPath());
            g_toc.push_back(e);
        }
        collect_toc(child);
    }
}

/**
 * Перестроить копию оглавления. `getToc` при загрузке из кэша сам пересчитает
 * номера страниц, если разметка с тех пор менялась (вертикальный scroll, кегль).
 */
void build_toc()
{
    drop_toc();
    if (!g_view)
        return;
    LVTocItem *root = g_view->getToc();
    if (!root)
        return;
    g_toc.reserve(256);
    collect_toc(root);
    g_tocValid = true;
    DEXP_LOG("reader: toc %d items", (int)g_toc.size());
}

/**
 * Отрезки диапазона [range] в координатах окна — плоский `int[]` по 4 числа
 * (`x, y, w, h`). Возвращает число отрезков (может быть меньше найденных, если [outLen]
 * мал). Одна строка текста — один отрезок.
 *
 * `getSegmentRects` отдаёт координаты документа целиком, поэтому каждая пара точек
 * переводится `docToWindowPoint`; отрезок, не попавший на текущую страницу, отбрасывается.
 * Верхняя точка — обычным вызовом, нижняя — с `isRectBottom`: низ строки совпадает
 * с верхом следующей, и без флага движок отнёс бы его к другой странице.
 */
int range_rects_to_window(ldomXRange &range, int *out, int outLen)
{
    LVArray<lvRect> rects;
    range.getSegmentRects(rects, false);
    int cap = outLen / 4;
    int n = 0;
    for (int i = 0; i < rects.length(); i++) {
        lvRect r = rects[i];
        if (r.isEmpty())
            continue;
        lvPoint p0 = r.topLeft();
        lvPoint p1 = r.bottomRight();
        if (!g_view->docToWindowPoint(p0))
            continue;
        if (!g_view->docToWindowPoint(p1, true))
            continue;
        int w = p1.x - p0.x;
        int h = p1.y - p0.y;
        if (w <= 0 || h <= 0)
            continue;
        if (n >= cap)
            break;
        out[n * 4 + 0] = p0.x;
        out[n * 4 + 1] = p0.y;
        out[n * 4 + 2] = w;
        out[n * 4 + 3] = h;
        n++;
    }
    return n;
}

} // namespace

extern "C" {

/**
 * Загрузить [path] и сверстать под окно [w]×[h]. 0 — ок, иначе код ошибки.
 * [appFontDir] — каталог со своими начертаниями (распакованный `res/font`), может быть NULL.
 */
DEXP_PROBE_API int dexp_reader_open(const char *path, const char *cacheDir,
                                    const char *appFontDir, int w, int h)
{
    if (!path || w <= 0 || h <= 0)
        return DEXP_PROBE_EARG;
    drop_document();
    if (!prepare(cacheDir, appFontDir))
        return DEXP_READER_EFONT;

    long t0 = now_ms();
    g_lastPercent = -1;
    g_view = new LVDocView(8, false);
    g_view->setCallback(&g_progressCallback);   // прогресс открытия (B58)
    g_view->setViewMode(DVM_PAGES, 1);
    g_view->setPageHeaderInfo(PGHDR_NONE);   // колонтитулы рисует оболочка (B3)
    g_view->setDefaultFontFace(lString8(face_name(g_type.face)));
    /*
     * Замечание 18: имена generic-семейств и предпочтение пользователя должны быть в силе
     * ДО первой разметки — `font-family` книги разбирается при загрузке документа
     * (`lvstsheet.cpp`), и поздняя подмена семейств уже не перепишет стили узлов без
     * полного `forceReinitStyles`. `apply_typography` ниже выставит их ещё раз: тот же
     * вызов безопасен и нужен для смены настроек на лету.
     */
    g_view->propApply(lString8(PROP_FONT_FAMILY_FACES),
                      Utf8ToUnicode(face_family_faces(face_name(g_type.face))));
    apply_preferred_face();
    g_view->Resize(w, h);
    long t1 = now_ms();

    if (!g_view->LoadDocument(path)) {
        DEXP_LOG("reader: open FAIL %s", path);
        drop_document();
        return DEXP_PROBE_EOPEN;
    }
    long t2 = now_ms();
    /* Типографика B4 — до первой разметки: иначе книга сверстается умолчаниями и
       переверстается повторно (на 5-МБ FB2 это лишние секунды). */
    apply_typography();
    g_view->checkRender();
    long t3 = now_ms();
    g_pages = g_view->getPageCount();
    if (g_pages <= 0) {
        DEXP_LOG("reader: no pages %s", path);
        drop_document();
        return DEXP_PROBE_EPAGE;
    }
    DEXP_LOG("reader: open ok %dx%d pages=%d face=%s size=%d hyph=%d setup=%ld load=%ld layout=%ld",
             w, h, g_pages, face_name(g_type.face), g_type.size, g_type.hyphens ? 1 : 0,
             t1 - t0, t2 - t1, t3 - t2);
    return DEXP_PROBE_OK;
}

/** Закрыть документ; менеджер шрифтов и кэш остаются. */
DEXP_PROBE_API int dexp_reader_close()
{
    drop_document();
    return DEXP_PROBE_OK;
}

/**
 * Куда слать прогресс открытия (0…100): JNI-переходник из `reader_jni.cpp`.
 * NULL — прогресс никому не нужен. Ставится один раз за сеанс приложения.
 */
DEXP_PROBE_API void dexp_reader_set_progress_hook(ProgressHook hook)
{
    g_hook = hook;
}

DEXP_PROBE_API int dexp_reader_page_count()
{
    return g_view ? g_pages : DEXP_READER_ENOSESSION;
}

/** Номер страницы, на которой стоит движок сейчас (0-based); < 0 — нет документа. */
DEXP_PROBE_API int dexp_reader_cur_page()
{
    if (!g_view)
        return DEXP_READER_ENOSESSION;
    return g_view->getCurPage();
}

/** Отрисовать страницу [page] в [gray] (8 бит, stride = w). 0 — ок. */
DEXP_PROBE_API int dexp_reader_page(int page, int w, int h, unsigned char *gray)
{
    if (!g_view)
        return DEXP_READER_ENOSESSION;
    if (!gray || w <= 0 || h <= 0)
        return DEXP_PROBE_EARG;
    if (page < 0 || page >= g_pages)
        return DEXP_PROBE_EPAGE;
    long t0 = now_ms();
    g_view->goToPage(page);
    LVGrayDrawBuf buf(w, h, 8, gray);
    g_view->Draw(buf, false);
    long ms = now_ms() - t0;
    if (ms > 100)
        DEXP_LOG("reader: page %d draw=%ldms", page, ms);
    return DEXP_PROBE_OK;
}

/**
 * Сменить кегль (дизайн-px макета) и сверстать заново. 0 — ок.
 * [keepPos] — xpointer строки, на которой стояли: кегль меняет число страниц,
 * место чтения держится позицией в тексте, а не номером страницы (READER_SPEC, раздел 4).
 */
DEXP_PROBE_API int dexp_reader_set_font(int size, const char *keepPos)
{
    if (!g_view)
        return DEXP_READER_ENOSESSION;
    if (size <= 0)
        return DEXP_PROBE_EARG;
    long t0 = now_ms();
    g_type.size = size;
    int pages = relayout(keepPos);
    DEXP_LOG("reader: font %d pages=%d %ldms", size, pages, now_ms() - t0);
    return pages > 0 ? DEXP_PROBE_OK : DEXP_PROBE_EPAGE;
}

/**
 * Типографика B4 «Типографика» разом (R3.4): гарнитура, кегль, интервал, поля и переносы.
 * Числа — из `design/v5/B04.json`; разбор того, чем каждая настройка ложится на crengine,
 * — в `apply_typography`.
 *
 * [face] 0 — антиква (Literata), 1 — гротекс (Manrope), 2 — моно, 3 — PT Serif (B70),
 *         100+N — шрифт из папки `Fonts`, зарегистрированный [dexp_reader_register_font];
 * [size] кегль дизайн-px, 0 — не менять;
 * [interline] процент 70…200, 0 — не менять;
 * [margin] поля слева и справа, px окна, `< 0` — не менять;
 * [hyphens] 1 — переносы, 0 — без переносов, `< 0` — не менять;
 * [keepPos] — xpointer места чтения, чтобы не уехать после перевёрстки.
 *
 * Возвращает число страниц после перевёрстки или код `< 0`. Свои значения настройки
 * запоминает [g_type]: следующее открытие книги начнётся сразу с них.
 */
DEXP_PROBE_API int dexp_reader_set_typography(int face, int size, int interline, int margin,
                                              int hyphens, const char *keepPos)
{
    if (!g_view)
        return DEXP_READER_ENOSESSION;
    if (face >= 0 && (face < FACE_COUNT || face - FACE_USER < (int) g_userFonts.size()))
        g_type.face = face;
    if (size > 0)
        g_type.size = size;
    /* Интервал: список движка `cr_interline_spaces` — 70…200 шагом 5; прочее отвергнется. */
    if (interline > 0)
        g_type.interline = interline < 70 ? 70 : (interline > 200 ? 200 : interline);
    if (margin >= 0)
        g_type.margin = margin < 400 ? margin : 400;
    if (hyphens >= 0)
        g_type.hyphens = hyphens != 0;
    long t0 = now_ms();
    int pages = relayout(keepPos);
    DEXP_LOG("reader: type face=%s size=%d inter=%d margin=%d hyph=%d pages=%d %ldms",
             face_name(g_type.face), g_type.size, g_type.interline, g_type.margin,
             g_type.hyphens ? 1 : 0, pages, now_ms() - t0);
    return pages > 0 ? pages : DEXP_PROBE_EPAGE;
}

/** Текущая типографика: в [out] 5 чисел — face, size, interline, margin, hyphens. 0 — ок. */
DEXP_PROBE_API int dexp_reader_typography(int *out)
{
    if (!out)
        return DEXP_PROBE_EARG;
    out[0] = g_type.face;
    out[1] = g_type.size;
    out[2] = g_type.interline;
    out[3] = g_type.margin;
    out[4] = g_type.hyphens ? 1 : 0;
    return DEXP_PROBE_OK;
}

/**
 * Имя семейства из файла шрифта: запись name ID 1 в кодировке Windows Unicode BMP —
 * именно это имя crengine кладёт в `_faceName` при регистрации (`familyName` в lvfntman.cpp).
 * Пишет в [out], если помещается. Возвращает длину имени или 0.
 *
 * Читаем самим FreeType, а не сравнением `getFaceList`: шрифт с уже зарегистрированным
 * семейством (например, копия PT Serif) нового имени в списке не даёт, и разница пустая
 * (проверено на книге 2026-11-15).
 */
static int read_family_name(const char *path, char *out, int cap)
{
    FT_Library lib = NULL;
    FT_Face face = NULL;
    if (FT_Init_FreeType(&lib) != 0)
        return 0;
    int n = 0;
    if (FT_New_Face(lib, path, 0, &face) == 0) {
        FT_SfntName sn;
        for (FT_UInt i = 0; i < FT_Get_Sfnt_Name_Count(face); i++) {
            if (FT_Get_Sfnt_Name(face, i, &sn) != 0)
                continue;
            if (sn.name_id != TT_NAME_ID_FONT_FAMILY && sn.name_id != TT_NAME_ID_PREFERRED_FAMILY)
                continue;
            if (sn.platform_id != TT_PLATFORM_MICROSOFT || sn.encoding_id != TT_MS_ID_UNICODE_CS)
                continue;
            /* UTF-16BE → ASCII/UTF-8: имена семейств латиницей; кириллица не встречалась. */
            int k = 0;
            for (FT_UInt j = 0; j + 1 < sn.string_len && k < cap - 1; j += 2) {
                unsigned hi = sn.string[j], lo = sn.string[j + 1];
                if (hi != 0)
                    break;                       /* не BMP-латиница — это не наше имя */
                out[k++] = (char) lo;
            }
            out[k] = 0;
            n = k;
            break;
        }
        FT_Done_Face(face);
    }
    FT_Done_FreeType(lib);
    return n;
}

/**
 * Подготовить шрифты без открытия книги (B70 «Открыть шрифт…»): настройки читалки
 * открываются и без книги, а менеджер шрифтов создаётся в [prepare]. Идемпотентно.
 * 0 — шрифты есть, `< 0` — нет.
 */
DEXP_PROBE_API int dexp_reader_prepare_fonts(const char *cacheDir, const char *appFontDir)
{
    return prepare(cacheDir, appFontDir) ? DEXP_PROBE_OK : DEXP_READER_EFONT;
}

/**
 * Зарегистрировать шрифт из папки `Fonts` на карте — строка «Открыть шрифт…» B70.
 * [path] — файл .ttf/.otf.
 *
 * Имя семейства читается из файла ([read_family_name]) и подставляется в `setDefaultFontFace`.
 * Своё имя («Мой шрифт») не годится: движок ищет по name-таблице — та же грабля,
 * что с `DroidSansMono.ttf` (BUILD.md).
 *
 * Возвращает код гарнитуры (`FACE_USER + индекс`) или `< 0`: файл не открылся, шрифтов
 * в нём нет или имени не нашлось.
 */
DEXP_PROBE_API int dexp_reader_register_font(const char *path)
{
    if (!fontMan || !path || !path[0])
        return DEXP_PROBE_EARG;

    /* Уже регистрировали этот файл — отдаём прежний код, второй раз не читаем. */
    for (size_t i = 0; i < g_userFonts.size(); i++) {
        if (g_userFonts[i].path == path)
            return FACE_USER + (int) i;
    }

    char family[128];
    int famLen = read_family_name(path, family, sizeof(family));
    if (famLen <= 0) {
        DEXP_LOG("reader: font name FAIL (%s)", path);
        return DEXP_READER_EFONT;
    }
    if (!fontMan->RegisterFont(lString8(path))) {
        DEXP_LOG("reader: RegisterFont FAIL %s [%s]", path, family);
        return DEXP_READER_EFONT;
    }

    /* Одно начертание — уже семейство; остальные (курсив, полужирный) движок подберёт
       сам или синтезирует. */
    UserFont uf;
    uf.family = lString8(family);
    uf.path = path;
    g_userFonts.push_back(uf);
    int code = FACE_USER + (int) g_userFonts.size() - 1;
    DEXP_LOG("reader: font +%s (%s) code=%d", family, path, code);
    return code;
}

/** Забыть шрифты из папки `Fonts` (сброс настроек B43): следующий старт регистрирует заново. */
DEXP_PROBE_API void dexp_reader_forget_user_fonts()
{
    g_userFonts.clear();
}

/**
 * Встать на позицию [pos] (xpointer) — так читалка восстанавливает место чтения.
 * Возвращает номер страницы (0-based) или `< 0`, если позиция не нашлась.
 */
DEXP_PROBE_API int dexp_reader_goto_pos(const char *pos)
{
    if (!g_view)
        return DEXP_READER_ENOSESSION;
    if (!pos || !pos[0])
        return DEXP_PROBE_EARG;
    ldomXPointer ptr = g_view->getDocument()->createXPointer(Utf8ToUnicode(pos));
    if (ptr.isNull())
        return DEXP_READER_EPOS;
    g_view->goToBookmark(ptr);
    g_view->checkRender();
    int page = g_view->getCurPage();
    DEXP_LOG("reader: goto pos -> page %d", page);
    return page;
}

/** Позиция страницы [page] строкой xpointer в [out]. Длина строки или код < 0. */
DEXP_PROBE_API int dexp_reader_pos(int page, char *out, int outLen)
{
    if (!g_view)
        return DEXP_READER_ENOSESSION;
    if (!out || outLen <= 1)
        return DEXP_PROBE_EARG;
    if (page < 0 || page >= g_pages)
        return DEXP_PROBE_EPAGE;
    ldomXPointer ptr = g_view->getPageBookmark(page, true);
    if (ptr.isNull())
        return DEXP_READER_EPOS;
    lString8 s = UnicodeToUtf8(ptr.toString());
    int n = (int)s.length();
    if (n + 1 > outLen) {
        n = outLen - 1;
        /* Не разрывать символ UTF-8 — см. `dexp_reader_page_text`. */
        while (n > 0 && ((unsigned char)s[n] & 0xC0) == 0x80)
            n--;
    }
    memcpy(out, s.c_str(), (size_t)n);
    out[n] = 0;
    return n;
}

/** Страница по xpointer [pos]; -1 — не нашлась. */
DEXP_PROBE_API int dexp_reader_page_of(const char *pos)
{
    if (!g_view)
        return DEXP_READER_ENOSESSION;
    if (!pos || !pos[0])
        return DEXP_PROBE_EARG;
    ldomXPointer ptr = g_view->getDocument()->createXPointer(Utf8ToUnicode(pos));
    if (ptr.isNull())
        return -1;
    int page = g_view->getBookmarkPage(ptr);
    if (page < 0 || page >= g_pages)
        return -1;
    return page;
}

/**
 * Первые байты текста текущей (последней отрисованной) страницы в [out] — для проверки,
 * что место чтения не уехало при смене кегля. Длина или код < 0.
 */
DEXP_PROBE_API int dexp_reader_page_text(char *out, int outLen)
{
    if (!g_view)
        return DEXP_READER_ENOSESSION;
    if (!out || outLen <= 1)
        return DEXP_PROBE_EARG;
    lString8 s = UnicodeToUtf8(g_view->getPageText(false, -1));
    int n = (int)s.length();
    if (n + 1 > outLen) {
        n = outLen - 1;
        /* Не разрывать символ UTF-8: обрыв посреди символа даёт невалидную строку,
           а `NewStringUTF` на ней абортит процесс (падеж на EPUB 2026-11-13). */
        while (n > 0 && ((unsigned char)s[n] & 0xC0) == 0x80)
            n--;
    }
    memcpy(out, s.c_str(), (size_t)n);
    out[n] = 0;
    return n;
}

/**
 * Число пунктов оглавления; `< 0` — документ не открыт. Первый вызов после открытия
 * или смены кегля строит копию (`build_toc`), дальше отдаётся готовая.
 */
DEXP_PROBE_API int dexp_reader_toc_count()
{
    if (!g_view)
        return DEXP_READER_ENOSESSION;
    if (!g_tocValid)
        build_toc();
    return (int)g_toc.size();
}

/** Уровень пункта [index] (1 — верхний); `< 0` — нет такого пункта. */
DEXP_PROBE_API int dexp_reader_toc_level(int index)
{
    if (!g_view)
        return DEXP_READER_ENOSESSION;
    if (!g_tocValid)
        build_toc();
    if (index < 0 || index >= (int)g_toc.size())
        return DEXP_PROBE_EARG;
    return g_toc[(size_t)index].level;
}

/** Номер страницы пункта [index] (0-based); `-1` — движок её не нашёл. */
DEXP_PROBE_API int dexp_reader_toc_page(int index)
{
    if (!g_view)
        return DEXP_READER_ENOSESSION;
    if (!g_tocValid)
        build_toc();
    if (index < 0 || index >= (int)g_toc.size())
        return DEXP_PROBE_EARG;
    return g_toc[(size_t)index].page;
}

/**
 * Строка пункта [index] в [out]: `which` 0 — заголовок, 1 — позиция (xpointer).
 * Длина строки или код < 0.
 */
DEXP_PROBE_API int dexp_reader_toc_string(int index, int which, char *out, int outLen)
{
    if (!g_view)
        return DEXP_READER_ENOSESSION;
    if (!out || outLen <= 1)
        return DEXP_PROBE_EARG;
    if (!g_tocValid)
        build_toc();
    if (index < 0 || index >= (int)g_toc.size())
        return DEXP_PROBE_EARG;
    const lString8 &s = which == 0 ? g_toc[(size_t)index].title : g_toc[(size_t)index].pos;
    int n = (int)s.length();
    if (n + 1 > outLen) {
        n = outLen - 1;
        /* Не разрывать символ UTF-8 — см. `dexp_reader_page_text`. */
        while (n > 0 && ((unsigned char)s[n] & 0xC0) == 0x80)
            n--;
    }
    memcpy(out, s.c_str(), (size_t)n);
    out[n] = 0;
    return n;
}

/**
 * Лежит ли позиция [pos] на странице, на которой сейчас стоит движок. 1 — да,
 * 0 — нет, `< 0` — нет документа или позиция не нашлась.
 *
 * Зачем: критерий приёмки — «та же фраза на странице», а не «страница начинается
 * с фразы». Страница почти всегда начинается раньше середины абзаца, поэтому
 * сравнение первого символа даёт ложное «место уехало» (разбор — DEVLOG, R1.2).
 */
DEXP_PROBE_API int dexp_reader_pos_on_page(const char *pos)
{
    if (!g_view)
        return DEXP_READER_ENOSESSION;
    if (!pos || !pos[0])
        return DEXP_PROBE_EARG;
    ldomXPointer ptr = g_view->getDocument()->createXPointer(Utf8ToUnicode(pos));
    if (ptr.isNull())
        return DEXP_READER_EPOS;
    int page = g_view->getCurPage();
    if (page < 0 || page >= g_pages)
        return DEXP_PROBE_EPAGE;
    int y = ptr.toPoint().y;
    int start = g_view->getPageStartY(page);
    int h = g_view->getPageHeight(page);
    DEXP_LOG("reader: posOnPage page %d y=%d pageY=%d..%d", page, y, start, start + h);
    return (y >= start && y < start + h) ? 1 : 0;
}

/* --------------------------------------------- выделение, цитаты и заметки (B50–B52) */

/**
 * Обрезать знаки препинания по краям слова: `getWordRange` режет только по пробелам,
 * поэтому «стриженым,» приходит с запятой, а «(Стива» — со скобкой. В B50 выделение
 * и плашки должны стоять на самом слове; подчёркивание ищет те же границы.
 *
 * Коды — числами: кириллические литералы в `switch` не влезают в `char32_t` на ARM
 * (`character too large for enclosing character literal type`).
 */
bool is_word_punct(lChar32 ch)
{
    switch (ch) {
    case 0x21: case 0x22: case 0x27: case 0x28: case 0x29:      /* ! " ' ( ) */
    case 0x2A: case 0x2C: case 0x2D: case 0x2E: case 0x2F:      /* * , - . / */
    case 0x3A: case 0x3B: case 0x3F:                            /* : ; ? */
    case 0x5B: case 0x5D: case 0x7B: case 0x7D:                 /* [ ] { } */
    case 0xAB: case 0xBB:                                       /* « » */
    case 0x2013: case 0x2014: case 0x2018: case 0x2019:         /* – — ' ' */
    case 0x201C: case 0x201D: case 0x201E: case 0x2026:         /* " " „ … */
        return true;
    default:
        return false;
    }
}

/**
 * Слово под точкой (B50, долгое нажатие): координаты [x],[y] — окно (тот же буфер, что
 * у [dexp_reader_page]), в дизайн-px. Выделение ставится на это слово целиком; текст
 * слова — в [out]. Длина слова или код `< 0`.
 *
 * Почему `getNodeByPoint(..., forTextSelection=true)`: без него точка в поле или в
 * пробеле между словами не даёт узла, и касание края абзаца не срабатывало бы.
 * Затем `ldomXRange::getWordRange` расширяет позицию до границ слова по пробелам.
 */
DEXP_PROBE_API int dexp_reader_word_at(int x, int y, char *out, int outLen)
{
    if (!g_view)
        return DEXP_READER_ENOSESSION;
    if (!out || outLen <= 1)
        return DEXP_PROBE_EARG;
    ldomXPointer ptr = g_view->getNodeByPoint(lvPoint(x, y), false, true);
    if (ptr.isNull())
        return DEXP_READER_EPOS;
    /* Точка попала между узлами (курсив, сноска) — берём следующий видимый текст. */
    if (!ptr.getNode() || !ptr.getNode()->isText()) {
        ldomXPointerEx ex(ptr);
        if (!ex.nextVisibleText())
            return DEXP_READER_EPOS;
        ptr = ex;
    }
    ldomXRange range;
    if (!ldomXRange::getWordRange(range, ptr) || range.isNull())
        return DEXP_READER_EPOS;
    /* Знаки препинания по краям — вон: выделение и плашки стоят на самом слове. */
    ldomXPointerEx a = range.getStart();
    ldomXPointerEx b = range.getEnd();
    ldomNode *node = a.getNode();
    if (node && node->isText()) {
        while (b.getNode() == node && a.getOffset() < b.getOffset()
               && is_word_punct(a.getChar()))
            a.setOffset(a.getOffset() + 1);
        while (b.getNode() == node && b.getOffset() > a.getOffset()) {
            b.setOffset(b.getOffset() - 1);
            if (!is_word_punct(b.getChar())) {
                b.setOffset(b.getOffset() + 1);   /* вернуть последнюю букву в слово */
                break;
            }
        }
        range = ldomXRange(a, b);
    }
    if (range.isNull())
        return DEXP_READER_EPOS;
    sel_set(range);
    lString8 word = UnicodeToUtf8(range.getRangeText(' '));
    DEXP_LOG("reader: wordAt %d,%d -> %s", x, y, word.c_str());
    return copy_utf8(word, out, outLen);
}

/** Текст выделения в [out]. Длина или код `< 0` (нет выделения — `EPOS`). */
DEXP_PROBE_API int dexp_reader_selection_text(char *out, int outLen)
{
    if (!g_view)
        return DEXP_READER_ENOSESSION;
    if (!g_sel || g_sel->isNull())
        return DEXP_READER_EPOS;
    return copy_utf8(UnicodeToUtf8(g_sel->getRangeText(' ')), out, outLen);
}

/**
 * Расширить выделение на слово: [dir] > 0 — вперёд (маркер конца B50), `< 0` — назад
 * (маркер начала). 1 — расширилось, 0 — край текста, `< 0` — нет выделения.
 *
 * Двигаемся по видимым словам движка (`nextVisibleWordEnd`/`prevVisibleWordStart`):
 * резать текст по пробелам руками нельзя — в книге есть курсив и сноски, и слово
 * бывает разорвано на несколько узлов (маркер B50 останавливался бы на «полслове»).
 */
DEXP_PROBE_API int dexp_reader_selection_extend(int dir)
{
    if (!g_view)
        return DEXP_READER_ENOSESSION;
    if (!g_sel || g_sel->isNull())
        return DEXP_READER_EPOS;
    if (dir > 0) {
        ldomXPointerEx end = g_sel->getEnd();
        if (!end.nextVisibleWordEnd())
            return 0;
        g_sel->setEnd(end);
    } else if (dir < 0) {
        ldomXPointerEx start = g_sel->getStart();
        if (!start.prevVisibleWordStart())
            return 0;
        g_sel->setStart(start);
    }
    return 1;
}

/** Снять выделение: плашки гаснут. B50 зовёт это перед «Меню» и листанием. */
DEXP_PROBE_API int dexp_reader_selection_clear()
{
    sel_clear();
    return DEXP_PROBE_OK;
}

/**
 * Позиция выделения в [out]: `which` 0 — начало, 1 — конец (xpointer). Так цитата и
 * заметка ложатся в таблицу `bookmarks` (поле `pos` = xpointer, R4.1) и остаются на
 * своём тексте после смены кегля. Длина строки или код `< 0`.
 */
DEXP_PROBE_API int dexp_reader_selection_pos(int which, char *out, int outLen)
{
    if (!g_view)
        return DEXP_READER_ENOSESSION;
    if (!g_sel || g_sel->isNull())
        return DEXP_READER_EPOS;
    ldomXPointer p = which == 0 ? (ldomXPointer) g_sel->getStart()
                                : (ldomXPointer) g_sel->getEnd();
    if (p.isNull())
        return DEXP_READER_EPOS;
    return copy_utf8(UnicodeToUtf8(p.toString()), out, outLen);
}

/**
 * Прямоугольники текущего выделения в окне — для плашек G12 на странице (B50).
 * Формат — [range_rects_to_window]: плоский массив по 4 числа на отрезок.
 */
DEXP_PROBE_API int dexp_reader_selection_rects(int *out, int outLen)
{
    if (!g_view)
        return DEXP_READER_ENOSESSION;
    if (!out || outLen < 4)
        return DEXP_PROBE_EARG;
    if (!g_sel || g_sel->isNull())
        return DEXP_READER_EPOS;
    return range_rects_to_window(*g_sel, out, outLen);
}

/**
 * Прямоугольники сохранённого диапазона [startPos]…[endPos] (xpointer) в окне — так B52
 * рисует уже записанные цитаты (подчёркивание G3) и заметки (заливка G13), не выделяя их
 * заново. Формат и возврат — как у [dexp_reader_selection_rects].
 */
DEXP_PROBE_API int dexp_reader_range_rects(const char *startPos, const char *endPos,
                                           int *out, int outLen)
{
    if (!g_view)
        return DEXP_READER_ENOSESSION;
    if (!out || outLen < 4 || !startPos || !startPos[0] || !endPos || !endPos[0])
        return DEXP_PROBE_EARG;
    ldomXPointer a = g_view->getDocument()->createXPointer(Utf8ToUnicode(startPos));
    ldomXPointer b = g_view->getDocument()->createXPointer(Utf8ToUnicode(endPos));
    if (a.isNull() || b.isNull())
        return DEXP_READER_EPOS;
    ldomXRange range(a, b);
    if (range.isNull())
        return DEXP_READER_EPOS;
    return range_rects_to_window(range, out, outLen);
}

/* ------------------------------------------------------------- поиск по книге (B28) */

/**
 * Найти [query] по всей книге и запомнить совпадения (B28 «Вся книга»).
 * Возвращает число найденных (не больше [SEARCH_MAX_HITS]) или код `< 0`.
 *
 * [look] — контекст фрагмента в символах, `<= 0` — умолчание 40.
 *
 * Тонкости движка, проверенные по коду:
 *  - `minY = maxY = 0` — весь документ: `ldomDocument::findText` сам ищет валидный y,
 *    если границы попали в пустое место (поля, пустой абзац);
 *  - `maxHeight = 0` выключает ограничение по высоте блока: cr3qt зовёт поиск с высотой
 *    страницы и нашёл бы только текущую;
 *  - флаги — сквозняк по текстовым узлам (курсив и `<b>` делят слово на два узла) и
 *    сжатие кратных пробелов; без них «в связи с» в форматированном тексте не нашлось бы.
 */
DEXP_PROBE_API int dexp_reader_search(const char *query, int look)
{
    if (!g_view)
        return DEXP_READER_ENOSESSION;
    if (!query || !query[0])
        return DEXP_PROBE_EARG;
    if (look <= 0)
        look = 40;
    long t0 = now_ms();
    drop_search();
    ldomXRangeList ranges;
    lUInt32 flags = LDOM_FIND_TEXT_MATCH_ACROSS_TEXT_NODES
                    | LDOM_FIND_TEXT_COLLAPSE_CONSECUTIVE_SPACES;
    lString32 pattern = Utf8ToUnicode(query);
    g_view->getDocument()->findText(pattern, true, false, 0, 0, ranges,
                                    SEARCH_MAX_HITS, 0, -1, false, flags);
    g_search.reserve((size_t) ranges.length());
    for (int i = 0; i < ranges.length(); i++) {
        ldomXRange *r = ranges[i];
        if (!r || r->isNull())
            continue;
        int page = g_view->getBookmarkPage(r->getStart());
        if (page < 0 || page >= g_pages)
            continue;
        SearchHit h;
        h.page = page;
        h.pos = UnicodeToUtf8(r->getStart().toString());
        h.text = excerpt_of(*r, look);
        g_search.push_back(h);
    }
    DEXP_LOG("reader: search '%s' %d hits (ranges %d) %ldms", query,
             (int) g_search.size(), ranges.length(), now_ms() - t0);
    return (int) g_search.size();
}

/** Сколько совпадений запомнил последний [dexp_reader_search]. */
DEXP_PROBE_API int dexp_reader_search_count()
{
    return (int) g_search.size();
}

/** Номер страницы совпадения [index] (0-based); `-1` — такого нет. */
DEXP_PROBE_API int dexp_reader_search_page(int index)
{
    if (index < 0 || index >= (int) g_search.size())
        return -1;
    return g_search[(size_t) index].page;
}

/**
 * Строка совпадения [index] в [out]: `which` 0 — фрагмент для списка B28,
 * 1 — позиция (xpointer) для перехода. Длина или код `< 0`.
 */
DEXP_PROBE_API int dexp_reader_search_string(int index, int which, char *out, int outLen)
{
    if (index < 0 || index >= (int) g_search.size())
        return DEXP_PROBE_EARG;
    const lString8 &s = which == 0 ? g_search[(size_t) index].text
                                   : g_search[(size_t) index].pos;
    return copy_utf8(s, out, outLen);
}

} // extern "C"
