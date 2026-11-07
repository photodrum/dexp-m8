cmake_minimum_required(VERSION 3.22.1)
project(dexp_core C)

# Только C99, без libc++ и без исключений: .so должна быть крошечной.
set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)

add_library(dexp_core SHARED
        core/book.c
        jni_bridge.c)

target_include_directories(dexp_core PRIVATE core)

target_compile_options(dexp_core PRIVATE
        -Os
        -ffunction-sections
        -fdata-sections
        -fvisibility=hidden
        -Wall
        -Wextra)

target_link_options(dexp_core PRIVATE
        -Wl,--gc-sections
        -Wl,--exclude-libs,ALL
        -Wl,-z,max-page-size=16384)

# zlib понадобится для EPUB (inflate) — подключаем системную из NDK.
find_library(ZLIB_LIB z)
if (ZLIB_LIB)
    target_link_libraries(dexp_core ${ZLIB_LIB})
endif()
