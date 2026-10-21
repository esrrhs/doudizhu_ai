package com.doudizhu.model;

/**
 * 斗地主阵营角色
 */
public enum Role {
    LANDLORD("地主"),
    FARMER("农民");

    private final String description;

    Role(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public boolean isLandlord() {
        return this == LANDLORD;
    }

    public boolean isFarmer() {
        return this == FARMER;
    }

    public boolean isTeammateWith(Role other) {
        return this == other && this == FARMER;
    }
}
