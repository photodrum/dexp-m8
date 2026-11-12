cmake_minimum_required(VERSION 3.22.1)
project(dexp_core C CXX)

# dexp_core — только C99, без libc++ и без исключений: .so должна быть крошечной.
# Стандарт задан на цель, а не глобально: движкам ниже нужен C11/C++.

add_library(dexp_core SHARED
        core/book.c
        jni_bridge.c)

set_target_properties(dexp_core PROPERTIES C_STANDARD 99 C_STANDARD_REQUIRED ON)

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

# --- Движки читалки, R0 (ROADMAP «План 7.1»): две отдельные .so, зависимости вшиты и скрыты.
# MuPDF (PDF/CBZ) снят в 7.3б: AGPL-3.0 несовместим с GPL-2.0-only. ---
set(DEXP_THIRD ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party)
set(DEXP_ENGINE_LDFLAGS
        -Wl,--gc-sections
        -Wl,--exclude-libs,ALL
        -Wl,-z,max-page-size=16384)
include(engines/djvu.cmake)
include(engines/cre.cmake)
