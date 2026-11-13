# libdexp_cre.so — crengine (KOReader): FB2, EPUB, MOBI/PDB, TXT, RTF, DOC, DOCX, ODT, FB3, MD.
# R0, ROADMAP «План 7.1», шаг 4. Свой список вместо CMakeLists crengine (там pkg-config, DEVLOG 7.0):
# исходники crengine — по CRE_SRCS из third_party/crengine/CMakeLists.txt; crsetup.h — свой.
# Внутри: freetype 2.14.3 полный и harfbuzz 13.0.1 (свои подмодули, шаг 7.3а),
# libpng из crengine/thirdparty, libunibreak, utf8proc, fribidi, xxhash, antiword.
# zlib — системная из NDK.

set(CRE_DIR ${DEXP_THIRD}/crengine)
set(CRE_TP ${CRE_DIR}/thirdparty)
set(CRE_FT ${DEXP_THIRD}/freetype)
set(CRE_HB ${DEXP_THIRD}/harfbuzz/src)
set(CRE_FRIBIDI ${DEXP_THIRD}/fribidi/lib)
set(CRE_GEN ${CMAKE_CURRENT_SOURCE_DIR}/engines/cre)

# NDEBUG всегда (и в Debug): assert'ы движков не отлаживаем, замеры 7.3 — как в release;
# antiword без DEBUG/NDEBUG не собирается.
set(CRE_LIB_OPTS -Os -ffunction-sections -fdata-sections -fvisibility=hidden -w -DNDEBUG)

# --- freetype полный (ftoption.h по умолчанию, zlib системная) ---
set(CRE_FT_NAMES
        autofit/autofit base/ftbase base/ftbbox base/ftbdf base/ftbitmap base/ftcid base/ftdebug
        base/ftfstype base/ftgasp base/ftglyph base/ftgxval base/ftinit base/ftmm base/ftotval
        base/ftpatent base/ftpfr base/ftstroke base/ftsynth base/ftsystem base/fttype1
        base/ftwinfnt bdf/bdf cache/ftcache cff/cff cid/type1cid gzip/ftgzip lzw/ftlzw pcf/pcf
        pfr/pfr psaux/psaux pshinter/pshinter psnames/psnames raster/raster sdf/sdf sfnt/sfnt
        smooth/smooth svg/svg truetype/truetype type1/type1 type42/type42 winfonts/winfnt)
list(TRANSFORM CRE_FT_NAMES PREPEND ${CRE_FT}/src/ OUTPUT_VARIABLE CRE_FT_SRC)
list(TRANSFORM CRE_FT_SRC APPEND .c)
add_library(cre_freetype STATIC ${CRE_FT_SRC})
target_include_directories(cre_freetype PRIVATE ${CRE_FT}/include)
target_compile_definitions(cre_freetype PRIVATE FT2_BUILD_LIBRARY FT_CONFIG_OPTION_SYSTEM_ZLIB)
target_compile_options(cre_freetype PRIVATE ${CRE_LIB_OPTS})

# --- harfbuzz (амальгама harfbuzz.cc) ---
add_library(cre_harfbuzz STATIC ${CRE_HB}/harfbuzz.cc)
target_include_directories(cre_harfbuzz PRIVATE ${CRE_HB} ${CRE_FT}/include)
target_compile_definitions(cre_harfbuzz PRIVATE
        HAVE_FREETYPE HAVE_FT_GET_VAR_BLEND_COORDINATES HAVE_FT_SET_VAR_BLEND_COORDINATES
        HAVE_FT_DONE_MM_VAR HAVE_FT_GET_TRANSFORM HB_NO_MT)
target_compile_options(cre_harfbuzz PRIVATE ${CRE_LIB_OPTS}
        -fno-exceptions -fno-rtti -fno-threadsafe-statics -fvisibility-inlines-hidden)

