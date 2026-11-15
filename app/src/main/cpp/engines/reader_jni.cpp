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
#include "jni_utf8.h"
#include "probe.h"

/**
 * Мост прогресса открытия: C-колбэк crengine → статический метод Kotlin
 * `Engine.onOpenProgress(Int)`. C++-сторона (`cre_session.cpp`) не знает про JNI,
 * JNI-сторона — про crengine.
 *
 * Вызовы идут из потока движка (`Engine.worker`) — обычного Java-потока, то есть
 * уже присоединённого к JVM. `GetEnv` на нём возвращает готовый `JNIEnv`;
 * если поток всё же не присоединён, `AttachCurrentThread` присоединяет его на время
 * вызова. Сам метод в Kotlin только складывает процент в поле и просит перерисовку —
 * ничего не блокирует поток движка.
 */
static JavaVM *g_vm = NULL;
static jclass g_engineCls = NULL;
static jmethodID g_progressMid = NULL;

static void progress_trampoline(int percent)
{
    if (!g_vm || !g_engineCls || !g_progressMid)
        return;
    JNIEnv *env = NULL;
    bool detach = false;
    jint r = g_vm->GetEnv((void **)&env, JNI_VERSION_1_6);
    if (r == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, NULL) != JNI_OK)
            return;
        detach = true;
    } else if (r != JNI_OK) {
        return;
    }
    env->CallStaticVoidMethod(g_engineCls, g_progressMid, (jint)percent);
    if (env->ExceptionCheck())
        env->ExceptionClear();     // прогресс не должен ронять открытие
    if (detach)
        g_vm->DetachCurrentThread();
}

/** Подготовить JNI-переходник (один раз); `< 0` — класс или метод не нашлись. */
static int setup_progress(JNIEnv *env)
{
    if (g_progressMid)
        return 0;
    if (!g_vm)
        env->GetJavaVM(&g_vm);
    jclass c = env->FindClass("ru/ogrpfsense/dexpm8/reader/Engine");
    if (!c)
        return -1;
    jmethodID m = env->GetStaticMethodID(c, "onOpenProgress", "(I)V");
    if (!m) {
        env->DeleteLocalRef(c);
        return -1;
    }
    g_engineCls = (jclass)env->NewGlobalRef(c);
    env->DeleteLocalRef(c);
    if (!g_engineCls)
        return -1;
    g_progressMid = m;
    return 0;
}

extern "C" {

DEXP_PROBE_API int dexp_reader_open(const char *path, const char *cacheDir,
                                   const char *appFontDir, int w, int h);
DEXP_PROBE_API int dexp_reader_close();
DEXP_PROBE_API void dexp_reader_set_progress_hook(void (*hook)(int percent));
DEXP_PROBE_API int dexp_reader_page_count();
DEXP_PROBE_API int dexp_reader_cur_page();
DEXP_PROBE_API int dexp_reader_page(int page, int w, int h, unsigned char *gray);
DEXP_PROBE_API int dexp_reader_set_font(int size, const char *keepPos);
DEXP_PROBE_API int dexp_reader_set_typography(int face, int size, int interline, int margin,
                                              int hyphens, const char *keepPos);
DEXP_PROBE_API int dexp_reader_typography(int *out);
/** B70 «Открыть шрифт…»: регистрация файла из папки `Fonts`; код гарнитуры или `< 0`. */
DEXP_PROBE_API int dexp_reader_register_font(const char *path);
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
        jstring path, jstring cacheDir, jstring fontDir, jint w, jint h)
{
    (void)cls;
    if (!path)
        return DEXP_PROBE_EARG;
    const char *p = env->GetStringUTFChars(path, NULL);
    if (!p)
        return DEXP_PROBE_EARG;
    const char *c = cacheDir ? env->GetStringUTFChars(cacheDir, NULL) : NULL;
    const char *f = fontDir ? env->GetStringUTFChars(fontDir, NULL) : NULL;
    /* Прогресс открытия (B58): переходник ставится один раз на сеанс .so. */
    if (setup_progress(env) == 0)
        dexp_reader_set_progress_hook(progress_trampoline);
    int rc = dexp_reader_open(p, c, f, w, h);
    dexp_reader_set_progress_hook(NULL);    // вне открытия прогресса нет
    if (f)
        env->ReleaseStringUTFChars(fontDir, f);
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

/**
 * B4 «Типографика»: гарнитура, кегль, интервал, поля, переносы — за один проход.
 * Возвращает число страниц после перевёрстки (`< 0` — код ошибки).
 */
JNIEXPORT jint JNICALL
Java_ru_ogrpfsense_dexpm8_reader_Engine_setTypographyNative(JNIEnv *env, jclass cls,
        jint face, jint size, jint interline, jint margin, jint hyphens, jstring keepPos)
{
    (void)cls;
    const char *k = keepPos ? env->GetStringUTFChars(keepPos, NULL) : NULL;
    int rc = dexp_reader_set_typography(face, size, interline, margin, hyphens, k);
    if (k)
        env->ReleaseStringUTFChars(keepPos, k);
    return rc;
}

/** Текущая типографика в [out] (5 чисел: face, size, interline, margin, hyphens). */
JNIEXPORT jint JNICALL
Java_ru_ogrpfsense_dexpm8_reader_Engine_typographyNative(JNIEnv *env, jclass cls, jintArray out)
{
    (void)cls;
    if (!out || env->GetArrayLength(out) < 5)
        return DEXP_PROBE_EARG;
    int v[5];
    int rc = dexp_reader_typography(v);
    if (rc != DEXP_PROBE_OK)
        return rc;
    jint j[5];
    for (int i = 0; i < 5; i++)
        j[i] = v[i];
    env->SetIntArrayRegion(out, 0, 5, j);
    return rc;
}

/**
 * B70 «Шрифт и размер», строка «Открыть шрифт…»: зарегистрировать файл из папки `Fonts`.
 * Возвращает код гарнитуры (`Engine.FACE_USER + индекс`) или `< 0`.
 */
JNIEXPORT jint JNICALL
Java_ru_ogrpfsense_dexpm8_reader_Engine_registerFontNative(JNIEnv *env, jclass cls, jstring path)
{
    (void)cls;
    if (!path)
        return DEXP_PROBE_EARG;
    const char *p = env->GetStringUTFChars(path, NULL);
    int rc = dexp_reader_register_font(p);
    env->ReleaseStringUTFChars(path, p);
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
