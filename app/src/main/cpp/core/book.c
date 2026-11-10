/*
 * Разбор FB2 потоковым токенизатором.
 *
 * Идея: единственный проход по документу. Текст накапливается в растущий буфер
 * по мере выхода из тегов, затем сжимается (схлопывание пробелов) и кладётся
 * в арену. Никакого DOM: на 1 ГБ ОЗУ книга в 5 МБ не должна превращаться
 * в 50 МБ объектов.
 */
#include "book.h"
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include <strings.h>   /* strncasecmp */

#define ERR_OOM      1
#define ERR_FORMAT   2
#define ERR_TOO_BIG  3
#define ERR_ENCODING 4

const char *book_error_text(int code) {
    switch (code) {
        case 0: return "ok";
        case ERR_OOM: return "out of memory";
        case ERR_FORMAT: return "malformed xml";
        case ERR_TOO_BIG: return "document too large";
        case ERR_ENCODING: return "unsupported encoding";
        default: return "unknown";
    }
}

/* ---------------------------------------------------------------- утилиты */

static int ci_eq(const char *a, const char *lit) {
    while (*a && *lit) {
        if (tolower((unsigned char) *a) != tolower((unsigned char) *lit)) return 0;
        a++; lit++;
    }
    return *a == '\0' && *lit == '\0';
}

static char *dup_trim(const char *s, size_t n) {
    while (n && (unsigned char) s[0] <= ' ') { s++; n--; }
    while (n && (unsigned char) s[n - 1] <= ' ') n--;
    char *p = (char *) malloc(n + 1);
    if (!p) return NULL;
    memcpy(p, s, n);
    p[n] = '\0';
    return p;
}

/* Растущий буфер накопления текста узла. */
typedef struct {
    char *p;
    size_t len;
    size_t cap;
} buf;

static int buf_reserve(buf *b, size_t extra) {
    if (b->len + extra + 1 <= b->cap) return 1;
    size_t cap = b->cap ? b->cap : 1024;
    while (cap < b->len + extra + 1) cap *= 2;
    char *n = (char *) realloc(b->p, cap);
    if (!n) return 0;
    b->p = n;
    b->cap = cap;
    return 1;
}

static void buf_reset(buf *b) { b->len = 0; }

/* Разворачивает сущности и схлопывает пробелы прямо в буфере. */
static void buf_normalize(buf *b) {
    if (!b->len) return;
    size_t r = 0, w = 0;
    int sp = 0;
    while (r < b->len) {
        char ch = b->p[r];
        if (ch == '&') {
            /* &nbsp; — обычный пробел: схлопывается с соседними */
            if (r + 6 <= b->len && !memcmp(b->p + r, "&nbsp;", 6)) {
                if (w) sp = 1;
                r += 6;
                continue;
            }
            if (sp) { b->p[w++] = ' '; sp = 0; }   /* пробел перед сущностью не терять */
            size_t j = r + 1;
            while (j < b->len && b->p[j] != ';' && j - r < 10) j++;
            if (j < b->len && b->p[j] == ';') {
                size_t n = j - r - 1;
                const char *e = b->p + r + 1;
                int handled = 1;
                if (n == 3 && !memcmp(e, "amp", 3)) b->p[w++] = '&';
                else if (n == 2 && !memcmp(e, "lt", 2)) b->p[w++] = '<';
                else if (n == 2 && !memcmp(e, "gt", 2)) b->p[w++] = '>';
                else if (n == 4 && !memcmp(e, "quot", 4)) b->p[w++] = '"';
                else if (n == 4 && !memcmp(e, "apos", 4)) b->p[w++] = '\'';
                else if (n >= 2 && e[0] == '#') {
                    unsigned long cp = 0;
                    size_t k = (e[1] == 'x' || e[1] == 'X') ? 2 : 1;
                    int base = (k == 2) ? 16 : 10;
                    int ok = k < n;
                    for (; ok && k < n; k++) {
                        char c = e[k];
                        int d = (c >= '0' && c <= '9') ? c - '0'
                              : (base == 16 && c >= 'a' && c <= 'f') ? c - 'a' + 10
                              : (base == 16 && c >= 'A' && c <= 'F') ? c - 'A' + 10 : -1;
                        if (d < 0) { ok = 0; break; }
                        cp = cp * base + d;
                    }
                    if (ok && cp && cp < 0x110000) {
                        if (cp < 0x80) b->p[w++] = (char) cp;
                        else if (cp < 0x800) {
                            b->p[w++] = (char) (0xC0 | (cp >> 6));
                            b->p[w++] = (char) (0x80 | (cp & 0x3F));
                        } else if (cp < 0x10000) {
                            b->p[w++] = (char) (0xE0 | (cp >> 12));
                            b->p[w++] = (char) (0x80 | ((cp >> 6) & 0x3F));
                            b->p[w++] = (char) (0x80 | (cp & 0x3F));
                        } else {
                            b->p[w++] = (char) (0xF0 | (cp >> 18));
                            b->p[w++] = (char) (0x80 | ((cp >> 12) & 0x3F));
                            b->p[w++] = (char) (0x80 | ((cp >> 6) & 0x3F));
                            b->p[w++] = (char) (0x80 | (cp & 0x3F));
                        }
                    } else handled = 0;
                } else handled = 0;
                if (!handled) { b->p[w++] = b->p[r]; r++; }
                else r = j + 1;
                sp = 0;
                continue;
            }
        }
        if (ch == '\n' || ch == '\r' || ch == '\t' || ch == ' ') {
            if (w) sp = 1;
        } else {
            if (sp) { b->p[w++] = ' '; sp = 0; }
            b->p[w++] = ch;
        }
        r++;
    }
    /* хвостовой пробел убираем */
    while (w && b->p[w - 1] == ' ') w--;
    b->len = w;
}

