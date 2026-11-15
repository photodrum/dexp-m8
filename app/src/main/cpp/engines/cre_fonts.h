/*
 * Регистрация шрифтов для crengine. R1 (README: «Literata для текста книги — полный шрифт»).
 *
 * Два источника, оба нужны:
 *  - системные `/system/fonts` — проба R0 и запас на случай, если наши файлы не доехали
 *    (без шрифтов движку нечем рисовать: `dexp_reader_open` вернёт EFONT);
 *  - свои файлы читалки (B4 «Гарнитура», R3.4) — путь передаёт Kotlin: ресурсы
 *    `res/font/` лежат внутри APK, у них нет файлового пути, поэтому `Engine.init`
 *    распаковывает нужные начертания в `filesDir/fonts` и отдаёт каталог сюда.
 *
 * Порядок регистрации важен: системные первыми, свои — следом. `RegisterFont` одного
 * начертания не вытесняет другое, а `GetFont` ищет по имени семейства (см. `familyName`
 * в lvfntman.cpp) — наши «Literata»/«Manrope» находятся по своим именам независимо от
 * того, сколько системных шрифтов уже зарегистрировано.
 *
 * Менеджер шрифтов в crengine один на процесс (`fontMan`), поэтому повторный вызов
 * ничего не переделывает.
 */
#ifndef DEXP_CRE_FONTS_H
#define DEXP_CRE_FONTS_H

#include <dirent.h>
#include <string.h>

#include "crengine.h"

static const char *const DEXP_FONT_DIR = "/system/fonts";

/** Зарегистрировать все .ttf/.otf из каталога [dir]; 0 — каталога нет или он пуст. */
static inline int dexp_register_fonts_dir(const char *dir)
{
    if (!fontMan || !dir || !dir[0])
        return 0;
    DIR *d = opendir(dir);
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
        lString8 path(dir);
        path << "/" << e->d_name;
        if (fontMan->RegisterFont(path))
            n++;
    }
    closedir(d);
    return n;
}

/**
 * Зарегистрировать свои шрифты читалки из [appFontDir] (B4) после системных.
 * [appFontDir] — каталог, куда Kotlin распаковал `res/font` (null — только системные).
 * Возвращает число зарегистрированных шрифтов всего; 0 — движку нечем рисовать.
 */
static inline int dexp_register_system_fonts(const char *appFontDir = NULL)
{
    if (!fontMan)
        return 0;
    int n = dexp_register_fonts_dir(DEXP_FONT_DIR);
    if (appFontDir && appFontDir[0])
        n += dexp_register_fonts_dir(appFontDir);
    return n;
}

#endif
