/*
 * JNI сеанса crengine (R1): `ru.ogrpfsense.dexpm8.reader.Engine` → `dexp_reader_*`
 * (`engines/cre_session.cpp`). Собирается в ту же .so, что и проба (`dexp_cre`);
 * DjVu-сеанс (R2) получит свою.
 *
 * Все методы вызываются из одного фонового потока (`Engine.worker`) — блокировок здесь
 * нет: `LVDocView` не потокобезопасен, порядок вызовов держит очередь в Kotlin.
 */
#include <jni.h>

#include "cre_gray.h"
#include "probe.h"

/**
 * UTF-8 → `jstring` через UTF-16. `NewStringUTF` здесь не годится: он ждёт Modified UTF-8,
 * а текст книги — обычный UTF-8, и 4-байтный символ роняет процесс (падеж на EPUB
 * 2026-11-13: «input is not valid Modified UTF-8»).
 */
static jstring utf8ToJString(JNIEnv *env, const char *s, int len)
{
    /* Худший случай: каждый байт — отдельный BMP-символ. */
    jchar *w = new jchar[(size_t)len + 1];
    int n = 0;
    for (int i = 0; i < len;) {
        unsigned char c = (unsigned char)s[i];
        unsigned cp;
        int extra;
        if (c < 0x80) {
            cp = c;
            extra = 0;
        } else if ((c & 0xE0) == 0xC0) {
            cp = c & 0x1F;
            extra = 1;
        } else if ((c & 0xF0) == 0xE0) {
            cp = c & 0x0F;
            extra = 2;
        } else if ((c & 0xF8) == 0xF0) {
            cp = c & 0x07;
            extra = 3;
        } else {
            i++;    // одиночный байт-продолжение: мусор, шагаем дальше
            continue;
        }
        if (i + extra >= len)       // обрезанный хвост
            break;
        bool ok = true;
        for (int k = 1; k <= extra; k++) {
            unsigned char cc = (unsigned char)s[i + k];
            if ((cc & 0xC0) != 0x80) {
                ok = false;
                break;
            }
            cp = (cp << 6) | (cc & 0x3F);
        }
        if (!ok) {
            i++;
            continue;
        }
        i += extra + 1;
        if (cp >= 0x10000) {        // вне BMP — пара суррогатов
            cp -= 0x10000;
            w[n++] = (jchar)(0xD800 + (cp >> 10));
            w[n++] = (jchar)(0xDC00 + (cp & 0x3FF));
        } else {
            w[n++] = (jchar)cp;
        }
    }
    jstring r = env->NewString(w, (jsize)n);
    delete[] w;
    return r;
}