/* ---------------------------------------------------------------- память */

static int ensure_items(book *b) {
    if (b->count < b->cap) return 1;
    size_t cap = b->cap ? b->cap * 2 : 512;
    blk *n = (blk *) realloc(b->items, cap * sizeof(blk));
    if (!n) return 0;
    b->items = n;
    b->cap = cap;
    return 1;
}

static int ensure_pool(book *b, size_t need) {
    if (b->pool && b->pool_cap >= need) return 1;
    size_t cap = b->pool_cap ? b->pool_cap : (256u * 1024u);
    while (cap < need) cap *= 2;
    char *p = (char *) realloc(b->pool, cap);
    if (!p) return 0;
    b->pool = p;
    b->pool_cap = cap;
    return 1;
}

static void push_block(book *b, blk_type t, const char *s, size_t len) {
    if (b->error) return;
    if (!ensure_items(b)) { b->error = ERR_OOM; return; }
    if (!ensure_pool(b, b->pool_len + len + 1)) { b->error = ERR_OOM; return; }
    char *dst = b->pool + b->pool_len;
    if (len) memcpy(dst, s, len);
    dst[len] = '\0';
    b->pool_len += len + 1;
    b->items[b->count].type = t;
    b->items[b->count].text = dst;
    b->items[b->count].len = len;
    b->count++;
}

void book_free(book *b) {
    if (!b) return;
    free(b->items);
    free(b->pool);
    free(b->title);
    free(b->author);
    free(b->lang);
    free(b->cover_href);
    memset(b, 0, sizeof(*b));
}

/* ---------------------------------------------------------------- разбор */

/* Классификация тегов: свой enum на каждый значимый, остальные = 0. */
enum {
    TAG_NONE = 0,
    TAG_P, TAG_V, TAG_TEXT_AUTHOR, TAG_EMPTY_LINE,
    TAG_TITLE, TAG_SUBTITLE, TAG_SECTION,
    TAG_EPIGRAPH, TAG_POEM, TAG_ANNOTATION,
    TAG_BODY, TAG_DESCRIPTION,
    TAG_BOOK_TITLE, TAG_LANG, TAG_BINARY,
    TAG_FIRST_NAME, TAG_MIDDLE_NAME, TAG_LAST_NAME, TAG_NICKNAME,
    TAG_COVERPAGE, TAG_IMAGE
};

