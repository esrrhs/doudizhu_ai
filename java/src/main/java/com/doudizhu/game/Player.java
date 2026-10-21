package com.doudizhu.game;

import com.doudizhu.model.Hand;
import com.doudizhu.model.Role;

import java.util.Objects;

/**
 * 斗地主玩家实体 (区分地主与农民阵营)
 */
public class Player {
    private final int id;
    private final String name;
    private final Role role;
    private final Hand hand;
    private final boolean isAi;

    public Player(int id, String name, Role role, Hand hand, boolean isAi) {
        this.id = id;
        this.name = name;
        this.role = Objects.requireNonNull(role, "role cannot be null");
        this.hand = Objects.requireNonNull(hand, "hand cannot be null");
        this.isAi = isAi;
    }

    public Player copy() {
        return new Player(id, name, role, hand.copy(), isAi);
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Role getRole() {
        return role;
    }

    public Hand getHand() {
        return hand;
    }

    public boolean isAi() {
        return isAi;
    }

    public int getCardCount() {
        return hand.getTotalCards();
    }

    public boolean hasWon() {
        return hand.isEmpty();
    }

    @Override
    public String toString() {
        return String.format("%s [%s] (ID=%d, %d cards)", name, role.getDescription(), id, getCardCount());
    }
}