extern "C" {

DEXP_PROBE_API int dexp_reader_open(const char *path, const char *cacheDir, int w, int h);
DEXP_PROBE_API int dexp_reader_close();
DEXP_PROBE_API int dexp_reader_page_count();
DEXP_PROBE_API int dexp_reader_cur_page();
DEXP_PROBE_API int dexp_reader_page(int page, int w, int h, unsigned char *gray);
DEXP_PROBE_API int dexp_reader_set_font(int size, const char *keepPos);
DEXP_PROBE_API int dexp_reader_pos(int page, char *out, int outLen);
DEXP_PROBE_API int dexp_reader_goto_pos(const char *pos);
DEXP_PROBE_API int dexp_reader_page_of(const char *pos);
DEXP_PROBE_API int dexp_reader_page_text(char *out, int outLen);
DEXP_PROBE_API int dexp_reader_toc_count();
DEXP_PROBE_API int dexp_reader_toc_level(int index);
DEXP_PROBE_API int dexp_reader_toc_page(int index);
DEXP_PROBE_API int dexp_reader_toc_string(int index, int which, char *out, int outLen);
DEXP_PROBE_API int dexp_reader_pos_on_page(const char *pos);

JNIEXPORT jint JNICALL
Java_ru_ogrpfsense_dexpm8_reader_Engine_openNative(JNIEnv *env, jclass cls,
        jstring path, jstring cacheDir, jint w, jint h)
{
    (void)cls;
    if (!path)
        return DEXP_PROBE_EARG;
    const char *p = env->GetStringUTFChars(path, NULL);
    if (!p)
        return DEXP_PROBE_EARG;
    const char *c = cacheDir ? env->GetStringUTFChars(cacheDir, NULL) : NULL;
    int rc = dexp_reader_open(p, c, w, h);
    if (c)
        env->ReleaseStringUTFChars(cacheDir, c);
    env->ReleaseStringUTFChars(path, p);
    return rc;
}

JNIEXPORT jint JNICALL
Java_ru_ogrpfsense_dexpm8_reader_Engine_closeNative(JNIEnv *env, jclass cls)
{
    (void)env;
    (void)cls;
    return dexp_reader_close();
}

JNIEXPORT jint JNICALL
Java_ru_ogrpfsense_dexpm8_reader_Engine_pageCountNative(JNIEnv *env, jclass cls)
{
    (void)env;
    (void)cls;
    return dexp_reader_page_count();
}

JNIEXPORT jint JNICALL
Java_ru_ogrpfsense_dexpm8_reader_Engine_curPageNative(JNIEnv *env, jclass cls)
{
    (void)env;
    (void)cls;
    return dexp_reader_cur_page();
}

JNIEXPORT jint JNICALL
Java_ru_ogrpfsense_dexpm8_reader_Engine_pageNative(JNIEnv *env, jclass cls,
        jint page, jint w, jint h, jbyteArray out)
{
    (void)cls;
    if (!out || w <= 0 || h <= 0)
        return DEXP_PROBE_EARG;
    if (env->GetArrayLength(out) < (jsize)w * (jsize)h)
        return DEXP_PROBE_EARG;
    /* Не Critical: отрисовка идёт десятки–сотни мс, GC блокировать нельзя. Большой
       массив в ART не перемещается, копии обычно нет. */
    jbyte *buf = env->GetByteArrayElements(out, NULL);
    if (!buf)
        return DEXP_PROBE_EARG;
    unsigned char *g = (unsigned char *)buf;
    int rc = dexp_reader_page(page, w, h, g);
    if (rc == DEXP_PROBE_OK)
        dexp_gray_to_palette(g, (size_t)w * (size_t)h);
    env->ReleaseByteArrayElements(out, buf, 0);
    return rc;
}

JNIEXPORT jint JNICALL
Java_ru_ogrpfsense_dexpm8_reader_Engine_setFontNative(JNIEnv *env, jclass cls,
        jint size, jstring keepPos)
{
    (void)cls;
    const char *k = keepPos ? env->GetStringUTFChars(keepPos, NULL) : NULL;
    int rc = dexp_reader_set_font(size, k);
    if (k)
        env->ReleaseStringUTFChars(keepPos, k);
    return rc;
}

JNIEXPORT jstring JNICALL
Java_ru_ogrpfsense_dexpm8_reader_Engine_posNative(JNIEnv *env, jclass cls, jint page)
{
    (void)cls;
    /* xpointer — строка из индексов узлов (`/body/div[3]/p[7].5`), а не из текста:
       256 байт хватает с запасом. */
    char buf[256];
    int rc = dexp_reader_pos(page, buf, (int)sizeof(buf));
    if (rc < 0)
        return NULL;
    return utf8ToJString(env, buf, rc);
}

JNIEXPORT jint JNICALL
Java_ru_ogrpfsense_dexpm8_reader_Engine_pageOfNative(JNIEnv *env, jclass cls, jstring pos)
{
    (void)cls;
    if (!pos)
        return -1;
    const char *p = env->GetStringUTFChars(pos, NULL);
    if (!p)
        return -1;
    int page = dexp_reader_page_of(p);
    env->ReleaseStringUTFChars(pos, p);
    return page;
}

JNIEXPORT jint JNICALL
Java_ru_ogrpfsense_dexpm8_reader_Engine_gotoPosNative(JNIEnv *env, jclass cls, jstring pos)
{
    (void)cls;
    if (!pos)
        return -1;
    const char *p = env->GetStringUTFChars(pos, NULL);
    if (!p)
        return -1;
    int page = dexp_reader_goto_pos(p);
    env->ReleaseStringUTFChars(pos, p);
    return page;
}

JNIEXPORT jstring JNICALL
Java_ru_ogrpfsense_dexpm8_reader_Engine_pageTextNative(JNIEnv *env, jclass cls)
{
    (void)cls;
    /* 400 байт UTF-8 — это до 400 символов BMP; UTF-16 буфера вдвое больше символов,
       чем байт исходника. */
    char buf[400];
    int rc = dexp_reader_page_text(buf, (int)sizeof(buf));
    if (rc < 0)
        return NULL;
    /* `NewStringUTF` ждёт Modified UTF-8, а текст книги — обычный UTF-8: любой
       суррогат или 4-байтный символ роняет процесс. Переводим посимвольно. */
    return utf8ToJString(env, buf, rc);
}

JNIEXPORT jint JNICALL
Java_ru_ogrpfsense_dexpm8_reader_Engine_tocCountNative(JNIEnv *env, jclass cls)
{
    (void)env;
    (void)cls;
    return dexp_reader_toc_count();
}

JNIEXPORT jint JNICALL
Java_ru_ogrpfsense_dexpm8_reader_Engine_tocLevelNative(JNIEnv *env, jclass cls, jint index)
{
    (void)env;
    (void)cls;
    return dexp_reader_toc_level(index);
}

JNIEXPORT jint JNICALL
Java_ru_ogrpfsense_dexpm8_reader_Engine_tocPageNative(JNIEnv *env, jclass cls, jint index)
{
    (void)env;
    (void)cls;
    return dexp_reader_toc_page(index);
}

/**
 * Заголовок или позиция пункта. Буфер 256 байт: заголовок главы в книге длиннее
 * не встречается, а обрезка идёт по границе символа UTF-8.
 */
JNIEXPORT jstring JNICALL
Java_ru_ogrpfsense_dexpm8_reader_Engine_tocStringNative(JNIEnv *env, jclass cls,
        jint index, jint which)
{
    (void)cls;
    char buf[256];
    int rc = dexp_reader_toc_string(index, which, buf, (int)sizeof(buf));
    if (rc < 0)
        return NULL;
    return utf8ToJString(env, buf, rc);
}

JNIEXPORT jint JNICALL
Java_ru_ogrpfsense_dexpm8_reader_Engine_posOnPageNative(JNIEnv *env, jclass cls, jstring pos)
{
    (void)cls;
    if (!pos)
        return -1;
    const char *p = env->GetStringUTFChars(pos, NULL);
    if (!p)
        return -1;
    int r = dexp_reader_pos_on_page(p);
    env->ReleaseStringUTFChars(pos, p);
    return r;
}

} // extern "C"
