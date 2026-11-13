/*
 * Регистрация системных шрифтов для crengine. R1 (README: «Literata для текста книги —
 * полный шрифт» — отдельный шаг, см. DEVLOG R1.1): пока берём /system/fonts, как проба R0.
 *
 * Функция общая для пробы (cre_probe.cpp) и сеанса (cre_session.cpp), но менеджер шрифтов
 * в crengine один на процесс (`fontMan`), поэтому повторный вызов ничего не переделывает.
 */
#ifndef DEXP_CRE_FONTS_H
#define DEXP_CRE_FONTS_H

#include <dirent.h>
#include <string.h>

#include "crengine.h"

static const char *const DEXP_FONT_DIR = "/system/fonts";

/** Сколько шрифтов удалось зарегистрировать; 0 — ни одного (движку нечем рисовать). */
static inline int dexp_register_system_fonts()
{
    if (!fontMan)
        return 0;
    DIR *d = opendir(DEXP_FONT_DIR);
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
        lString8 path(DEXP_FONT_DIR);
        path << "/" << e->d_name;
        if (fontMan->RegisterFont(path))
            n++;
    }
    closedir(d);
    return n;
}

#endif
