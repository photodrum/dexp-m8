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

const char *book_error_text(int code) {
    switch (code) {
        case 0: return "ok";
        case ERR_OOM: return "out of memory";
        case ERR_FORMAT: return "malformed xml";
        case ERR_TOO_BIG: return "document too large";
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
                else if (n == 4 && !memcmp(e, "nbsp", 4)) { b->p[w++] = ' '; }
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

