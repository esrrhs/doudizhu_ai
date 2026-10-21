#!/usr/bin/env python3
"""Convert PerfectDouCPP libtorch checkpoints into a TorchScript module with a forward.

The training code saves actors with torch::serialize::OutputArchive, which produces a
parameter-only archive: torch.jit.load opens it but `forward` is not defined, so no
TorchScript runtime (DJL / libtorch Java) can call it. This tool rebuilds the module in
Python and scripts it. See tools/README.md for the full workflow.

Run in two stages, because the archive must be read by the version that wrote it and the
script must be written by the version the Java runtime bundles:

  # stage 1, torch matching the training box (extracts parameters)
  python3 export_actor.py extract --in models/latest --out /tmp/params
  # stage 2, torch matching the Java engine's native libtorch (DJL 0.39 -> 2.7.1)
  /path/to/venv/bin/python export_actor.py script --in /tmp/params --out models/ts

Stage 2 also replays the golden decisions produced by tools/pddump to report the largest
logit deviation between the C++ reference and the scripted module.
"""

import argparse
import os
import sys

import torch

LSTM_IN = 540
LSTM_HIDDEN = 128
LSTM_STEPS = 10
NODE_IN = 1676
ACTION_IN = 186
HIDDEN = 256

LSTM_GATE_ORDER = (
    "LSTM weight chunks are ordered i (input gate), f (forget gate), "
    "g (cell candidate), o (output gate)"
)

PARAM_NAMES = [
    "lstm.weight_ih_l0", "lstm.weight_hh_l0", "lstm.bias_ih_l0", "lstm.bias_hh_l0",
    "l1.weight", "l1.bias", "l2.weight", "l2.bias", "l3.weight", "l3.bias",
    "l4.weight", "l4.bias", "head.weight", "head.bias",
]


class Actor(torch.nn.Module):
    """Mirror of torch_nn::ActorImpl::forward in src/torch_nn/models.cpp."""

    def __init__(self) -> None:
        super().__init__()
        self.lstm = torch.nn.LSTM(LSTM_IN, LSTM_HIDDEN, batch_first=True)
        self.l1 = torch.nn.Linear(NODE_IN + LSTM_HIDDEN + ACTION_IN, HIDDEN)
        self.l2 = torch.nn.Linear(HIDDEN, HIDDEN)
        self.l3 = torch.nn.Linear(HIDDEN, HIDDEN)
        self.l4 = torch.nn.Linear(HIDDEN, HIDDEN * 2)
        self.head = torch.nn.Linear(HIDDEN * 2, 1)

    def forward(self, x_imp: torch.Tensor, seq: torch.Tensor, action_feat: torch.Tensor,
                action_sample: torch.Tensor, action_id: torch.Tensor) -> torch.Tensor:
        # 1804 = NODE_IN + LSTM_HIDDEN, 2048 = NUM_ACTIONS; TorchScript will not close over
        # module-level ints, so the shapes that appear in the body are spelled out.
        h = self.lstm(seq)[1][0].squeeze(0)
        node = torch.cat([x_imp, h], dim=1)
        scores = torch.relu(self.l1(torch.cat([node.index_select(0, action_sample), action_feat], dim=1)))
        scores = torch.relu(self.l2(scores))
        scores = torch.relu(self.l3(scores))
        scores = torch.relu(self.l4(scores))
        scores = self.head(scores).squeeze(-1)
        logits = torch.full((x_imp.size(0), 2048), -1e9, dtype=torch.float32)
        logits.index_put_((action_sample, action_id), scores)
        return logits


def extract(src: str, dst: str) -> None:
    os.makedirs(dst, exist_ok=True)
    for seat in range(3):
        path = os.path.join(src, f"actor{seat}.pt")
        module = torch.jit.load(path, map_location="cpu")
        available = dict(module.named_parameters())
        extra = set(available) - set(PARAM_NAMES)
        assert not extra, f"{path} has unexpected parameters {extra}; the checkpoint was " \
                          f"trained with a different architecture than this exporter"
        params = {name: available[name].detach().clone() for name in PARAM_NAMES}
        for name, tensor in params.items():
            print(f"  actor{seat} {name:20s} {tuple(tensor.shape)}")
        torch.save(params, os.path.join(dst, f"actor{seat}.params.pt"))
    meta = os.path.join(src, "meta.txt")
    if os.path.exists(meta):
        open(os.path.join(dst, "meta.txt"), "wb").write(open(meta, "rb").read())


