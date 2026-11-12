/*
 * Пробные входы движков R0 (ROADMAP «План 7.1», шаг 5). JNI и экран — шаг 7.2.
 *
 * Каждая функция открывает файл, рисует первую страницу по центру буфера w×h
 * (8-битный серый, stride = w, фон 255) с сохранением пропорций.
 * Возвращает 0 или отрицательный код ошибки.
 */
#ifndef DEXP_ENGINE_PROBE_H
#define DEXP_ENGINE_PROBE_H

#ifdef __cplusplus
extern "C" {
#endif

#define DEXP_PROBE_API __attribute__((visibility("default")))

enum {
    DEXP_PROBE_OK = 0,
    DEXP_PROBE_EARG = -1,   /* неверные аргументы */
    DEXP_PROBE_EOPEN = -2,  /* файл не открылся */
    DEXP_PROBE_EPAGE = -3,  /* страница не загрузилась */
    DEXP_PROBE_ERENDER = -4 /* ошибка отрисовки */
};

DEXP_PROBE_API int dexp_djvu_probe(const char *path, int w, int h, unsigned char *gray);
DEXP_PROBE_API int dexp_cre_probe(const char *path, int w, int h, unsigned char *gray);

#ifdef __cplusplus
}
#endif

#endif
