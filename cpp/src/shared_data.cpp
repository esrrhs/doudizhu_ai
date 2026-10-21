// 共享资产定位实现。
#include "doudizhu/shared_data.h"

#include <filesystem>

namespace doudizhu
{

std::string FindSharedData(const std::string &relativeToRepoRoot)
{
    std::error_code ec;
    std::filesystem::path base = std::filesystem::current_path(ec);
    if (ec)
    {
        return {};
    }
    for (int up = 0; up <= kSharedDataMaxParents; ++up)
    {
        std::filesystem::path candidate = base;
        for (int i = 0; i < up; ++i)
        {
            candidate /= "..";
        }
        candidate /= relativeToRepoRoot;
        std::filesystem::path normalized = candidate.lexically_normal();
        if (std::filesystem::exists(normalized, ec) && !ec)
        {
            return normalized.string();
        }
    }
    return {};
}

std::string ResolveSharedData(const std::string &relativeToRepoRoot)
{
    std::string found = FindSharedData(relativeToRepoRoot);
    if (!found.empty())
    {
        return found;
    }
    std::error_code ec;
    std::filesystem::path base = std::filesystem::current_path(ec);
    if (ec)
    {
        return relativeToRepoRoot;
    }
    return (base / relativeToRepoRoot).lexically_normal().string();
}

} // namespace doudizhu
