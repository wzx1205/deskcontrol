// xr_math.h —— 3DoF 内核数学层（与 xioTechnologies/Fusion 位级兼容）
//
// 设计约束：
//  * 只用 C 头文件（math.h/stdint.h），便于在 Android NDK、宿主测试、
//    WebAssembly 校验环境之间共用同一份源码，不依赖 libc++。
//  * Fusion 默认用近似快速逆平方根（0x5F1F1412 + 一次 Newton 修正），
//    这里逐位复刻，保证与 XREAL 主机侧算法数值一致。
#pragma once

#include <math.h>
#include <stdint.h>

namespace xr {

constexpr float kPi = 3.14159265358979323846f;

inline float degToRad(float d) { return d * (kPi / 180.0f); }
inline float radToDeg(float r) { return r * (180.0f / kPi); }

inline float xrMin(float a, float b) { return a < b ? a : b; }
inline float xrMax(float a, float b) { return a > b ? a : b; }

struct Vec3 {
    float x, y, z;
};

inline Vec3 vec3(float x, float y, float z) { return Vec3{x, y, z}; }
inline Vec3 vZero() { return Vec3{0.0f, 0.0f, 0.0f}; }

inline Vec3 add(const Vec3 a, const Vec3 b) { return Vec3{a.x + b.x, a.y + b.y, a.z + b.z}; }
inline Vec3 sub(const Vec3 a, const Vec3 b) { return Vec3{a.x - b.x, a.y - b.y, a.z - b.z}; }
inline Vec3 mulSc(const Vec3 a, float s) { return Vec3{a.x * s, a.y * s, a.z * s}; }
inline float dot(const Vec3 a, const Vec3 b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
inline Vec3 cross(const Vec3 a, const Vec3 b) {
    return Vec3{a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x};
}
inline float magSq(const Vec3 a) { return dot(a, a); }

// 与 Fusion 位一致的近似逆平方根
inline float fastInverseSqrt(float x) {
    union Union32 {
        float f;
        int32_t i;
    } u;
    u.f = x;
    u.i = 0x5F1F1412 - (u.i >> 1);
    return u.f * (1.69000231f - 0.714158168f * x * u.f * u.f);
}

inline Vec3 normalise(const Vec3 v) {
    const float r = fastInverseSqrt(magSq(v));
    return mulSc(v, r);
}

inline bool isZero(const Vec3 v) { return v.x == 0.0f && v.y == 0.0f && v.z == 0.0f; }

struct Quat {
    float w, x, y, z;
};

constexpr Quat kIdentityQuat = {1.0f, 0.0f, 0.0f, 0.0f};

inline Quat qAdd(const Quat a, const Quat b) {
    return Quat{a.w + b.w, a.x + b.x, a.y + b.y, a.z + b.z};
}

inline Quat qMul(const Quat a, const Quat b) {
    return Quat{
        a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z,
        a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
        a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
        a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w,
    };
}

// q ⊗ (0, v)：四元数微分方程 q' = 0.5 * q ⊗ ω
inline Quat qMulVec(const Quat q, const Vec3 v) {
    return Quat{
        -q.x * v.x - q.y * v.y - q.z * v.z,
        q.w * v.x + q.y * v.z - q.z * v.y,
        q.w * v.y - q.x * v.z + q.z * v.x,
        q.w * v.z + q.x * v.y - q.y * v.x,
    };
}

inline Quat qNormalise(const Quat q) {
    const float r = fastInverseSqrt(q.w * q.w + q.x * q.x + q.y * q.y + q.z * q.z);
    return Quat{q.w * r, q.x * r, q.y * r, q.z * r};
}

inline Quat qConjugate(const Quat q) { return Quat{q.w, -q.x, -q.y, -q.z}; }

inline float qDot(const Quat a, const Quat b) {
    return a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z;
}

inline Quat qSlerp(Quat a, Quat b, float t) {
    float d = qDot(a, b);
    if (d < 0.0f) {
        b = Quat{-b.w, -b.x, -b.y, -b.z};
        d = -d;
    }
    if (d > 0.9995f) {  // 夹角很小，线性插值 + 归一化即可
        return qNormalise(Quat{a.w + (b.w - a.w) * t, a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t,
                               a.z + (b.z - a.z) * t});
    }
    const float theta0 = acosf(d);
    const float theta = theta0 * t;
    const float s0 = sinf(theta0 - theta) / sinf(theta0);
    const float s1 = sinf(theta) / sinf(theta0);
    return Quat{
        a.w * s0 + b.w * s1,
        a.x * s0 + b.x * s1,
        a.y * s0 + b.y * s1,
        a.z * s0 + b.z * s1,
    };
}

// 角速度（rad/s）在 dt 秒内的小角度积分
inline Quat qFromAngularRate(const Vec3 wRadPerSec, float dt) {
    const Vec3 half = mulSc(wRadPerSec, 0.5f * dt);
    const float angle = sqrtf(magSq(half));
    if (angle < 1e-9f) {
        return Quat{1.0f, half.x, half.y, half.z};
    }
    const float s = sinf(angle) / angle;
    return Quat{cosf(angle), half.x * s, half.y * s, half.z * s};
}

// 四元数 -> 3x3 旋转矩阵（行主序）
inline void qToMat3(const Quat q, float m[9]) {
    const float ww = q.w * q.w, xx = q.x * q.x, yy = q.y * q.y, zz = q.z * q.z;
    m[0] = ww + xx - yy - zz;
    m[1] = 2.0f * (q.x * q.y - q.w * q.z);
    m[2] = 2.0f * (q.x * q.z + q.w * q.y);
    m[3] = 2.0f * (q.x * q.y + q.w * q.z);
    m[4] = ww - xx + yy - zz;
    m[5] = 2.0f * (q.y * q.z - q.w * q.x);
    m[6] = 2.0f * (q.x * q.z - q.w * q.y);
    m[7] = 2.0f * (q.y * q.z + q.w * q.x);
    m[8] = ww - xx - yy + zz;
}

// 四元数 -> yaw/pitch/roll（度，NWU 约定：Z 轴为上，偏航绕 Z）
inline void qToEulerDeg(const Quat q, float& yaw, float& pitch, float& roll) {
    yaw = radToDeg(atan2f(q.w * q.z + q.x * q.y, 0.5f - q.y * q.y - q.z * q.z));
    pitch = radToDeg(asinf(-2.0f * (q.x * q.z - q.w * q.y)));
    roll = radToDeg(atan2f(q.w * q.x + q.y * q.z, 0.5f - q.x * q.x - q.y * q.y));
}

}  // namespace xr
