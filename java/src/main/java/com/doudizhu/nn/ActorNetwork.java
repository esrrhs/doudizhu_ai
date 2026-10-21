package com.doudizhu.nn;

import ai.djl.Model;
import ai.djl.Device;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.pytorch.engine.PtNDArray;
import ai.djl.pytorch.engine.PtNDManager;
import ai.djl.pytorch.engine.PtSymbolBlock;
import ai.djl.pytorch.jni.IValue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * 用 DJL 的 libtorch 后端跑重新导出的 TorchScript actor。
 *
 * <p>训练侧用 {@code torch::serialize::OutputArchive} 存的是纯参数档, 里面没有 forward,
 * 任何 TorchScript 运行时都无法直接调用; 需要先由 PerfectDouCPP 的
 * {@code tools/export_actor.py} 重建成带 forward 的脚本模块。脚本用的 PyTorch 版本必须与
 * 这里加载的原生库版本一致 (DJL 0.39 对应 libtorch 2.7.1)。
 *
 * <p>DJL 依赖在本项目里是 optional 的: 不用神经网络玩家时, 核心引擎不引入任何原生库。
 */
public final class ActorNetwork implements AutoCloseable {

    /** 三个座位各一份策略: 0 地主, 1 地主下家, 2 地主上家 */
    public static final int SEATS = 3;

    private final NDManager manager;
    private final Model[] models = new Model[SEATS];
    private final PtSymbolBlock[] blocks = new PtSymbolBlock[SEATS];

    private ActorNetwork(NDManager manager) {
        this.manager = manager;
    }

    /**
     * 从目录加载 {@code actor0_ts.pt} .. {@code actor2_ts.pt}。
     *
     * @param dir 存放重新导出的 TorchScript 权重的目录
     */
    public static ActorNetwork load(Path dir) throws IOException {
        for (int seat = 0; seat < SEATS; ++seat) {
            Path file = dir.resolve("actor" + seat + "_ts.pt");
            if (!Files.isRegularFile(file)) {
                throw new IOException("缺少权重文件 " + file
                        + " (需先用 PerfectDouCPP tools/export_actor.py 从训练权重导出 TorchScript)");
            }
        }
        ActorNetwork network = new ActorNetwork(NDManager.newBaseManager(Device.cpu(), "PyTorch"));
        try {
            for (int seat = 0; seat < SEATS; ++seat) {
                String name = "actor" + seat + "_ts";
                Model model = Model.newInstance(name, Device.cpu(), "PyTorch");
                model.load(dir, name);
                network.models[seat] = model;
                network.blocks[seat] = (PtSymbolBlock) model.getBlock();
            }
        } catch (Exception e) {
            network.close();
            throw new IOException("加载 TorchScript actor 失败: " + e, e);
        }
        return network;
    }

    /**
     * 给单个决策的全部候选动作打分。
     *
     * @param seat       座位 (actor 编号)
     * @param xImp       1676 维节点特征
     * @param seq        5400 维历史序列 ([10][540] 展平)
     * @param actionFeat options * 186 维候选动作特征, 按槽位顺序紧密排列
     * @param options    候选动作数
     * @return 每个候选动作的得分, 长度等于 options
     */
    public synchronized float[] score(int seat, float[] xImp, float[] seq,
                                      float[] actionFeat, int options) {
        if (options <= 0) {
            return new float[0];
        }
        long[] sample = new long[options];      // batch=1, 每个候选都取自第 0 个状态
        long[] slot = new long[options];
        for (int i = 0; i < options; ++i) {
            slot[i] = i;
        }
        try (NDManager sub = manager.newSubManager()) {
            PtNDManager pt = (PtNDManager) sub;
            NDArray x = sub.create(xImp, new Shape(1, PerfectDouFeatures.NODE_SIZE));
            NDArray s = sub.create(seq, new Shape(1, PerfectDouFeatures.LSTM_STEPS,
                    PerfectDouFeatures.LSTM_IN));
            NDArray a = sub.create(actionFeat, new Shape(options, PerfectDouFeatures.ACTION_SIZE));
            NDArray ids = sub.create(slot, new Shape(options));
            NDArray samples = sub.create(sample, new Shape(options));
            // IValue 是独立的原生对象, 不随 NDManager 释放, 必须逐个 close
            IValue[] inputs = new IValue[]{
                    IValue.from((PtNDArray) x),
                    IValue.from((PtNDArray) s),
                    IValue.from((PtNDArray) a),
                    IValue.from((PtNDArray) samples),
                    IValue.from((PtNDArray) ids)};
            try {
                IValue result = blocks[seat].forward(inputs);
                try (PtNDArray logits = result.toTensor(pt)) {
                    // 未使用的槽位被填成 -1e9, 前 options 个就是各候选的得分
                    return Arrays.copyOf(logits.toFloatArray(), options);
                } finally {
                    result.close();
                }
            } finally {
                for (IValue input : inputs) {
                    input.close();
                }
            }
        }
    }

    @Override
    public void close() {
        for (Model model : models) {
            if (model != null) {
                model.close();
            }
        }
        manager.close();
    }

    /** 判断 DJL 与 PyTorch 引擎是否可用 (依赖是 optional 的, 缺失时不应连累核心引擎) */
    public static boolean isAvailable() {
        try {
            return ai.djl.engine.Engine.hasEngine("PyTorch");
        } catch (Throwable t) {
            return false;
        }
    }
}
