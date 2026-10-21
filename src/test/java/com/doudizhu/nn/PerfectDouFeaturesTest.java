package com.doudizhu.nn;

import com.doudizhu.model.Rank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 座位映射与牌矩阵的定点检查。
 *
 * <p>golden 对齐测试里地主恒为 seat 0, 覆盖不到「地主不是 0 号玩家」时的 actor 选择, 这里补上。
 */
class PerfectDouFeaturesTest {

    @Test
    @DisplayName("座位映射：actor 编号按相对地主的位置确定")
    void actorIndexFollowsLandlordSeat() {
        // 出牌顺序恒为 0 -> 1 -> 2 -> 0, 训练侧 seat: 0=地主, 1=地主下家, 2=地主上家
        for (int landlordId = 0; landlordId < 3; ++landlordId) {
            assertThat(PerfectDouFeatures.actorIndex(landlordId, landlordId)).isEqualTo(0);
            assertThat(PerfectDouFeatures.actorIndex((landlordId + 1) % 3, landlordId)).isEqualTo(1);
            assertThat(PerfectDouFeatures.actorIndex((landlordId + 2) % 3, landlordId)).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("牌矩阵：张数行、炸弹、王炸与飞机标记落在正确的行列")
    void cardMatrixMarksExpectedRows() {
        float[] out = new float[PerfectDouFeatures.CARD_MATRIX];

        // KKKK: 张数 4 -> 行 0..3 点亮, 行 7 (炸弹) 点亮; K 的索引是 10
        PerfectDouFeatures.cardMatrix(counts(10, 4), out, 0);
        for (int row = 0; row < 4; ++row) {
            assertThat(out[row * 15 + 10]).as("第%d行", row).isEqualTo(1f);
        }
        assertThat(out[7 * 15 + 10]).isEqualTo(1f);
        assertThat(out[4 * 15 + 10]).isEqualTo(1f);   // 单张
        assertThat(out[5 * 15 + 10]).isEqualTo(1f);   // 对子
        assertThat(out[6 * 15 + 10]).isEqualTo(1f);   // 三张

        // 王炸点亮行 8 的两个王牌列
        PerfectDouFeatures.cardMatrix(counts(13, 1, 14, 1), out, 0);
        assertThat(out[8 * 15 + 13]).isEqualTo(1f);
        assertThat(out[8 * 15 + 14]).isEqualTo(1f);

        // 34567 顺子: 行 9 (单张链) 对这五个点数都点亮
        PerfectDouFeatures.cardMatrix(counts(0, 1, 1, 1, 2, 1, 3, 1, 4, 1), out, 0);
        for (int r = 0; r <= 4; ++r) {
            assertThat(out[9 * 15 + r]).as("链上的 %d", r).isEqualTo(1f);
        }
        assertThat(out[10 * 15 + 2]).as("连对不成立").isEqualTo(0f);
    }

    private static int[] counts(int... rankCountPairs) {
        int[] c = new int[15];
        for (int i = 0; i < rankCountPairs.length; i += 2) {
            c[rankCountPairs[i]] = rankCountPairs[i + 1];
        }
        return c;
    }

    @Test
    @DisplayName("点数索引：Rank 数值减 3 即训练侧的牌索引")
    void rankIndexMatchesTrainingSide() {
        assertThat(Rank.THREE.getValue() - 3).isZero();
        assertThat(Rank.ACE.getValue() - 3).isEqualTo(11);
        assertThat(Rank.TWO.getValue() - 3).isEqualTo(12);
        assertThat(Rank.BLACK_JOKER.getValue() - 3).isEqualTo(13);
        assertThat(Rank.RED_JOKER.getValue() - 3).isEqualTo(14);
    }
}
