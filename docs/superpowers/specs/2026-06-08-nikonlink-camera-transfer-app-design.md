# NikonLink — 尼康相机安卓传输与遥控应用设计规格

**日期**: 2026-06-08
**状态**: 设计已确认
**版本**: 1.0

---

## 1. 产品概述

### 1.1 产品定位

NikonLink 是一款面向尼康数码相机的安卓手机应用，通过蓝牙（BLE）与 WiFi 协同工作，实现照片/视频的无线传输和相机遥控拍摄。

### 1.2 目标用户

- 专业摄影师：需要快速将RAW文件传输到手机进行筛选和初修
- 旅行/日常用户：需要备份照片视频、释放存储卡
- 社交媒体用户：需要即时分享相机拍摄的高质量内容

### 1.3 目标机型

| 系列 | 机型 |
|------|------|
| Z系列无反 | Z9, Z8, Z6III, Zf, Z50II, Z50, Z7II, Z6II, Z5 |
| 新款单反 | D780, D850, D6 |

所有目标机型为 2019 年后发布，支持 SnapBridge 2.x 协议。

### 1.4 平台要求

- **操作系统**: Android 9+ (API Level 28)
- **最低存储**: 应用本体约 30MB，用户数据取决于传输内容

---

## 2. 技术架构

### 2.1 架构模式：MVVM + Clean Architecture

```
UI Layer (XML + Fragment/Activity + ViewModel)
    ↕
Domain Layer (UseCase + Repository Interface) — Pure Kotlin
    ↕
Data Layer (Repository Impl + DataSource)
```

**单向依赖**：UI → Domain → Data。Domain 层不依赖任何 Android Framework。

### 2.2 技术选型

| 类别 | 选择 | 理由 |
|------|------|------|
| 编程语言 | Kotlin | Android 官方推荐，简洁安全 |
| UI框架 | XML View + ViewBinding | 成熟稳定，复杂列表和图片加载表现好 |
| 依赖注入 | Hilt (Dagger-Hilt) | Google 推荐，ViewModel 集成最优 |
| 异步处理 | Kotlin Coroutines + Flow | 原生支持，轻量高效 |
| 图片加载 | Coil | Kotlin 原生，支持自定义解码器（RAW预览） |
| 后台任务 | WorkManager | 保证传输完成，兼容各版本后台限制 |
| 本地数据库 | Room | 官方 ORM，编译期 SQL 校验 |
| 网络通信 | OkHttp | 成熟 HTTP 客户端 |
| BLE通信 | Android BLE API | 系统原生支持 |
| PTP-IP通信 | 自定义 PTP/IP 客户端 | 基于 TCP Socket 实现 |
| 本地文件 | MediaStore + SAF | 写入系统相册，其他应用可访问 |

### 2.3 项目模块结构

```
NikonLink/
├── app/                          # 壳工程，DI 组装
├── feature-connection/           # 连接管理模块
├── feature-browser/              # 文件浏览模块
├── feature-transfer/             # 文件传输模块
├── feature-remote/               # 遥控拍摄模块
├── core-common/                  # 公共工具类
└── core-data/                    # 数据层（Room DB, DataSource）
```

---

## 3. 连接管理层设计

### 3.1 连接状态机

```
Disconnected → BLE Scanning → BLE Connected ⇄ WiFi Handshake → WiFi Transfer
```

- **Disconnected**: 初始状态 / 主动断开
- **BLE Scanning**: 扫描附近尼康相机（SnapBridge Service UUID）
- **BLE Connected**: 低功耗常连接，支持缩略图浏览和快门释放
- **WiFi Handshake**: 通过 BLE 协商 WiFi 参数（SSID/密码），自动切换网络
- **WiFi Transfer**: 高速传输模式，支持文件下载和 LiveView 流

### 3.2 连接流程

1. BLE 扫描发现相机 → 配对认证
2. 建立 BLE 常连接 → 同步相机信息
3. BLE 传输缩略图列表（数据量小，低速可行）
4. 用户请求传输大文件 → BLE 发送 WiFi 配置请求
5. 相机返回 WiFi 热点信息 → 手机自动连接
6. WiFi 建立 → 高速 PTP-IP / HTTP 传输
7. 传输完成 → 断开 WiFi → 回归 BLE 低功耗连接

### 3.3 协议适配

| 协议 | 适用场景 | 底层技术 |
|------|---------|---------|
| SnapBridge 2.x (BLE) | 配对、缩略图、遥控、WiFi握手 | Android BLE API |
| PTP-IP (WiFi) | 文件浏览、高速传输、LiveView | TCP Socket 实现 PTP 协议 |
| Nikon HTTP API (WiFi) | REST风格文件访问、相机设置 | OkHttp |
| 尼康 WMU 协议 | D850/D6/D780 降级兼容 | TCP Socket |

