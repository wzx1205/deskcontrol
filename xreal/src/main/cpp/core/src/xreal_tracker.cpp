#include "xreal_tracker.h"

#include <math.h>

namespace xr {
namespace {

// 地球系（NWU：Z 为上）下的偏航角
inline float yawOf(const Quat q) {
    return atan2f(2.0f * (q.w * q.z + q.x * q.y), 1.0f - 2.0f * (q.y * q.y + q.z * q.z));
}

// 绕地球 Z 轴（上）的纯偏航旋转
inline Quat yawQuat(float yawRad) {
    return Quat{cosf(0.5f * yawRad), 0.0f, 0.0f, sinf(0.5f * yawRad)};
}

// NWU(X北,Y西,Z上) -> GL(X右,Y上,-Z前) 的基变换 S：
//   gl_x = -Y_nwu, gl_y = Z_nwu, gl_z = -X_nwu;  R_gl = S ∘ R_nwu ∘ Sᵀ
void toGlBasis(const float r[9], float out[9]) {
    static const float sx[3] = {0.0f, -1.0f, 0.0f};
    static const float sy[3] = {0.0f, 0.0f, 1.0f};
    static const float sz[3] = {-1.0f, 0.0f, 0.0f};
    const float* rows[3] = {sx, sy, sz};
    for (int i = 0; i < 3; ++i) {
        for (int j = 0; j < 3; ++j) {
            float acc = 0.0f;
            for (int k = 0; k < 3; ++k) {
                for (int l = 0; l < 3; ++l) {
                    acc += rows[i][k] * r[k * 3 + l] * rows[j][l];
                }
            }
            out[i * 3 + j] = acc;
        }
    }
}

}  // namespace

Tracker::Tracker() {
    offset_.initialise(1000);
    offset_.timeout = 500;               // 静止 0.5 s 就开始吸收零偏（原版是 5 s，反应太慢）
    // 0.02 Hz 太慢（要几分钟才收敛），0.2 Hz 大约 5 s 静止就能吸收掉大部分残余零偏
    offset_.filterCoefficient = 2.0f * kPi * 0.2f / 1000.0f;
    AhrsSettings s;
    s.gain = 0.5f;                       // 与 XREAL 主机侧一致
    // 10° 是厂商值，但那是给"手不动"的场景；观影时正常转头就会超过，
    // 结果加计一直被拒 → 俯仰/横滚跟着零偏漂。放到 30°，并缩短强制恢复窗口。
    s.accelerationRejection = 30.0f;
    s.magneticRejection = 20.0f;
    s.recoveryTriggerPeriod = 1000;      // 1 s @ 1 kHz
    ahrs_.setSettings(s);
    ahrs_.reset();
}

void Tracker::reset() {
    ahrs_.reset();
    offset_.initialise(1000);
    anchorQ_ = kIdentityQuat;
    recenterFrom_ = kIdentityQuat;
    recenterTo_ = kIdentityQuat;
    recenterT_ = 1.0f;
    followQ_ = kIdentityQuat;
    smoothQ_ = kIdentityQuat;
    smoothInit_ = false;
    lastTs_ = 0;
    lastGyroDps_ = vZero();
    stats_ = TrackerStats{};
    biasAccum_ = vZero();
    biasSamples_ = 0;
    biasAge_ = 0;
    biasSeeded_ = false;
    stillFrames_ = 0;
    rateCount_ = 0;
    rateWindowStart_ = 0;
}

void Tracker::pushSample(const ImuSample& s) {
    if (!s.valid) {
        stats_.dropped++;
        return;
    }

    if (lastTs_ == 0) {
        lastTs_ = s.timestampNs;
        lastHostNs_ = s.hostNs;
        stats_.samples++;
        return;
    }

    const uint64_t delta = s.timestampNs - lastTs_;
    if (delta == 0 || delta > 200000000ULL) {  // 断流 > 200 ms：重置时基，不更新
        lastTs_ = s.timestampNs;
        return;
    }
    float dt = static_cast<float>(static_cast<double>(delta) * 1e-9);
    dt = xrMin(dt, 0.05f);
    lastTs_ = s.timestampNs;
    if (s.hostNs != 0) lastHostNs_ = s.hostNs;

    // 1) 上电零偏标定：只在"真的不动"（三轴 |ω|<1 dps）时累计，凑够 1.5 s 才生效；
    //    20 s 还凑不够就放弃，交给 GyroOffset 自己慢慢学。
    if (!biasSeeded_) {
        const bool still = fabsf(s.gyroDps.x) < 1.0f && fabsf(s.gyroDps.y) < 1.0f &&
                           fabsf(s.gyroDps.z) < 1.0f;
        if (still) {
            biasAccum_ = add(biasAccum_, s.gyroDps);
            biasSamples_++;
        }
        biasAge_++;
        if (biasSamples_ >= 1500) {
            offset_.gyroscopeOffset = mulSc(biasAccum_, 1.0f / static_cast<float>(biasSamples_));
            offset_.timer = offset_.timeout;
            biasSeeded_ = true;
        } else if (biasAge_ > 20000) {  // 20 s 没凑够，放弃
            biasSeeded_ = true;
        }
    }

    // 2) 陀螺零偏在线估计
    const Vec3 gyro = offset_.update(s.gyroDps);
    lastGyroDps_ = gyro;

    // 3) 姿态解算
    ahrs_.updateNoMagnetometer(gyro, s.accelG, dt);

    // 3) 统计
    stats_.samples++;
    stats_.temperatureC = s.temperatureC;
    stats_.gyroScale = s.gyroScale;
    stats_.accelScale = s.accelScale;
    stats_.gyroBiasDps = offset_.gyroscopeOffset;
    stats_.initialising = ahrs_.initialising;
    stats_.accelIgnored = ahrs_.accelerometerIgnored;
    const Vec3 fb = ahrs_.halfAccelerometerFeedback;
    stats_.accelErrorDeg = 2.0f * radToDeg(asinf(xrMin(1.0f, 2.0f * sqrtf(magSq(fb)))));
    const Quat q = ahrs_.getQuaternion();
    qToEulerDeg(q, stats_.yawDeg, stats_.pitchDeg, stats_.rollDeg);

    // 4) 采样率（每 0.5 s 刷新）
    rateCount_++;
    if (rateWindowStart_ == 0) rateWindowStart_ = s.timestampNs;
    if (s.timestampNs - rateWindowStart_ >= 500000000ULL) {
        const float spanSec = static_cast<float>(static_cast<double>(s.timestampNs - rateWindowStart_) * 1e-9);
        stats_.sampleRateHz = spanSec > 0.0f ? static_cast<float>(rateCount_) / spanSec : 0.0f;
        rateCount_ = 0;
        rateWindowStart_ = s.timestampNs;
    }

    // 5) 偏航漂移补偿：无磁力计 → 偏航不可观测，只能用"慢跟随"把漂移藏起来。
    //    做法：只让锚点的偏航角以 8 s 时间常数慢慢追上头部偏航（限速 2°/s），
    //    俯仰/横滚完全不动，所以画面永远是水平的、快速转头仍然是世界锁定。
    if (cfg_.enableYawDriftComp && !ahrs_.initialising && recenterT_ >= 1.0f) {
        const float yawHead = yawOf(q);
        float d = yawHead - yawOf(anchorQ_);
        while (d > kPi) d -= 2.0f * kPi;
        while (d < -kPi) d += 2.0f * kPi;
        const float tau = xrMax(2.0f, cfg_.yawDriftTauSec);
        float step = d * (1.0f - expf(-dt / tau));
        const float maxStep = degToRad(2.0f) * dt;
        if (step > maxStep) step = maxStep;
        if (step < -maxStep) step = -maxStep;
        if (step != 0.0f) anchorQ_ = qMul(yawQuat(step), anchorQ_);  // 左乘 = 绕世界 Z 轴转
    }
}

Quat Tracker::getPredictedQuaternion(uint64_t nowNs) {
    Quat q = ahrs_.getQuaternion();
    // 帧间时基：优先用宿主收包时刻（elapsedRealtimeNanos），否则退回眼镜时间戳
    const uint64_t refNs = (lastHostNs_ != 0) ? lastHostNs_ : lastTs_;
    if (refNs == 0) return q;

    float dtPred = 0.0f;
    if (nowNs > refNs) {
        dtPred = static_cast<float>(static_cast<double>(nowNs - refNs) * 1e-9);
    }
    dtPred += cfg_.predictMs * 0.001f;
    dtPred = xrMin(xrMax(dtPred, 0.0f), 0.05f);  // 最多外推 50 ms

    if (dtPred > 0.0f) {
        const Vec3 wRad = mulSc(lastGyroDps_, degToRad(1.0f));  // dps -> rad/s
        q = qMul(q, qFromAngularRate(wRad, dtPred));
        q = qNormalise(q);
    }
    return q;
}

void Tracker::recenter() {
    recenterFrom_ = anchorQ_;
    recenterTo_ = ahrs_.getQuaternion();
    recenterT_ = 0.0f;
    smoothQ_ = ahrs_.getQuaternion();
    smoothInit_ = true;
    // 重定心时用户通常正对着屏幕不动，顺手再做一次零偏标定
    biasAccum_ = vZero();
    biasSamples_ = 0;
    biasAge_ = 0;
    biasSeeded_ = false;
}

Quat Tracker::applyAnchor(const Quat& qHead, uint64_t nowNs) {
    // 渲染帧间隔（用于所有跟时间相关的插值，避免写死 16 ms 导致高刷屏手感变差）
    float dtRender = 0.016f;
    if (lastRenderNs_ != 0 && nowNs > lastRenderNs_) {
        dtRender = static_cast<float>(static_cast<double>(nowNs - lastRenderNs_) * 1e-9);
    }
    lastRenderNs_ = nowNs;
    dtRender = xrMin(xrMax(dtRender, 0.001f), 0.05f);

    if (recenterT_ < 1.0f) {  // 重定心平滑过渡，避免画面跳变
        const float step = dtRender / xrMax(0.05f, cfg_.recenterBlendSec);
        recenterT_ = xrMin(1.0f, recenterT_ + step);
        anchorQ_ = qSlerp(recenterFrom_, recenterTo_, recenterT_);
    }

    // 相机系 <- 锚点系：V = R(q)⁻¹ · R(q_a) = R(q⁻¹ ⊗ q_a)
    // 四元数不满足交换律：写成 q ⊗ q_a⁻¹ 再取共轭虽然"看着像"，但两者相差一个
    // 由锚点姿态 q_a 决定的相似变换，表现就是俯仰/横滚的符号随放置姿态乱变。
    const Quat qRel = qMul(qConjugate(qHead), anchorQ_);

    switch (cfg_.mode) {
        case AnchorMode::Off:
            return kIdentityQuat;
        case AnchorMode::World:
            return qRel;
        case AnchorMode::YawOnly: {
            const float yaw = yawOf(qRel);
            return yawQuat(yaw);
        }
        case AnchorMode::Follow: {
            // 软跟随（本质是"稳过的 0DoF"）：
            //   维护一个世界系里平滑过的头姿 smoothQ，渲染用 R(head⁻¹ · smooth)，
            //   静止时它收敛到单位阵（画面永远回到正前方），运动时画面滞后一点点、
            //   不抖也不飘走。和"世界锁定"是两套语义，别混。
            if (!smoothInit_) {
                smoothQ_ = qHead;
                smoothInit_ = true;
            }
            const float tau = xrMax(0.02f, cfg_.followTauSec);
            const float alpha = 1.0f - expf(-dtRender / tau);
            smoothQ_ = qSlerp(smoothQ_, qHead, alpha);
            return qMul(qConjugate(qHead), smoothQ_);
        }
    }
    return qRel;
}

void Tracker::getViewRotation(uint64_t nowNs, float outM9[9]) {
    const Quat qHead = getPredictedQuaternion(nowNs);
    const Quat qRel = applyAnchor(qHead, nowNs);
    float r[9];
    qToMat3(qRel, r);
    toGlBasis(r, outM9);
}

}  // namespace xr