# --- libpng 1.6.6 из crengine/thirdparty ---
set(CRE_PNG_NAMES png pngerror pngget pngmem pngpread pngread pngrio pngrtran pngrutil pngset
        pngtrans pngwio pngwrite pngwtran pngwutil)
list(TRANSFORM CRE_PNG_NAMES PREPEND ${CRE_TP}/libpng/ OUTPUT_VARIABLE CRE_PNG_SRC)
list(TRANSFORM CRE_PNG_SRC APPEND .c)
add_library(cre_png STATIC ${CRE_PNG_SRC})
target_include_directories(cre_png PRIVATE ${CRE_TP}/libpng)
# NEON-код libpng (arm/*.c) не собираем: для картинок в книгах выигрыш не нужен.
target_compile_definitions(cre_png PRIVATE PNG_ARM_NEON_OPT=0)
target_compile_options(cre_png PRIVATE ${CRE_LIB_OPTS})

# --- libjpeg-turbo 3.2.1 (lvimg.cpp требует JCS_EXT_BGRX), API libjpeg 6.2, без SIMD ---
# Исходники — JPEG_SOURCES из third_party/libjpeg-turbo/CMakeLists.txt (тег 3.2.1) + арифметика.
# jconfig.h, jconfigint.h, jversion.h — сгенерированы их CMake под NDK (WITH_SIMD=0), лежат в
# engines/cre/jpeg; перегенерировать при обновлении подмодуля (docs/BUILD.md).
set(CRE_JPEG ${DEXP_THIRD}/libjpeg-turbo/src)
set(CRE_JPEG_NAMES
        jcapimin
        wrapper/jcapistd-8 wrapper/jcapistd-12 wrapper/jcapistd-16
        wrapper/jccoefct-8 wrapper/jccoefct-12
        wrapper/jccolor-8 wrapper/jccolor-12 wrapper/jccolor-16
        wrapper/jcdctmgr-8 wrapper/jcdctmgr-12
        wrapper/jcdiffct-8 wrapper/jcdiffct-12 wrapper/jcdiffct-16
        jchuff jcicc jcinit jclhuff
        wrapper/jclossls-8 wrapper/jclossls-12 wrapper/jclossls-16
        wrapper/jcmainct-8 wrapper/jcmainct-12 wrapper/jcmainct-16
        jcmarker jcmaster jcomapi jcparam jcphuff
        wrapper/jcprepct-8 wrapper/jcprepct-12 wrapper/jcprepct-16
        wrapper/jcsample-8 wrapper/jcsample-12 wrapper/jcsample-16
        jctrans jdapimin
        wrapper/jdapistd-8 wrapper/jdapistd-12 wrapper/jdapistd-16
        jdatadst jdatasrc
        wrapper/jdcoefct-8 wrapper/jdcoefct-12
        wrapper/jdcolor-8 wrapper/jdcolor-12 wrapper/jdcolor-16
        wrapper/jddctmgr-8 wrapper/jddctmgr-12
        wrapper/jddiffct-8 wrapper/jddiffct-12 wrapper/jddiffct-16
        jdhuff jdicc jdinput jdlhuff
        wrapper/jdlossls-8 wrapper/jdlossls-12 wrapper/jdlossls-16
        wrapper/jdmainct-8 wrapper/jdmainct-12 wrapper/jdmainct-16
        jdmarker jdmaster
        wrapper/jdmerge-8 wrapper/jdmerge-12
        jdphuff
        wrapper/jdpostct-8 wrapper/jdpostct-12 wrapper/jdpostct-16
        wrapper/jdsample-8 wrapper/jdsample-12 wrapper/jdsample-16
        jdtrans jerror jfdctflt
        wrapper/jfdctfst-8 wrapper/jfdctfst-12
        wrapper/jfdctint-8 wrapper/jfdctint-12
        wrapper/jidctflt-8 wrapper/jidctflt-12
        wrapper/jidctfst-8 wrapper/jidctfst-12
        wrapper/jidctint-8 wrapper/jidctint-12
        wrapper/jidctred-8 wrapper/jidctred-12
        jmemmgr jmemnobs jpeg_nbits
        wrapper/jquant1-8 wrapper/jquant1-12
        wrapper/jquant2-8 wrapper/jquant2-12
        wrapper/jutils-8 wrapper/jutils-12 wrapper/jutils-16
        jaricom jcarith jdarith)
