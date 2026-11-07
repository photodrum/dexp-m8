/*
 * Хост-тест ядра: собирается обычным gcc, без NDK и без устройства.
 *   make -C app/src/main/cpp test
 */
#include "book.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int failures = 0;

static void check(int cond, const char *what) {
    if (!cond) { printf("FAIL: %s\n", what); failures++; }
    else printf("ok:   %s\n", what);
}

static char *slurp(const char *path, size_t *out_len) {
    FILE *f = fopen(path, "rb");
    if (!f) return NULL;
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    char *p = (char *) malloc((size_t) n + 1);
    if (!p) { fclose(f); return NULL; }
    size_t got = fread(p, 1, (size_t) n, f);
    p[got] = '\0';
    fclose(f);
    *out_len = got;
    return p;
}

int main(int argc, char **argv) {
    const char *path = (argc > 1) ? argv[1] : "testdata/sample.fb2";
    size_t len = 0;
    char *xml = slurp(path, &len);
    if (!xml) { printf("FAIL: cannot read %s\n", path); return 2; }

    book b;
    int rc = book_parse_fb2(&b, xml, len);
    printf("parse rc=%d (%s) blocks=%zu title=%s author=%s lang=%s\n",
           rc, book_error_text(rc), b.count,
           b.title ? b.title : "-", b.author ? b.author : "-",
           b.lang ? b.lang : "-");

    check(rc == 0, "parse returns 0");
    check(b.title && strstr(b.title, "Мастер") != NULL, "book title extracted");
    check(b.author && strstr(b.author, "Булгаков") != NULL, "author extracted");

    int paras = 0, titles = 0, poems = 0;
    for (size_t i = 0; i < b.count; i++) {
        if (b.items[i].type == BLK_PARA) paras++;
        if (b.items[i].type == BLK_TITLE) titles++;
        if (b.items[i].type == BLK_POEM) poems++;
    }
    printf("paras=%d titles=%d poems=%d\n", paras, titles, poems);
    check(paras >= 3, "at least 3 paragraphs");
    check(titles >= 2, "at least 2 titles");

    /* все блоки — валидный UTF-8 и без незакрытых сущностей */
    int bad_utf8 = 0, bad_entity = 0;
    for (size_t i = 0; i < b.count; i++) {
        const char *s = b.items[i].text;
        if (!s) continue;
        for (size_t k = 0; k < b.items[i].len; k++) {
            unsigned char c = (unsigned char) s[k];
            if (c >= 0xF5) bad_utf8++;
        }
        if (strstr(s, "&amp;") || strstr(s, "&lt;")) bad_entity++;
    }
    check(bad_utf8 == 0, "utf8 sane");
    check(bad_entity == 0, "entities unescaped");

    /* повторный разбор того же документа должен дать те же числа (нет утечки состояния) */
    book b2;
    int rc2 = book_parse_fb2(&b2, xml, len);
    check(rc2 == 0 && b2.count == b.count, "deterministic re-parse");

    book_free(&b);
    book_free(&b2);
    free(xml);

    printf(failures ? "\n%d FAILURES\n" : "\nall good\n", failures);
    return failures ? 1 : 0;
}
