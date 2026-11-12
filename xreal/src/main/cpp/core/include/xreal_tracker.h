// xreal_tracker.h —— 3DoF 云台（虚拟巨幕锚定）流水线
//
// 数据流：
//   眼镜 IMU 1 kHz 报文 → ImuParser → GyroOffset（零偏）→ Ahrs（姿态）
//        → 预测器（角速度外推补偿光子延迟）→ 锚定器（世界锁定 / 仅偏航 / 跟随）
//        → 渲染旋转矩阵（3x3，行主序，world->camera，已转到 GL 轴系）
//
// 锚定模式语义：
//   Off     0DoF：屏幕贴脸，等同原生 DP 直连
//   World   3DoF：屏幕钉在空间里（观影固定巨幕）
//   YawOnly 只保留绕重力轴的偏航，屏幕永远水平正对（飞机/车上看片）
//   Follow  全轴阻尼跟随（软云台，减少晃动）
#pragma once

#include <stdint.h>

#include "fusion_port.h"
#include "xreal_imu.h"

namespace xr {

enum class AnchorMode : int {
    Off = 0,
    World = 1,
    YawOnly = 2,
    Follow = 3,
};

struct TrackerConfig {
    AnchorMode mode = AnchorMode::World;
    float screenDistanceM = 3.0f;
    float screenWidthM = 2.6f;
    float predictMs = 18.0f;       // 光子延迟补偿
    float followTauSec = 0.12f;    // Follow 时间常数（越小越跟手）
    float recenterBlendSec = 0.35f;
    bool enableYawDriftComp = true;
    float yawDriftTauSec = 8.0f;
};

struct TrackerStats {
    uint64_t samples = 0;
    uint64_t dropped = 0;
    float sampleRateHz = 0.0f;
    float temperatureC = 0.0f;
    Vec3 gyroBiasDps = vZero();
    float accelErrorDeg = 0.0f;
    bool initialising = true;
    bool accelIgnored = false;
    float yawDeg = 0.0f;
    float pitchDeg = 0.0f;
    float rollDeg = 0.0f;
    float gyroScale = 0.0f;   // 现场核对用：当前帧 dps/LSB
    float accelScale = 0.0f;  // 当前帧 g/LSB
};

class Tracker {
public:
    Tracker();

    void configure(const TrackerConfig& cfg) { cfg_ = cfg; }
    const TrackerConfig& config() const { return cfg_; }

    void pushSample(const ImuSample& s);
    void getViewRotation(uint64_t nowNs, float outM9[9]);
    Quat getPredictedQuaternion(uint64_t nowNs);

    void recenter();
    void reset();
    void setAxisMapping(AxisMapping m) { parser_.setMapping(m); }

    const TrackerStats& stats() const { return stats_; }
    Quat rawQuaternion() const { return ahrs_.getQuaternion(); }

private:
    Quat applyAnchor(const Quat& qHead, uint64_t nowNs);

    TrackerConfig cfg_{};
    ImuParser parser_{};
    GyroOffset offset_{};
    Ahrs ahrs_{};

    Quat anchorQ_{1.0f, 0.0f, 0.0f, 0.0f};
    Quat recenterFrom_{1.0f, 0.0f, 0.0f, 0.0f};
    Quat recenterTo_{1.0f, 0.0f, 0.0f, 0.0f};
    float recenterT_ = 1.0f;

    Vec3 lastGyroDps_ = vZero();
    uint64_t lastTs_ = 0;
    uint64_t lastHostNs_ = 0;
    uint64_t lastRenderNs_ = 0;

    Quat followQ_{1.0f, 0.0f, 0.0f, 0.0f};
    Quat smoothQ_{1.0f, 0.0f, 0.0f, 0.0f};   // 世界系下的"平滑头姿"，用于软跟随
    bool smoothInit_ = false;

    // 上电快速零偏标定（2 s 均值），否则残余 0.5 dps 就是 30°/min 的偏航漂移
    Vec3 biasAccum_ = vZero();
    uint32_t biasSamples_ = 0;
    bool biasSeeded_ = false;
    int stillFrames_ = 0;
    uint32_t biasAge_ = 0;

    TrackerStats stats_{};
    uint64_t rateWindowStart_ = 0;
    uint64_t rateCount_ = 0;
};

}  // namespace xr
