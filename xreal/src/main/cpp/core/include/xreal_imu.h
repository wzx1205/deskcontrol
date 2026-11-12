// xreal_imu.h —— XREAL Air / Air 2 / Air 2 Pro / Air 2 Ultra 眼镜 IMU HID 协议
//
// 协议结论来自四处交叉验证（全部可在 recon/ 复查）：
//  1. 厂商二进制：Beam Pro 的 libnr_glasses_api.so -> cmd_build_imu
//     命令帧 = 0xAA | crc32(LE) | len16(LE) | cmd | payload   [llvm-objdump 反汇编确认]
//  2. AirAPI_Windows (MSmithDev) parse_report()：64 字节报文 / ns 时间戳 / 24bit 定点
//  3. nrealAirLinuxDriver (UnicornXR / thejackimonster) device_imu_packet_t：
//     multiplier-divisor 动态标定 + 温度 + 磁力计，packed 后正好 64 字节
//  4. libnr_api.xreal.so 内置 JSON：gyro 4000/65536、acc 32/65536、gravity 9.8、
//     imu_payload 128（128/512 字节变体用于带摄像头的机型）
//
// 64 字节标准帧（小端）：
//   0   2  signature          正常 0x01 0x02；上电首帧 0xAA 0x53
//   2   2  temperature        int16, °C = raw/132.48 + 25（ICM-42688-P）
//   4   8  timestamp          uint64 纳秒
//   12  2  angular_multiplier int16
//   14  4  angular_divisor    int32
//   18  3  gyro_x   21 gyro_y   24 gyro_z           24bit 有符号
//   27  2  accel_multiplier   int16
//   29  4  accel_divisor      int32
//   33  3  accel_x  36 accel_y  39 accel_z          24bit 有符号
//   42  2  mag_multiplier     int16（字节序反转）
//   44  4  mag_divisor        int32（字节序反转）
//   48  2  mag_x  50 mag_y  52 mag_z                高位异或 0x80 的 16bit
//   54  4  checksum
//   58  6  padding            合计 64
//
// 换算：dps = raw24 * angular_multiplier / angular_divisor
//       g   = raw24 * accel_multiplier  / accel_divisor
#pragma once

#include <stddef.h>
#include <stdint.h>

#include "xr_math.h"

namespace xr {

constexpr uint16_t kXrealVid = 0x3318;
constexpr uint16_t kPidAir = 0x0424;
constexpr uint16_t kPidAir2 = 0x0428;
constexpr uint16_t kPidAir2Pro = 0x0432;
constexpr uint16_t kPidAir2Ultra = 0x0426;

int imuInterfaceFor(uint16_t pid);   // Air/Air2/Pro = 3，Ultra = 2
int mcuInterfaceFor(uint16_t pid);   // Air/Air2/Pro = 4，Ultra = 0
int maxPayloadFor(uint16_t pid);     // 64 / 512

// 启动 IMU 推流（HID feature report，report id = 0，共 10 字节）
// 帧体 = AA | crc32(04 00 19 01) | 04 00 | 19 | 01
constexpr uint8_t kCmdStartImu[10] = {0x00, 0xAA, 0xC5, 0xD1, 0x21, 0x42, 0x04, 0x00, 0x19, 0x01};

// 组帧：out[0]=0xAA, out[1..4]=crc32(LE), out[5..6]=len(LE), out[7]=cmd, out[8..]=payload
// 返回整帧长度（8 + payloadLen）
int buildCommand(uint8_t* out, uint8_t cmd, const uint8_t* payload, int payloadLen);
uint32_t crc32(const uint8_t* data, size_t len);

#pragma pack(push, 1)
struct RawImuPacket {  // 与厂商 device_imu_packet_t 布局一致
    uint8_t signature[2];
    uint8_t temperature[2];
    uint64_t timestamp;
    uint8_t angular_multiplier[2];
    uint8_t angular_divisor[4];
    uint8_t angular_velocity_x[3];
    uint8_t angular_velocity_y[3];
    uint8_t angular_velocity_z[3];
    uint8_t acceleration_multiplier[2];
    uint8_t acceleration_divisor[4];
    uint8_t acceleration_x[3];
    uint8_t acceleration_y[3];
    uint8_t acceleration_z[3];
    uint8_t magnetic_multiplier[2];
    uint8_t magnetic_divisor[4];
    uint8_t magnetic_x[2];
    uint8_t magnetic_y[2];
    uint8_t magnetic_z[2];
    uint32_t checksum;
    uint8_t padding[6];
};
#pragma pack(pop)

static_assert(sizeof(RawImuPacket) == 64, "XREAL IMU 报文必须是 64 字节");

struct ImuSample {
    uint64_t timestampNs = 0;  // 眼镜端硬件时间戳（用于 dt）
    uint64_t hostNs = 0;       // 宿主收到该帧的时刻（elapsedRealtimeNanos，用于外推）
    Vec3 gyroDps = vZero();
    Vec3 accelG = vZero();
    Vec3 mag = vZero();
    float temperatureC = 25.0f;
    float gyroScale = 0.0f;   // dps / LSB（由该帧 mult/div 推出，便于现场核对）
    float accelScale = 0.0f;  // g / LSB
    bool valid = false;
    bool initFrame = false;
};

// 坐标轴置换：
//   pre  = NXNZNY : (x,y,z) -> (-x,-z,-y)
//   post = PZPXPY : (x,y,z) -> ( z, x, y)
//   复合      (x,y,z) -> (-y,-x,-z)   —— 纯旋转（绕 (1,-1,0)/√2 转 180°），不翻手性
enum class AxisMapping {
    VendorDefault = 0,  // 厂商驱动那套 (-y,-x,-z)
    PassThrough = 1,    // 原样
    RotZ90 = 2,         // (x,y,z) -> ( y,-x, z)   绕上方向 +90°
    RotZ180 = 3,        // (x,y,z) -> (-x,-y, z)
    RotZ270 = 4,        // (x,y,z) -> (-y, x, z)   绕上方向 -90°
};

class ImuParser {
public:
    explicit ImuParser(AxisMapping mapping = AxisMapping::VendorDefault) : mapping_(mapping) {}
    bool parse(const uint8_t* data, int len, ImuSample* out);
    void setMapping(AxisMapping m) { mapping_ = m; }

private:
    AxisMapping mapping_;
};

}  // namespace xr
