/*
 * JNI сеанса DjVuLibre (R2): `ru.ogrpfsense.dexpm8.reader.DjvuEngine` → `dexp_djvu_*`
 * (`engines/djvu_session.cpp`). Собирается в `libdexp_djvu.so` вместе с пробой R0.
 *
 * Все методы вызываются из одного фонового потока (`Engine.worker`) — блокировок нет.
 * Прогресса открытия нет: DjVu не верстается, открытие — чтение заголовка (десятки мс).
 */
#include <jni.h>

#include "cre_gray.h"
#include "jni_utf8.h"
#include "probe.h"

extern "C" {

DEXP_PROBE_API int dexp_djvu_open(const char *path);
DEXP_PROBE_API int dexp_djvu_close();
DEXP_PROBE_API int dexp_djvu_page_count();
DEXP_PROBE_API int dexp_djvu_cur_page();
DEXP_PROBE_API int dexp_djvu_page(int page, int w, int h, unsigned char *gray);
DEXP_PROBE_API int dexp_djvu_pos(int page, char *out, int outLen);
DEXP_PROBE_API int dexp_djvu_page_of(const char *pos);
DEXP_PROBE_API int dexp_djvu_goto_pos(const char *pos);
DEXP_PROBE_API int dexp_djvu_pos_on_page(const char *pos);
DEXP_PROBE_API int dexp_djvu_page_text(char *out, int outLen);
DEXP_PROBE_API int dexp_djvu_toc_count();
DEXP_PROBE_API int dexp_djvu_toc_level(int index);
DEXP_PROBE_API int dexp_djvu_toc_page(int index);
DEXP_PROBE_API int dexp_djvu_toc_string(int index, int which, char *out, int outLen);

#define DJVU_JNI(name) Java_ru_ogrpfsense_dexpm8_reader_DjvuEngine_##name

/** Строковый аргумент → вызов [fn] → результат; null — -1, как у crengine. */
static jint with_str(JNIEnv *env, jstring s, int (*fn)(const char *))
{
    if (!s)
        return -1;
    const char *p = env->GetStringUTFChars(s, NULL);
    if (!p)
        return -1;
    int r = fn(p);
    env->ReleaseStringUTFChars(s, p);
    return r;
}

JNIEXPORT jint JNICALL
DJVU_JNI(openNative)(JNIEnv *env, jclass, jstring path)
{
    return with_str(env, path, dexp_djvu_open);
}

JNIEXPORT jint JNICALL
DJVU_JNI(closeNative)(JNIEnv *, jclass)
{
    return dexp_djvu_close();
}

JNIEXPORT jint JNICALL
DJVU_JNI(pageCountNative)(JNIEnv *, jclass)
{
    return dexp_djvu_page_count();
}

JNIEXPORT jint JNICALL
DJVU_JNI(curPageNative)(JNIEnv *, jclass)
{
    return dexp_djvu_cur_page();
}

JNIEXPORT jint JNICALL
DJVU_JNI(pageNative)(JNIEnv *env, jclass, jint page, jint w, jint h, jbyteArray out)
{
    if (!out || w <= 0 || h <= 0)
        return DEXP_PROBE_EARG;
    if (env->GetArrayLength(out) < (jsize)w * (jsize)h)
        return DEXP_PROBE_EARG;
    /* Не Critical: цветной скан декодируется до 1,5 с, GC блокировать нельзя. */
    jbyte *buf = env->GetByteArrayElements(out, NULL);
    if (!buf)
        return DEXP_PROBE_EARG;
    unsigned char *g = (unsigned char *)buf;
    int rc = dexp_djvu_page(page, w, h, g);
    if (rc == DEXP_PROBE_OK)
        dexp_gray_to_palette(g, (size_t)w * (size_t)h);
    env->ReleaseByteArrayElements(out, buf, 0);
    return rc;
}

JNIEXPORT jstring JNICALL
DJVU_JNI(posNative)(JNIEnv *env, jclass, jint page)
{
    char buf[32];
    int rc = dexp_djvu_pos(page, buf, (int)sizeof(buf));
    if (rc < 0)
        return NULL;
    return utf8ToJString(env, buf, rc);
}

JNIEXPORT jint JNICALL
DJVU_JNI(pageOfNative)(JNIEnv *env, jclass, jstring pos)
{
    return with_str(env, pos, dexp_djvu_page_of);
}

JNIEXPORT jint JNICALL
DJVU_JNI(gotoPosNative)(JNIEnv *env, jclass, jstring pos)
{
    return with_str(env, pos, dexp_djvu_goto_pos);
}

JNIEXPORT jint JNICALL
DJVU_JNI(posOnPageNative)(JNIEnv *env, jclass, jstring pos)
{
    return with_str(env, pos, dexp_djvu_pos_on_page);
}

JNIEXPORT jstring JNICALL
DJVU_JNI(pageTextNative)(JNIEnv *env, jclass)
{
    char buf[400];
    int rc = dexp_djvu_page_text(buf, (int)sizeof(buf));
    if (rc < 0)
        return NULL;
    return utf8ToJString(env, buf, rc);
}

JNIEXPORT jint JNICALL
DJVU_JNI(tocCountNative)(JNIEnv *, jclass)
{
    return dexp_djvu_toc_count();
}

JNIEXPORT jint JNICALL
DJVU_JNI(tocLevelNative)(JNIEnv *, jclass, jint index)
{
    return dexp_djvu_toc_level(index);
}

JNIEXPORT jint JNICALL
DJVU_JNI(tocPageNative)(JNIEnv *, jclass, jint index)
{
    return dexp_djvu_toc_page(index);
}

JNIEXPORT jstring JNICALL
DJVU_JNI(tocStringNative)(JNIEnv *env, jclass, jint index, jint which)
{
    char buf[256];
    int rc = dexp_djvu_toc_string(index, which, buf, (int)sizeof(buf));
    if (rc < 0)
        return NULL;
    return utf8ToJString(env, buf, rc);
}

} // extern "C"
