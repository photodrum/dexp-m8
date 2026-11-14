/*
 * Хостовый тест раскладки страницы DjVu (R2.4): `engines/djvu_layout.h` без DjVuLibre.
 *
 *   g++ -std=c++17 -O1 -Wall -Wextra -Iapp/src/main/cpp/engines \
 *       -o /tmp/test_djvu_layout app/src/main/cpp/test/test_djvu_layout.cpp
 *   /tmp/test_djvu_layout
 *
 * Проверяет то, из-за чего движок бросал `bad_rect2`: `renderrect` всегда внутри
 * `pagerect` и не выходит за буфер; обрезка крупнит (масштаб по обрезанной части);
 * сдвиг ограничивается и отдаётся наружу; «по ширине» заполняет окно по X.
 */
#include <stdio.h>
#include <string.h>

#include "djvu_layout.h"

using namespace dexpdjvu;

static int fails = 0;
static int checks = 0;

static void check(bool ok, const char *what, int a = 0, int b = 0)
{
    checks++;
    if (!ok) {
        fails++;
        printf("FAIL %s (%d, %d)\n", what, a, b);
    }
}

/** Общие инварианты любого удачного расклада. */
static void invariants(const Layout &l, int w, int h, const char *what)
{
    check(l.pageW > 0 && l.pageH > 0, what);
    check(l.rectX >= 0 && l.rectY >= 0, what);
    check(l.rectX + l.rectW <= l.pageW, what, l.rectX + l.rectW, l.pageW);
    check(l.rectY + l.rectH <= l.pageH, what, l.rectY + l.rectH, l.pageH);
    check(l.bufX >= 0 && l.bufY >= 0, what);
    check(l.bufX + l.rectW <= w, what, l.bufX + l.rectW, w);
    check(l.bufY + l.rectH <= h, what, l.bufY + l.rectH, h);
    check(l.panX >= 0 && l.panY >= 0, what);
    check(l.panX <= l.maxX && l.panY <= l.maxY, what, l.panX, l.maxX);
    check(l.maxX >= 0 && l.maxY >= 0, what);
}

static void test_page_fit()
{
    /* Страница 1000×1400 в окно 1000×1200: «по странице» — по высоте, по центру по X. */
    Layout l;
    bool ok = layout(1000, 1400, 1000, 1200, ZOOM_PAGE, NULL, 0, 0, l);
    check(ok, "page fit ok");
    invariants(l, 1000, 1200, "page fit");
    check(l.pageH == 1200, "page fit height", l.pageH, 1200);
    check(l.pageW == 857, "page fit width", l.pageW, 857);
    check(l.maxX == 0 && l.maxY == 0, "page fit nothing to pan", l.maxX, l.maxY);
    check(l.bufX == 71 && l.bufY == 0, "page fit centred", l.bufX, l.bufY);
    check(l.rectW == 857 && l.rectH == 1200, "page fit covers", l.rectW, l.rectH);
}
static void test_width_fit()
{
    /* «По ширине»: X заполнен целиком, по Y картинка может не влезть. */
    Layout l;
    bool ok = layout(1000, 1400, 1000, 1200, ZOOM_WIDTH, NULL, 0, 0, l);
    check(ok, "width fit ok");
    invariants(l, 1000, 1200, "width fit");
    check(l.pageW == 1000 && l.pageH == 1400, "width fit size", l.pageW, l.pageH);
    check(l.maxX == 0, "width fit no x pan", l.maxX, 0);
    check(l.maxY == 200, "width fit y pan", l.maxY, 200);
    check(l.rectH == 1200, "width fit visible", l.rectH, 1200);
}

static void test_crop_enlarges()
{
    /* Обрезка 100 промилле по краям: масштаб по обрезанному, текст крупнее. */
    const int crop[4] = {100, 100, 100, 100};
    Layout plain, cut;
    layout(1000, 1400, 1000, 1200, ZOOM_PAGE, NULL, 0, 0, plain);
    bool ok = layout(1000, 1400, 1000, 1200, ZOOM_PAGE, crop, 0, 0, cut);
    check(ok, "crop ok");
    invariants(cut, 1000, 1200, "crop");
    check(cut.pageW > plain.pageW, "crop enlarges width", cut.pageW, plain.pageW);
    check(cut.pageH > plain.pageH, "crop enlarges height", cut.pageH, plain.pageH);
    /* renderrect — обрезанная часть внутри pagerect: сдвинут на обрезку и уже страницы
     * целиком, но по высоте закрывает окно (масштаб по обрезанному). */
    check(cut.rectX > 0 && cut.rectW < cut.pageW, "crop rect inside page",
          cut.rectX, cut.rectW);
    check(cut.rectH == 1200, "crop covers window height", cut.rectH, 1200);
    check(cut.maxX == 0 && cut.maxY == 0, "crop fit nothing to pan", cut.maxX, cut.maxY);
}