### 3.4 异常处理策略

- **BLE 断线检测**: 5 秒心跳超时，自动进入扫描重连
- **WiFi 传输中断**: 记录断点，回到 BLE 模式后提示用户续传
- **后台保活**: 传输任务由 WorkManager 接管，APP 退到后台继续
- **省电策略**: 空闲 5 分钟后断开 WiFi，仅保持 BLE 低功耗连接

---

## 4. 文件浏览设计

### 4.1 浏览模式：文件夹/目录浏览

采用文件夹目录树视图（而非时间线），显示相机存储卡实际结构：

```
📁 DCIM
  📁 100ND750
    🖼 DSC_1234.NEF [RAW]
    🖼 DSC_1235.JPG
    🎬 DSC_1236.MOV [00:32]
  📁 101ND750
📁 NIKON
```

### 4.2 核心功能

- **格式识别标签**: RAW (NEF) 橙色角标、JPEG 无标、视频显示时长角标
- **RAW 内嵌预览**: 解析 NEF 文件内嵌的 JPEG 预览作为缩略图，无需传输完整文件
- **多选操作**: 长按进入多选模式，支持全选/反选，底部栏显示已选数量和总大小
- **筛选排序**: 按类型筛选（全部/照片/视频/RAW），按名称/日期/大小排序

### 4.3 缩略图加载策略

- BLE 连接时：请求相机生成低分辨率缩略图（传输快）
- WiFi 连接时：可获取更高分辨率预览
- Coil 自定义解码器：从 NEF 二进制中提取内嵌 JPEG 预览（通常 2-4MB）

---

## 5. 文件传输设计

### 5.1 传输队列模型

```
用户选择文件 → 加入传输队列 → Room DB 持久化 → WorkManager 执行 → 完成通知
```

### 5.2 传输特性

- **优先级策略**: 先传 JPEG 缩略图（用户快速预览）→ 再传 RAW/视频
- **断点续传**: 每传输完一个文件更新 DB 进度，中断后从已传字节继续
- **后台传输**: WorkManager 保证任务完成，不受 APP 前后台切换影响
- **通知栏进度**: 显示当前文件进度条 + 总进度 + 实时速率 (MB/s) + 预估剩余时间
- **重复检测**: 传输前比对文件名和大小，避免重复传输

### 5.3 文件存储规则

| 类型 | 路径 | 备注 |
|------|------|------|
| 照片 (JPEG/NEF/TIFF) | `Pictures/NikonLink/{相机名}/{日期}/` | MediaStore 注册到系统相册 |
| 视频 (MP4/MOV) | `Movies/NikonLink/{相机名}/{日期}/` | MediaStore 注册到系统视频 |

---

## 6. 遥控拍摄设计

### 6.1 界面布局

- **上半屏**: LiveView 实时取景画面，可点击设置对焦点
- **中部**: 曝光参数显示（快门/光圈/ISO/EV）+ 可调节滑块
- **下部**: 快门按钮（中央圆形按钮）+ 定时拍摄 + 设置入口
- **底部标签栏**: 拍摄 / 回放 / 视频 模式切换

### 6.2 功能清单

| 功能 | 实现方式 |
|------|---------|
| LiveView 取景 | WiFi 下获取 MJPEG 流，SurfaceView 渲染 |
| 触屏对焦 | 点击坐标通过 PTP-IP 发送 AF 指令 |
| 参数显示/调整 | BLE 读取/设置快门、光圈、ISO、EV |
| 快门释放 | BLE 发送快门指令（低延迟） |
| 定时拍摄 | 本地倒计时 2s/5s/10s 后触发快门 |
| 间隔拍摄 | 本地定时器按间隔触发，用于延时摄影 |
| 即时回传 | 拍摄后 WiFi 自动下载 JPEG 预览 |
| 模式切换 | 拍照/视频模式切换，对应不同参数集 |

### 6.3 BLE / WiFi 功能分工

| 功能 | BLE | WiFi |
|------|-----|------|
| 快门释放 | ✅ 低延迟 | ✅ |
| 参数读写 | ✅ 轻量数据 | ✅ |
| LiveView | ❌ 带宽不足 | ✅ MJPEG流 |
| 触屏对焦 | ⚠️ 坐标可传 | ✅ 实时 |
| 即时回传 | ❌ 太慢 | ✅ 秒级 |

策略：进入遥控界面即触 WiFi Handshake，退出遥控时回归 BLE 省电模式。

---

## 7. 数据模型

### 7.1 Room 数据库表

**camera_devices** — 已配对相机

