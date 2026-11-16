project(queueplayer)
cmake_minimum_required(VERSION 3.9.0)

set(PACKAGE_NAME queueplayer)
set(CMAKE_VERBOSE_MAKEFILE ON)
set(CMAKE_CXX_STANDARD 20)

# Define C++ library — JNI adapter only. The shared pure-logic ../cpp
# layer (QueueCore + LookaheadCachePolicy + EventBus) was deleted in
# Phase 2 / PR-2.1 (2026-04-29) — it had been dead at runtime since
# Phase 1 with iOS Swift + Android Kotlin both maintaining native
# ports. Only the GoogleTest suite consumed the C++ symbols.
add_library(${PACKAGE_NAME} SHARED
    src/main/cpp/cpp-adapter.cpp
)

# Add Nitrogen specs :)
include(${CMAKE_SOURCE_DIR}/../nitrogen/generated/android/queueplayer+autolinking.cmake)

# Set up local includes
include_directories("src/main/cpp")

find_library(LOG_LIB log)

# Link all libraries together
target_link_libraries(
        ${PACKAGE_NAME}
        ${LOG_LIB}
        android # <-- Android core
)

# AirPlay 1 (libraop) + AirPlay 2 (libairplay2) — native sender libs
# defined in src/main/cpp/CMakeLists.txt. Only arm64-v8a and x86_64 are
# supported — the prebuilt OpenSSL + libsodium archives shipped under
# src/main/cpp/prebuilt/ exist for those ABIs only. On other ABIs the
# raop / airplay2 .so are simply not built; AirplayJNI / AirPlay2JNI
# catch the System.loadLibrary failure and AirPlayBackend treats the
# protocol as unavailable.
if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "x86_64")
    add_subdirectory(src/main/cpp)
endif()
