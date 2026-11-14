/*
 * Раскладка страницы DjVu в окно читалки: масштаб, обрезка полей, сдвиг (R2.4, B31).
 *
 * Чистая арифметика без DjVuLibre: сеанс (`djvu_session.cpp`) только рисует по готовым
 * прямоугольникам, а инварианты проверяет хостовый тест (`test/test_djvu_layout.cpp`).
 *
 * Почему обрезка считается до вписывания: если вписать страницу целиком и потом закрасить
 * поля, текст не крупнеет — масштаб уже выбран по полному размеру. Поэтому масштаб
 * считается по обрезанным габаритам (поправка 3 к ТЗ дизайна, DEVLOG 2026-11-14).
 *
 * Две системы координат:
 *  - `pagerect` — полная страница после масштаба, от (0,0);
 *  - буфер экрана `w`×`h` — куда попадает видимая часть обрезанной страницы.
 * `renderrect` считается в координатах `pagerect` и всегда обязан лежать внутри него:
 * иначе `DjVuImage::do_pixmap` бросает `bad_rect2`, а `ddjvu_page_render` возвращает
 * пусто (`ddjvuapi.cpp:2449`, `DjVuImage.cpp:1183`).
 */
#ifndef DEXP_DJVU_LAYOUT_H
#define DEXP_DJVU_LAYOUT_H

#include <math.h>

