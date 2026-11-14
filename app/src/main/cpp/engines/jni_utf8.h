/*
 * Общий для JNI движков (reader_jni.cpp, djvu_jni.cpp) перевод UTF-8 → jstring (R1, R2).
 */
#ifndef DEXP_JNI_UTF8_H
#define DEXP_JNI_UTF8_H

#include <jni.h>

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

#endif
