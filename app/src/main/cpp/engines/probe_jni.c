/*
 * JNI к пробным входам движков R0 (шаг 7.2). Один файл собирается в каждую .so движка
 * с -DDEXP_ENGINE=mupdf|djvu|cre и даёт метод
 *   ru.ogrpfsense.dexpm8.reader.EngineProbe.<engine>Native(path, w, h, out): Int.
 *
 * Страница рисуется прямо в out (byte[w*h], stride = w), затем сводится к 16 уровням
 * серого (шаг 17, без дизеринга) и инвертируется: out — альфа для Bitmap ALPHA_8,
 * который выводится цветом G0 поверх G15 (альфа 0 = белый, 255 = чёрный).
 */
#include <jni.h>

#include "probe.h"

#define DEXP_CAT3_(a, b, c) a##b##c
#define DEXP_CAT3(a, b, c) DEXP_CAT3_(a, b, c)

#define PROBE_FN DEXP_CAT3(dexp_, DEXP_ENGINE, _probe)
#define JNI_FN DEXP_CAT3(Java_ru_ogrpfsense_dexpm8_reader_EngineProbe_, DEXP_ENGINE, Native)

JNIEXPORT jint JNICALL
JNI_FN(JNIEnv *env, jclass cls, jstring path, jint w, jint h, jbyteArray out)
{
    (void)cls;
    if (!path || !out || w <= 0 || h <= 0)
        return DEXP_PROBE_EARG;
    if ((*env)->GetArrayLength(env, out) < (jsize)w * (jsize)h)
        return DEXP_PROBE_EARG;

    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    if (!p)
        return DEXP_PROBE_EARG;
    /* Не Critical: отрисовка идёт сотни мс, GC блокировать нельзя. Большой массив в ART
       не перемещается, копии обычно нет. */
    jbyte *buf = (*env)->GetByteArrayElements(env, out, NULL);
    if (!buf) {
        (*env)->ReleaseStringUTFChars(env, path, p);
        return DEXP_PROBE_EARG;
    }

    unsigned char *g = (unsigned char *)buf;
    int rc = PROBE_FN(p, w, h, g);
    size_t n = (size_t)w * (size_t)h;
    for (size_t i = 0; i < n; i++) {
        unsigned v = g[i];
        unsigned q = ((v * 15u + 127u) / 255u) * 17u;   /* 0, 17, …, 255 */
        g[i] = (unsigned char)(255u - q);
    }

    (*env)->ReleaseByteArrayElements(env, out, buf, 0);
    (*env)->ReleaseStringUTFChars(env, path, p);
    return rc;
}