| 字段 | 类型 | 说明 |
|------|------|------|
| id | INTEGER (PK) | 自增ID |
| name | TEXT | 用户自定义名称 |
| model | TEXT | 相机型号 |
| ble_address | TEXT | BLE MAC 地址 |
| wifi_ssid | TEXT | WiFi 热点 SSID |
| wifi_password | TEXT | WiFi 密码（加密存储） |
| last_connected | INTEGER | 最后连接时间戳 |
| firmware_version | TEXT | 相机固件版本 |
| protocol | TEXT | 协议类型（snapbridge/ptpip/http/wmu） |

**transfer_tasks** — 传输任务

| 字段 | 类型 | 说明 |
|------|------|------|
| id | INTEGER (PK) | 自增ID |
| camera_id | INTEGER (FK) | 关联相机 |
| remote_path | TEXT | 相机上文件路径 |
| file_name | TEXT | 文件名 |
| file_size | INTEGER | 文件大小（字节） |
| file_type | TEXT | 类型（JPEG/NEF/TIFF/MP4/MOV） |
| status | TEXT | pending/transferring/completed/failed |
| progress_bytes | INTEGER | 已传输字节数 |
| local_uri | TEXT | 本地文件 URI |
| created_at | INTEGER | 创建时间戳 |
| completed_at | INTEGER | 完成时间戳 |
| error_message | TEXT | 错误信息（失败时） |

**app_settings** — 应用设置

| 字段 | 类型 | 说明 |
|------|------|------|
| key | TEXT (PK) | 设置键 |
| value | TEXT | 设置值 |

### 7.2 DataStore (Preferences)

用于存储简单键值偏好：自动传输开关、传输画质偏好、省电策略配置等。

---

## 8. 应用导航

```
🏠 主页（已配对相机列表 + 扫描入口）
  ├── 📡 扫描配对页面
  │     └── BLE扫描 → 选择相机 → 配对确认
  ├── 📁 文件浏览页面
  │     ├── 文件夹列表 → 缩略图网格
  │     └── 选择文件 → 加入传输队列
  ├── 📤 传输管理页面
  │     ├── 传输中队列
  │     └── 传输历史
  ├── 📷 遥控拍摄页面
  │     └── LiveView + 参数控制 + 快门
  └── ⚙️ 设置页面
        ├── 存储路径
        ├── 传输质量
        ├── 自动传输
        └── 省电策略
```

所有页面通过 Jetpack Navigation Component 管理，使用底部导航栏切换主要功能。

---

## 9. 功能优先级

| 优先级 | 模块 | 功能 |
|--------|------|------|
| **P0 核心** | 连接管理 | BLE 扫描/配对/常连 + WiFi 自动切换 + 断线重连 |
| **P0 核心** | 文件浏览 | 目录树 + 缩略图（含RAW预览）+ 多选筛选 |
| **P0 核心** | 文件传输 | 队列 + WorkManager 后台 + 断点续传 + 通知栏进度 |
| **P1 重要** | 遥控拍摄 | LiveView + 触屏对焦 + 参数调节 + 快门 + 间隔拍摄 |
| **P1 重要** | 传输历史 | 传输记录查看 + 重复检测 |
| **P1 重要** | 本地浏览 | 查看已传输照片/视频 + 分享到其他APP |
| **P2 增强** | 设置 | 存储路径自定义 + 传输质量选择 + 自动传输规则 + 省电策略 |

---

## 10. 安全与隐私

- WiFi 密码使用 Android EncryptedSharedPreferences 加密存储
- 不收集用户照片/视频内容
- 网络通信仅限局域网（相机 WiFi 热点），不经过外部服务器
- 无需互联网权限（除未来可能的固件更新）
- 应用不请求位置权限（使用 Companion Device Manager 绕过 BLE 扫描对位置权限的要求）

---

## 11. 测试策略

### 单元测试
- Domain 层 UseCase 纯 Kotlin 单元测试（JUnit5 + MockK）
- Repository 实现 + 假 DataSource 集成测试

### UI 测试
- 各页面 ViewModel 测试（Turbine 测试 Flow）
- 关键流程 Espresso UI 测试：扫描→配对→浏览→传输

### 设备测试
- 实体尼康相机测试矩阵：Z8, Z6III, Zf, D780（覆盖 Z系列无反 + 单反）
- 不同 Android 版本：9, 10, 11, 12, 13, 14

---

## 12. 不做的（YAGNI）

- ❌ 云端备份/同步 — 保持应用专注本地传输
- ❌ 照片编辑功能 — 传输后使用其他专业编辑APP
- ❌ 相机固件更新 — 第一版不做
- ❌ 多相机同时连接 — 增加复杂度，使用场景极少
- ❌ 旧款相机兼容（2019年前）— 协议差异大，维护成本高
- ❌ iOS 版本 — 当前仅 Android
