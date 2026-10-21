cmake_minimum_required(VERSION 3.16)
project(doudizhu_cpp CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

set(DOUDIZHU_SRCS
    src/model.cpp
    src/perfect_dou_moves.cpp
    src/oracle.cpp
    src/perfect_dou_features.cpp
    src/public_view.cpp
    src/game_state.cpp
    src/shared_data.cpp
    src/pt_archive.cpp
    src/policy.cpp
)

# 库零第三方依赖: 权重是 STORED(不压缩) 的 TorchScript zip 条目, 自己解析中央目录
add_library(doudizhu STATIC ${DOUDIZHU_SRCS})
target_include_directories(doudizhu PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)

if(MSVC)
    target_compile_options(doudizhu PRIVATE /utf-8 /W4)
else()
    # -ffp-contract=off 保证浮点结果与 Java/Go 逐位一致 (特征标量与 logits)
    target_compile_options(doudizhu PRIVATE -Wall -Wextra -ffp-contract=off)
endif()

enable_testing()

# 基准记录是 gzip 文本: 测试优先用系统 zlib 读 .gz;
# 若同目录已解压出 golden24.txt, 则不需要 zlib。
add_executable(doudizhu_parity_test test/parity_test.cpp)
target_link_libraries(doudizhu_parity_test PRIVATE doudizhu)
target_include_directories(doudizhu_parity_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src)
if(NOT MSVC)
    target_compile_options(doudizhu_parity_test PRIVATE -Wall -Wextra -ffp-contract=off)
endif()

find_package(ZLIB)
if(ZLIB_FOUND)
    target_link_libraries(doudizhu_parity_test PRIVATE ZLIB::ZLIB)
else()
    message(STATUS "未找到 zlib: parity 测试改为只接受已解压的 data/golden/golden24.txt")
    target_compile_definitions(doudizhu_parity_test PRIVATE DOUDIZHU_NO_ZLIB=1)
endif()

# 测试工作目录固定为 cpp/, 使 FindSharedData 能找到 ../data
add_test(NAME parity COMMAND doudizhu_parity_test
         WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR})
