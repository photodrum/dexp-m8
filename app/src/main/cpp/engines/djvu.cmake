# libdexp_djvu.so — DjVuLibre (libdjvu + ddjvuapi), R0, ROADMAP «План 7.1», шаг 3.
# Список исходников — по third_party/djvulibre/libdjvu/Makefile.am целиком: кодировщики,
# редактор и вывод в PostScript нужны ddjvuapi.cpp, лишнее уберёт --gc-sections.
# config.h свой (engines/djvu/config.h), autotools не нужен.

set(DJVU_DIR ${DEXP_THIRD}/djvulibre/libdjvu)

set(DJVU_NAMES
        Arrays BSByteStream BSEncodeByteStream ByteStream DataPool DjVmDir DjVmDir0 DjVmDoc
        DjVmNav DjVuAnno DjVuDocEditor DjVuDocument DjVuDumpHelper DjVuErrorList DjVuFile
        DjVuFileCache DjVuGlobal DjVuGlobalMemory DjVuImage DjVuInfo DjVuMessage
        DjVuMessageLite DjVuNavDir DjVuPalette DjVuPort DjVuText DjVuToPS GBitmap GContainer
        GException GIFFManager GMapAreas GOS GPixmap GRect GScaler GSmartPointer GString
        GThreads GURL GUnicode IFFByteStream IW44EncodeCodec IW44Image JB2EncodeCodec
        JB2Image JPEGDecoder MMRDecoder MMX UnicodeByteStream XMLParser XMLTags ZPCodec
        atomic ddjvuapi debug miniexp)
list(TRANSFORM DJVU_NAMES PREPEND ${DJVU_DIR}/ OUTPUT_VARIABLE DJVU_SRC)
list(TRANSFORM DJVU_SRC APPEND .cpp)

add_library(djvu_lib STATIC ${DJVU_SRC})
target_include_directories(djvu_lib PRIVATE engines/djvu ${DJVU_DIR})
target_compile_definitions(djvu_lib PRIVATE HAVE_CONFIG_H)
target_compile_options(djvu_lib PRIVATE
        -Os -ffunction-sections -fdata-sections -fvisibility=hidden -fvisibility-inlines-hidden
        -fexceptions -w -DNDEBUG)

add_library(dexp_djvu SHARED engines/djvu_probe.cpp)
target_include_directories(dexp_djvu PRIVATE engines ${DEXP_THIRD}/djvulibre)
target_compile_options(dexp_djvu PRIVATE -Os -ffunction-sections -fdata-sections -fvisibility=hidden -Wall)
target_link_options(dexp_djvu PRIVATE ${DEXP_ENGINE_LDFLAGS})
target_link_libraries(dexp_djvu djvu_lib m log)
