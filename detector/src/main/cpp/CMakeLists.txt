cmake_minimum_required(VERSION 3.22.1)

project(litert_jni LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# LiteRT C++ SDK（由 Gradle 配置阶段自动下载到 litert_cc_sdk/）
set(LITERT_CC_SDK "${CMAKE_CURRENT_SOURCE_DIR}/litert_cc_sdk")

# 使用 zip 归档下载替代 git clone，避免国内网络环境下卡住
set(OVERRIDABLE_FETCH_CONTENT_GIT_REPOSITORY_AND_TAG_TO_URL ON CACHE BOOL "" FORCE)

# LiteRT C++ API（静态库）
# 二进制目录放在 CMAKE_BINARY_DIR 下，避免污染 litert_cc_sdk/build 源码树
add_subdirectory("${LITERT_CC_SDK}" "${CMAKE_BINARY_DIR}/litert_cc_sdk-build" EXCLUDE_FROM_ALL)

# 编译选项（arm64-v8a 默认支持 NEON，无需 -mfpu=neon）
add_compile_options(
    "-O2"
    "-DNDEBUG"
    "-march=armv8-a"
    "-ffast-math"
    "-Wno-deprecated-declarations"
    "-Wno-return-type"
    "-fpermissive"
)

# JNI 库
add_library(litert_jni SHARED
    LiteRtJni.cpp
    LiteRtDetector.cpp
    PostprocessorFactory.cpp
    YoloDetPostprocessor.cpp
    YoloClsPostprocessor.cpp
    YoloKptPostprocessor.cpp
    YoloSegPostprocessor.cpp
    YoloObbPostprocessor.cpp
)

target_include_directories(litert_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${LITERT_CC_SDK}
    ${CMAKE_BINARY_DIR}/include
)

target_link_libraries(litert_jni PRIVATE
    litert_cc_api
    absl::status
    absl::statusor
    absl::span
    absl::strings
    absl::log
    absl::die_if_null
    absl::raw_hash_set
    absl::log_internal_check_op
    flatbuffers::flatbuffers
    jnigraphics
    android
    EGL
    GLESv3
    log
)
