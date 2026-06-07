# RemoteControlCar

Android 远程遥控车控制客户端，通过 TCP 连接远程服务器，实现视频流播放、音频对讲、遥控操作和 GPS 定位。

## 功能

- **视频**：H.265 硬解码实时播放，支持高清/流畅切换，MP4 录制
- **音频**：双向对讲（raw PCM），支持回声消除和噪声抑制
- **遥控**：虚拟摇杆 + 蓝牙手柄，油门/转向/灯光控制（50Hz）
- **遥测**：实时显示信号强度、电池电压
- **定位**：高德地图显示遥控车 GPS 位置和速度

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin |
| 构建 | Gradle (AGP 9.x) |
| 最低 SDK | 24 (Android 7.0) |
| 视频 | MediaCodec (H.265) + SurfaceView |
| 音频 | AudioTrack + AudioRecord + AEC/NS |
| 网络 | 原生 TCP Socket + Coroutines |
| 地图 | 高德地图 3D SDK |

## 构建

使用 Android Studio 打开项目，等待 Gradle Sync 完成后点击 **Run** 即可。

首次构建需要能访问 Maven Central 下载高德地图 SDK 依赖。

## 使用

1. 安装 APK 到 Android 设备
2. 输入服务器地址（`host:port`）和设备序列号
3. 选择画质（高清 1920×1080 / 流畅 1280×720）
4. 点击"进入遥控"，自动查询端口并建立连接

## 控制协议

TCP 二进制协议，通用帧格式：`[Magic 2B][Type 1B][Length 1B][Payload][Checksum 1B]`

| Type | 名称 | 方向 | 说明 |
|------|------|------|------|
| 0x01 | 控制包 | APP→服务器 | 油门/转向/灯光，20ms 一帧 |
| 0x02 | 遥测包 | 服务器→APP | 信号强度/电池电压 |
| 0x03 | GPS 包 | 服务器→APP | 经纬度/速度 |

详细协议定义见 [遥控协议文档.md](遥控协议文档.md)。

## 项目结构

```
app/src/main/java/.../
├── MainActivity.kt            # 首页：服务器连接
├── ControlActivity.kt         # 控制页：视频+遥控+音频+遥测
├── MapPopupActivity.kt        # 地图弹窗：GPS 定位
├── network/
│   ├── PortQuerier.kt         # 端口查询
│   ├── ControlClient.kt       # 控制通道
│   ├── VideoStreamManager.kt  # H.265 视频流
│   └── AudioStream.kt         # 双向音频流
└── view/
    └── JoystickView.kt        # 虚拟摇杆
```

## License

MIT
