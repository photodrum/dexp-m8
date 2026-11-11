// crsetup.h для crengine (KOReader) под Android, armeabi-v7a — вместо crsetup.h.cmake.
// ROADMAP «План 7.1», шаг 4. Значения @...@ подставлены вручную, без pkg-config.
#ifndef CRSETUP_H_INCLUDED
#define CRSETUP_H_INCLUDED

#define LINUX 1
#define _LINUX 1

/// Compression. zlib — системная из NDK.
#define USE_ZLIB                             1
#define USE_ZSTD                             0

/// Documents cache.
#define DOCUMENT_CACHING_MIN_SIZE            0x10000   //  64.0 KiB
#define DOCUMENT_CACHING_SIZE_THRESHOLD      0x100000  //   1.0 MiB

/// Document formats. CHM — выключен (не в списке форматов READER_SPEC); DOC — antiword.
#define CHM_SUPPORT_ENABLED                  0
#define ENABLE_ANTIWORD                      1
#define USE_MD4C                             0

/// Images. GIF — встроенный декодер crengine, без зависимостей.
#define ARBITRARY_IMAGE_SCALE_ENABLED        1
#define MAX_IMAGE_SCALE_MUL                  2
#define USE_GIF                              1
#define USE_LIBJPEG                          0 // lvimg.cpp требует libjpeg-turbo (JCS_EXT_BGRX), см. cre.cmake
#define USE_LIBPNG                           1
#define USE_LIBWEBP                          0
#define USE_LUNASVG                          0
#define USE_NANOSVG                          0
#define USE_STB_IMAGE                        0

/// Miscellaneous.
#define MATHML_SUPPORT                       0
#define USE_SRELL_REGEX                      0

/// Output buffer.
#define COLOR_BACKBUFFER                     1
#define CR_INTERNAL_PAGE_ORIENTATION         1
#define GRAY_BACKBUFFER_BITS                 2
#define GRAY_INVERSE                         0

/// Streams. bionic (android-27) умеет off64_t и stat64.
#define HAVE_OFF64_T                         1
#ifdef HAVE_OFF64_T
# define HAVE_STAT64                         1
# ifndef _LARGEFILE64_SOURCE
#  define _LARGEFILE64_SOURCE                1
# endif
#endif
#define LVLONG_FILE_SUPPORT                  0
#define USE_ANSI_FILES                       0
#define FILE_STREAM_BUFFER_SIZE              0x20000   // 128.0 KiB
#define ZIP_STREAM_BUFFER_SIZE               0x40000   // 256.0 KiB

/// System. Свой менеджер памяти — как в KOReader (OWN_MEM_MAN ON по умолчанию).
#define CR_USE_THREADS                       0
#define LDOM_USE_OWN_MEM_MAN                 1

/// Text.
#define USE_LIMITED_FONT_SIZES_SET           0
#define USE_BITMAP_FONTS                     0
#define USE_WIN32_FONTS                      0
#define USE_GLYPHCACHE_HASHTABLE             0
#define GLYPH_CACHE_SIZE                     0x40000   // 256.0 KiB
#define ALLOW_KERNING                        1
#define USE_FONTCONFIG                       0
#define USE_FREETYPE                         1
#define USE_FRIBIDI                          1
#define USE_HARFBUZZ                         1
#define USE_LIBUNIBREAK                      1
#define USE_UTF8PROC                         1

/// Disable unused code.
#define CR_ENABLE_PAGE_IMAGE_CACHE           0

#endif//CRSETUP_H_INCLUDED