def replay(path: str, out_dir: str) -> None:
    """Compare the scripted module against a pddump golden file, decision by decision.

    Record layout written by tools/pddump.cpp, one key per line:
      DEC <d> TURN <seat> HIST <n> / XIMP <n> + values /
      SEQ <n> + values / OPT <L> then L x ("OPTLINE <cards>", "AFEAT <n>" + values) /
      LOGIT <L> + values / CHOSEN <slot> <cards> / ACT <seat> <cards> / ENDDEC
    Values are printed 20 per line, so a count-prefixed block is read until satisfied.
    """
    with open(path) as handle:
        lines = handle.read().split("\n")

    seat_models = {}
    cursor = 0
    worst = 0.0
    worst_where = None
    mismatch = 0
    decisions = 0
    games = 0

    def expect(key: str) -> int:
        """Consume a "<key> <count>" header line and return the count."""
        nonlocal cursor
        head = lines[cursor].split()
        assert head and head[0] == key, f"expected {key}, got {lines[cursor]!r}"
        cursor += 1
        return int(head[1])

    def floats(count: int):
        nonlocal cursor
        values = []
        while len(values) < count:
            values += [float(tok) for tok in lines[cursor].split()]
            cursor += 1
        assert len(values) == count, (key, count, len(values))
        return values

    while cursor < len(lines):
        head = lines[cursor].split()
        cursor += 1
        if not head:
            continue
        key = head[0]
        if key == "GAME":
            games += 1
        elif key == "ACT" or key == "DEAL":
            pass
        elif key == "DEC":
            turn = int(head[3])
            x_imp = torch.tensor(floats(expect("XIMP"))).view(1, NODE_IN)
            seq = torch.tensor(floats(expect("SEQ"))).view(1, LSTM_STEPS, LSTM_IN)
            options = expect("OPT")
            feats = []
            for _ in range(options):
                parts = lines[cursor].split()
                cursor += 1
                assert parts[0] == "OPTLINE", parts[:1]
                feats += floats(expect("AFEAT"))
            action_feat = torch.tensor(feats).view(options, ACTION_IN)
            assert action_feat.numel() == options * ACTION_IN
            golden = floats(expect("LOGIT"))
            chosen = lines[cursor].split()
            cursor += 1
            assert chosen[0] == "CHOSEN"
            acted = lines[cursor].split()
            cursor += 1
            assert acted[0] == "ACT"
            end = lines[cursor].split()
            cursor += 1
            assert end[0] == "ENDDEC"

            if turn not in seat_models:
                seat_models[turn] = torch.jit.load(os.path.join(out_dir, f"actor{turn}_ts.pt"),
                                                   map_location="cpu")
            with torch.no_grad():
                sample = torch.zeros(options, dtype=torch.long)
                ids = torch.arange(options, dtype=torch.long)
                ours = seat_models[turn](x_imp, seq, action_feat, sample, ids)[0, :options]
            diff = (ours - torch.tensor(golden)).abs().max().item()
            decisions += 1
            if diff > worst:
                worst, worst_where = diff, (games, decisions, turn, chosen[1])
            if int(chosen[1]) != int(ours.argmax()):
                mismatch += 1
    print(f"replayed {decisions} decisions over {games} games: "
          f"max |logit diff| = {worst:.3e} at game/dec/seat/slot {worst_where}, "
          f"argmax mismatches = {mismatch}")


def write_manifest(model: "Actor", dst: str, seat: int) -> None:
    """Record the zip entry order -> layer mapping so other languages can read the archive.

    TorchScript stores tensor data as STORED (uncompressed) entries named ``<root>/data/<i>``,
    numbered in module registration order. A reader only needs this manifest plus a raw
    float32 copy of those entries, so Go/C++ can load the weights with no libtorch and no zlib.
    """
    order = [name for name, _ in model.named_parameters()]
    if order != PARAM_NAMES:
        raise AssertionError(f"parameter order changed: {order}")
    manifest = {
        "root": f"actor{seat}",
        "dtype": "float32",
        "byteOrder": "little",
        "gateOrder": LSTM_GATE_ORDER,
        "parameters": [{"entry": i, "name": name, "shape": list(t.shape)}
                       for i, (name, t) in enumerate(model.named_parameters())],
    }
    with open(os.path.join(dst, f"actor{seat}_manifest.json"), "w") as handle:
        json.dump(manifest, handle, indent=2, sort_keys=True)
        handle.write("\n")


def script(src: str, dst: str, golden: str) -> None:
    import json
    os.makedirs(dst, exist_ok=True)
    for seat in range(3):
        model = Actor()
        params = torch.load(os.path.join(src, f"actor{seat}.params.pt"), map_location="cpu")
        missing = set(PARAM_NAMES) - set(params)
        assert not missing, missing
        model.load_state_dict(params, strict=True)
        model.eval()
        scripted = torch.jit.script(model)
        torch.jit.save(scripted, os.path.join(dst, f"actor{seat}_ts.pt"))
        write_manifest(model, dst, seat)
        print(f"  wrote actor{seat}_ts.pt + actor{seat}_manifest.json")
    if golden:
        replay(golden, dst)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="cmd", required=True)
    p1 = sub.add_parser("extract")
    p1.add_argument("--in", dest="src", required=True)
    p1.add_argument("--out", dest="dst", required=True)
    p2 = sub.add_parser("script")
    p2.add_argument("--in", dest="src", required=True)
    p2.add_argument("--out", dest="dst", required=True)
    p2.add_argument("--golden", default="", help="pddump output to replay for parity")
    args = parser.parse_args()
    if args.cmd == "extract":
        extract(args.src, args.dst)
    else:
        script(args.src, args.dst, args.golden)
    return 0


if __name__ == "__main__":
    sys.exit(main())