static int tag_of(const char *n) {
    switch (n[0]) {
        case 'p': return ci_eq(n, "p") ? TAG_P : (ci_eq(n, "poem") ? TAG_POEM : TAG_NONE);
        case 'v': return ci_eq(n, "v") ? TAG_V : TAG_NONE;
        case 'b': return ci_eq(n, "body") ? TAG_BODY
                      : (ci_eq(n, "book-title") ? TAG_BOOK_TITLE
                      : (ci_eq(n, "binary") ? TAG_BINARY : TAG_NONE));
        case 's': return ci_eq(n, "section") ? TAG_SECTION
                      : (ci_eq(n, "subtitle") ? TAG_SUBTITLE : TAG_NONE);
        case 't': return ci_eq(n, "title") ? TAG_TITLE
                      : (ci_eq(n, "text-author") ? TAG_TEXT_AUTHOR : TAG_NONE);
        case 'e': return ci_eq(n, "epigraph") ? TAG_EPIGRAPH
                      : (ci_eq(n, "empty-line") ? TAG_EMPTY_LINE : TAG_NONE);
        case 'a': return ci_eq(n, "annotation") ? TAG_ANNOTATION : TAG_NONE;
        case 'd': return ci_eq(n, "description") ? TAG_DESCRIPTION : TAG_NONE;
        case 'l': return ci_eq(n, "lang") ? TAG_LANG
                      : (ci_eq(n, "last-name") ? TAG_LAST_NAME : TAG_NONE);
        case 'f': return ci_eq(n, "first-name") ? TAG_FIRST_NAME : TAG_NONE;
        case 'm': return ci_eq(n, "middle-name") ? TAG_MIDDLE_NAME : TAG_NONE;
        case 'n': return ci_eq(n, "nickname") ? TAG_NICKNAME : TAG_NONE;
        case 'c': return ci_eq(n, "coverpage") ? TAG_COVERPAGE : TAG_NONE;
        case 'i': return ci_eq(n, "image") ? TAG_IMAGE : TAG_NONE;
        default: return TAG_NONE;
    }
}

typedef struct {
    int open[64];
    int depth;
} stack;

static int top_of(const stack *s) {
    for (int i = s->depth - 1; i >= 0; i--) {
        int t = s->open[i];
        if (t == TAG_TITLE || t == TAG_SUBTITLE || t == TAG_EPIGRAPH ||
            t == TAG_POEM || t == TAG_ANNOTATION) return t;
    }
    return TAG_NONE;
}

static void stack_push(stack *s, int tag) {
    if (s->depth < (int) (sizeof(s->open) / sizeof(s->open[0]))) s->open[s->depth++] = tag;
}

static void stack_pop(stack *s, int tag) {
    for (int i = s->depth - 1; i >= 0; i--) {
        if (s->open[i] == tag) { s->depth = i; return; }
    }
}

/* Значение атрибута по имени; возвращает malloc-строку или NULL. */
static char *attr_value(const char *tag_start, const char *tag_end, const char *name) {
    size_t nl = strlen(name);
    const char *p = tag_start;
    while (p + nl < tag_end) {
        if ((p == tag_start || (unsigned char) p[-1] <= ' ') &&
            !strncasecmp(p, name, nl) &&
            (p[nl] == '=' || (unsigned char) p[nl] <= ' ')) {
            const char *q = p + nl;
            while (q < tag_end && *q != '=') q++;
            if (q >= tag_end) return NULL;
            q++;
            while (q < tag_end && (unsigned char) *q <= ' ') q++;
            if (q >= tag_end) return NULL;
            char quote = *q;
            if (quote == '"' || quote == '\'') {
                q++;
                const char *e = q;
                while (e < tag_end && *e != quote) e++;
                return dup_trim(q, (size_t) (e - q));
            }
        }
        p++;
    }
    return NULL;
}

