/*
 * Сеанс DjVuLibre для читалки (R2, `docs/READER_SPEC.md` раздел 4).
 *
 * Один открытый документ на процесс, все вызовы — из одного фонового потока
 * (Kotlin — `Engine.worker`). Контекст `ddjvu` живёт весь процесс, документ — до закрытия.
 *
 * Отличия от пробы R0 (`djvu_probe.cpp`):
 *  - документ держится между вызовами, кэш декодированных страниц общий (8 МБ);
 *  - страница рисуется в буфер вызывающего, формат вывода — один на процесс;
 *  - позиция — строка `djvu:<страница>` (READER_SPEC: «страница + смещение»; смещение
 *    появится с масштабом B31, разбор уже понимает `djvu:<страница>:<смещение>`);
 *  - ошибка декодирования возвращается кодом, процесс не падает: битый файл — EOPEN (B45),
 *    битая страница — EPAGE (B59).
 *
 * Коды — общие `DEXP_PROBE_*` из probe.h и те же `DEXP_READER_*`, что у crengine.
 */
#include <android/log.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include <vector>

#include "libdjvu/ddjvuapi.h"
#include "libdjvu/miniexp.h"
#include "probe.h"

#define DEXP_LOG(...) __android_log_print(ANDROID_LOG_INFO, "DexpM8", __VA_ARGS__)

enum {
    DEXP_READER_ENOSESSION = -10,
    DEXP_READER_EPOS = -11
};

namespace {

/* Кэш декодированных страниц: на книге 1 ГБ ОЗУ, как в пробе R0. */
const unsigned long CACHE_BYTES = 8ul << 20;

/* Защита от зацикленного или раздутого оглавления в битом файле. */
const int TOC_MAX_DEPTH = 16;
const size_t TOC_MAX_ITEMS = 4096;

ddjvu_context_t *g_ctx = NULL;
ddjvu_document_t *g_doc = NULL;
ddjvu_format_t *g_fmt = NULL;
int g_pages = 0;
int g_cur = 0;

struct TocEntry {
    int level;
    int page;
    std::vector<char> title;   // UTF-8 с нулём
};

std::vector<TocEntry> g_toc;
bool g_tocValid = false;

long now_ms()
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long)(ts.tv_sec * 1000L + ts.tv_nsec / 1000000L);
}

/** Разобрать очередь сообщений; ошибки движка — в лог (по ним видно, что сломано в файле). */
void drain()
{
    const ddjvu_message_t *m;
    while ((m = ddjvu_message_peek(g_ctx)) != NULL) {
        if (m->m_any.tag == DDJVU_ERROR)
            DEXP_LOG("djvu: error %s", m->m_error.message ? m->m_error.message : "?");
        ddjvu_message_pop(g_ctx);
    }
}

void wait_messages()
{
    ddjvu_message_wait(g_ctx);
    drain();
}

/** Контекст и формат вывода (8-битный серый, строки сверху вниз) — раз на процесс. */
bool prepare()
{
    if (!g_ctx) {
        g_ctx = ddjvu_context_create("dexp");
        if (!g_ctx)
            return false;
        ddjvu_cache_set_size(g_ctx, CACHE_BYTES);
    }
    if (!g_fmt) {
        g_fmt = ddjvu_format_create(DDJVU_FORMAT_GREY8, 0, NULL);
        if (!g_fmt)
            return false;
        ddjvu_format_set_row_order(g_fmt, 1);
        ddjvu_format_set_y_direction(g_fmt, 1);
    }
    return true;
}

void drop_toc()
{
    g_toc.clear();
    g_tocValid = false;
}

void drop_document()
{
    if (g_doc) {
        ddjvu_document_release(g_doc);
        g_doc = NULL;
        drain();
    }
    g_pages = 0;
    g_cur = 0;
    drop_toc();
}

