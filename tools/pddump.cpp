// Golden-reference dumper: plays games with the trained actors and writes, per decision,
// the deal, xImp/seq, every legal option with its actionFeat, the actor logits and the
// chosen action. The Java tests in this repository replay that record to prove the ported
// feature encoding is bit-identical to the training side.
//
// Kept here but compiled against an external PerfectDouCPP checkout (it only links that
// project's build/libperfectdou.a; the project itself is not modified):
//   g++ -std=c++20 -O2 -o pddump pddump.cpp -I<PerfectDouCPP>/src \
//       -I$TORCH/include -I$TORCH/include/torch/csrc/api/include \
//       <PerfectDouCPP>/build/libperfectdou.a -L$TORCH/lib -ltorch -ltorch_cpu -lc10 \
//       -lpthread -ldl -D_GLIBCXX_USE_CXX11_ABI=1
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

#include <torch/torch.h>

#include "ddz/features.h"
#include "ddz/game.h"
#include "torch_nn/models.h"

namespace {

void dumpFloats(const float* p, int n) {
    for (int i = 0; i < n; ++i) {
        std::printf(" %.9g", p[i]);
        if ((i + 1) % 20 == 0) std::printf("\n");
    }
    if (n % 20 != 0) std::printf("\n");
}

std::string actionStr(const ddz::CardSet& cs) {
    if (cs.empty()) return "-";
    return cs.str();
}

ddz::CardSet ourMove(const torch_nn::Actor& actor, const ddz::Game& g,
                    const std::vector<ddz::LegalOption>& options, std::vector<float>& logits) {
    torch::NoGradGuard no_grad;
    const int64_t L = static_cast<int64_t>(options.size());
    auto xImp = torch::zeros({1, torch_nn::kImpInput}, torch::kFloat32);
    auto seq = torch::zeros({1, torch_nn::kLstmSteps, torch_nn::kLstmIn}, torch::kFloat32);
    auto actFeat = torch::zeros({L, torch_nn::kActionInput}, torch::kFloat32);
    auto actSample = torch::zeros({L}, torch::kLong);
    auto actId = torch::zeros({L}, torch::kLong);

    ddz::EncodedState e = ddz::encodeState(g);
    float* px = xImp.data_ptr<float>();
    for (int j = 0; j < ddz::kNodeBin; ++j) px[j] = float(e.imp[j]);
    for (int j = 0; j < ddz::kImpScalars; ++j) px[ddz::kNodeBin + j] = e.scalar[j];

    float* ps = seq.data_ptr<float>();
    for (int k = 0; k < ddz::kHistoryLen; ++k) {
        const uint8_t* src = e.imp.data() + (ddz::kStaticMatrices + k) * ddz::kCardMat;
        for (int j = 0; j < ddz::kCardMat; ++j) ps[k * ddz::kCardMat + j] = float(src[j]);
    }

    float* pa = actFeat.data_ptr<float>();
    int64_t* pid = actId.data_ptr<int64_t>();
    for (int64_t i = 0; i < L; ++i) {
        for (int j = 0; j < ddz::kActionSize; ++j) pa[i * ddz::kActionSize + j] = options[i].feature[j];
        pid[i] = options[i].abstractId;
    }

    torch::Tensor out = actor->forward(xImp, seq, actFeat, actSample, actId);
    logits.assign(L, 0.0f);
    for (int64_t i = 0; i < L; ++i) logits[i] = out.data_ptr<float>()[options[i].abstractId];

    int best = 0;
    for (size_t i = 1; i < options.size(); ++i) {
        if (logits[i] > logits[best]) best = static_cast<int>(i);
    }
    return options[best].concrete;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 4) {
        std::fprintf(stderr, "usage: pddump <ngames> <seed0> <modelDir> [maxSteps]\n");
        return 2;
    }
    const int nGames = std::atoi(argv[1]);
    const uint64_t seed0 = std::strtoull(argv[2], nullptr, 10);
    const std::string modelDir = argv[3];
    const int maxSteps = argc > 4 ? std::atoi(argv[4]) : 200;

    torch::manual_seed(0);
    std::array<torch_nn::Actor, 3> actors;
    for (int s = 0; s < 3; ++s) {
        actors[s] = torch_nn::Actor();
        actors[s]->load(modelDir + "/actor" + std::to_string(s) + ".pt");
    }

    std::printf("DDZGOLDEN 1 %d %llu\n", nGames, (unsigned long long)seed0);
    torch::NoGradGuard no_grad;
    for (int g = 0; g < nGames; ++g) {
        ddz::Game gm;
        ddz::Rng rng(seed0 + uint64_t(g) * 7919ULL);
        gm.deal(rng);
        std::printf("GAME %d\n", g);
        for (int s = 0; s < 3; ++s) std::printf("DEAL %d %s\n", s, gm.hand[s].str().c_str());
        std::printf("BOTTOM %s\n", gm.bottom.str().c_str());
        std::printf("LANDLORD 0\n");  // 发牌后 seat 0 恒为地主

        int dec = 0;
        while (!gm.over && dec < maxSteps) {
            auto options = ddz::legalOptions(gm);
            if (options.empty()) break;

            ddz::EncodedState e = ddz::encodeState(gm);
            std::printf("DEC %d TURN %d HIST %zu\n", dec, gm.turn, gm.seq.size());
            std::printf("XIMP %d\n", ddz::kNodeSize);
            {
                std::vector<float> x(ddz::kNodeSize);
                for (int j = 0; j < ddz::kNodeBin; ++j) x[j] = float(e.imp[j]);
                for (int j = 0; j < ddz::kImpScalars; ++j) x[ddz::kNodeBin + j] = e.scalar[j];
                dumpFloats(x.data(), ddz::kNodeSize);
            }
            std::printf("SEQ %d\n", ddz::kHistoryLen * ddz::kCardMat);
            {
                std::vector<float> s(ddz::kHistoryLen * ddz::kCardMat);
                for (int k = 0; k < ddz::kHistoryLen; ++k) {
                    const uint8_t* src = e.imp.data() + (ddz::kStaticMatrices + k) * ddz::kCardMat;
                    for (int j = 0; j < ddz::kCardMat; ++j) s[k * ddz::kCardMat + j] = float(src[j]);
                }
                dumpFloats(s.data(), ddz::kHistoryLen * ddz::kCardMat);
            }
            std::printf("OPT %zu\n", options.size());
            for (const ddz::LegalOption& o : options) {
                std::printf("OPTLINE %s\n", actionStr(o.concrete).c_str());
                std::printf("AFEAT %d\n", ddz::kActionSize);
                dumpFloats(o.feature.data(), ddz::kActionSize);
            }
            std::vector<float> logits;
            ddz::CardSet mv = ourMove(actors[gm.turn], gm, options, logits);
            std::printf("LOGIT %zu\n", logits.size());
            dumpFloats(logits.data(), static_cast<int>(logits.size()));
            int chosen = 0;
            for (size_t i = 1; i < options.size(); ++i) {
                if (logits[i] > logits[chosen]) chosen = static_cast<int>(i);
            }
            std::printf("CHOSEN %d %s\n", chosen, actionStr(options[chosen].concrete).c_str());
            // The action taken at this decision, so a reader can replay the record
            // incrementally: at DEC d the prefix is the first d ACT lines of the game.
            std::printf("ACT %d %s\n", gm.turn, actionStr(mv).c_str());
            std::printf("ENDDEC\n");

            gm.step(mv);
            ++dec;
        }
        std::printf("ENDGAME\n");
        std::fflush(stdout);
    }
    return 0;
}
