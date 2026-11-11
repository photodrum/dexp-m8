// Пробный вход DjVuLibre: первая страница в серый буфер. См. probe.h.
#include <string.h>

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
    ddjvu_page_t *page = NULL;
    ddjvu_format_t *fmt = NULL;
    ddjvu_document_t *doc = ddjvu_document_create_by_filename_utf8(ctx, path, 1);

    if (!doc || wait_doc(ctx, doc) != DDJVU_JOB_OK) {
        rc = DEXP_PROBE_EOPEN;
    } else {
        page = ddjvu_page_create_by_pageno(doc, 0);
        if (!page || wait_page(ctx, page) != DDJVU_JOB_OK) {
            rc = DEXP_PROBE_EPAGE;
        } else {
            int pw = ddjvu_page_get_width(page);
            int ph = ddjvu_page_get_height(page);
            if (pw <= 0 || ph <= 0) {
                rc = DEXP_PROBE_EPAGE;
            } else {
                // Вписать страницу в w×h с сохранением пропорций, по центру.
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
    }

    if (fmt) ddjvu_format_release(fmt);
    if (page) ddjvu_page_release(page);
    if (doc) ddjvu_document_release(doc);
    ddjvu_context_release(ctx);
    return rc;
}
