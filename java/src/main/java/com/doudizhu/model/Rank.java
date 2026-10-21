package com.doudizhu.model;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 斗地主卡牌点数枚举 (3最小, 大王最大)
 */
public enum Rank {
    THREE(3, "3"),
    FOUR(4, "4"),
    FIVE(5, "5"),
    SIX(6, "6"),
    SEVEN(7, "7"),
    EIGHT(8, "8"),
    NINE(9, "9"),
    TEN(10, "10"),
    JACK(11, "J"),
    QUEEN(12, "Q"),
    KING(13, "K"),
    ACE(14, "A"),
    TWO(15, "2"),
    BLACK_JOKER(16, "BJ"),
    RED_JOKER(17, "RJ");

    private final int value;
    private final String symbol;

    private static final Map<Integer, Rank> VALUE_MAP =
            Arrays.stream(values()).collect(Collectors.toMap(Rank::getValue, r -> r));

    private static final Map<String, Rank> SYMBOL_MAP = new java.util.HashMap<>();

    static {
        for (Rank r : values()) {
            SYMBOL_MAP.put(r.getSymbol().toUpperCase(), r);
        }
        SYMBOL_MAP.put("小王", BLACK_JOKER);
        SYMBOL_MAP.put("大王", RED_JOKER);
        SYMBOL_MAP.put("B", BLACK_JOKER);
        SYMBOL_MAP.put("R", RED_JOKER);
        SYMBOL_MAP.put("T", TEN);
    }

    Rank(int value, String symbol) {
        this.value = value;
        this.symbol = symbol;
    }

    public int getValue() {
        return value;
    }

    public String getSymbol() {
        return symbol;
    }

    public static Rank fromValue(int value) {
        Rank rank = VALUE_MAP.get(value);
        if (rank == null) {
            throw new IllegalArgumentException("Unknown card rank value: " + value);
        }
        return rank;
    }

    public static Rank fromSymbol(String symbol) {
        if (symbol == null) {
            throw new IllegalArgumentException("Symbol cannot be null");
        }
        Rank rank = SYMBOL_MAP.get(symbol.trim().toUpperCase());
        if (rank == null) {
            // Check Chinese symbols directly
            rank = SYMBOL_MAP.get(symbol.trim());
        }
        if (rank == null) {
            throw new IllegalArgumentException("Unknown card rank symbol: " + symbol);
        }
        return rank;
    }

    @Override
    public String toString() {
        return symbol;
    }
}
