// 仓库内跨语言共享资产的定位 (权重与对齐基准)。对应 Java nn/SharedData.java。
//
// data/ 与 java/ go/ cpp/ server/ 平级, 从不同目录启动时相对路径不一样,
// 因此依次在当前目录及其上 1..3 层查找, 保证三种语言读到同一份文件。
#pragma once

#include <string>

namespace doudizhu
{

// 最多向上找几层目录
inline constexpr int kSharedDataMaxParents = 3;

// 返回相对仓库根 relativeToRepoRoot 的实际路径; 找不到返回空串 (Java SharedData.find)
std::string FindSharedData(const std::string &relativeToRepoRoot);

// 同 FindSharedData, 但找不到时返回按当前目录解释的绝对路径,
// 让调用方抛出带有具体文件名的错误 (Java SharedData.resolve)
std::string ResolveSharedData(const std::string &relativeToRepoRoot);

} // namespace doudizhu
