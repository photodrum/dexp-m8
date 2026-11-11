# libdexp_mupdf.so — MuPDF только для PDF и CBZ/картинок (R0, ROADMAP «План 7.1», шаг 2).
# Списки исходников и флаги — по third_party/mupdf/Makelists, Makerules и platform/java/Android.mk.
# HTML-движок (EPUB/FB2/MOBI/TXT/MD/Office), JS, XPS, SVG, brotli, штрихкоды, docx/odt — выключены:
# эти форматы у нас читает crengine. harfbuzz и gumbo поэтому не нужны.
# zlib — системная из NDK (-lz). Все зависимости вшиты статически и скрыты.

set(MUPDF_DIR ${DEXP_THIRD}/mupdf)
set(MUPDF_TP ${MUPDF_DIR}/thirdparty)

set(MUPDF_DEFS
        FZ_ENABLE_JS=0 FZ_ENABLE_HTML=0 FZ_ENABLE_HTML_ENGINE=0 FZ_ENABLE_EPUB=0 FZ_ENABLE_FB2=0
        FZ_ENABLE_MOBI=0 FZ_ENABLE_OFFICE=0 FZ_ENABLE_TXT=0 FZ_ENABLE_MD=0 FZ_ENABLE_XPS=0
        FZ_ENABLE_SVG=0 FZ_ENABLE_BROTLI=0 FZ_ENABLE_BARCODE=0 FZ_ENABLE_DOCX_OUTPUT=0
        FZ_ENABLE_ODT_OUTPUT=0 FZ_ENABLE_OCR_OUTPUT=0 FZ_ENABLE_HYPHEN=0
        TOFU_CJK TOFU_NOTO TOFU_SIL AA_BITS=8 _FILE_OFFSET_BITS=32
        OPJ_STATIC OPJ_HAVE_INTTYPES_H OPJ_HAVE_STDINT_H
        HAVE_LCMS2MT LCMS2MT_PREFIX=lcms2mt_)

set(MUPDF_INC
        ${MUPDF_DIR}/include
        ${MUPDF_TP}/freetype/include ${MUPDF_DIR}/scripts/freetype
        ${MUPDF_TP}/libjpeg ${MUPDF_DIR}/scripts/libjpeg
        ${MUPDF_TP}/jbig2dec
        ${MUPDF_TP}/openjpeg/src/lib/openjp2
        ${MUPDF_TP}/lcms2/include)

set(MUPDF_LIB_OPTS -Os -ffunction-sections -fdata-sections -fvisibility=hidden -w)

# --- freetype, урезанный (slimftmodules.h / slimftoptions.h) ---
set(MUPDF_FT ${MUPDF_TP}/freetype/src)
add_library(mupdf_freetype STATIC
        ${MUPDF_FT}/base/ftbase.c ${MUPDF_FT}/base/ftbbox.c ${MUPDF_FT}/base/ftbitmap.c
        ${MUPDF_FT}/base/ftdebug.c ${MUPDF_FT}/base/ftfstype.c ${MUPDF_FT}/base/ftgasp.c
        ${MUPDF_FT}/base/ftglyph.c ${MUPDF_FT}/base/ftinit.c ${MUPDF_FT}/base/ftstroke.c
        ${MUPDF_FT}/base/ftsynth.c ${MUPDF_FT}/base/ftsystem.c ${MUPDF_FT}/base/fttype1.c
        ${MUPDF_FT}/cff/cff.c ${MUPDF_FT}/cid/type1cid.c ${MUPDF_FT}/psaux/psaux.c
        ${MUPDF_FT}/pshinter/pshinter.c ${MUPDF_FT}/psnames/psnames.c ${MUPDF_FT}/raster/raster.c
        ${MUPDF_FT}/sfnt/sfnt.c ${MUPDF_FT}/smooth/smooth.c ${MUPDF_FT}/truetype/truetype.c
        ${MUPDF_FT}/type1/type1.c)
target_include_directories(mupdf_freetype PRIVATE ${MUPDF_TP}/freetype/include ${MUPDF_DIR}/scripts/freetype)
target_compile_definitions(mupdf_freetype PRIVATE
        "FT_CONFIG_MODULES_H=\"slimftmodules.h\"" "FT_CONFIG_OPTIONS_H=\"slimftoptions.h\"" FT2_BUILD_LIBRARY)
target_compile_options(mupdf_freetype PRIVATE ${MUPDF_LIB_OPTS})

# --- libjpeg ---
set(MUPDF_JPEG_NAMES
        jaricom jcapimin jcapistd jcarith jccoefct jccolor jcdctmgr jchuff jcinit jcmainct jcmarker
        jcmaster jcomapi jcparam jcprepct jcsample jdapimin jdapistd jdarith jdatadst jdatasrc
        jdcoefct jdcolor jddctmgr jdhuff jdinput jdmainct jdmarker jdmaster jdmerge jdpostct
        jdsample jdtrans jerror jfdctflt jfdctfst jfdctint jidctflt jidctfst jidctint jmemmgr
        jquant1 jquant2 jutils)