/** Строку UTF-8 в [out] без разрыва символа (иначе JNI получит невалидный текст); длина. */
int copy_utf8(const char *s, size_t n, char *out, int outLen)
{
    if ((int)n + 1 > outLen) {
        n = (size_t)(outLen - 1);
        while (n > 0 && ((unsigned char)s[n] & 0xC0) == 0x80)
            n--;
    }
    memcpy(out, s, n);
    out[n] = 0;
    return (int)n;
}

/** Ссылка пункта оглавления (`#12`, `#имя_файла`) → страница 0-based; -1 — не нашлась. */
int page_of_url(const char *url)
{
    if (!url || url[0] != '#')
        return -1;
    int p = ddjvu_document_search_pageno(g_doc, url + 1);
    if (p >= 0 && p < g_pages)
        return p;
    char *end = NULL;
    long n = strtol(url + 1, &end, 10);
    if (end != url + 1 && !*end && n >= 1 && n <= g_pages)
        return (int)n - 1;
    return -1;
}

/**
 * Пункты оглавления — `(title url child…)`, весь список — `(bookmarks …)`.
 * Складываем плоско, в порядке документа; верхний уровень — 1, как у crengine.
 */
void collect_toc(miniexp_t list, int level)
{
    if (level > TOC_MAX_DEPTH)
        return;
    for (miniexp_t p = list; miniexp_consp(p); p = miniexp_cdr(p)) {
        if (g_toc.size() >= TOC_MAX_ITEMS)
            return;
        miniexp_t item = miniexp_car(p);
        if (!miniexp_consp(item))
            continue;
        miniexp_t t = miniexp_car(item);
        miniexp_t u = miniexp_car(miniexp_cdr(item));
        if (!miniexp_stringp(t))
            continue;
        const char *title = miniexp_to_str(t);
        TocEntry e;
        e.level = level;
        e.page = miniexp_stringp(u) ? page_of_url(miniexp_to_str(u)) : -1;
        e.title.assign(title, title + strlen(title));
        e.title.push_back(0);
        g_toc.push_back(e);
        collect_toc(miniexp_cdr(miniexp_cdr(item)), level + 1);
    }
}

void build_toc()
{
    drop_toc();
    g_tocValid = true;     // и пустое оглавление строится один раз
    if (!g_doc)
        return;
    miniexp_t r;
    while ((r = ddjvu_document_get_outline(g_doc)) == miniexp_dummy)
        wait_messages();
    if (miniexp_consp(r) && miniexp_symbolp(miniexp_car(r))
        && strcmp(miniexp_to_name(miniexp_car(r)), "bookmarks") == 0)
        collect_toc(miniexp_cdr(r), 1);
    ddjvu_miniexp_release(g_doc, r);
    DEXP_LOG("djvu: toc %d items", (int)g_toc.size());
}

/** Склеить строки текстового слоя через пробел, пока есть место в [out]. */
void collect_text(miniexp_t e, char *out, int outLen, int *len)
{
    for (miniexp_t p = e; miniexp_consp(p); p = miniexp_cdr(p)) {
        if (*len >= outLen - 1)
            return;
        miniexp_t x = miniexp_car(p);
        if (miniexp_stringp(x)) {
            const char *s = miniexp_to_str(x);
            if (*len > 0 && *len < outLen - 1)
                out[(*len)++] = ' ';
            *len += copy_utf8(s, strlen(s), out + *len, outLen - *len);
        } else if (miniexp_consp(x)) {
            collect_text(x, out, outLen, len);
        }
    }
}

} // namespace

