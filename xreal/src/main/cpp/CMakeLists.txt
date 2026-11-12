cmake_minimum_required(VERSION 3.22)
project(xrealbeamlink CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 内核源码随模块走，不再依赖仓库根目录
set(XR_ROOT "${CMAKE_CURRENT_SOURCE_DIR}")

add_library(xrealcore STATIC
        "${XR_ROOT}/core/src/fusion_port.cpp"
        "${XR_ROOT}/core/src/xreal_imu.cpp"
        "${XR_ROOT}/core/src/xreal_tracker.cpp"
)

target_include_directories(xrealcore PUBLIC
        "${XR_ROOT}/core/include"
)

add_library(xrealbeamlink SHARED
        xr_jni.cpp
)

target_link_libraries(xrealbeamlink xrealcore android log)

target_compile_options(xrealcore PRIVATE -O3 -fno-math-errno)
