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

static int eq(const char *a, const char *b) {
    if (!a || !b) return a == b;
    return strcmp(a, b) == 0;
}

static int meta_of(book_meta *m, const char *xml) {
    return book_meta_fb2(m, xml, strlen(xml));
}

/* Разбор из буфера ровно в len байт: ASan поймает чтение за концом. */
static int meta_exact(book_meta *m, const char *xml, size_t len) {
    char *p = (char *) malloc(len ? len : 1);
    if (len) memcpy(p, xml, len);
    int rc = book_meta_fb2(m, p, len);
    free(p);
    return rc;
}

/* ---------------------------------------------- метаданные <title-info> */
static void test_meta_main(const char *path) {
    book_meta m;
    int rc;

    /* 1. Образец: все поля, разбор кончается на </title-info>. */
    size_t len = 0;
    char *xml = slurp(path, &len);
    rc = meta_exact(&m, xml, len);
    printf("meta rc=%d title=%s authors=%s lang=%s cover=%s complete=%d\n", rc,
           m.title ? m.title : "-", m.authors ? m.authors : "-",
           m.lang ? m.lang : "-", m.cover_id ? m.cover_id : "-", m.complete);
    check(rc == 0 && m.complete, "meta: sample parsed to </title-info>");
    check(eq(m.title, "Мастер и Маргарита"), "meta: title");
    check(eq(m.authors, "Михаил Афанасьевич Булгаков"), "meta: author full name");
    check(eq(m.first_name, "Михаил") && eq(m.middle_name, "Афанасьевич") &&
          eq(m.last_name, "Булгаков") && !m.nickname, "meta: author parts");
    check(eq(m.lang, "ru"), "meta: lang");
    check(eq(m.cover_id, "cover.jpg"), "meta: cover id without #");
    check(!m.series && m.series_num == 0, "meta: no series");
    book_meta_free(&m);

    /* 2. Обрезанное начало файла: то, что успели, без ошибки. */
    rc = meta_exact(&m, xml, (size_t) (strstr(xml, "<lang>") - xml));
    check(rc == 0 && !m.complete && eq(m.title, "Мастер и Маргарита") && !m.lang,
          "meta: truncated head -> partial, complete=0");
    book_meta_free(&m);
    rc = meta_exact(&m, xml, (size_t) (strstr(xml, "<book-title>") - xml) + 5);
    check(rc == 0 && !m.title && m.authors, "meta: cut inside tag -> no crash");
    book_meta_free(&m);

    /* Все префиксы образца — без падений и утечек (ASan). */
    for (size_t k = 0; k <= len; k++) {
        meta_exact(&m, xml, k);
        book_meta_free(&m);
    }
    check(1, "meta: all prefixes of sample survived");
    free(xml);

    /* 3. Несколько авторов, ник, серия, сущности, тег внутри, автор документа. */
    rc = meta_of(&m,
        "<?xml version='1.0' encoding='utf-8'?>\n<FictionBook><description><title-info>"
        "<author><first-name>Аркадий</first-name><last-name>Стругацкий</last-name></author>"
        "<author>\n  <first-name> Борис </first-name>\n  <last-name>Стругацкий</last-name>\n</author>"
        "<author><nickname>Ник</nickname></author>"
        "<book-title>Пикник &amp; &#171;обочина&#xBB; <emphasis>ещё</emphasis>&nbsp;раз"
        " Мир<sup>2</sup></book-title>"
        "<sequence name=\"Мир &quot;Полудня&quot;\" number=\"12\"/>"
        "<lang>ru</lang></title-info>"
        "<document-info><author><nickname>верстальщик</nickname></author></document-info>"
        "</description><body><title><p>Не то</p></title></body></FictionBook>");
    check(rc == 0 && m.complete, "meta: multi rc");
    check(eq(m.authors, "Аркадий Стругацкий, Борис Стругацкий, Ник"), "meta: authors list");
    check(eq(m.first_name, "Аркадий") && eq(m.last_name, "Стругацкий"), "meta: first author parts");
    check(eq(m.title, "Пикник & «обочина» ещё раз Мир2"), "meta: entities and inner tags");
    check(eq(m.series, "Мир \"Полудня\"") && m.series_num == 12, "meta: sequence");
    book_meta_free(&m);

    /* 4. windows-1251 и префиксы пространства имён. */
    rc = meta_of(&m,
        "<?xml version=\"1.0\" encoding=\"windows-1251\"?>"
        "<fb:FictionBook xmlns:fb='x' xmlns:xlink='y'><fb:description><fb:title-info>"
        "<fb:author><fb:last-name>\xD7\xE5\xF5\xEE\xE2</fb:last-name></fb:author>"
        "<fb:book-title>\xD1\xE0\xF5\xE0\xEB\xE8\xED \xB9 1 \xAB\xB8\xBB</fb:book-title>"
        "<fb:coverpage><fb:image xlink:href='#c1.png'/></fb:coverpage>"
        "</fb:title-info></fb:description></fb:FictionBook>");
    check(rc == 0 && eq(m.last_name, "Чехов") && eq(m.authors, "Чехов"), "meta: cp1251 author");
    check(eq(m.title, "Сахалин № 1 «ё»"), "meta: cp1251 title");
    check(eq(m.cover_id, "c1.png"), "meta: xlink:href with prefix");
    book_meta_free(&m);
}
static void test_meta_edge(void) {
    book_meta m;
    int rc;

    /* 5. BOM; src-title-info (оригинал перевода) не мешает. */
    rc = meta_of(&m,
        "\xEF\xBB\xBF<FictionBook><description><title-info><book-title>Улисс</book-title>"
        "</title-info><src-title-info><book-title>Ulysses</book-title></src-title-info>"
        "</description></FictionBook>");
    check(rc == 0 && eq(m.title, "Улисс") && !m.authors, "meta: BOM, src-title-info ignored");
    book_meta_free(&m);

    /* 6. Ошибки. */
    rc = meta_of(&m, "<?xml version='1.0' encoding='koi8-r'?><FictionBook/>");
    check(rc != 0 && !m.title, "meta: koi8-r rejected");
    book_meta_free(&m);
    rc = meta_exact(&m, "\xFF\xFE<\0F\0i\0c\0t\0i\0o\0n\0B\0o\0o\0k\0", 24);
    check(rc != 0, "meta: utf-16 rejected");
    book_meta_free(&m);
    rc = meta_of(&m, "<html><body><p>not a book at all</p></body></html>");
    check(rc != 0, "meta: no title-info -> error");
    book_meta_free(&m);
    rc = meta_exact(&m, "<", 1);
    check(rc != 0, "meta: tiny input -> error");
    book_meta_free(&m);

    /* 7. Очень длинное поле режется по границе символа. */
    size_t big = 20000;
    char *long_xml = (char *) malloc(big + 200);
    strcpy(long_xml, "<FictionBook><description><title-info><book-title>");
    char *w = long_xml + strlen(long_xml);
    for (size_t i = 0; i < big / 2; i++) { *w++ = '\xD0'; *w++ = '\xAF'; }   /* «Я» */
    strcpy(w, "</book-title></title-info></description></FictionBook>");
    rc = meta_of(&m, long_xml);
    check(rc == 0 && m.title && strlen(m.title) <= 1024 && strlen(m.title) % 2 == 0,
          "meta: long field capped on char boundary");
    book_meta_free(&m);
    free(long_xml);

    /* 8. Мусор из «опасных» символов — без падений и утечек (ASan). */
    static const char alphabet[] = "<>/=\"' a:-#&;title-info\xD0\xAF";
    unsigned seed = 1;
    char noise[512];
    for (int round = 0; round < 2000; round++) {
        for (size_t i = 0; i < sizeof(noise); i++) {
            seed = seed * 1103515245u + 12345u;
            noise[i] = alphabet[(seed >> 16) % (sizeof(alphabet) - 1)];
        }
        size_t n = 16 + (seed >> 8) % (sizeof(noise) - 16);
        memcpy(noise, "<title-info>", 12);
        meta_exact(&m, noise, n);
        book_meta_free(&m);
    }
    check(1, "meta: fuzz survived");
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

    /* пробел перед сущностью сохраняется */
    int amp_ok = 0;
    for (size_t i = 0; i < b.count; i++)
        if (b.items[i].text && strstr(b.items[i].text, "амперсандом & кавычками")) amp_ok = 1;
    check(amp_ok, "space before entity kept");

    /* повторный разбор того же документа должен дать те же числа (нет утечки состояния) */
    book b2;
    int rc2 = book_parse_fb2(&b2, xml, len);
    check(rc2 == 0 && b2.count == b.count, "deterministic re-parse");

    book_free(&b);
    book_free(&b2);
    free(xml);

    test_meta_main(path);
    test_meta_edge();

    printf(failures ? "\n%d FAILURES\n" : "\nall good\n", failures);
    return failures ? 1 : 0;
}
