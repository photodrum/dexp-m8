/*
 * 8-битный серый буфер crengine → 16 уровней палитры G0…G15 (шаг 17, без дизеринга)
 * и инверсия: получается альфа для `Bitmap.Config.ALPHA_8`, который рисуется цветом G0
 * поверх G15 (альфа 0 = белый, 255 = чёрный). Шаги 7.2 и R1.
 */
#ifndef DEXP_CRE_GRAY_H
#define DEXP_CRE_GRAY_H

#include <stddef.h>

/** Свести [n] байт 8-битного серого к палитре и инвертировать (на месте). */
static inline void dexp_gray_to_palette(unsigned char *g, size_t n)
{
    for (size_t i = 0; i < n; i++) {
        unsigned v = g[i];
        unsigned q = ((v * 15u + 127u) / 255u) * 17u;   /* 0, 17, …, 255 */
        g[i] = (unsigned char)(255u - q);
    }
}

#endif
