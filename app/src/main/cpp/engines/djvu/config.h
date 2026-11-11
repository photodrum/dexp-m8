/*
 * config.h для libdjvu (djvulibre 3.5.30) под Android NDK, armeabi-v7a — вместо autotools.
 * Значения — по configure.ac (проверки AC_CHECK_*) и AH_BOTTOM; на хосте autoconf нет (DEVLOG 7.0).
 * ROADMAP «План 7.1», шаг 3.
 */
#ifndef CONFIG_H
#define CONFIG_H

#define PACKAGE_NAME "djvulibre"
#define PACKAGE_VERSION "3.5.30"
#define DJVULIBRE_VERSION "3.5.30"
/* Каталог сообщений osi не ставим: DjVuMessage вернёт ключи вместо текстов, для рендера не нужно. */
#define DIR_DATADIR "/system/usr/share"

/* Компилятор C++ (AC_CXX_*) */
#define HAVE_BOOL 1
#define HAVE_EXCEPTIONS 1
#define HAVE_TYPENAME 1
#define HAVE_STDINCLUDES 1
#define HAVE_NAMESPACES 1
#define HAVE_MEMBER_TEMPLATES 1
#define HAVE_INTEL_ATOMIC_BUILTINS 1
#define HAVE_GCCTLS 1

/* Заголовки (bionic, android-27) */
#define HAVE_DIRENT_H 1
#define HAVE_SYS_TIME_H 1
#define HAVE_SYS_WAIT_H 1
#define HAVE_WCHAR_H 1
#define HAVE_WCTYPE_H 1
#define HAVE_SYS_MMAN_H 1
#define HAVE_SCHED_H 1
#define HAVE_NEW_H 1
#define HAVE_STDINT_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STDDEF_H 1
#define HAVE_UNISTD_H 1

/* Типы */
#define HAVE_WCHAR_T 1
#define HAVE_LONG_LONG_INT 1
#define HAVE_MBSTATE_T 1

/* Функции */
#define HAVE_MMAP 1
#define HAVE_WCRTOMB 1
#define HAVE_ISWSPACE 1
#define HAVE_SETENV 1
#define HAVE_STRERROR 1
#define HAVE_VSNPRINTF 1
#define HAVE_SNPRINTF 1
#define HAVE_STRFTIME 1
#define HAVE_GETPWUID 1
#define HAVE_SIGACTION 1
#define HAVE_MKSTEMP 1
#define HAVE_SCHED_YIELD 1
/* iconv в bionic появился в API 28, у нас minSdk 27 — без iconv. */

/* Потоки: libdjvu без них не собирается (GThreads.h) */
#define HAVE_PTHREAD 1

/* JPEG внутри DjVu встречается редко; libjpeg не тянем (NEED_JPEG_DECODER не задан). */

/* --- AH_BOTTOM из configure.ac --- */
#define AUTOCONF 1
#define UNIX 1

#define HAS_WCHAR 1
#define HAS_WCTYPE 1
#define HAS_MBSTATE 1
#define HAS_ICONV 0

#define HAS_CTRL_C_IN_ERR_MSG 1

#if defined(HAVE_MMAP) && defined(HAVE_SYS_MMAN_H)
#define HAS_MEMMAP 1
#else
#define HAS_MEMMAP 0
#endif

#endif /* CONFIG_H */