int book_parse_fb2(book *b, const char *xml, size_t len) {
    if (!b) return ERR_FORMAT;
    memset(b, 0, sizeof(*b));
    if (!xml || len < 16) { b->error = ERR_FORMAT; return ERR_FORMAT; }

    const char *p = xml;
    const char *end = xml + len;

    stack st;
    memset(&st, 0, sizeof(st));

    buf text;
    memset(&text, 0, sizeof(text));

    buf author;
    memset(&author, 0, sizeof(author));

    int in_desc = 0;
    int block_open = 0;
    blk_type block_type = BLK_PARA;
    int in_coverpage = 0;

    while (p < end) {
        if (*p != '<') {
            const char *q = p;
            while (q < end && *q != '<') q++;
            if (block_open && !b->error) {
                if (!buf_reserve(&text, (size_t) (q - p))) { b->error = ERR_OOM; break; }
                memcpy(text.p + text.len, p, (size_t) (q - p));
                text.len += (size_t) (q - p);
            }
            p = q;
            continue;
        }

        if (end - p >= 4 && !memcmp(p, "<!--", 4)) {
            const char *q = p + 4;
            while (q + 3 <= end && memcmp(q, "-->", 3)) q++;
            p = (q + 3 <= end) ? q + 3 : end;
            continue;
        }
        if (end - p >= 2 && p[1] == '?') {
            const char *q = p + 2;
            while (q + 1 < end && !(q[0] == '?' && q[1] == '>')) q++;
            p = (q + 1 < end) ? q + 2 : end;
            continue;
        }
        if (end - p >= 9 && !memcmp(p, "<!DOCTYPE", 9)) {
            const char *q = p;
            int d = 0;
            while (q < end) {
                if (*q == '<') d++;
                else if (*q == '>') { d--; if (!d) { q++; break; } }
                q++;
            }
            p = q;
            continue;
        }

        /* закрывающий тег */
        if (end - p >= 2 && p[1] == '/') {
            const char *q = p + 2;
            const char *n = q;
            while (q < end && *q != '>') q++;
            if (q >= end) break;
            size_t nl = (size_t) (q - n);
            char name[64];
            if (nl >= sizeof(name)) nl = sizeof(name) - 1;
            for (size_t i = 0; i < nl; i++) name[i] = (char) tolower((unsigned char) n[i]);
            name[nl] = '\0';
            int tag = tag_of(name);
            p = q + 1;

            if (tag == TAG_P || tag == TAG_V || tag == TAG_TEXT_AUTHOR) {
                if (block_open) {
                    buf_normalize(&text);
                    if (text.len) push_block(b, block_type, text.p, text.len);
                    buf_reset(&text);
                    block_open = 0;
                }
            } else {
                if (tag == TAG_DESCRIPTION) in_desc = 0;
                if (tag == TAG_COVERPAGE) in_coverpage = 0;
                stack_pop(&st, tag);
            }
            continue;
        }

        /* открывающий тег */
        const char *q = p + 1;
        const char *n = q;
        while (q < end && *q != '>' && *q != '/' && (unsigned char) *q > ' ') q++;
        size_t nl = (size_t) (q - n);
        char name[64];
        if (nl >= sizeof(name)) nl = sizeof(name) - 1;
        for (size_t i = 0; i < nl; i++) name[i] = (char) tolower((unsigned char) n[i]);
        name[nl] = '\0';

        const char *gt = q;
        while (gt < end && *gt != '>') gt++;
        if (gt >= end) break;
        int self_close = (gt > q && gt[-1] == '/');
        const char *body_start = gt + 1;
        int tag = tag_of(name);
        p = body_start;

        if (tag == TAG_BINARY) {
            const char *z = body_start;
            while (z + 9 <= end && !(z[0] == '<' && z[1] == '/' &&
                   !strncasecmp(z + 2, "binary", 6))) z++;
            p = (z + 9 <= end) ? z + 9 : end;
            continue;
        }

        if (tag == TAG_IMAGE) {
            if (in_coverpage && !b->cover_href) {
                char *href = attr_value(q, gt, "href");
                if (!href) href = attr_value(q, gt, "xlink:href");
                if (href) b->cover_href = href;
            }
            continue;
        }

        if (tag == TAG_P || tag == TAG_V || tag == TAG_TEXT_AUTHOR) {
            if (self_close) continue;
            block_open = 1;
            buf_reset(&text);
            int ctx = top_of(&st);
            block_type = (tag == TAG_V) ? BLK_POEM
                       : (tag == TAG_TEXT_AUTHOR) ? BLK_EPIGRAPH
                       : (ctx == TAG_TITLE) ? BLK_TITLE
                       : (ctx == TAG_SUBTITLE) ? BLK_SUBTITLE
                       : (ctx == TAG_EPIGRAPH) ? BLK_EPIGRAPH
                       : (ctx == TAG_ANNOTATION) ? BLK_ANNOTATION
                       : (ctx == TAG_POEM) ? BLK_POEM : BLK_PARA;
            continue;
        }

        if (tag == TAG_EMPTY_LINE) {
            if (!self_close) stack_push(&st, tag);
            push_block(b, BLK_EMPTY, NULL, 0);
            continue;
        }

        if (tag == TAG_COVERPAGE) {
            in_coverpage = 1;
            if (!self_close) stack_push(&st, tag);
            continue;
        }
        if (tag == TAG_DESCRIPTION) {
            in_desc = 1;
            if (!self_close) stack_push(&st, tag);
            continue;
        }
        if (!self_close) stack_push(&st, tag);

        /* Метаданные — только из description, чтобы не поймать <title> тела. */
        if (tag == TAG_BOOK_TITLE && in_desc && !b->title) {
            const char *z = body_start;
            while (z < end && *z != '<') z++;
            b->title = dup_trim(body_start, (size_t) (z - body_start));
        } else if (tag == TAG_LANG && in_desc && !b->lang) {
            const char *z = body_start;
            while (z < end && *z != '<') z++;
            b->lang = dup_trim(body_start, (size_t) (z - body_start));
        } else if ((tag == TAG_FIRST_NAME || tag == TAG_MIDDLE_NAME ||
                    tag == TAG_LAST_NAME || tag == TAG_NICKNAME) && in_desc && !self_close) {
            const char *z = body_start;
            while (z < end && *z != '<') z++;
            if (z > body_start) {
                if (!buf_reserve(&author, (size_t) (z - body_start) + 2)) {
                    b->error = ERR_OOM;
                    break;
                }
                if (author.len) author.p[author.len++] = ' ';
                memcpy(author.p + author.len, body_start, (size_t) (z - body_start));
                author.len += (size_t) (z - body_start);
            }
        }
    }

    if (!b->error && author.len) {
        buf_normalize(&author);
        if (author.len) b->author = dup_trim(author.p, author.len);
    }
    free(text.p);
    free(author.p);

    if (b->error) return b->error;
    if (!b->count && !b->title) {
        b->error = ERR_FORMAT;
        return ERR_FORMAT;
    }
    return 0;
}

