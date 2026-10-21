// 斗地主牌局状态机 (2v1 阵营胜负结算)。对应 Java game/GameState.java 与 game/Player.java。
#pragma once

#include <array>
#include <optional>
#include <vector>

#include "doudizhu/model.h"
#include "doudizhu/public_view.h"

namespace doudizhu
{

class GameState
{
public:
    // hands: 三家手牌 (地主已含 3 张底牌); landlordId: 地主座位; bottom: 底牌牌面
    GameState(const std::array<CardSet, 3> &hands, int landlordId, const CardSet &bottom);

    // 执行一个动作并推进游戏状态 (Java applyMove)
    void ApplyMove(const Move &move);

    // 指定玩家的公开视角 (Java getPublicView)
    PublicView GetPublicView(int viewingPlayerId) const;

    // 本视角下的合法动作 (多重集), 应牌时末项恒为过牌。
    // 注意: 这里用训练侧的完整枚举 PerfectDouMoves::Legal, 而非引擎 rules/MoveGenerator
    //       (本工程未移植 MoveGenerator; 策略网络的动作空间就是完整枚举)。
    std::vector<CardSet> LegalMoves() const;

    int ActivePlayerIndex() const { return activePlayerIndex_; }
    int LandlordId() const { return landlordId_; }
    bool IsLandlord(int playerId) const { return playerId == landlordId_; }
    const std::optional<Move> &LastMove() const { return lastMove_; }
    int LastMovePlayerId() const { return lastMovePlayerId_; }
    int PassCount() const { return passCount_; }
    const std::vector<Move> &MoveHistory() const { return moveHistory_; }
    bool IsGameOver() const { return isGameOver_; }
    int WinnerId() const { return winnerId_; }
    // 斗地主中任意农民出完手牌, 两位农民均获胜
    bool IsPlayerWinner(int playerId) const;

    const CardSet &Hand(int playerId) const { return hands_[static_cast<std::size_t>(playerId)]; }
    int HandCount(int playerId) const { return Hand(playerId).Total(); }
    const CardSet &Bottom() const { return bottom_; }

private:
    std::array<CardSet, 3> hands_{};
    int landlordId_ = 0;
    CardSet bottom_;
    int activePlayerIndex_ = 0;
    std::optional<Move> lastMove_;
    int lastMovePlayerId_ = -1;
    int passCount_ = 0;
    bool isGameOver_ = false;
    int winnerId_ = -1;
    std::vector<Move> moveHistory_;
};

} // namespace doudizhu