list(TRANSFORM CRE_JPEG_NAMES PREPEND ${CRE_JPEG}/ OUTPUT_VARIABLE CRE_JPEG_SRC)
list(TRANSFORM CRE_JPEG_SRC APPEND .c)
add_library(cre_jpeg STATIC ${CRE_JPEG_SRC})
target_include_directories(cre_jpeg PRIVATE ${CRE_GEN}/jpeg ${CRE_JPEG})
target_compile_options(cre_jpeg PRIVATE ${CRE_LIB_OPTS})

# --- libunibreak, utf8proc, xxhash ---
set(CRE_UB ${DEXP_THIRD}/libunibreak/src)
add_library(cre_unibreak STATIC
        ${CRE_UB}/unibreakbase.c ${CRE_UB}/unibreakdef.c ${CRE_UB}/linebreak.c
        ${CRE_UB}/linebreakdata.c ${CRE_UB}/linebreakdef.c ${CRE_UB}/eastasianwidthdef.c
        ${CRE_UB}/emojidef.c ${CRE_UB}/graphemebreak.c ${CRE_UB}/wordbreak.c)
target_compile_options(cre_unibreak PRIVATE ${CRE_LIB_OPTS})

add_library(cre_utf8proc STATIC ${DEXP_THIRD}/utf8proc/utf8proc.c)
target_compile_definitions(cre_utf8proc PRIVATE UTF8PROC_STATIC)
target_compile_options(cre_utf8proc PRIVATE ${CRE_LIB_OPTS})

add_library(cre_xxhash STATIC ${DEXP_THIRD}/xxhash/xxhash.c)
target_compile_options(cre_xxhash PRIVATE ${CRE_LIB_OPTS})

# --- fribidi: таблицы и fribidi-config.h — engines/cre/fribidi (tools/gen_fribidi.sh) ---
set(CRE_FRIBIDI_NAMES
        fribidi fribidi-arabic fribidi-bidi fribidi-bidi-types fribidi-char-sets
        fribidi-char-sets-cap-rtl fribidi-char-sets-cp1255 fribidi-char-sets-cp1256
        fribidi-char-sets-iso8859-6 fribidi-char-sets-iso8859-8 fribidi-char-sets-utf8
        fribidi-deprecated fribidi-joining fribidi-joining-types fribidi-mirroring
        fribidi-brackets fribidi-run fribidi-shape)
list(TRANSFORM CRE_FRIBIDI_NAMES PREPEND ${CRE_FRIBIDI}/ OUTPUT_VARIABLE CRE_FRIBIDI_SRC)
list(TRANSFORM CRE_FRIBIDI_SRC APPEND .c)
add_library(cre_fribidi STATIC ${CRE_FRIBIDI_SRC})
target_include_directories(cre_fribidi PRIVATE ${CRE_GEN}/fribidi ${CRE_FRIBIDI})
target_compile_definitions(cre_fribidi PRIVATE HAVE_CONFIG_H FRIBIDI_BUILD FRIBIDI_LIB_STATIC)
target_compile_options(cre_fribidi PRIVATE ${CRE_LIB_OPTS})

# --- antiword (DOC), по thirdparty/antiword/CMakeLists.txt ---
set(CRE_AW_NAMES
        asc85enc blocklist chartrans datalist depot dib2eps doclist fail finddata findtext
        fontlist fonts fonts_u hdrftrlist imgexam listlist misc notes options out2window pdf
        pictlist prop0 prop2 prop6 prop8 properties propmod rowlist sectlist stylelist
        stylesheet summary tabstop unix utf8 word2text worddos wordlib wordmac wordole wordwin
        xmalloc)