namespace dexpdjvu {

/** Порядок полей обрезки и элементов выхода `pan`. */
enum {
    CROP_LEFT = 0, CROP_TOP = 1, CROP_RIGHT = 2, CROP_BOTTOM = 3,
    PAN_X = 0, PAN_Y = 1, PAN_MAX_X = 2, PAN_MAX_Y = 3
};

/** Масштаб: 0 — по странице, −1 — по ширине, 50…300 — проценты от «по странице». */
const int ZOOM_PAGE = 0;
const int ZOOM_WIDTH = -1;
const int ZOOM_MIN = 50;
const int ZOOM_MAX = 300;

/** Обрезка задаётся в промилле исходной страницы (1000 = вся сторона). */
const int CROP_FULL = 1000;

/**
 * Сколько отрезать с двух сторон вместе, максимум: 900 промилле, то есть от страницы
 * остаётся не меньше 10 % — иначе битые настройки дадут пустую страницу и деление на
 * почти ноль в масштабе.
 */
const int CROP_PAIR_MAX = 900;

/** Отмасштабированная страница не больше этого: битый файл иначе съест память. */
const int PAGE_SIDE_MAX = 20000;

struct Layout {
    int pageW, pageH;                  // pagerect: полная страница после масштаба
    int rectX, rectY, rectW, rectH;    // renderrect — внутри pagerect
    int bufX, bufY;                    // левый верхний угол видимой части в буфере
    int panX, panY;                    // фактический сдвиг после ограничения
    int maxX, maxY;                    // насколько ещё можно сдвинуть (для третей)
};

inline int clamp_int(int v, int lo, int hi)
{
    return v < lo ? lo : (v > hi ? hi : v);
}

/** Промилле от [total] с округлением до пикселя. */
inline int per_mille(int total, int pm)
{
    return (int)(((long long)total * pm + CROP_FULL / 2) / CROP_FULL);
}

/**
 * Разложить страницу [pw]×[ph] в окно [w]×[h]. [crop] — четыре промилле (слева, сверху,
 * справа, снизу), может быть NULL — без обрезки. [panXIn]/[panYIn] — желаемый сдвиг
 * в пикселях буфера (0 = левый/верхний край, больше — дальше в страницу).
 * `false` — раскладывать нечего (нулевые размеры, битый масштаб).
 */
inline bool layout(int pw, int ph, int w, int h, int zoom, const int *crop,
                   int panXIn, int panYIn, Layout &out)
{
    if (pw <= 0 || ph <= 0 || w <= 0 || h <= 0)
        return false;

    int cl = crop ? crop[CROP_LEFT] : 0;
    int ct = crop ? crop[CROP_TOP] : 0;
    int cr = crop ? crop[CROP_RIGHT] : 0;
    int cb = crop ? crop[CROP_BOTTOM] : 0;
    cl = clamp_int(cl, 0, CROP_PAIR_MAX);    // одна сторона тоже не больше 90 %
    ct = clamp_int(ct, 0, CROP_PAIR_MAX);
    cr = clamp_int(cr, 0, CROP_FULL);
    cb = clamp_int(cb, 0, CROP_FULL);
    if (cl + cr > CROP_PAIR_MAX)
        cr = CROP_PAIR_MAX - cl;
    if (ct + cb > CROP_PAIR_MAX)
        cb = CROP_PAIR_MAX - ct;

    const int x0 = per_mille(pw, cl);
    const int x1 = pw - per_mille(pw, cr);
    const int y0 = per_mille(ph, ct);
    const int y1 = ph - per_mille(ph, cb);
    int cw = x1 - x0;
    int ch = y1 - y0;
    if (cw < 1) cw = 1;
    if (ch < 1) ch = 1;

    /* Масштаб — по обрезанной части: только так обрезка крупнит текст. */
    const double fitBoth = fmin((double)w / (double)cw, (double)h / (double)ch);
    const double fitWidth = (double)w / (double)cw;
    double scale;
    if (zoom == ZOOM_WIDTH)
        scale = fitWidth;
    else if (zoom > 0)
        scale = fitBoth * (double)clamp_int(zoom, ZOOM_MIN, ZOOM_MAX) / 100.0;
    else
        scale = fitBoth;
    if (!(scale > 0.0))
        return false;
    /* Предел стороны: не ошибка, а повод уменьшить масштаб — иначе битый или огромный
     * скан отдал бы кадр на гигабайт. Отказом это было бы неверно: экран принял бы
     * его за повреждённую страницу (B59). */
    const double cap = fmin((double)PAGE_SIDE_MAX / (double)pw,
                            (double)PAGE_SIDE_MAX / (double)ph);
    if (scale > cap)
        scale = cap;

    /* pagerect — полная страница в этом масштабе: её и ждёт ddjvu_page_render. */
    int pageW = (int)lround((double)pw * scale);
    int pageH = (int)lround((double)ph * scale);
    if (pageW < 1) pageW = 1;
    if (pageH < 1) pageH = 1;

    /* Обрезанная часть в координатах pagerect. */
    int cx = clamp_int((int)lround((double)x0 * scale), 0, pageW - 1);
    int cy = clamp_int((int)lround((double)y0 * scale), 0, pageH - 1);
    int cw2 = (int)lround((double)cw * scale);
    int ch2 = (int)lround((double)ch * scale);
    if (cw2 < 1) cw2 = 1;
    if (ch2 < 1) ch2 = 1;
    if (cx + cw2 > pageW) cw2 = pageW - cx;      // округление даёт лишний пиксель
    if (cy + ch2 > pageH) ch2 = pageH - cy;
    if (cw2 < 1 || ch2 < 1)
        return false;

    /* Куда ещё можно сдвинуть: излишек обрезанной части за окном. */
    const int maxX = cw2 > w ? cw2 - w : 0;
    const int maxY = ch2 > h ? ch2 - h : 0;
    const int panX = clamp_int(panXIn, 0, maxX);
    const int panY = clamp_int(panYIn, 0, maxY);

    /* Меньше окна — по центру, больше — сдвиг от левого/верхнего края. */
    int startX, bufX, startY, bufY;
    if (cw2 <= w) {
        startX = 0;
        bufX = (w - cw2) / 2;
    } else {
        startX = panX;
        bufX = 0;
    }
    if (ch2 <= h) {
        startY = 0;
        bufY = (h - ch2) / 2;
    } else {
        startY = panY;
        bufY = 0;
    }

    int rectW = cw2 - startX;
    int rectH = ch2 - startY;
    if (rectW > w - bufX) rectW = w - bufX;
    if (rectH > h - bufY) rectH = h - bufY;
    int rectX = cx + startX;
    int rectY = cy + startY;
    /* renderrect ⊆ pagerect: последний рубеж перед ddjvu_page_render. */
    if (rectX + rectW > pageW) rectW = pageW - rectX;
    if (rectY + rectH > pageH) rectH = pageH - rectY;
    if (rectX < 0 || rectY < 0 || rectW < 1 || rectH < 1)
        return false;

    out.pageW = pageW;
    out.pageH = pageH;
    out.rectX = rectX;
    out.rectY = rectY;
    out.rectW = rectW;
    out.rectH = rectH;
    out.bufX = bufX;
    out.bufY = bufY;
    out.panX = panX;
    out.panY = panY;
    out.maxX = maxX;
    out.maxY = maxY;
    return true;
}

} // namespace dexpdjvu

#endif
