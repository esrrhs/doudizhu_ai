package com.doudizhu.nn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 定位仓库内跨语言共享的资产 (权重与对齐基准)。
 *
 * <p>{@code data/} 与 {@code java/ go/ cpp/ server/} 平级，因此同一份文件在不同启动方式下的相对路径
 * 不一样：从 {@code java/} 里跑 Maven、从仓库根跑 IDE、或 Go/C++ 测试各自的工作目录都不同。
 * 这里按顺序试探当前目录及其上层，保证三种语言读到的是同一份数据。
 */
public final class SharedData {

    /** 最多向上找几层目录 */
    private static final int MAX_PARENTS = 3;

    private SharedData() {
    }

    /** 返回相对仓库根 {@code relativeToRepoRoot} 的实际路径，找不到返回 null */
    public static Path find(String relativeToRepoRoot) {
        Path base = Paths.get("").toAbsolutePath();
        for (int up = 0; up <= MAX_PARENTS; ++up) {
            Path candidate = up == 0
                    ? base.resolve(relativeToRepoRoot)
                    : base.resolve(Path.of("../").toString().repeat(up)).resolve(relativeToRepoRoot)
                            .normalize();
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 同 {@link #find}，但找不到时返回按当前目录解释的路径，
     * 让调用方抛出带有具体文件名的错误信息。
     */
    public static Path resolve(String relativeToRepoRoot) {
        Path found = find(relativeToRepoRoot);
        return found != null ? found : Paths.get(relativeToRepoRoot).toAbsolutePath();
    }

    /** 必须在仓库里找到该文件，否则给出可操作的报错 */
    public static Path require(String relativeToRepoRoot) throws IOException {
        Path found = find(relativeToRepoRoot);
        if (found == null) {
            throw new IOException("在 " + Paths.get("").toAbsolutePath() + " 及其上层未找到 "
                    + relativeToRepoRoot + " (该文件应与 java/ 平级放在 data/ 下)");
        }
        return found;
    }
}
