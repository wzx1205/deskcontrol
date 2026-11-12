// fusion_port.h —— XREAL 主机侧 3DoF 姿态解算移植（Fusion v9.x API 子集）
//
// 提取来源（recon/ref 内已保留克隆，可复查）：
//   * XREAL Air / Air2 主机侧：AirAPI_Windows（MSmithDev），Fusion 子模块
//     8dcc9a987494ce7e573cad9653b0e4bb7f95fbac
//   * nrealAirLinuxDriver（UnicornXR / thejackimonster），Fusion 子模块
//     b607fc32d2d8dd1ac5fb9f81ca8244dda6e58ddf  ← 本移植基准
//
// 参数：gain=0.5；加计拒绝 10°；恢复窗口 5 s（1 kHz 下 5000 帧）；
//      陀螺零偏：截止 0.02 Hz、阈值 3 dps、静止 5 s 后开始吸收。
#pragma once

#include <stdint.h>

#include "xr_math.h"

namespace xr {

// ------------------------- 陀螺零偏在线估计（对应 FusionOffset）-------------------------
struct GyroOffset {
    float filterCoefficient = 0.0f;
    int timeout = 0;
    int timer = 0;
    Vec3 gyroscopeOffset = vZero();

    void initialise(unsigned sampleRate);
    Vec3 update(Vec3 gyroscope);
};

// ------------------------- AHRS（对应 FusionAhrs，NWU，仅陀螺+加计）-------------------------
struct AhrsSettings {
    float gain = 0.5f;
    float gyroscopeRange = 0.0f;
    float accelerationRejection = 90.0f;
    float magneticRejection = 90.0f;
    unsigned recoveryTriggerPeriod = 0;
};

struct Ahrs {
    AhrsSettings settings;
    Quat quaternion = kIdentityQuat;
    Vec3 accelerometer = vZero();
    bool initialising = true;
    float rampedGain = 10.0f;
    float rampedGainStep = 0.0f;
    bool angularRateRecovery = false;
    Vec3 halfAccelerometerFeedback = vZero();
    Vec3 halfMagnetometerFeedback = vZero();
    bool accelerometerIgnored = false;
    int accelerationRecoveryTrigger = 0;
    int accelerationRecoveryTimeout = 0;
    bool magnetometerIgnored = false;
    int magneticRecoveryTrigger = 0;
    int magneticRecoveryTimeout = 0;

    // setSettings 换算出的生效阈值
    float accelRejectionThreshold = 0.0f;
    float magneticRejectionThreshold = 0.0f;
    float gyroscopeRangeEffective = 0.0f;

    void initialise();
    void reset();
    void setSettings(const AhrsSettings& s);
    void update(Vec3 gyroscopeDps, Vec3 accelerometerG, Vec3 magnetometer, float deltaTime);
    void updateNoMagnetometer(Vec3 gyroscopeDps, Vec3 accelerometerG, float deltaTime);
    void setHeading(float headingDeg);
    Quat getQuaternion() const { return quaternion; }
    Vec3 getGravity() const;
};

}  // namespace xr
