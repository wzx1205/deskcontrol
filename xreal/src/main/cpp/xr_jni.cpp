// xr_jni.cpp —— Kotlin <-> 3DoF 内核的 JNI 桥
#include <jni.h>

#include <android/log.h>

#include "xreal_imu.h"
#include "xreal_tracker.h"

#define LOG_TAG "XrealCore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

struct Engine {
    xr::Tracker tracker;
    xr::ImuParser parser;
    xr::ImuSample sample;
    uint64_t packets = 0;
    uint64_t parsed = 0;
    uint64_t initFrames = 0;
    uint64_t badFrames = 0;
};

inline Engine* toEngine(jlong h) { return reinterpret_cast<Engine*>(h); }

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_xreal_beamlink_PoseEngine_nativeCreate(JNIEnv*, jobject) {
    auto* e = new Engine();
    LOGI("engine created");
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT void JNICALL Java_com_xreal_beamlink_PoseEngine_nativeDestroy(JNIEnv*, jobject, jlong h) {
    delete toEngine(h);
}

JNIEXPORT void JNICALL Java_com_xreal_beamlink_PoseEngine_nativeConfigure(
    JNIEnv*, jobject, jlong h, jint mode, jfloat distM, jfloat widthM, jfloat predictMs, jfloat followTau,
    jfloat recenterBlend, jboolean yawDriftComp, jint axisMap) {
    Engine* e = toEngine(h);
    if (!e) return;
    xr::TrackerConfig cfg = e->tracker.config();
    cfg.mode = static_cast<xr::AnchorMode>(mode);
    cfg.screenDistanceM = distM;
    cfg.screenWidthM = widthM;
    cfg.predictMs = predictMs;
    cfg.followTauSec = followTau;
    cfg.recenterBlendSec = recenterBlend;
    cfg.enableYawDriftComp = yawDriftComp == JNI_TRUE;
    e->tracker.configure(cfg);
    e->tracker.setAxisMapping(static_cast<xr::AxisMapping>(axisMap));
    e->parser.setMapping(static_cast<xr::AxisMapping>(axisMap));
}

// 返回 true 表示该帧是有效 IMU 样本（已喂给解算器）
JNIEXPORT jboolean JNICALL Java_com_xreal_beamlink_PoseEngine_nativePushPacket(
    JNIEnv* env, jobject, jlong h, jbyteArray data, jint len, jlong hostNs) {
    Engine* e = toEngine(h);
    if (!e || data == nullptr) return JNI_FALSE;
    jsize avail = env->GetArrayLength(data);
    if (len <= 0 || len > avail) len = avail;
    jbyte buf[520];
    if (len > static_cast<jint>(sizeof(buf))) return JNI_FALSE;
    env->GetByteArrayRegion(data, 0, len, buf);

    e->packets++;
    xr::ImuSample s;
    const bool ok = e->parser.parse(reinterpret_cast<const uint8_t*>(buf), len, &s);
    if (s.initFrame) {
        e->initFrames++;
        return JNI_FALSE;
    }
    if (!ok || !s.valid) {
        e->badFrames++;
        return JNI_FALSE;
    }
    s.hostNs = static_cast<uint64_t>(hostNs);
    e->tracker.pushSample(s);
    e->parsed++;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_com_xreal_beamlink_PoseEngine_nativeGetView(
    JNIEnv* env, jobject, jlong h, jlong nowNs, jfloatArray out9) {
    Engine* e = toEngine(h);
    if (!e || out9 == nullptr) return;
    float m[9];
    e->tracker.getViewRotation(static_cast<uint64_t>(nowNs), m);
    env->SetFloatArrayRegion(out9, 0, 9, m);
}

JNIEXPORT void JNICALL Java_com_xreal_beamlink_PoseEngine_nativeRecenter(JNIEnv*, jobject, jlong h) {
    Engine* e = toEngine(h);
    if (e) e->tracker.recenter();
}

JNIEXPORT void JNICALL Java_com_xreal_beamlink_PoseEngine_nativeReset(JNIEnv*, jobject, jlong h) {
    Engine* e = toEngine(h);
    if (e) e->tracker.reset();
}

// 输出 16 个统计量，字段顺序与 Kotlin 侧一致
JNIEXPORT void JNICALL Java_com_xreal_beamlink_PoseEngine_nativeStats(
    JNIEnv* env, jobject, jlong h, jfloatArray out) {
    Engine* e = toEngine(h);
    if (!e || out == nullptr) return;
    const xr::TrackerStats& s = e->tracker.stats();
    float v[18];
    v[0] = static_cast<float>(s.samples);
    v[1] = static_cast<float>(s.dropped);
    v[2] = s.sampleRateHz;
    v[3] = s.temperatureC;
    v[4] = s.gyroBiasDps.x;
    v[5] = s.gyroBiasDps.y;
    v[6] = s.gyroBiasDps.z;
    v[7] = s.accelErrorDeg;
    v[8] = s.initialising ? 1.0f : 0.0f;
    v[9] = s.accelIgnored ? 1.0f : 0.0f;
    v[10] = s.yawDeg;
    v[11] = s.pitchDeg;
    v[12] = s.rollDeg;
    v[13] = static_cast<float>(e->packets);
    v[14] = static_cast<float>(e->badFrames);
    v[15] = static_cast<float>(e->initFrames);
    v[16] = s.gyroScale;
    v[17] = s.accelScale;
    env->SetFloatArrayRegion(out, 0, 18, v);
}

}  // extern "C"
