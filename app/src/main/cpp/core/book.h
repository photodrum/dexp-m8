/*
 * Ядро разбора книг. Задача — вытащить из FB2/EPUB плоский поток блоков
 * (заголовок/абзац/цитата/стих) в UTF-8, без DOM и без аллокаций на каждый тег.
 *
 * Ни libxml2, ни expat: .so должна весить десятки килобайт, а не мегабайт,
 * и не приносить с собой C++ рантайм. XML в FB2 простой, атрибуты нам нужны
 * только у трёх тегов, поэтому разбор потоковый, с одним буфером на документ.
 */
#ifndef DEXP_CORE_BOOK_H
#define DEXP_CORE_BOOK_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    BLK_PARA = 0,   /* обычный абзац */
    BLK_TITLE = 1,  /* заголовок любого уровня (section/title) */
    BLK_SUBTITLE = 2,
    BLK_EPIGRAPH = 3,
    BLK_POEM = 4,   /* строка стиха */
    BLK_ANNOTATION = 5,
    BLK_EMPTY = 6   /* вертикальный отступ, без текста */
} blk_type;

typedef struct {
    blk_type type;
    const char *text;   /* UTF-8, не NUL-терминирован в общем случае */
    size_t len;
} blk;

typedef struct {
    blk *items;
    size_t count;
    size_t cap;
    char *pool;         /* вся текстовая память одним блоком */
    size_t pool_len;
    size_t pool_cap;

    /* метаданные */
    char *title;
    char *author;
    char *lang;
    char *cover_href;
    int error;          /* 0 = ok, иначе код */
} book;

/* Разбор FB2 из памяти. Возвращает 0 при успехе. */
int book_parse_fb2(book *b, const char *xml, size_t len);

void book_free(book *b);

/*
 * Метаданные FB2 для библиотеки: только <description><title-info>, тело не
 * читается — разбор останавливается на </title-info>. Достаточно начала файла
 * (BOOK_META_HEAD байт): обрезанный документ не ошибка, берётся то, что успели.
 * Все строки — UTF-8 (windows-1251 перекодируется), сущности раскрыты,
 * пробелы схлопнуты; NULL — поля нет.
 */
#define BOOK_META_HEAD (64 * 1024)

typedef struct {
    char *title;        /* book-title */
    char *authors;      /* все <author> через ", ": «Имя Отчество Фамилия» или ник */
    char *first_name;   /* части первого автора — для сортировки и «Ф. Фамилия» */
    char *middle_name;
    char *last_name;
    char *nickname;
    char *series;       /* <sequence name> */
    int series_num;     /* <sequence number>, 0 — нет */
    char *lang;
    char *cover_id;     /* id <binary> обложки, без '#' */
    int complete;       /* 1 — дошли до </title-info>; 0 — начало файла обрезано раньше */
    int error;          /* 0 = ok, иначе код (book_error_text) */
} book_meta;

/* 0 — успех (title-info найден, title может быть NULL). */
int book_meta_fb2(book_meta *m, const char *xml, size_t len);

void book_meta_free(book_meta *m);

/* Диагностика: текст ошибки по коду. */
const char *book_error_text(int code);

#ifdef __cplusplus
}
#endif

#endif /* DEXP_CORE_BOOK_H */
