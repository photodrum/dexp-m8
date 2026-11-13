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

#include "crengine.h"
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
 * Подготовить движок: менеджер шрифтов (раз на процесс) и кэш разметки.
 * Каталог кэша передаёт Kotlin: путь `/data/data/<пакет>` знает только он.
 */
bool prepare(const char *cacheDir)
{
    if (!fontMan) {
        InitFontManager(lString8::empty_str);
        g_fontsOk = dexp_register_system_fonts() > 0;
        DEXP_LOG("reader: fontMan %s", g_fontsOk ? "ok" : "empty");
    }
    if (!g_fontsOk)
        return false;
    if (!g_cacheOk && cacheDir && cacheDir[0]) {
        g_cacheOk = ldomDocCache::init(Utf8ToUnicode(cacheDir), CACHE_MAX_BYTES);
        DEXP_LOG("reader: cache %s (%s)", g_cacheOk ? "ok" : "off", cacheDir);
    }
    return true;
}

/**
 * Закрыть документ. Вид удаляется целиком: деструктор `LVDocView` освобождает DOM и разметку.
 * Пустой `LVStreamRef` в `LoadDocument` не годится — там `stream->GetSize()` без проверки.
 */
void drop_document()
{
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

} // namespace

extern "C" {

/** Загрузить [path] и сверстать под окно [w]×[h]. 0 — ок, иначе код ошибки. */
DEXP_PROBE_API int dexp_reader_open(const char *path, const char *cacheDir, int w, int h)
{
    if (!path || w <= 0 || h <= 0)
        return DEXP_PROBE_EARG;
    drop_document();
    if (!prepare(cacheDir))
        return DEXP_READER_EFONT;

    long t0 = now_ms();
    g_lastPercent = -1;
    g_view = new LVDocView(8, false);
    g_view->setCallback(&g_progressCallback);   // прогресс открытия (B58)
    g_view->setViewMode(DVM_PAGES, 1);
    g_view->setPageHeaderInfo(PGHDR_NONE);   // колонтитулы рисует оболочка (B3)
    g_view->setDefaultFontFace(lString8("Noto Serif"));
    g_view->Resize(w, h);
    long t1 = now_ms();

    if (!g_view->LoadDocument(path)) {
        DEXP_LOG("reader: open FAIL %s", path);
        drop_document();
        return DEXP_PROBE_EOPEN;
    }
    long t2 = now_ms();
    g_view->checkRender();
    long t3 = now_ms();
    g_pages = g_view->getPageCount();
    if (g_pages <= 0) {
        DEXP_LOG("reader: no pages %s", path);
        drop_document();
        return DEXP_PROBE_EPAGE;
    }
    DEXP_LOG("reader: open ok %dx%d pages=%d setup=%ld load=%ld layout=%ld",
             w, h, g_pages, t1 - t0, t2 - t1, t3 - t2);
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
    g_view->setFontSize(size);
    g_view->checkRender();
    g_pages = g_view->getPageCount();
    drop_toc();   // номера страниц пунктов посчитает build_toc на новой разметке
    /* Позицию ищем строкой ПОСЛЕ перевёрстки: xpointer, созданный на старом DOM,
       после новой разметки указывает в другое место (проверено на книге 2026-11-13 —
       место чтения уезжало с 50 % на 61 %). Условие приёмки — «та же фраза на
       странице», а не её первый символ: страница начинается раньше позиции
       (R1.2, замер на EPUB 2026-11-13: фраза y=618338 внутри страницы 618272..619856). */
    if (keepPos && keepPos[0]) {
        ldomXPointer pos = g_view->getDocument()->createXPointer(Utf8ToUnicode(keepPos));
        if (!pos.isNull()) {
            g_view->goToBookmark(pos);
            g_view->checkRender();
            DEXP_LOG("reader: font %d keepPos -> page %d", size, g_view->getCurPage());
        }
    }
    DEXP_LOG("reader: font %d pages=%d %ldms", size, g_pages, now_ms() - t0);
    return g_pages > 0 ? DEXP_PROBE_OK : DEXP_PROBE_EPAGE;
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

} // extern "C"
