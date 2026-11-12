#include "fusion_port.h"

#include <float.h>

namespace xr {
namespace {

constexpr float kInitialGain = 10.0f;         // INITIAL_GAIN
constexpr float kInitialisationPeriod = 3.0f; // INITIALISATION_PERIOD

// FusionOffset 常量
constexpr float kOffsetCutoffHz = 0.02f;
constexpr int kOffsetTimeoutSec = 5;
constexpr float kOffsetThresholdDps = 3.0f;

Vec3 feedback(const Vec3 sensor, const Vec3 reference) {
    if (dot(sensor, reference) < 0.0f) {  // 误差 > 90°
        return normalise(cross(sensor, reference));
    }
    return cross(sensor, reference);
}

int clampInt(int value, int lo, int hi) {
    if (value < lo) return lo;
    if (value > hi) return hi;
    return value;
}

// NWU：算法估计的重力方向（传感器系，缩放 0.5）
Vec3 halfGravity(const Quat q) {
    return Vec3{
        q.x * q.z - q.w * q.y,
        q.y * q.z + q.w * q.x,
        q.w * q.w - 0.5f + q.z * q.z,
    };
}

}  // namespace

// ----------------------------------------------------------------------------
// GyroOffset
// ----------------------------------------------------------------------------
void GyroOffset::initialise(unsigned sampleRate) {
    filterCoefficient = 2.0f * kPi * kOffsetCutoffHz * (1.0f / static_cast<float>(sampleRate));
    timeout = kOffsetTimeoutSec * static_cast<int>(sampleRate);
    timer = 0;
    gyroscopeOffset = vZero();
}

Vec3 GyroOffset::update(Vec3 gyroscope) {
    gyroscope = sub(gyroscope, gyroscopeOffset);

    // 非静止：清零计时，不修正
    if (fabsf(gyroscope.x) > kOffsetThresholdDps || fabsf(gyroscope.y) > kOffsetThresholdDps ||
        fabsf(gyroscope.z) > kOffsetThresholdDps) {
        timer = 0;
        return gyroscope;
    }

    if (timer < timeout) {
        timer++;
        return gyroscope;
    }

    // 静止足够久：一阶低通缓慢吸收残余零偏
    gyroscopeOffset = add(gyroscopeOffset, mulSc(gyroscope, filterCoefficient));
    return gyroscope;
}

// ----------------------------------------------------------------------------
// Ahrs
// ----------------------------------------------------------------------------
void Ahrs::initialise() {
    AhrsSettings s;  // NWU / gain 0.5 / range 0 / 拒绝 90° / 窗口 0
    setSettings(s);
    reset();
}

void Ahrs::reset() {
    quaternion = kIdentityQuat;
    accelerometer = vZero();
    initialising = true;
    rampedGain = kInitialGain;
    angularRateRecovery = false;
    halfAccelerometerFeedback = vZero();
    halfMagnetometerFeedback = vZero();
    accelerometerIgnored = false;
    accelerationRecoveryTrigger = 0;
    accelerationRecoveryTimeout = static_cast<int>(settings.recoveryTriggerPeriod);
    magnetometerIgnored = false;
    magneticRecoveryTrigger = 0;
    magneticRecoveryTimeout = static_cast<int>(settings.recoveryTriggerPeriod);
}

void Ahrs::setSettings(const AhrsSettings& s) {
    settings = s;
    gyroscopeRangeEffective = (s.gyroscopeRange == 0.0f) ? FLT_MAX : 0.98f * s.gyroscopeRange;
    accelRejectionThreshold =
        (s.accelerationRejection == 0.0f) ? FLT_MAX : powf(0.5f * sinf(degToRad(s.accelerationRejection)), 2.0f);
    magneticRejectionThreshold =
        (s.magneticRejection == 0.0f) ? FLT_MAX : powf(0.5f * sinf(degToRad(s.magneticRejection)), 2.0f);
    accelerationRecoveryTimeout = static_cast<int>(s.recoveryTriggerPeriod);
    magneticRecoveryTimeout = static_cast<int>(s.recoveryTriggerPeriod);

    if (s.gain == 0.0f || s.recoveryTriggerPeriod == 0) {
        // 增益为 0 或未启用恢复窗口时关闭拒绝逻辑（与参考实现一致）
        accelRejectionThreshold = FLT_MAX;
        magneticRejectionThreshold = FLT_MAX;
    }
    if (!initialising) {
        rampedGain = s.gain;
    }
    rampedGainStep = (kInitialGain - s.gain) / kInitialisationPeriod;
}

void Ahrs::update(Vec3 gyroscope, Vec3 accelerometerIn, Vec3 magnetometer, float deltaTime) {
    accelerometer = accelerometerIn;

    // 角速率越界：保留姿态，重新进入初始化
    if (fabsf(gyroscope.x) > gyroscopeRangeEffective || fabsf(gyroscope.y) > gyroscopeRangeEffective ||
        fabsf(gyroscope.z) > gyroscopeRangeEffective) {
        const Quat keep = quaternion;
        reset();
        quaternion = keep;
        angularRateRecovery = true;
    }

    // 初始化期：增益从 10 斜坡降到 0.5
    if (initialising) {
        rampedGain -= rampedGainStep * deltaTime;
        if (rampedGain < settings.gain || settings.gain == 0.0f) {
            rampedGain = settings.gain;
            initialising = false;
            angularRateRecovery = false;
        }
    }

    const Vec3 halfG = halfGravity(quaternion);

    // ---------------- 加计反馈 ----------------
    Vec3 halfAccelFeedback = vZero();
    accelerometerIgnored = true;
    if (!isZero(accelerometerIn)) {
        halfAccelerometerFeedback = feedback(normalise(accelerometerIn), halfG);

        if (initialising || magSq(halfAccelerometerFeedback) <= accelRejectionThreshold) {
            accelerometerIgnored = false;
            accelerationRecoveryTrigger -= 9;
        } else {
            accelerationRecoveryTrigger += 1;
        }

        // 长时间被拒绝 → 强制恢复
        if (accelerationRecoveryTrigger > accelerationRecoveryTimeout) {
            accelerationRecoveryTimeout = 0;
            accelerometerIgnored = false;
        } else {
            accelerationRecoveryTimeout = static_cast<int>(settings.recoveryTriggerPeriod);
        }
        accelerationRecoveryTrigger =
            clampInt(accelerationRecoveryTrigger, 0, static_cast<int>(settings.recoveryTriggerPeriod));

        if (!accelerometerIgnored) {
            halfAccelFeedback = halfAccelerometerFeedback;
        }
    }

    // ---------------- 磁力计反馈（本工程不用，保留结构以对齐参考实现）----------------
    Vec3 halfMagFeedback = vZero();
    magnetometerIgnored = true;
    if (!isZero(magnetometer)) {
        const Vec3 halfMagnetic{quaternion.x * quaternion.y + quaternion.w * quaternion.z,
                                quaternion.w * quaternion.w - 0.5f + quaternion.y * quaternion.y,
                                quaternion.y * quaternion.z - quaternion.w * quaternion.x};
        halfMagnetometerFeedback = feedback(normalise(cross(halfG, magnetometer)), halfMagnetic);
        if (initialising || magSq(halfMagnetometerFeedback) <= magneticRejectionThreshold) {
            magnetometerIgnored = false;
            magneticRecoveryTrigger -= 9;
        } else {
            magneticRecoveryTrigger += 1;
        }
        if (magneticRecoveryTrigger > magneticRecoveryTimeout) {
            magneticRecoveryTimeout = 0;
            magnetometerIgnored = false;
        } else {
            magneticRecoveryTimeout = static_cast<int>(settings.recoveryTriggerPeriod);
        }
        magneticRecoveryTrigger =
            clampInt(magneticRecoveryTrigger, 0, static_cast<int>(settings.recoveryTriggerPeriod));
        if (!magnetometerIgnored) {
            halfMagFeedback = halfMagnetometerFeedback;
        }
    }

    // ---------------- 四元数积分 ----------------
    const Vec3 halfGyro = mulSc(gyroscope, degToRad(0.5f));
    const Vec3 adjusted = add(halfGyro, mulSc(add(halfAccelFeedback, halfMagFeedback), rampedGain));
    quaternion = qAdd(quaternion, qMulVec(quaternion, mulSc(adjusted, deltaTime)));
    quaternion = qNormalise(quaternion);
}

void Ahrs::updateNoMagnetometer(Vec3 gyroscope, Vec3 accelerometerIn, float deltaTime) {
    update(gyroscope, accelerometerIn, vZero(), deltaTime);
    if (initialising) {
        setHeading(0.0f);  // 初始化期间航向钉 0，避免上电随机朝向
    }
}

void Ahrs::setHeading(float headingDeg) {
    const float yaw = atan2f(quaternion.w * quaternion.z + quaternion.x * quaternion.y,
                             0.5f - quaternion.y * quaternion.y - quaternion.z * quaternion.z);
    const float halfYawMinusHeading = 0.5f * (yaw - degToRad(headingDeg));
    const Quat rotation{cosf(halfYawMinusHeading), 0.0f, 0.0f, -sinf(halfYawMinusHeading)};
    quaternion = qMul(rotation, quaternion);
}

Vec3 Ahrs::getGravity() const {
    return Vec3{
        2.0f * (quaternion.x * quaternion.z - quaternion.w * quaternion.y),
        2.0f * (quaternion.y * quaternion.z + quaternion.w * quaternion.x),
        2.0f * (quaternion.w * quaternion.w - 0.5f + quaternion.z * quaternion.z),
    };
}

}  // namespace xr