list(TRANSFORM MUPDF_JPEG_NAMES PREPEND ${MUPDF_TP}/libjpeg/ OUTPUT_VARIABLE MUPDF_JPEG_SRC)
list(TRANSFORM MUPDF_JPEG_SRC APPEND .c)
add_library(mupdf_jpeg STATIC ${MUPDF_JPEG_SRC})
target_include_directories(mupdf_jpeg PRIVATE ${MUPDF_TP}/libjpeg ${MUPDF_DIR}/scripts/libjpeg)
target_compile_options(mupdf_jpeg PRIVATE ${MUPDF_LIB_OPTS})


# --- jbig2dec ---
set(MUPDF_JBIG2_NAMES
        jbig2 jbig2_arith jbig2_arith_iaid jbig2_arith_int jbig2_generic jbig2_halftone
        jbig2_huffman jbig2_hufftab jbig2_image jbig2_mmr jbig2_page jbig2_refinement
        jbig2_segment jbig2_symbol_dict jbig2_text)
list(TRANSFORM MUPDF_JBIG2_NAMES PREPEND ${MUPDF_TP}/jbig2dec/ OUTPUT_VARIABLE MUPDF_JBIG2_SRC)
list(TRANSFORM MUPDF_JBIG2_SRC APPEND .c)
add_library(mupdf_jbig2 STATIC ${MUPDF_JBIG2_SRC})
target_include_directories(mupdf_jbig2 PRIVATE ${MUPDF_TP}/jbig2dec ${MUPDF_DIR}/include)
target_compile_definitions(mupdf_jbig2 PRIVATE HAVE_STDINT_H "JBIG_EXTERNAL_MEMENTO_H=\"mupdf/memento.h\"")
target_compile_options(mupdf_jbig2 PRIVATE ${MUPDF_LIB_OPTS})

# --- openjpeg (JPX) ---
set(MUPDF_OPJ_NAMES
        bio cio dwt event function_list ht_dec image invert j2k jp2 mct mqc openjpeg pi
        sparse_array t1 t2 tcd tgt thread)
list(TRANSFORM MUPDF_OPJ_NAMES PREPEND ${MUPDF_TP}/openjpeg/src/lib/openjp2/ OUTPUT_VARIABLE MUPDF_OPJ_SRC)
list(TRANSFORM MUPDF_OPJ_SRC APPEND .c)
add_library(mupdf_openjpeg STATIC ${MUPDF_OPJ_SRC})
target_include_directories(mupdf_openjpeg PRIVATE ${MUPDF_TP}/openjpeg/src/lib/openjp2)
target_compile_definitions(mupdf_openjpeg PRIVATE OPJ_STATIC OPJ_HAVE_INTTYPES_H OPJ_HAVE_STDINT_H MUTEX_pthread=0)
target_compile_options(mupdf_openjpeg PRIVATE ${MUPDF_LIB_OPTS})

# --- lcms2mt (ICC) ---
file(GLOB MUPDF_LCMS_SRC ${MUPDF_TP}/lcms2/src/*.c)
add_library(mupdf_lcms2 STATIC ${MUPDF_LCMS_SRC})
target_include_directories(mupdf_lcms2 PRIVATE ${MUPDF_TP}/lcms2/include)
target_compile_definitions(mupdf_lcms2 PRIVATE HAVE_LCMS2MT LCMS2MT_PREFIX=lcms2mt_)
target_compile_options(mupdf_lcms2 PRIVATE ${MUPDF_LIB_OPTS})

# --- ядро MuPDF: fitz + pdf + cbz, встроенные шрифты URW (уже сгенерированы в generated/) ---
file(GLOB MUPDF_CORE_SRC
        ${MUPDF_DIR}/source/fitz/*.c
        ${MUPDF_DIR}/source/pdf/*.c
        ${MUPDF_DIR}/source/cbz/*.c
        ${MUPDF_DIR}/generated/resources/fonts/urw/*.c)
add_library(mupdf_core STATIC ${MUPDF_CORE_SRC})
target_include_directories(mupdf_core PRIVATE ${MUPDF_INC})
target_compile_definitions(mupdf_core PRIVATE ${MUPDF_DEFS})
target_compile_options(mupdf_core PRIVATE ${MUPDF_LIB_OPTS})

# --- итоговая .so: пробный вход (JNI — шаг 7.2) ---
add_library(dexp_mupdf SHARED engines/mupdf_probe.c)
target_include_directories(dexp_mupdf PRIVATE ${MUPDF_INC} engines)
target_compile_definitions(dexp_mupdf PRIVATE ${MUPDF_DEFS})
target_compile_options(dexp_mupdf PRIVATE -Os -ffunction-sections -fdata-sections -fvisibility=hidden -Wall)
target_link_options(dexp_mupdf PRIVATE ${DEXP_ENGINE_LDFLAGS})
target_link_libraries(dexp_mupdf
        mupdf_core mupdf_freetype mupdf_jpeg mupdf_jbig2 mupdf_openjpeg mupdf_lcms2
        z m log)
