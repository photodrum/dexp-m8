// Пробный вход crengine (FB2, EPUB, MOBI, TXT, RTF, DOC…): первая страница в серый буфер. См. probe.h.
// Шрифты для пробы — системные из /system/fonts (свои шрифты читалки — R1).
#include <dirent.h>
#include <string.h>

#include "crengine.h"
#include "probe.h"

static const char *const FONT_DIR = "/system/fonts";

static int register_fonts()
{
    DIR *d = opendir(FONT_DIR);
    if (!d)
        return 0;
    int n = 0;
    struct dirent *e;
    while ((e = readdir(d)) != NULL) {
        size_t len = strlen(e->d_name);
        if (len < 5)
            continue;
        const char *ext = e->d_name + len - 4;
        if (strcasecmp(ext, ".ttf") != 0 && strcasecmp(ext, ".otf") != 0)
            continue;
        lString8 path(FONT_DIR);
        path << "/" << e->d_name;
        if (fontMan->RegisterFont(path))
            n++;
    }
    closedir(d);
    return n;
}

extern "C" int dexp_cre_probe(const char *path, int w, int h, unsigned char *gray)
{
    if (!path || !gray || w <= 0 || h <= 0)
        return DEXP_PROBE_EARG;
    memset(gray, 255, (size_t)w * (size_t)h);

    if (!fontMan) {
        InitFontManager(lString8::empty_str);
        if (register_fonts() == 0)
            return DEXP_PROBE_ERENDER;
    }

    int rc = DEXP_PROBE_OK;
    {
        LVDocView view(8, true);
        view.setViewMode(DVM_PAGES, 1);
        view.setPageHeaderInfo(PGHDR_NONE);
        view.setDefaultFontFace(lString8("Noto Serif"));
        view.Resize(w, h);
        if (!view.LoadDocument(path)) {
            rc = DEXP_PROBE_EOPEN;
        } else {
            view.checkRender();
            if (view.getPageCount() <= 0) {
                rc = DEXP_PROBE_EPAGE;
            } else {
                view.goToPage(0);
                // 8 бит на пиксель, данные пишутся прямо в gray (stride = w).
                LVGrayDrawBuf buf(w, h, 8, gray);
                view.Draw(buf, false);
            }
        }
    }
    return rc;
}
