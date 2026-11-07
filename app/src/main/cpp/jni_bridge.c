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