/* ------------------------------------------------- метаданные (title-info) */

/*
 * Отдельный проход, а не флаг в book_parse_fb2: сканеру библиотеки нужны только
 * поля <title-info>, и читать для них тело книги на 5 МБ незачем. Разбор идёт
 * по началу файла и останавливается на </title-info>. <author> из <document-info>
 * (автор FB2-файла, а не книги) и <src-title-info> (оригинал перевода) не берутся.
 */

enum { ENC_UTF8 = 0, ENC_CP1251, ENC_LATIN1 };

#define META_FIELD_IN  4096   /* больше сырых байт на поле не читаем */
#define META_FIELD_OUT 1024   /* больше байт в поле не отдаём */

/* windows-1251, байты 0x80..0xFF -> Unicode (0x98 в кодировке не занят). */
static const unsigned short CP1251[128] = {
    0x0402, 0x0403, 0x201A, 0x0453, 0x201E, 0x2026, 0x2020, 0x2021,
    0x20AC, 0x2030, 0x0409, 0x2039, 0x040A, 0x040C, 0x040B, 0x040F,
    0x0452, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014,
    0xFFFD, 0x2122, 0x0459, 0x203A, 0x045A, 0x045C, 0x045B, 0x045F,
    0x00A0, 0x040E, 0x045E, 0x0408, 0x00A4, 0x0490, 0x00A6, 0x00A7,
    0x0401, 0x00A9, 0x0404, 0x00AB, 0x00AC, 0x00AD, 0x00AE, 0x0407,
    0x00B0, 0x00B1, 0x0406, 0x0456, 0x0491, 0x00B5, 0x00B6, 0x00B7,
    0x0451, 0x2116, 0x0454, 0x00BB, 0x0458, 0x0405, 0x0455, 0x0457,
    0x0410, 0x0411, 0x0412, 0x0413, 0x0414, 0x0415, 0x0416, 0x0417,
    0x0418, 0x0419, 0x041A, 0x041B, 0x041C, 0x041D, 0x041E, 0x041F,
    0x0420, 0x0421, 0x0422, 0x0423, 0x0424, 0x0425, 0x0426, 0x0427,
    0x0428, 0x0429, 0x042A, 0x042B, 0x042C, 0x042D, 0x042E, 0x042F,
    0x0430, 0x0431, 0x0432, 0x0433, 0x0434, 0x0435, 0x0436, 0x0437,
    0x0438, 0x0439, 0x043A, 0x043B, 0x043C, 0x043D, 0x043E, 0x043F,
    0x0440, 0x0441, 0x0442, 0x0443, 0x0444, 0x0445, 0x0446, 0x0447,
    0x0448, 0x0449, 0x044A, 0x044B, 0x044C, 0x044D, 0x044E, 0x044F,
};

/* Локальное имя без префикса пространства имён: "fb:book-title" -> "book-title". */
static const char *local_name(const char *n) {
    const char *c = strrchr(n, ':');
    return c ? c + 1 : n;
}

/*
 * Атрибут по локальному имени (href найдётся и как l:href, и как xlink:href).
 * [s, e) — внутренность тега после имени. Отдаёт указатель и длину значения.
 */
