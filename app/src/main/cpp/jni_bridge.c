#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include "core/book.h"

/*
 * Мост «C → Kotlin». Наружу отдаём плоский массив объектов, а не держим
 * нативный указатель в Java: так нет риска, что книга «утечёт» при убийстве
 * Activity. Парсинг идёт один раз при открытии, дальше UI живёт с ArrayList.
 */

#define JNI_FN(name) Java_ru_ogrpfsense_dexpm8_core_NativeBook_##name

static jstring to_jstring(JNIEnv *env, const char *s) {
    if (!s) return NULL;
    return (*env)->NewStringUTF(env, s);
}

JNIEXPORT jobjectArray JNICALL
JNI_FN(parseFb2)(JNIEnv *env, jclass cls, jbyteArray data) {
    (void) cls;
    if (!data) return NULL;
    jsize n = (*env)->GetArrayLength(env, data);
    jbyte *raw = (*env)->GetByteArrayElements(env, data, NULL);
    if (!raw) return NULL;

    book b;
    int rc = book_parse_fb2(&b, (const char *) raw, (size_t) n);
    (*env)->ReleaseByteArrayElements(env, data, raw, JNI_ABORT);

    if (rc != 0) {
        book_free(&b);
        return NULL;
    }

    /* Ищем класс блока один раз. */
    jclass blkCls = (*env)->FindClass(env, "ru/ogrpfsense/dexpm8/core/NativeBook$Block");
    if (!blkCls) { book_free(&b); return NULL; }
    jmethodID ctor = (*env)->GetMethodID(env, blkCls, "<init>", "(ILjava/lang/String;)V");
    if (!ctor) { book_free(&b); return NULL; }

    /* Метаданные складываем в первые три элемента с отрицательными типами? Нет:
       отдельный вызов meta() ниже, чтобы массив оставался однородным. */
    jobjectArray out = (*env)->NewObjectArray(env, (jsize) b.count, blkCls, NULL);
    if (!out) { book_free(&b); return NULL; }

    for (size_t i = 0; i < b.count; i++) {
        jstring s = to_jstring(env, b.items[i].text ? b.items[i].text : "");
        jobject obj = (*env)->NewObject(env, blkCls, ctor, (jint) b.items[i].type, s);
        if (s) (*env)->DeleteLocalRef(env, s);
        if (obj) {
            (*env)->SetObjectArrayElement(env, out, (jsize) i, obj);
            (*env)->DeleteLocalRef(env, obj);
        }
    }

    book_free(&b);
    return out;
}

JNIEXPORT jstring JNICALL
JNI_FN(title)(JNIEnv *env, jclass cls, jbyteArray data) {
    (void) cls;
    if (!data) return NULL;
    jsize n = (*env)->GetArrayLength(env, data);
    jbyte *raw = (*env)->GetByteArrayElements(env, data, NULL);
    if (!raw) return NULL;
    book b;
    int rc = book_parse_fb2(&b, (const char *) raw, (size_t) n);
    (*env)->ReleaseByteArrayElements(env, data, raw, JNI_ABORT);
    jstring s = (rc == 0) ? to_jstring(env, b.title) : NULL;
    book_free(&b);
    return s;
}

JNIEXPORT jstring JNICALL
JNI_FN(errorText)(JNIEnv *env, jclass cls, jint code) {
    (void) cls;
    return to_jstring(env, book_error_text(code));
}

/*
 * UTF-8 -> jstring через UTF-16 и NewString, а не NewStringUTF: тот ждёт
 * «modified UTF-8», и на Android 8 с CheckJNI процесс падает на 4-байтовых
 * последовательностях (эмодзи) и битых байтах — а в чужих FB2 бывает всякое.
 * Битые байты -> U+FFFD.
 */
