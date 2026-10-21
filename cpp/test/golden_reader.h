// 读取 PerfectDouCPP tools/pddump.cpp 导出的 golden 记录 (data/golden/golden24.txt.gz)。
//
// 记录是行式的: 每个数值块以一行 "{@code <KEY> <个数>} 表头" 开头, 其后每行最多 20 个值,
// 所以按 token 逐个读取最稳妥 (与 Java 侧 test/.../GoldenReader.java 同构)。牌面用训练侧写法:
// 3..9 T J Q K A 2 B(小王) R(大王), 过牌为 "-"。
//
// 库本身不依赖 zlib, 只有测试用系统 zlib 解压 .gz; 若同目录已有解压好的 golden24.txt,
// 或构建时未找到 zlib (CMake 会定义 DOUDIZHU_NO_ZLIB), 则只读纯文本。
#pragma once

#include <cstdio>
#include <cstdlib>
#include <stdexcept>
#include <string>
#include <vector>

#ifndef DOUDIZHU_NO_ZLIB
#include <zlib.h>
#endif

#include "doudizhu/model.h"
#include "doudizhu/shared_data.h"

namespace dtest
{

class GoldenReader
{
public:
    // 依次尝试 relativeToRepoRoot 与其去掉 .gz 的纯文本版本
    bool Open(const std::string &relativeToRepoRoot, std::string *err)
    {
        std::string plain = relativeToRepoRoot;
        if (plain.size() > 3 && plain.compare(plain.size() - 3, 3, ".gz") == 0)
        {
            plain.erase(plain.size() - 3);
        }
#ifndef DOUDIZHU_NO_ZLIB
        std::string gzPath = doudizhu::FindSharedData(relativeToRepoRoot);
        if (!gzPath.empty())
        {
            gz_ = gzopen(gzPath.c_str(), "rb");
            if (gz_ == nullptr)
            {
                *err = "gzopen 失败: " + gzPath;
                return false;
            }
            path_ = gzPath;
            return true;
        }
#endif
        std::string txtPath = doudizhu::FindSharedData(plain);
        if (!txtPath.empty())
        {
            fp_ = std::fopen(txtPath.c_str(), "rb");
            if (fp_ == nullptr)
            {
                *err = "打不开 " + txtPath;
                return false;
            }
            path_ = txtPath;
            return true;
        }
        *err = "找不到 " + relativeToRepoRoot + " 或 " + plain +
               " (基准记录应与 java/ go/ cpp/ 平级放在 data/ 下)";
        return false;
    }

    GoldenReader() = default;
    ~GoldenReader() { Close(); }

    GoldenReader(const GoldenReader &) = delete;
    GoldenReader &operator=(const GoldenReader &) = delete;

    void Close()
    {
#ifndef DOUDIZHU_NO_ZLIB
        if (gz_ != nullptr)
        {
            gzclose(gz_);
            gz_ = nullptr;
        }
#endif
        if (fp_ != nullptr)
        {
            std::fclose(fp_);
            fp_ = nullptr;
        }
    }

    const std::string &Path() const { return path_; }

    bool HasNext()
    {
        return index_ < tokens_.size() || ReadLine();
    }

    const std::string &NextToken()
    {
        while (index_ >= tokens_.size())
        {
            if (!ReadLine())
            {
                throw std::runtime_error("golden 文件提前结束: " + path_);
            }
        }
        return tokens_[index_++];
    }

    // 丢弃当前行剩余内容
    void SkipLine()
    {
        tokens_.clear();
        index_ = 0;
    }

    std::vector<float> ReadFloats(int n)
    {
        std::vector<float> out(static_cast<std::size_t>(n));
        for (int i = 0; i < n; ++i)
        {
            out[static_cast<std::size_t>(i)] = std::strtof(NextToken().c_str(), nullptr);
        }
        return out;
    }

private:
    // 读入下一非空行并填满缓冲区; 文件结束返回 false
    bool ReadLine()
    {
        for (;;)
        {
            std::string line;
            if (!ReadRawLine(&line))
            {
                return false;
            }
            tokens_.clear();
            index_ = 0;
            std::size_t i = 0;
            while (i < line.size())
            {
                while (i < line.size() && (line[i] == ' ' || line[i] == '\t' || line[i] == '\r' ||
                                           line[i] == '\n'))
                {
                    ++i;
                }
                const std::size_t begin = i;
                while (i < line.size() && line[i] != ' ' && line[i] != '\t' && line[i] != '\r' &&
                       line[i] != '\n')
                {
                    ++i;
                }
                if (i > begin)
                {
                    tokens_.push_back(line.substr(begin, i - begin));
                }
            }
            if (!tokens_.empty())
            {
                return true;
            }
        }
    }

    bool ReadRawLine(std::string *out)
    {
        char buf[65536];
#ifndef DOUDIZHU_NO_ZLIB
        if (gz_ != nullptr)
        {
            if (gzgets(gz_, buf, static_cast<int>(sizeof(buf))) == nullptr)
            {
                return false;
            }
            out->assign(buf);
            return true;
        }
#endif
        if (fp_ == nullptr)
        {
            return false;
        }
        if (std::fgets(buf, static_cast<int>(sizeof(buf)), fp_) == nullptr)
        {
            return false;
        }
        out->assign(buf);
        return true;
    }

#ifndef DOUDIZHU_NO_ZLIB
    gzFile gz_ = nullptr;
#endif
    std::FILE *fp_ = nullptr;
    std::string path_;
    std::vector<std::string> tokens_;
    std::size_t index_ = 0;
};

// 期望读到 key, 否则记录一次格式错误 (返回 false 时调用方应尽快退出回放)
inline bool ExpectKey(const std::string &got, const char *key, int decision,
                      std::vector<std::string> *problems)
{
    if (got == key)
    {
        return true;
    }
    if (problems->size() < 20)
    {
        problems->push_back("第" + std::to_string(decision) + "个决策: 期望 " + key + ", 实际读到 " +
                            got);
    }
    return false;
}

inline doudizhu::CardSet ParseCards(const std::string &token)
{
    try
    {
        return doudizhu::ParseCards(token);
    }
    catch (const std::exception &)
    {
        return doudizhu::CardSet{};
    }
}

} // namespace dtest