static int attr_find(const char *s, const char *e, const char *local,
                     const char **val, size_t *vlen) {
    size_t ll = strlen(local);
    const char *p = s;
    while (p < e) {
        while (p < e && ((unsigned char) *p <= ' ' || *p == '/')) p++;
        const char *n = p;
        while (p < e && *p != '=' && (unsigned char) *p > ' ' && *p != '/') p++;
        const char *ne = p;
        while (p < e && (unsigned char) *p <= ' ') p++;
        if (p >= e || *p != '=') continue;        /* атрибут без значения */
        p++;
        while (p < e && (unsigned char) *p <= ' ') p++;
        if (p >= e) return 0;
        const char *v, *ve;
        if (*p == '"' || *p == '\'') {
            char qc = *p++;
            v = p;
            while (p < e && *p != qc) p++;
            ve = p;
            if (p < e) p++;
        } else {
            v = p;
            while (p < e && (unsigned char) *p > ' ') p++;
            ve = p;
        }
        const char *ln = ne;
        while (ln > n && ln[-1] != ':') ln--;
        if ((size_t) (ne - ln) == ll && !strncasecmp(ln, local, ll)) {
            *val = v;
            *vlen = (size_t) (ve - v);
            return 1;
        }
    }
    return 0;
}

/* Кодовая точка до U+FFFF в UTF-8 (1–3 байта). */
static int put_bmp(buf *o, unsigned cp) {
    if (!buf_reserve(o, 3)) return 0;
    if (cp < 0x80) o->p[o->len++] = (char) cp;
    else if (cp < 0x800) {
        o->p[o->len++] = (char) (0xC0 | (cp >> 6));
        o->p[o->len++] = (char) (0x80 | (cp & 0x3F));
    } else {
        o->p[o->len++] = (char) (0xE0 | (cp >> 12));
        o->p[o->len++] = (char) (0x80 | ((cp >> 6) & 0x3F));
        o->p[o->len++] = (char) (0x80 | (cp & 0x3F));
    }
    return 1;
}



/*
 * Сырые байты поля -> malloc-строка UTF-8: вложенные теги выброшены, кодировка
 * перекодирована, сущности раскрыты, пробелы схлопнуты, длина ограничена.
 * *oom = 1 при нехватке памяти; NULL без oom — поле пустое.
 */
static char *meta_field(const char *s, size_t n, int enc, int *oom) {
    buf o;
    memset(&o, 0, sizeof(o));
    if (n > META_FIELD_IN) n = META_FIELD_IN;
    for (size_t i = 0; i < n; i++) {
        unsigned char c = (unsigned char) s[i];
        if (c == '<') {                       /* <emphasis> и т. п.: тег выбросить, текст склеить */
            while (i < n && s[i] != '>') i++;
            continue;
        }
        if (c < 0x80 || enc == ENC_UTF8) {
            if (!buf_reserve(&o, 1)) { *oom = 1; free(o.p); return NULL; }
            o.p[o.len++] = (char) c;
        } else {
            unsigned cp = (enc == ENC_CP1251) ? CP1251[c - 0x80] : c;
            if (!put_bmp(&o, cp)) { *oom = 1; free(o.p); return NULL; }
        }
    }
    buf_normalize(&o);
    if (o.len > META_FIELD_OUT) {
        size_t k = META_FIELD_OUT;
        while (k && ((unsigned char) o.p[k] & 0xC0) == 0x80) k--;   /* не рвать символ */
        o.len = k;
    }
    char *r = NULL;
    if (o.len) {
        r = dup_trim(o.p, o.len);
        if (!r) *oom = 1;
        else if (!r[0]) { free(r); r = NULL; }
    }
    free(o.p);
    return r;
}

/* Текст элемента от конца открывающего тега до </qname>; NULL, если не закрыт. */
static char *meta_elem(const char *body, const char *end, const char *qname,
                       int enc, int *oom) {
    size_t ql = strlen(qname);
    for (const char *z = body; z + 2 + ql < end; z++) {
        if (z[0] == '<' && z[1] == '/' && !strncasecmp(z + 2, qname, ql) &&
            (z[2 + ql] == '>' || (unsigned char) z[2 + ql] <= ' '))
            return meta_field(body, (size_t) (z - body), enc, oom);
    }
    return NULL;
}

static char *meta_attr(const char *s, const char *e, const char *local, int enc, int *oom) {
    const char *v;
    size_t vl;
    if (!attr_find(s, e, local, &v, &vl)) return NULL;
    return meta_field(v, vl, enc, oom);
}