extern "C" {

/** Открыть [path]: 0 — ок, EOPEN — файл не DjVu или повреждён (B45). */
DEXP_PROBE_API int dexp_djvu_open(const char *path)
{
    if (!path)
        return DEXP_PROBE_EARG;
    drop_document();
    if (!prepare())
        return DEXP_PROBE_ERENDER;
    long t0 = now_ms();
    g_doc = ddjvu_document_create_by_filename_utf8(g_ctx, path, 1);
    if (!g_doc) {
        DEXP_LOG("djvu: open FAIL create %s", path);
        return DEXP_PROBE_EOPEN;
    }
    ddjvu_status_t s;
    while ((s = ddjvu_document_decoding_status(g_doc)) < DDJVU_JOB_OK)
        wait_messages();
    if (s != DDJVU_JOB_OK) {
        DEXP_LOG("djvu: open FAIL status %d %s", (int)s, path);
        drop_document();
        return DEXP_PROBE_EOPEN;
    }
    g_pages = ddjvu_document_get_pagenum(g_doc);
    if (g_pages <= 0) {
        DEXP_LOG("djvu: open FAIL pages=%d %s", g_pages, path);
        drop_document();
        return DEXP_PROBE_EOPEN;
    }
    g_cur = 0;
    DEXP_LOG("djvu: open pages=%d %ldms %s", g_pages, now_ms() - t0, path);
    return DEXP_PROBE_OK;
}

DEXP_PROBE_API int dexp_djvu_close()
{
    drop_document();
    return DEXP_PROBE_OK;
}

DEXP_PROBE_API int dexp_djvu_page_count()
{
    return g_doc ? g_pages : DEXP_READER_ENOSESSION;
}

DEXP_PROBE_API int dexp_djvu_cur_page()
{
    return g_doc ? g_cur : DEXP_READER_ENOSESSION;
}


/**
 * Страница [page] в [gray] (w×h, stride = w, 8 бит): вписана с сохранением пропорций,
 * по центру, фон 255. EPAGE — страница в файле повреждена (B59), документ остаётся открыт.
 */
DEXP_PROBE_API int dexp_djvu_page(int page, int w, int h, unsigned char *gray)
{
    if (!g_doc)
        return DEXP_READER_ENOSESSION;
    if (!gray || w <= 0 || h <= 0)
        return DEXP_PROBE_EARG;
    if (page < 0 || page >= g_pages)
        return DEXP_PROBE_EPAGE;
    memset(gray, 255, (size_t)w * (size_t)h);

    long t0 = now_ms();
    ddjvu_page_t *pg = ddjvu_page_create_by_pageno(g_doc, page);
    if (!pg)
        return DEXP_PROBE_EPAGE;
    ddjvu_status_t s;
    while ((s = ddjvu_page_decoding_status(pg)) < DDJVU_JOB_OK)
        wait_messages();
    int rc = DEXP_PROBE_OK;
    if (s != DDJVU_JOB_OK) {
        rc = DEXP_PROBE_EPAGE;
    } else {
        int pw = ddjvu_page_get_width(pg);
        int ph = ddjvu_page_get_height(pg);
        if (pw <= 0 || ph <= 0) {
            rc = DEXP_PROBE_EPAGE;
        } else {
            int rw = w, rh = (int)((long long)ph * w / pw);
            if (rh > h) {
                rh = h;
                rw = (int)((long long)pw * h / ph);
            }
            if (rw < 1) rw = 1;
            if (rh < 1) rh = 1;
            ddjvu_rect_t prect = {0, 0, (unsigned)rw, (unsigned)rh};
            ddjvu_rect_t rrect = prect;
            unsigned char *dst = gray + (size_t)((h - rh) / 2) * (size_t)w + (size_t)((w - rw) / 2);
            if (!ddjvu_page_render(pg, DDJVU_RENDER_COLOR, &prect, &rrect, g_fmt,
                                   (unsigned long)w, (char *)dst))
                rc = DEXP_PROBE_ERENDER;
        }
    }
    ddjvu_page_release(pg);
    drain();
    if (rc == DEXP_PROBE_OK)
        g_cur = page;
    DEXP_LOG("djvu: page %d rc=%d %ldms", page, rc, now_ms() - t0);
    return rc;
}

/** Позиция страницы строкой `djvu:<страница>`. Длина или код < 0. */
DEXP_PROBE_API int dexp_djvu_pos(int page, char *out, int outLen)
{
    if (!g_doc)
        return DEXP_READER_ENOSESSION;
    if (!out || outLen < 16)
        return DEXP_PROBE_EARG;
    if (page < 0 || page >= g_pages)
        return DEXP_PROBE_EPAGE;
    return snprintf(out, (size_t)outLen, "djvu:%d", page);
}

/**
 * Страница по позиции `djvu:<страница>[:<смещение>]`; -1 — позиция чужая
 * (xpointer от crengine, файл заменён) или вне книги.
 */
DEXP_PROBE_API int dexp_djvu_page_of(const char *pos)
{
    if (!g_doc)
        return DEXP_READER_ENOSESSION;
    if (!pos || strncmp(pos, "djvu:", 5) != 0)
        return -1;
    char *end = NULL;
    long p = strtol(pos + 5, &end, 10);
    if (end == pos + 5 || (*end && *end != ':'))
        return -1;
    if (p < 0 || p >= g_pages)
        return -1;
    return (int)p;
}

/** Встать на позицию (место чтения при открытии); страница или `< 0`. */
DEXP_PROBE_API int dexp_djvu_goto_pos(const char *pos)
{
    int p = dexp_djvu_page_of(pos);
    if (p < 0)
        return p == DEXP_READER_ENOSESSION ? p : DEXP_READER_EPOS;
    g_cur = p;
    DEXP_LOG("djvu: goto pos -> page %d", p);
    return p;
}

/** 1 — позиция на текущей странице, 0 — нет, `< 0` — нет документа или чужая позиция. */
DEXP_PROBE_API int dexp_djvu_pos_on_page(const char *pos)
{
    int p = dexp_djvu_page_of(pos);
    if (p < 0)
        return p == DEXP_READER_ENOSESSION ? p : DEXP_READER_EPOS;
    return p == g_cur ? 1 : 0;
}

/** Начало текстового слоя текущей страницы (трасса). 0 — слоя нет: обычный скан, не ошибка. */
DEXP_PROBE_API int dexp_djvu_page_text(char *out, int outLen)
{
    if (!g_doc)
        return DEXP_READER_ENOSESSION;
    if (!out || outLen <= 1)
        return DEXP_PROBE_EARG;
    miniexp_t r;
    while ((r = ddjvu_document_get_pagetext(g_doc, g_cur, "line")) == miniexp_dummy)
        wait_messages();
    int len = 0;
    if (miniexp_consp(r))
        collect_text(r, out, outLen, &len);
    ddjvu_miniexp_release(g_doc, r);
    out[len] = 0;
    return len;
}

DEXP_PROBE_API int dexp_djvu_toc_count()
{
    if (!g_doc)
        return DEXP_READER_ENOSESSION;
    if (!g_tocValid)
        build_toc();
    return (int)g_toc.size();
}

DEXP_PROBE_API int dexp_djvu_toc_level(int index)
{
    if (!g_doc)
        return DEXP_READER_ENOSESSION;
    if (!g_tocValid)
        build_toc();
    if (index < 0 || index >= (int)g_toc.size())
        return DEXP_PROBE_EARG;
    return g_toc[(size_t)index].level;
}

DEXP_PROBE_API int dexp_djvu_toc_page(int index)
{
    if (!g_doc)
        return DEXP_READER_ENOSESSION;
    if (!g_tocValid)
        build_toc();
    if (index < 0 || index >= (int)g_toc.size())
        return DEXP_PROBE_EARG;
    return g_toc[(size_t)index].page;
}

/** `which` 0 — заголовок, 1 — позиция (`djvu:<страница>`; пусто — страница не нашлась). */
DEXP_PROBE_API int dexp_djvu_toc_string(int index, int which, char *out, int outLen)
{
    if (!g_doc)
        return DEXP_READER_ENOSESSION;
    if (!out || outLen <= 1)
        return DEXP_PROBE_EARG;
    if (!g_tocValid)
        build_toc();
    if (index < 0 || index >= (int)g_toc.size())
        return DEXP_PROBE_EARG;
    const TocEntry &e = g_toc[(size_t)index];
    if (which == 0)
        return copy_utf8(e.title.data(), e.title.size() - 1, out, outLen);
    if (e.page < 0) {
        out[0] = 0;
        return 0;
    }
    return dexp_djvu_pos(e.page, out, outLen);
}

} // extern "C"

