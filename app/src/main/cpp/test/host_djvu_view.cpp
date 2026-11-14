/*
 * Проверка R2.4 на настоящем DjVu (хостовая сборка libdjvu, не Android).
 * Не входит в сборку приложения: временный стенд для проверки раскладки на файле.
 *
 *   g++ -std=c++17 -O1 -w -DHAVE_CONFIG_H -I engines/djvu -I third_party/djvulibre/libdjvu \
 *       -I engines test/host_djvu_view.cpp /tmp/hostdjvu/*.o -lpthread -o /tmp/host_view
 *   /tmp/host_view third_party/djvulibre/doc/djvu3spec.djvu
 *
 * Что проверяет:
 *  - «по странице» без обрезки и с обрезкой — оба кадра не пустые, разного масштаба;
 *  - сдвиг вниз/вправо не переворачивает страницу (нижняя треть полна чернил,
 *    верхняя — почти пуста: так выглядит страница текста, а не отражённая);
 *  - контраст не выбеливает кадр целиком.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "djvu_gray.h"
#include "probe.h"

extern "C" {
DEXP_PROBE_API int dexp_djvu_open(const char *path);
DEXP_PROBE_API int dexp_djvu_close();
DEXP_PROBE_API int dexp_djvu_page_count();
DEXP_PROBE_API int dexp_djvu_page_view(int page, int w, int h, unsigned char *gray,
                                       int zoom, const int *crop, int panX, int panY,
                                       int contrast, int *out);
}

static int fails = 0;

static void check(bool ok, const char *what, double a = 0, double b = 0)
{
    if (!ok) {
        fails++;
        printf("FAIL %s (%.4f, %.4f)\n", what, a, b);
    } else {
        printf("ok   %s (%.4f, %.4f)\n", what, a, b);
    }
}

/** Доля «чернил» (до инверсии 255 = чёрный) в прямоугольнике. */
static double ink(const unsigned char *g, int w, int x0, int y0, int x1, int y1)
{
    long dark = 0, total = 0;
    for (int y = y0; y < y1; y++)
        for (int x = x0; x < x1; x++) {
            total++;
            if (g[(size_t)y * w + x] < 128) dark++;
        }
    return total ? (double)dark / (double)total : 0.0;
}

/**
 * Доля «глубоких чернил» (< 64) — этим и меряется контраст: растяжение вокруг 128
 * сохраняет знак (x − 128), поэтому счёт по порогу 128 не меняется вообще,
 * а тёмные штрихи уходят к нулю и порог 64 переходят.
 */
static double deep(const unsigned char *g, int w, int x0, int y0, int x1, int y1)
{
    long dark = 0, total = 0;
    for (int y = y0; y < y1; y++)
        for (int x = x0; x < x1; x++) {
            total++;
            if (g[(size_t)y * w + x] < 64) dark++;
        }
    return total ? (double)dark / (double)total : 0.0;
}

/** Профиль чернил по горизонтальным полосам: 8 полос сверху вниз. */
static void profile(const unsigned char *g, int w, int h, const char *what)
{
    printf("     профиль %s:", what);
    for (int i = 0; i < 8; i++)
        printf(" %.3f", ink(g, w, 0, h * i / 8, w, h * (i + 1) / 8));
    printf("\n");
}

