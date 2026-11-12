// Пробный вход DjVuLibre: первая страница в серый буфер. См. probe.h.
#include <android/log.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "libdjvu/ddjvuapi.h"
#include "probe.h"

// Ждать, пока документ или страница не перестанут декодироваться. Сообщения просто выбрасываем.
static void drain(ddjvu_context_t *ctx)
    {
    const ddjvu_message_t *m;
    while ((m = ddjvu_message_peek(ctx)) != NULL)
        ddjvu_message_pop(ctx);
}

static ddjvu_status_t wait_doc(ddjvu_context_t *ctx, ddjvu_document_t *doc)
    {
    ddjvu_status_t s;
    while ((s = ddjvu_document_decoding_status(doc)) < DDJVU_JOB_OK) {
        ddjvu_message_wait(ctx);
        drain(ctx);
    }
    return s;
}

static ddjvu_status_t wait_page(ddjvu_context_t *ctx, ddjvu_page_t *page)
    {
    ddjvu_status_t s;
    while ((s = ddjvu_page_decoding_status(page)) < DDJVU_JOB_OK) {
        ddjvu_message_wait(ctx);
        drain(ctx);
    }
    return s;
}

static long now_ms()
    {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long)(ts.tv_sec * 1000L + ts.tv_nsec / 1000000L);
}

// Страница pageno в буфер w×h: вписать с сохранением пропорций, по центру.
static int render_page(ddjvu_context_t *ctx, ddjvu_document_t *doc, int pageno,
                       int w, int h, unsigned char *gray)
{
    ddjvu_page_t *page = ddjvu_page_create_by_pageno(doc, pageno);
    if (!page)
        return DEXP_PROBE_EPAGE;
    int rc = DEXP_PROBE_OK;
    ddjvu_format_t *fmt = NULL;
    if (wait_page(ctx, page) != DDJVU_JOB_OK) {
        rc = DEXP_PROBE_EPAGE;
    } else {
        int pw = ddjvu_page_get_width(page);
        int ph = ddjvu_page_get_height(page);
        if (pw <= 0 || ph <= 0) {
            rc = DEXP_PROBE_EPAGE;
        } else {
            int rw = w, rh = (int)((long long)ph * w / pw);
            if (rh > h) {
                rh = h;
                rw = (int)((long long)pw * h / ph);
            }
            ddjvu_rect_t prect = {0, 0, (unsigned)rw, (unsigned)rh};
            ddjvu_rect_t rrect = prect;
            fmt = ddjvu_format_create(DDJVU_FORMAT_GREY8, 0, NULL);
            ddjvu_format_set_row_order(fmt, 1);
            ddjvu_format_set_y_direction(fmt, 1);
            unsigned char *dst = gray + (size_t)((h - rh) / 2) * w + (w - rw) / 2;
            if (!ddjvu_page_render(page, DDJVU_RENDER_COLOR, &prect, &rrect, fmt,
                (unsigned long)w, (char *)dst))
                rc = DEXP_PROBE_ERENDER;
        }
    }
    if (fmt) ddjvu_format_release(fmt);
    ddjvu_page_release(page);
    return rc;
}

extern "C" int dexp_djvu_probe(const char *path, int w, int h, unsigned char *gray)
{
    if (!path || !gray || w <= 0 || h <= 0)
        return DEXP_PROBE_EARG;
    memset(gray, 255, (size_t)w * (size_t)h);

    ddjvu_context_t *ctx = ddjvu_context_create("dexp");
    if (!ctx)
        return DEXP_PROBE_ERENDER;
    // Кэш декодированных страниц: на книге 1 ГБ ОЗУ.
    ddjvu_cache_set_size(ctx, 8ul << 20);

    int rc = DEXP_PROBE_OK;
    ddjvu_document_t *doc = ddjvu_document_create_by_filename_utf8(ctx, path, 1);

    if (!doc || wait_doc(ctx, doc) != DDJVU_JOB_OK) {
        rc = DEXP_PROBE_EOPEN;
    } else {
        // Замеры 7.3г: файл /data/local/tmp/dexp-bench включает листание (до 30 страниц).
        const bool bench = access("/data/local/tmp/dexp-bench", F_OK) == 0;
        int pages = ddjvu_document_get_pagenum(doc);
        long t0 = now_ms();
        rc = render_page(ctx, doc, 0, w, h, gray);
        long t1 = now_ms();
        if (rc == DEXP_PROBE_OK && bench && pages > 1) {
            int n = pages < 30 ? pages : 30;
            long f0 = now_ms();
            for (int i = 1; i < n && rc == DEXP_PROBE_OK; i++)
                rc = render_page(ctx, doc, i, w, h, gray);
            long f1 = now_ms();
            __android_log_print(ANDROID_LOG_INFO, "DexpM8",
                "bench: djvu pages=%d draw0=%ld flips=%d flip_avg=%ld",
                pages, t1 - t0, n - 1, (f1 - f0) / (n - 1));
            if (rc == DEXP_PROBE_OK)
                rc = render_page(ctx, doc, 0, w, h, gray);
        } else {
            __android_log_print(ANDROID_LOG_INFO, "DexpM8",
                "bench: djvu pages=%d draw0=%ld", pages, t1 - t0);
        }
    }

    if (doc) ddjvu_document_release(doc);
    ddjvu_context_release(ctx);
    return rc;
}
