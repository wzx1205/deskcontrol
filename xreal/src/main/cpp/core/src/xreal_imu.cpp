#include "xreal_imu.h"

#include <string.h>

namespace xr {
namespace {

inline int16_t rd16(const uint8_t* d) {
    return static_cast<int16_t>(static_cast<uint16_t>(d[1]) << 8 | d[0]);
}
inline int16_t rd16be(const uint8_t* d) {
    return static_cast<int16_t>(static_cast<uint16_t>(d[0]) << 8 | d[1]);
}
inline int16_t rd16bizarre(const uint8_t* d) {
    return static_cast<int16_t>(static_cast<uint16_t>(d[0]) |
                                (static_cast<uint16_t>(d[1] ^ 0x80) << 8));
}
inline int32_t rd32(const uint8_t* d) {
    return static_cast<int32_t>(static_cast<uint32_t>(d[0]) | (static_cast<uint32_t>(d[1]) << 8) |
                                (static_cast<uint32_t>(d[2]) << 16) | (static_cast<uint32_t>(d[3]) << 24));
}
inline int32_t rd32be(const uint8_t* d) {
    return static_cast<int32_t>((static_cast<uint32_t>(d[0]) << 24) | (static_cast<uint32_t>(d[1]) << 16) |
                                (static_cast<uint32_t>(d[2]) << 8) | static_cast<uint32_t>(d[3]));
}
inline int32_t rd24(const uint8_t* d) {
    uint32_t v = static_cast<uint32_t>(d[0]) | (static_cast<uint32_t>(d[1]) << 8) |
                 (static_cast<uint32_t>(d[2]) << 16);
    if (d[2] & 0x80) v |= 0xFF000000u;
    return static_cast<int32_t>(v);
}

// 厂商默认映射 = AxesSwap(NXNZNY) ∘ AxesSwap(PZPXPY) = (x,y,z) -> (-y,-x,-z)
inline Vec3 mapVendor(const Vec3 v) { return Vec3{-v.y, -v.x, -v.z}; }

// 绕"上方向"(机体 Z)旋转：只换水平两轴，重力方向不变，
// 用来对齐 IMU 贴装轴与外壳轴（否则抬头会被当成横滚）
inline Vec3 mapAxis(const Vec3 v, AxisMapping m) {
    switch (m) {
        case AxisMapping::VendorDefault: return mapVendor(v);
        case AxisMapping::PassThrough:   return v;
        case AxisMapping::RotZ90:        return Vec3{ v.y, -v.x, v.z};
        case AxisMapping::RotZ180:       return Vec3{-v.x, -v.y, v.z};
        case AxisMapping::RotZ270:       return Vec3{-v.y,  v.x, v.z};
    }
    return v;
}

}  // namespace

int imuInterfaceFor(uint16_t pid) {
    switch (pid) {
        case kPidAir:
        case kPidAir2:
        case kPidAir2Pro:
            return 3;
        case kPidAir2Ultra:
            return 2;
        default:
            return -1;
    }
}

int mcuInterfaceFor(uint16_t pid) {
    switch (pid) {
        case kPidAir:
        case kPidAir2:
        case kPidAir2Pro:
            return 4;
        case kPidAir2Ultra:
            return 0;
        default:
            return -1;
    }
}

int maxPayloadFor(uint16_t pid) { return pid == kPidAir2Ultra ? 512 : 64; }

uint32_t crc32(const uint8_t* data, size_t len) {
    uint32_t crc = 0xFFFFFFFFu;
    for (size_t i = 0; i < len; ++i) {
        crc ^= data[i];
        for (int b = 0; b < 8; ++b) {
            crc = (crc >> 1) ^ (0xEDB88320u & (0u - (crc & 1u)));
        }
    }
    return crc ^ 0xFFFFFFFFu;
}

int buildCommand(uint8_t* out, uint8_t cmd, const uint8_t* payload, int payloadLen) {
    const uint16_t len = static_cast<uint16_t>(3 + payloadLen);  // 覆盖 len/cmd/payload
    out[0] = 0xAA;
    out[5] = static_cast<uint8_t>(len & 0xFF);
    out[6] = static_cast<uint8_t>(len >> 8);
    out[7] = cmd;
    if (payload != nullptr && payloadLen > 0) {
        memcpy(out + 8, payload, static_cast<size_t>(payloadLen));
    }
    const uint32_t crc = crc32(out + 5, len);
    out[1] = static_cast<uint8_t>(crc & 0xFF);
    out[2] = static_cast<uint8_t>((crc >> 8) & 0xFF);
    out[3] = static_cast<uint8_t>((crc >> 16) & 0xFF);
    out[4] = static_cast<uint8_t>((crc >> 24) & 0xFF);
    return 8 + payloadLen;
}

bool ImuParser::parse(const uint8_t* data, int len, ImuSample* out) {
    if (data == nullptr || out == nullptr) return false;
    out->valid = false;
    out->initFrame = false;
    if (len < 64) return false;

    RawImuPacket pkt;
    memcpy(&pkt, data, sizeof(pkt));

    if (pkt.signature[0] == 0xAA && pkt.signature[1] == 0x53) {
        out->initFrame = true;
        out->timestampNs = pkt.timestamp;
        return false;
    }
    if (pkt.signature[0] != 0x01 || pkt.signature[1] != 0x02) return false;

    out->timestampNs = pkt.timestamp;
    out->temperatureC = static_cast<float>(rd16(pkt.temperature)) / 132.48f + 25.0f;

    const int32_t gMul = rd16(pkt.angular_multiplier);
    const int32_t gDiv = rd32(pkt.angular_divisor);
    const int32_t aMul = rd16(pkt.acceleration_multiplier);
    const int32_t aDiv = rd32(pkt.acceleration_divisor);
    if (gDiv == 0 || aDiv == 0) return false;  // 除零保护

    const float gScale = static_cast<float>(gMul) / static_cast<float>(gDiv);
    const float aScale = static_cast<float>(aMul) / static_cast<float>(aDiv);

    Vec3 g{static_cast<float>(rd24(pkt.angular_velocity_x)) * gScale,
           static_cast<float>(rd24(pkt.angular_velocity_y)) * gScale,
           static_cast<float>(rd24(pkt.angular_velocity_z)) * gScale};
    Vec3 a{static_cast<float>(rd24(pkt.acceleration_x)) * aScale,
           static_cast<float>(rd24(pkt.acceleration_y)) * aScale,
           static_cast<float>(rd24(pkt.acceleration_z)) * aScale};

    const int32_t mMul = rd16be(pkt.magnetic_multiplier);
    const int32_t mDiv = rd32be(pkt.magnetic_divisor);
    Vec3 m{0.0f, 0.0f, 0.0f};
    if (mDiv != 0) {
        const float mScale = static_cast<float>(mMul) / static_cast<float>(mDiv);
        m = Vec3{static_cast<float>(rd16bizarre(pkt.magnetic_x)) * mScale,
                 static_cast<float>(rd16bizarre(pkt.magnetic_y)) * mScale,
                 static_cast<float>(rd16bizarre(pkt.magnetic_z)) * mScale};
    }

    g = mapAxis(g, mapping_);
    a = mapAxis(a, mapping_);
    m = mapAxis(m, mapping_);

    out->gyroDps = g;
    out->accelG = a;
    out->mag = m;
    out->gyroScale = gScale;
    out->accelScale = aScale;
    out->valid = true;
    return true;
}

}  // namespace xr
