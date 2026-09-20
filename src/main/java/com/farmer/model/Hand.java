package com.farmer.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 斗地主手牌类，支持包含大小王在内的 54 张牌频次管理
 */
public class Hand {
    // 数组索引对应 Rank 的点数: 3..17 (16:小王, 17:大王)
    private final int[] counts = new int[18];
    private int totalCards = 0;

    public Hand() {
    }

    public Hand(Hand other) {
        System.arraycopy(other.counts, 0, this.counts, 0, 18);
        this.totalCards = other.totalCards;
    }

    public Hand copy() {
        return new Hand(this);
    }

    public void add(Rank rank) {
        counts[rank.getValue()]++;
        totalCards++;
    }

    public void add(int rankValue) {
        counts[rankValue]++;
        totalCards++;
    }

    public void remove(Rank rank) {
        int v = rank.getValue();
        if (counts[v] <= 0) {
            throw new IllegalStateException("Hand does not contain rank: " + rank);
        }
        counts[v]--;
        totalCards--;
    }

    public void remove(int rankValue) {
        if (counts[rankValue] <= 0) {
            throw new IllegalStateException("Hand does not contain rank value: " + rankValue);
        }
        counts[rankValue]--;
        totalCards--;
    }

    public boolean canPlay(Move move) {
        if (move.isPass()) {
            return true;
        }
        if (move.getCardCount() > totalCards) {
            return false;
        }
        int[] temp = new int[18];
        for (Rank r : move.getCards()) {
            temp[r.getValue()]++;
            if (temp[r.getValue()] > counts[r.getValue()]) {
                return false;
            }
        }
        return true;
    }

    public void play(Move move) {
        if (move.isPass()) {
            return;
        }
        for (Rank r : move.getCards()) {
            remove(r);
        }
    }

    public void unplay(Move move) {
        if (move.isPass()) {
            return;
        }
        for (Rank r : move.getCards()) {
            add(r);
        }
    }

    public int getCount(int rankValue) {
        if (rankValue < 3 || rankValue > 17) return 0;
        return counts[rankValue];
    }

    public int getCount(Rank rank) {
        return counts[rank.getValue()];
    }

    public int getTotalCards() {
        return totalCards;
    }

    public boolean isEmpty() {
        return totalCards == 0;
    }

    public void clear() {
        Arrays.fill(counts, 0);
        totalCards = 0;
    }

    public boolean hasRocket() {
        return counts[Rank.BLACK_JOKER.getValue()] > 0 && counts[Rank.RED_JOKER.getValue()] > 0;
    }

    public List<Rank> getCards() {
        List<Rank> list = new ArrayList<>(totalCards);
        for (int v = 3; v <= 17; v++) {
            for (int i = 0; i < counts[v]; i++) {
                list.add(Rank.fromValue(v));
            }
        }
        return list;
    }

    public String toCardString() {
        return getCards().stream()
                .map(Rank::getSymbol)
                .collect(Collectors.joining(","));
    }

    @Override
    public String toString() {
        return String.format("[%s] (%d cards)", toCardString(), totalCards);
    }
}