int main(int argc, char **argv)
{
    if (argc < 2) {
        printf("usage: host_view <file.djvu> [page]\n");
        return 2;
    }
    int page = argc > 2 ? atoi(argv[2]) : 0;
    const int W = 1404, H = 1600;
    unsigned char *buf = (unsigned char *)malloc((size_t)W * (size_t)H);
    if (!buf) return 2;

    int rc = dexp_djvu_open(argv[1]);
    check(rc == DEXP_PROBE_OK, "open", rc);
    if (rc != DEXP_PROBE_OK) return 1;
    printf("pages=%d\n", dexp_djvu_page_count());

    int pan[4] = {0, 0, 0, 0};

    /* 1. «По странице», без обрезки — как было в R2.1. */
    rc = dexp_djvu_page_view(page, W, H, buf, 0, NULL, 0, 0, 0, pan);
    double inkFull = ink(buf, W, 0, 0, W, H);
    double deepFull = deep(buf, W, 0, 0, W, H);
    check(rc == DEXP_PROBE_OK, "page fit rc", rc);
    check(inkFull > 0.001 && inkFull < 0.9, "page fit has content", inkFull);
    check(pan[2] == 0 && pan[3] == 0, "page fit no pan", pan[2], pan[3]);
    printf("     по странице: чернил %.3f, pan %d,%d max %d,%d\n",
           inkFull, pan[0], pan[1], pan[2], pan[3]);

    /* 2. Обрезка 100 промилле по краям: тот же кадр, но текст крупнее — чернил
     *    относительно всей страницы больше не станет, а полей по краям меньше. */
    const int crop[4] = {100, 100, 100, 100};
    rc = dexp_djvu_page_view(page, W, H, buf, 0, crop, 0, 0, 0, pan);
    double inkCrop = ink(buf, W, 0, 0, W, H);
    check(rc == DEXP_PROBE_OK, "crop rc", rc);
    check(inkCrop > 0.001, "crop has content", inkCrop);
    printf("     с обрезкой:  чернил %.3f, pan max %d,%d\n", inkCrop, pan[2], pan[3]);

    /* 3. «По ширине» — Y не влезает, сдвиг возможен. */
    rc = dexp_djvu_page_view(page, W, H, buf, -1, NULL, 0, 0, 0, pan);
    check(rc == DEXP_PROBE_OK, "width fit rc", rc);
    check(pan[3] >= 0, "width fit maxY >= 0", pan[3]);
    printf("     по ширине:   чернил %.3f, pan max %d,%d\n",
           ink(buf, W, 0, 0, W, H), pan[2], pan[3]);

    /* 4. Сдвиг в самый низ: нижняя полоса должна быть полнее верхней (страница
     *    текста внизу кончается колонтитулом, сверху — заголовок; но отражение
     *    дало бы ровно обратное, и это главное). */
    /* 4. Сдвиг: кадр при pan maxY — это строки [maxY, maxY+H) страницы, значит он
     *    обязан совпасть с кадром pan 0, сдвинутым на maxY: строки 0…H−maxY−1.
     *    Это и есть проверка направления Y и точности сдвига в пикселях
     *    (перевёрнутый Y дал бы полное расхождение). */
    if (pan[3] > 0) {
        int maxY = pan[3];
        unsigned char *top = (unsigned char *)malloc((size_t)W * (size_t)H);
        rc = dexp_djvu_page_view(page, W, H, top, -1, NULL, 0, 0, 0, pan);
        check(rc == DEXP_PROBE_OK, "pan top rc", rc);
        rc = dexp_djvu_page_view(page, W, H, buf, -1, NULL, 0, maxY, 0, pan);
        check(rc == DEXP_PROBE_OK, "pan bottom rc", rc);
        check(pan[1] == maxY, "pan bottom applied", pan[1], maxY);
        profile(buf, W, H, "pan max ");
        long same = 0, total = 0;
        for (int y = 0; y < H - maxY; y++)
            for (int x = 0; x < W; x++) {
                total++;
                if (buf[(size_t)y * W + x] == top[(size_t)(y + maxY) * W + x]) same++;
            }
        double match = total ? (double)same / (double)total : 0.0;
        printf("     сдвиг: совпало строк %.1f %%\n", match * 100.0);
        check(match > 0.99, "pan matches shifted rows", match);
        free(top);
    }

    /* 6. Сдвиг по X: тот же приём, что по Y, но нужен масштаб крупнее окна по обеим
     *    осям — «по ширине» даёт maxX = 0. Здесь же проверяется, что сдвиг не уводит
     *    renderrect за pagerect: иначе движок бросает bad_rect2 и кадр пуст. */
    rc = dexp_djvu_page_view(page, W, H, buf, 200, NULL, 0, 0, 0, pan);
    check(rc == DEXP_PROBE_OK, "zoom 200 rc", rc);
    printf("     масштаб 200: pan max %d,%d\n", pan[2], pan[3]);
    if (pan[2] > 0) {
        int maxX = pan[2];
        unsigned char *left = (unsigned char *)malloc((size_t)W * (size_t)H);
        rc = dexp_djvu_page_view(page, W, H, left, 200, NULL, 0, 0, 0, pan);
        check(rc == DEXP_PROBE_OK, "pan left rc", rc);
        rc = dexp_djvu_page_view(page, W, H, buf, 200, NULL, maxX, 0, 0, pan);
        check(rc == DEXP_PROBE_OK, "pan right rc", rc);
        check(pan[0] == maxX, "pan right applied", pan[0], maxX);
        long same = 0, total = 0;
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W - maxX; x++) {
                total++;
                if (buf[(size_t)y * W + x] == left[(size_t)y * W + x + maxX]) same++;
            }
        double match = total ? (double)same / (double)total : 0.0;
        printf("     сдвиг X: совпало %.1f %%\n", match * 100.0);
        check(match > 0.99, "pan x matches shifted cols", match);
        free(left);
    }

    /* 5. Контраст: фон выбеливается, тёмные штрихи уходят глубже. */
    rc = dexp_djvu_page_view(page, W, H, buf, 0, NULL, 0, 0, 1, pan);
    double deepContrast = deep(buf, W, 0, 0, W, H);
    check(rc == DEXP_PROBE_OK, "contrast rc", rc);
    check(deepContrast > deepFull, "contrast deepens", deepContrast, deepFull);
    printf("     контраст:    глубоких чернил %.3f (без него %.3f)\n", deepContrast, deepFull);

    dexp_djvu_close();
    free(buf);
    printf(fails ? "%d провалов\n" : "all good\n", fails);
    return fails ? 1 : 0;
}
