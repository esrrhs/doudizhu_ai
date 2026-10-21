// 牌局状态机实现, 逐分支移植自 Java game/GameState.java。
#include "doudizhu/game_state.h"

#include <stdexcept>

#include "doudizhu/perfect_dou_moves.h"

namespace doudizhu
{

GameState::GameState(const std::array<CardSet, 3> &hands, int landlordId, const CardSet &bottom)
    : hands_(hands), landlordId_(landlordId), bottom_(bottom)
{
    if (landlordId < 0 || landlordId > 2)
    {
        throw std::invalid_argument("GameState 要求地主座位在 0..2");
    }
    activePlayerIndex_ = landlordId; // 斗地主由地主先手出牌
}

void GameState::ApplyMove(const Move &move)
{
    if (isGameOver_)
    {
        throw std::logic_error("Game is already over");
    }

    const int currentId = activePlayerIndex_;
    CardSet &hand = hands_[static_cast<std::size_t>(currentId)];
    for (int r = 0; r < kRanks; ++r)
    {
        if (hand[r] < move.cards[r])
        {
            throw std::logic_error("手牌中没有这手牌: " + DebugCards(move.cards));
        }
    }
    for (int r = 0; r < kRanks; ++r)
    {
        hand[r] -= move.cards[r];
    }
    moveHistory_.push_back(move);

    // 检查终局
    if (hand.Empty())
    {
        isGameOver_ = true;
        winnerId_ = currentId;
        return;
    }

    if (move.IsPass())
    {
        passCount_++;
        if (passCount_ == 2)
        {
            // 两家都过牌, 本轮结束, 由上一轮最大出牌者重新出牌 (Lead)
            activePlayerIndex_ = lastMovePlayerId_;
            lastMove_.reset();
            lastMovePlayerId_ = -1;
            passCount_ = 0;
        }
        else
        {
            activePlayerIndex_ = (activePlayerIndex_ + 1) % 3;
        }
    }
    else
    {
        lastMove_ = move;
        lastMovePlayerId_ = currentId;
        passCount_ = 0;
        activePlayerIndex_ = (activePlayerIndex_ + 1) % 3;
    }
}

bool GameState::IsPlayerWinner(int playerId) const
{
    if (!isGameOver_)
    {
        return false;
    }
    // 地主出完只有地主赢; 任一农民出完两位农民都算赢
    return IsLandlord(winnerId_) ? playerId == landlordId_ : playerId != landlordId_;
}

std::vector<CardSet> GameState::LegalMoves() const
{
    if (isGameOver_)
    {
        return {};
    }
    CardSet toBeat;
    if (lastMove_)
    {
        toBeat = lastMove_->cards;
    }
    return Legal(Hand(ActivePlayerIndex()), toBeat);
}

PublicView GameState::GetPublicView(int viewingPlayerId) const
{
    PublicView view;
    view.viewingPlayerId = viewingPlayerId;
    view.landlordId = landlordId_;
    view.bottom = bottom_;
    view.myHand = hands_[static_cast<std::size_t>(viewingPlayerId)];
    for (int i = 0; i < 3; ++i)
    {
        view.cardCounts[static_cast<std::size_t>(i)] = hands_[static_cast<std::size_t>(i)].Total();
    }
    view.moveHistory = moveHistory_;
    view.lastMove = lastMove_;
    view.lastMovePlayerId = lastMovePlayerId_;
    view.activePlayerId = activePlayerIndex_;
    view.passCount = passCount_;
    return view;
}

} // namespace doudizhu
