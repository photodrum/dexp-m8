// Пробный вход crengine (FB2, EPUB, MOBI, TXT, RTF, DOC…): первая страница в серый буфер. См. probe.h.
// Шрифты для пробы — системные из /system/fonts; свои шрифты читалки и сеанс — R1
// (`cre_session.cpp`, `cre_fonts.h`).
#include <android/log.h>
#include <dirent.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "cre_fonts.h"
#include "crengine.h"
#include "probe.h"

static long now_ms()
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long)(ts.tv_sec * 1000L + ts.tv_nsec / 1000000L);
}

extern "C" int dexp_cre_probe(const char *path, int w, int h, unsigned char *gray)
{
    if (!path || !gray || w <= 0 || h <= 0)
        return DEXP_PROBE_EARG;
    memset(gray, 255, (size_t)w * (size_t)h);

    if (!fontMan) {
        InitFontManager(lString8::empty_str);
        if (dexp_register_system_fonts() == 0)
            return DEXP_PROBE_ERENDER;
    }

    // Замеры 7.3г: наличие файла /data/local/tmp/dexp-bench включает листание;
    // тайминги — в logcat (тег DexpM8, «bench: …»).
    const bool bench = access("/data/local/tmp/dexp-bench", F_OK) == 0;

    int rc = DEXP_PROBE_OK;
    {
        long t0 = now_ms();
        LVDocView view(8, true);
        view.setViewMode(DVM_PAGES, 1);
        view.setPageHeaderInfo(PGHDR_NONE);
        view.setDefaultFontFace(lString8("Noto Serif"));
        view.Resize(w, h);
        long t1 = now_ms();
        if (!view.LoadDocument(path)) {
            rc = DEXP_PROBE_EOPEN;
        } else {
            long t2 = now_ms();
            view.checkRender();
            long t3 = now_ms();
            int pages = view.getPageCount();
            if (pages <= 0) {
                rc = DEXP_PROBE_EPAGE;
            } else {
                view.goToPage(0);
                // 8 бит на пиксель, данные пишутся прямо в gray (stride = w).
                LVGrayDrawBuf buf(w, h, 8, gray);
                view.Draw(buf, false);
                long t4 = now_ms();
                if (bench) {
                    int n = pages < 30 ? pages : 30;
                    long f0 = now_ms();
                    for (int i = 1; i < n; i++) {
                        view.goToPage(i);
                        view.Draw(buf, false);
                    }
                    long f1 = now_ms();
                    view.goToPage(0);
                    view.Draw(buf, false);
                    __android_log_print(ANDROID_LOG_INFO, "DexpM8",
                            "bench: cre setup=%ld load=%ld layout=%ld draw0=%ld pages=%d flips=%d flip_avg=%ld",
                            t1 - t0, t2 - t1, t3 - t2, t4 - t3, pages, n - 1,
                            n > 1 ? (f1 - f0) / (n - 1) : -1L);
                } else {
                    __android_log_print(ANDROID_LOG_INFO, "DexpM8",
                            "bench: cre setup=%ld load=%ld layout=%ld draw0=%ld pages=%d",
                            t1 - t0, t2 - t1, t3 - t2, t4 - t3, pages);
                }
            }
        }
    }
    return rc;
}