static void test_zoom_and_pan()
{
    /* Увеличение 200 %: страница больше окна, сдвиг ограничивается пределом. */
    Layout l;
    bool ok = layout(1000, 1400, 1000, 1200, 200, NULL, 5000, 5000, l);
    check(ok, "zoom ok");
    invariants(l, 1000, 1200, "zoom");
    check(l.pageW == 1714 && l.pageH == 2400, "zoom size", l.pageW, l.pageH);
    check(l.panX == l.maxX && l.panY == l.maxY, "zoom pan clamped", l.panX, l.maxX);
    check(l.maxX == 714 && l.maxY == 1200, "zoom max", l.maxX, l.maxY);
    check(l.bufX == 0 && l.bufY == 0, "zoom starts at corner", l.bufX, l.bufY);
    check(l.rectW == 1000 && l.rectH == 1200, "zoom visible", l.rectW, l.rectH);

    /* Тот же сдвиг, но в отрицательную сторону — 0, изображение не рвётся. */
    ok = layout(1000, 1400, 1000, 1200, 200, NULL, -500, -500, l);
    check(ok && l.panX == 0 && l.panY == 0, "pan negative -> 0", l.panX, l.panY);
    invariants(l, 1000, 1200, "zoom pan 0");
}

static void test_crop_clamped()
{
    /* Битые настройки: 1000+1000 промилле оставили бы пустое место. */
    const int crop[4] = {1000, 1000, 1000, 1000};
    Layout l;
    bool ok = layout(1000, 1400, 1000, 1200, ZOOM_PAGE, crop, 0, 0, l);
    check(ok, "crop clamped ok");
    invariants(l, 1000, 1200, "crop clamped");
    /* Остаётся 10 % по каждой стороне, растянутое на окно: по высоте окно закрыто
     * целиком, по ширине обрезанная часть уже окна и стоит по центру. */
    check(l.maxX == 0 && l.maxY == 0, "crop clamped fits", l.maxX, l.maxY);
    check(l.rectH == 1200, "crop clamped covers height", l.rectH, 1200);
    check(l.bufX > 0 && l.bufX + l.rectW <= 1000, "crop clamped centred", l.bufX, l.rectW);

    /* Обрезка перекрывает страницу (левая 900, правая 500): правую срезаем, не падаем. */
    const int bad[4] = {900, 0, 500, 0};
    ok = layout(1000, 1400, 1000, 1200, ZOOM_PAGE, bad, 0, 0, l);
    check(ok, "crop overlap ok");
    invariants(l, 1000, 1200, "crop overlap");
}

static void test_zoom_scale_cap()
{
    /* Предел стороны не отказ, а уменьшение масштаба: битый скан не съест память,
     * но и не покажется повреждённой страницей (B59). */
    Layout l;
    bool ok = layout(8000, 11000, 1404, 1600, 300, NULL, 0, 0, l);
    check(ok, "zoom 300 huge ok");
    invariants(l, 1404, 1600, "zoom 300 huge");
    check(l.pageH <= PAGE_SIDE_MAX && l.pageW <= PAGE_SIDE_MAX, "zoom 300 side cap", l.pageH);

    /* 40000 px в окно 30000: масштаб урезан пределом, кадр собирается. */
    ok = layout(40000, 40000, 30000, 30000, ZOOM_PAGE, NULL, 0, 0, l);
    check(ok, "huge frame capped not refused");
    invariants(l, 30000, 30000, "huge frame capped");
    check(l.pageW <= PAGE_SIDE_MAX && l.pageH <= PAGE_SIDE_MAX, "huge frame side cap", l.pageW);
    ok = layout(40000, 40000, 30000, 30000, 50, NULL, 0, 0, l);
    check(ok, "huge page half zoom ok");
    invariants(l, 30000, 30000, "huge page half zoom");
}

static void test_bad_args()
{
    Layout l;
    check(!layout(0, 1400, 1000, 1200, ZOOM_PAGE, NULL, 0, 0, l), "zero page");
    check(!layout(1000, 1400, 0, 1200, ZOOM_PAGE, NULL, 0, 0, l), "zero window");
    check(!layout(-5, 1400, 1000, 1200, ZOOM_PAGE, NULL, 0, 0, l), "negative page");
    /* Масштаб вне диапазона ограничивается, а не даёт деления на ноль. */
    Layout a, b;
    bool okA = layout(1000, 1400, 1000, 1200, 10, NULL, 0, 0, a);
    bool okB = layout(1000, 1400, 1000, 1200, ZOOM_MIN, NULL, 0, 0, b);
    check(okA && okB && a.pageW == b.pageW, "zoom below min clamped", a.pageW, b.pageW);
    okA = layout(1000, 1400, 1000, 1200, 9000, NULL, 0, 0, a);
    okB = layout(1000, 1400, 1000, 1200, ZOOM_MAX, NULL, 0, 0, b);
    check(okA && okB && a.pageW == b.pageW, "zoom above max clamped", a.pageW, b.pageW);
    check(okA && a.rectW <= 1000 && a.rectH <= 1200, "zoom max visible", a.rectW, a.rectH);
}

int main()
{
    test_page_fit();
    test_width_fit();
    test_crop_enlarges();
    test_zoom_and_pan();
    test_crop_clamped();
    test_zoom_scale_cap();
    test_bad_args();
    printf("%d проверок, %d провалов\n", checks, fails);
    if (fails == 0)
        printf("all good\n");
    return fails == 0 ? 0 : 1;
}

