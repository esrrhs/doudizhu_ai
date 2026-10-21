# 斗地主网页端的 fakelua 宿主。
#
# 依赖两样: cpp/ 的引擎静态库 (与 Java/Go 逐位对齐的那一份) 和 fakelua。
# fakelua 优先用系统里装好的 (cmake --install 会导出 fakelua::fakelua), 找不到时
# 用 -DFAKELUA_DIR=<源码目录> 直接把它编进来。
cmake_minimum_required(VERSION 3.16)
project(doudizhu_server CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

# 宿主自己读 3 个开关, 不引入 gflags; 引擎的对齐测试由 cpp/ 自己那条流水线跑
set(DOUDIZHU_BUILD_TESTS OFF CACHE BOOL "" FORCE)
add_subdirectory(../../cpp ${CMAKE_CURRENT_BINARY_DIR}/cpp)

option(FAKELUA_BUILD_TESTS "构建 fakelua 单元测试" OFF)
option(FAKELUA_BUILD_BENCHMARKS "构建 fakelua benchmark" OFF)
# 系统里可能同时有多个前缀装过 fakelua (/usr/local 与 /opt/homebrew), 只认这里指定的那个。
# PATH_SUFFIXES 里带上 Debian/Ubuntu 的 multiarch 目录, 那里 GNUInstallDirs 会把
# lib/cmake/fakelua 装成 lib/x86_64-linux-gnu/cmake/fakelua。
set(FAKELUA_PREFIX "/opt/homebrew" CACHE PATH "已安装 fakelua 的前缀")
find_package(fakelua CONFIG QUIET HINTS "${FAKELUA_PREFIX}"
        PATH_SUFFIXES lib lib64 lib/x86_64-linux-gnu NO_DEFAULT_PATH)
if(NOT fakelua_FOUND)
    if(NOT FAKELUA_DIR)
        message(FATAL_ERROR
            "在 ${FAKELUA_PREFIX} 找不到 fakeluaConfig.cmake。二选一:\n"
            "  1) 在 fakelua 源码目录 cmake --install build --prefix ${FAKELUA_PREFIX}\n"
            "  2) 配置时加 -DFAKELUA_PREFIX=/other/prefix 或 -DFAKELUA_DIR=/path/to/fakelua")
    endif()
    add_subdirectory(${FAKELUA_DIR} ${CMAKE_CURRENT_BINARY_DIR}/fakelua)
endif()

# 装好的 fakelua 导出带命名空间的 target, 源码内编出来的只有裸名
if(TARGET fakelua::fakelua)
    set(FAKELUA_LINK fakelua::fakelua)
else()
    set(FAKELUA_LINK fakelua)
endif()

add_executable(doudizhu_server main.cpp)
target_link_libraries(doudizhu_server PRIVATE doudizhu ${FAKELUA_LINK})
if(NOT MSVC)
    target_compile_options(doudizhu_server PRIVATE -Wall -Wextra)
endif()

install(TARGETS doudizhu_server RUNTIME DESTINATION bin)