list(TRANSFORM CRE_AW_NAMES PREPEND ${CRE_TP}/antiword/ OUTPUT_VARIABLE CRE_AW_SRC)
list(TRANSFORM CRE_AW_SRC APPEND .c)
add_library(cre_antiword STATIC ${CRE_AW_SRC})
target_include_directories(cre_antiword PRIVATE ${CRE_TP}/antiword)
target_compile_definitions(cre_antiword PRIVATE CR3_ANTIWORD_PATCH=1)
target_compile_options(cre_antiword PRIVATE ${CRE_LIB_OPTS})

# --- сам crengine: CRE_SRCS из third_party/crengine/CMakeLists.txt ---
set(CRE_NAMES
        chmfmt cp_stats crtxtenc docxfmt drawutil epubfmt fb3fmt hist hyphman lstridmap
        lvdocview lvdrawbuf lvfnt lvfntman lvimg lvmemman lvopc lvpagesplitter lvrend lvstream
        lvstring lvstsheet lvstyles lvtextfm lvtinydom lvxml mathml mdfmt odtfmt odxutil pdbfmt
        props renderutil rtfimp textlang txtselector wordfmt)
list(TRANSFORM CRE_NAMES PREPEND ${CRE_DIR}/crengine/src/ OUTPUT_VARIABLE CRE_SRC)
list(TRANSFORM CRE_SRC APPEND .cpp)
list(APPEND CRE_SRC ${CRE_DIR}/crengine/qimagescale/qimagescale.cpp)

set(CRE_INC
        ${CRE_GEN}                      # crsetup.h
        ${CRE_DIR}/crengine/include
        ${CRE_FT}/include ${CRE_HB}
        ${CRE_TP}/libpng ${CRE_TP}/antiword
        ${CRE_GEN}/jpeg ${CRE_JPEG}     # jconfig.h, затем jpeglib.h libjpeg-turbo
        ${CRE_UB} ${DEXP_THIRD}/utf8proc ${DEXP_THIRD}/xxhash
        ${CRE_GEN}/fribidi ${CRE_FRIBIDI})
set(CRE_DEFS CR3_ANTIWORD_PATCH=1 UTF8PROC_STATIC FRIBIDI_LIB_STATIC)

add_library(cre_core STATIC ${CRE_SRC})
target_include_directories(cre_core PRIVATE ${CRE_INC})
target_compile_definitions(cre_core PRIVATE ${CRE_DEFS})
target_compile_features(cre_core PRIVATE cxx_std_17)
target_compile_options(cre_core PRIVATE ${CRE_LIB_OPTS} -fvisibility-inlines-hidden -fexceptions)

# --- итоговая .so: пробный вход + JNI (шаг 7.2), сеанс читалки и его JNI (R1) ---
add_library(dexp_cre SHARED
        engines/cre_probe.cpp
        engines/cre_session.cpp
        engines/probe_jni.c
        engines/reader_jni.cpp)
target_include_directories(dexp_cre PRIVATE ${CRE_INC} engines)
target_compile_definitions(dexp_cre PRIVATE ${CRE_DEFS} DEXP_ENGINE=cre)
target_compile_features(dexp_cre PRIVATE cxx_std_17)
target_compile_options(dexp_cre PRIVATE -Os -ffunction-sections -fdata-sections -fvisibility=hidden -Wall)
target_link_options(dexp_cre PRIVATE ${DEXP_ENGINE_LDFLAGS})
target_link_libraries(dexp_cre
        cre_core cre_antiword cre_harfbuzz cre_freetype cre_png cre_jpeg cre_fribidi
        cre_unibreak cre_utf8proc cre_xxhash
        z m log)