static jstring utf8_to_jstring(JNIEnv *env, const char *s) {
    if (!s) return NULL;
    size_t n = strlen(s);
    jchar *u = (jchar *) malloc((n + 1) * sizeof(jchar));   /* UTF-16 не длиннее UTF-8 в байтах */
    if (!u) return NULL;
    const unsigned char *p = (const unsigned char *) s, *e = p + n;
    size_t w = 0;
    while (p < e) {
        unsigned c = *p, cp, need;
        if (c < 0x80) { u[w++] = (jchar) c; p++; continue; }
        if (c >= 0xC2 && c <= 0xDF) { cp = c & 0x1F; need = 1; }
        else if (c >= 0xE0 && c <= 0xEF) { cp = c & 0x0F; need = 2; }
        else if (c >= 0xF0 && c <= 0xF4) { cp = c & 0x07; need = 3; }
        else { u[w++] = 0xFFFD; p++; continue; }
        if ((size_t) (e - p) <= need) { u[w++] = 0xFFFD; p++; continue; }
        unsigned k = 1;
        for (; k <= need && (p[k] & 0xC0) == 0x80; k++) cp = (cp << 6) | (p[k] & 0x3F);
        if (k <= need || (need == 2 && (cp < 0x800 || (cp >= 0xD800 && cp <= 0xDFFF))) ||
            (need == 3 && (cp < 0x10000 || cp > 0x10FFFF))) {
            u[w++] = 0xFFFD; p++; continue;
        }
        if (cp >= 0x10000) {
            cp -= 0x10000;
            u[w++] = (jchar) (0xD800 | (cp >> 10));
            u[w++] = (jchar) (0xDC00 | (cp & 0x3FF));
        } else u[w++] = (jchar) cp;
        p += need + 1;
    }
    jstring r = (*env)->NewString(env, u, (jsize) w);
    free(u);
    return r;
}

/* Порядок полей — как индексы META_* в NativeBook.kt. */
#define META_FIELDS 11

/*
 * Метаданные FB2 (только <title-info>) по началу файла. Наружу — String[11]:
 * title, authors, first, middle, last, nickname, series, seriesNum, lang,
 * coverId, complete ("1"/"0"); null — ошибка разбора.
 */
JNIEXPORT jobjectArray JNICALL
JNI_FN(metaFb2Native)(JNIEnv *env, jclass cls, jbyteArray data, jint len) {
    (void) cls;
    if (!data) return NULL;
    jsize n = (*env)->GetArrayLength(env, data);
    if (len < 0 || len > n) return NULL;
    jbyte *raw = (*env)->GetByteArrayElements(env, data, NULL);
    if (!raw) return NULL;

    book_meta m;
    int rc = book_meta_fb2(&m, (const char *) raw, (size_t) len);
    (*env)->ReleaseByteArrayElements(env, data, raw, JNI_ABORT);
    if (rc != 0) { book_meta_free(&m); return NULL; }

    jclass strCls = (*env)->FindClass(env, "java/lang/String");
    if (!strCls) { book_meta_free(&m); return NULL; }
    jobjectArray out = (*env)->NewObjectArray(env, META_FIELDS, strCls, NULL);
    (*env)->DeleteLocalRef(env, strCls);
    if (!out) { book_meta_free(&m); return NULL; }

    char num[12];
    num[0] = '\0';
    if (m.series_num > 0) {
        int v = m.series_num, k = 0;
        char t[12];
        while (v && k < 11) { t[k++] = (char) ('0' + v % 10); v /= 10; }
        for (int i = 0; i < k; i++) num[i] = t[k - 1 - i];
        num[k] = '\0';
    }
    const char *f[META_FIELDS] = {
        m.title, m.authors, m.first_name, m.middle_name, m.last_name, m.nickname,
        m.series, num[0] ? num : NULL, m.lang, m.cover_id, m.complete ? "1" : "0"
    };
    for (int i = 0; i < META_FIELDS; i++) {
        if (!f[i]) continue;
        jstring s = utf8_to_jstring(env, f[i]);
        if (!s) {                                  /* OOM: исключение уже выставлено JVM */
            if ((*env)->ExceptionCheck(env)) break;
            continue;
        }
        (*env)->SetObjectArrayElement(env, out, i, s);
        (*env)->DeleteLocalRef(env, s);
    }
    book_meta_free(&m);
    return out;
}
