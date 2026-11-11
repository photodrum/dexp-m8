/* Пробный вход MuPDF (PDF, CBZ, картинки): первая страница в серый буфер. См. probe.h. */
#include <string.h>

#include "mupdf/fitz.h"
#include "probe.h"

/* Кэш ресурсов MuPDF: на книге 1 ГБ ОЗУ, FZ_STORE_DEFAULT (256 МБ) слишком много. */
#define DEXP_MUPDF_STORE (32u << 20)

int dexp_mupdf_probe(const char *path, int w, int h, unsigned char *gray)
{
    fz_context *ctx;
    fz_document *doc = NULL;
    fz_page *page = NULL;
    fz_pixmap *pix = NULL;
    int rc = DEXP_PROBE_OK;
    int stage = DEXP_PROBE_EOPEN;

    if (!path || !gray || w <= 0 || h <= 0)
        return DEXP_PROBE_EARG;
    memset(gray, 255, (size_t)w * (size_t)h);

    ctx = fz_new_context(NULL, NULL, DEXP_MUPDF_STORE);
    if (!ctx)
        return DEXP_PROBE_ERENDER;

    fz_try(ctx) {
        fz_rect b;
        float sx, sy, s;
        int x, y, ox, oy, pw, ph;

        fz_register_document_handlers(ctx);
        doc = fz_open_document(ctx, path);
        stage = DEXP_PROBE_EPAGE;
        page = fz_load_page(ctx, doc, 0);
        stage = DEXP_PROBE_ERENDER;

        b = fz_bound_page(ctx, page);
        sx = (float)w / (b.x1 - b.x0);
        sy = (float)h / (b.y1 - b.y0);
        s = sx < sy ? sx : sy;
        pix = fz_new_pixmap_from_page(ctx, page, fz_scale(s, s), fz_device_gray(ctx), 0);

        pw = fz_pixmap_width(ctx, pix);
        ph = fz_pixmap_height(ctx, pix);
        if (pw > w) pw = w;
        if (ph > h) ph = h;
        ox = (w - pw) / 2;
        oy = (h - ph) / 2;
        for (y = 0; y < ph; y++) {
            const unsigned char *src = fz_pixmap_samples(ctx, pix) + (size_t)y * fz_pixmap_stride(ctx, pix);
            unsigned char *dst = gray + (size_t)(oy + y) * w + ox;
            for (x = 0; x < pw; x++)
                dst[x] = src[x];
        }
    }
    fz_always(ctx) {
        fz_drop_pixmap(ctx, pix);
        fz_drop_page(ctx, page);
        fz_drop_document(ctx, doc);
    }
    fz_catch(ctx) {
        rc = stage;
    }

    fz_drop_context(ctx);
    return rc;
}