/* Кодировка из BOM и <?xml encoding?>; -1 — не поддерживается. *start — после BOM. */
static int meta_encoding(const char *xml, size_t len, const char **start) {
    const unsigned char *u = (const unsigned char *) xml;
    *start = xml;
    if (len >= 3 && u[0] == 0xEF && u[1] == 0xBB && u[2] == 0xBF) {
        *start = xml + 3;
        return ENC_UTF8;
    }
    if (len >= 2 && ((u[0] == 0xFF && u[1] == 0xFE) || (u[0] == 0xFE && u[1] == 0xFF) ||
                     u[0] == 0 || u[1] == 0)) return -1;               /* UTF-16/32 */
    const char *end = xml + len;
    const char *p = xml;
    while (p < end && (unsigned char) *p <= ' ') p++;
    if (end - p < 5 || memcmp(p, "<?xml", 5)) return ENC_UTF8;
    const char *q = p + 5;
    while (q + 1 < end && q - p < 512 && !(q[0] == '?' && q[1] == '>')) q++;
    const char *v;
    size_t vl;
    if (!attr_find(p + 5, q, "encoding", &v, &vl)) return ENC_UTF8;
    char e[24];
    if (vl >= sizeof(e)) return -1;
    for (size_t i = 0; i < vl; i++) e[i] = (char) tolower((unsigned char) v[i]);
    e[vl] = '\0';
    if (!strcmp(e, "utf-8") || !strcmp(e, "utf8") || !strcmp(e, "us-ascii") ||
        !strcmp(e, "ascii")) return ENC_UTF8;
    if (!strcmp(e, "windows-1251") || !strcmp(e, "cp1251") || !strcmp(e, "win-1251") ||
        !strcmp(e, "cp-1251")) return ENC_CP1251;
    if (!strcmp(e, "iso-8859-1") || !strcmp(e, "latin1") || !strcmp(e, "latin-1"))
        return ENC_LATIN1;
    return -1;                                                     /* koi8-r и пр. */
}

static int buf_add(buf *b, const char *s) {
    size_t n = strlen(s);
    if (!buf_reserve(b, n)) return 0;
    memcpy(b->p + b->len, s, n);
    b->len += n;
    b->p[b->len] = '\0';
    return 1;
}

void book_meta_free(book_meta *m) {
    if (!m) return;
    free(m->title);
    free(m->authors);
    free(m->first_name);
    free(m->middle_name);
    free(m->last_name);
    free(m->nickname);
    free(m->series);
    free(m->lang);
    free(m->cover_id);
    memset(m, 0, sizeof(*m));
}


/* Закрыт <author>: дописать «Имя Отчество Фамилия» (или ник) в список. */
static int meta_author_done(book_meta *m, buf *authors, char *part[4], int *n_authors) {
    int named = part[0] || part[1] || part[2];
    if (!named && !part[3]) return 1;
    if (authors->len && !buf_add(authors, ", ")) return 0;
    int first = 1;
    for (int i = 0; i < 3; i++) {
        if (!part[i]) continue;
        if ((!first && !buf_add(authors, " ")) || !buf_add(authors, part[i])) return 0;
        first = 0;
    }
    if (!named && !buf_add(authors, part[3])) return 0;
    if (!(*n_authors)++) {                      /* части имени — только у первого */
        m->first_name = part[0];
        m->middle_name = part[1];
        m->last_name = part[2];
        m->nickname = part[3];
        part[0] = part[1] = part[2] = part[3] = NULL;
    }
    return 1;
}

int book_meta_fb2(book_meta *m, const char *xml, size_t len) {
    if (!m) return ERR_FORMAT;
    memset(m, 0, sizeof(*m));
    if (!xml || len < 16) { m->error = ERR_FORMAT; return ERR_FORMAT; }

    const char *p;
    int enc = meta_encoding(xml, len, &p);
    if (enc < 0) { m->error = ERR_ENCODING; return ERR_ENCODING; }
    const char *end = xml + len;

    int oom = 0, in_ti = 0, seen_ti = 0, in_author = 0, in_cover = 0, n_authors = 0;
    char *part[4] = { NULL, NULL, NULL, NULL };   /* first, middle, last, nickname */
    buf authors;
    memset(&authors, 0, sizeof(authors));

    while (p < end && !oom) {
        if (*p != '<') {
            const char *q = memchr(p, '<', (size_t) (end - p));
            p = q ? q : end;
            continue;
        }
        if (end - p >= 4 && !memcmp(p, "<!--", 4)) {
            const char *q = p + 4;
            while (q + 3 <= end && memcmp(q, "-->", 3)) q++;
            p = (q + 3 <= end) ? q + 3 : end;
            continue;
        }
        if (end - p >= 2 && (p[1] == '?' || p[1] == '!')) {   /* <?xml?>, DOCTYPE */
            const char *q = memchr(p, '>', (size_t) (end - p));
            p = q ? q + 1 : end;
            continue;
        }

        int closing = (end - p >= 2 && p[1] == '/');
        const char *n = p + (closing ? 2 : 1);
        const char *q = n;
        while (q < end && *q != '>' && *q != '/' && (unsigned char) *q > ' ') q++;
        size_t nl = (size_t) (q - n);
        char qname[64];
        if (nl >= sizeof(qname)) nl = sizeof(qname) - 1;
        for (size_t i = 0; i < nl; i++) qname[i] = (char) tolower((unsigned char) n[i]);
        qname[nl] = '\0';
        const char *name = local_name(qname);

        const char *gt = memchr(q, '>', (size_t) (end - q));
        if (!gt) break;                               /* начало файла обрезано посреди тега */
        int self_close = (gt > q && gt[-1] == '/');
        const char *body = gt + 1;
        p = body;

        if (closing) {
            if (!in_ti) continue;
            if (!strcmp(name, "title-info")) { m->complete = 1; break; }
            if (!strcmp(name, "coverpage")) in_cover = 0;
            else if (!strcmp(name, "author") && in_author) {
                in_author = 0;
                if (!meta_author_done(m, &authors, part, &n_authors)) oom = 1;
                for (int i = 0; i < 4; i++) { free(part[i]); part[i] = NULL; }
            }
            continue;
        }

        /* <body> или <binary> до </title-info> — description битый, дальше искать нечего. */
        if (!strcmp(name, "body") || !strcmp(name, "binary")) break;
        if (!strcmp(name, "title-info")) {
            if (!self_close && !seen_ti) in_ti = seen_ti = 1;
            continue;
        }
        if (!in_ti) continue;

        if (!strcmp(name, "author")) {
            if (!self_close) {
                in_author = 1;
                for (int i = 0; i < 4; i++) { free(part[i]); part[i] = NULL; }
            }
        } else if (in_author && !self_close &&
                   (!strcmp(name, "first-name") || !strcmp(name, "middle-name") ||
                    !strcmp(name, "last-name") || !strcmp(name, "nickname"))) {
            int i = name[0] == 'f' ? 0 : name[0] == 'm' ? 1 : name[0] == 'l' ? 2 : 3;
            if (!part[i]) part[i] = meta_elem(body, end, qname, enc, &oom);
        } else if (!strcmp(name, "book-title") && !self_close && !m->title) {
            m->title = meta_elem(body, end, qname, enc, &oom);
        } else if (!strcmp(name, "lang") && !self_close && !m->lang) {
            m->lang = meta_elem(body, end, qname, enc, &oom);
        } else if (!strcmp(name, "sequence") && !m->series) {
            m->series = meta_attr(q, gt, "name", enc, &oom);
            const char *v;
            size_t vl;
            if (m->series && attr_find(q, gt, "number", &v, &vl)) {
                int num = 0;
                for (size_t i = 0; i < vl && v[i] >= '0' && v[i] <= '9' && num < 100000; i++)
                    num = num * 10 + (v[i] - '0');
                m->series_num = num;
            }
        } else if (!strcmp(name, "coverpage")) {
            if (!self_close) in_cover = 1;
        } else if (!strcmp(name, "image") && in_cover && !m->cover_id) {
            char *href = meta_attr(q, gt, "href", enc, &oom);
            if (href && href[0] == '#') memmove(href, href + 1, strlen(href));
            if (href && !href[0]) { free(href); href = NULL; }
            m->cover_id = href;
        }
    }

    for (int i = 0; i < 4; i++) free(part[i]);
    if (!oom && authors.len) {
        m->authors = dup_trim(authors.p, authors.len);
        if (!m->authors) oom = 1;
    }
    free(authors.p);

    if (oom) { book_meta_free(m); m->error = ERR_OOM; return ERR_OOM; }
    if (!seen_ti) { m->error = ERR_FORMAT; return ERR_FORMAT; }
    return 0;
}

