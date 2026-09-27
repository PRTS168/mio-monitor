<div align="center">

# Mio 澪

### 一部手机的硬件控制台

实时看懂 CPU、GPU、电池、温度与传感器 —— 像电脑「任务管理器」一样直观。
**纯只读 · 免 root · 免提权即可用 · 局域网多设备监控**

<br/>

![Version](https://img.shields.io/badge/version-v1.0-007AFF)
![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-blue)
![Readonly](https://img.shields.io/badge/%E5%8F%AA%E8%AF%BB-%E4%B8%8D%E5%86%99%E7%B3%BB%E7%BB%9F%E8%8A%82%E7%82%B9-orange)

</div>

---

## 截图

<div align="center">

| 仪表盘 | CPU | GPU |
|:---:|:---:|:---:|
| <img src="docs/screenshots/01-dashboard.png" width="230"/> | <img src="docs/screenshots/02-cpu.png" width="230"/> | <img src="docs/screenshots/03-gpu.png" width="230"/> |

| 电池 | 温度与散热 | 设置 |
|:---:|:---:|:---:|
| <img src="docs/screenshots/04-battery.png" width="230"/> | <img src="docs/screenshots/05-thermal.png" width="230"/> | <img src="docs/screenshots/06-settings.png" width="230"/> |

*真机实拍：中兴天机 A41 Ultra（骁龙 8 Gen1 / Adreno 730）*

</div>

---

## 它能给你什么

- **一台随身硬件控制台**：不用连电脑、不用 root、不用 Xposed，打开就看到这颗 SoC 此刻在干什么。
- **能读到的都读出来**：CPU 每核频率与占用、GPU 占用与频率、电池电压/电流/功率/健康度、几十路热区温度与热缓解动作、内存与传感器。
- **看得懂**：浅色 iOS 风格、卡片分组、曲线与数字同源同口径；读不到就显示「–」，**绝不用 0 伪装**。
- **两台手机当一块屏用**：局域网双模式，一台当被监控端、一台当监控端，多设备同屏观察。

---

## 功能一览

### 本机监控

| 模块 | 内容 |
|---|---|
| **CPU** | 每核当前/最高频率、动态分簇（超大核/性能核/能效核四色角色）、每核占用、总占用曲线、频率档位驻留（time_in_state）、governor 与各簇限频、loadavg |
| **GPU** | 占用曲线、频率（MHz）、GPU 核心温度；高通 Adreno / Mali / 联发科 GED 多平台自动识别 |
| **电池** | 电量环、充放电状态、电压/电流/功率（充电时优先取**适配器输入侧**口径）、充电器协商上限、健康度/设计容量/学习容量/循环次数/OCV/截止电压/充入电量/限流档位、24h 容量趋势、预计充满/续航估算 |
| **温度与散热** | 最高结温 / 平均 / 外壳温度、**距温度红线**、热缓解动作（cooling device，默认只列已触发项，可展开全部）、按 CPU / GPU·NPU·内存 / 射频·充电·外壳 三分组的热区列表、84 格热图 |
| **内存** | 总量 / 可用 / 已用 / 缓存 / Swap，进度条与数值 |
| **传感器** | 加速度、陀螺仪、磁力计、光线、距离等真实物理量 + 全部传感器清单 |
| **Thermal HAL** | 经 `dumpsys thermalservice` 读取 HAL 分类温度（CPU/GPU/电池/外壳/电压/电流/电量/NPU）、热状态等级与降频阈值 |

### 局域网双模式（两部手机）

- **被监控端**：前台服务 1s 采集、mDNS 自动广播、向监控端推送；常驻通知 + 开机自启 + JobScheduler 兜底 + 厂商保活引导（OPPO / vivo / 小米 / 华为 / 三星 逐项跳转）。
- **监控端**：自动发现并连接，可同时看多台设备；设备卡片含 CPU 各核频率、结温、电量、HAL 分类温度、电池、内存 / GPU / 充电；可下钻单设备详情页看历史曲线。
- **协议**：TCP + 4 字节长度前缀 + UTF-8 JSON，字段向后兼容。
- **隐私**：只在局域网内点对点传输，**不经过任何外部服务器**。

### 显示与交互

- **玻璃效果三档**：`完整`（半透明 + 顶部高光 + 光泽柔化，Android 12+ 走 RenderEffect）/ `简约`（更实、少一层叠加）/ `关闭`（全实色卡片）。切换立即生效。
- **触感反馈**：底部 Tab 与卡片按下均有震动；**触感强度 轻 / 中 / 重** 可调；**按住「轻/中/重」任意一键会一直震、松手即停**，可当场比对手感。
- **统一动效**：单一缓动曲线 + 时长令牌（按下 130ms / 松开 240ms / Tab 指示器 280ms / 数字 320ms）；Tab 指示器平滑滑动，页面淡入淡出。
- **按压反馈**：卡片按下轻微收缩 + 阴影下沉 + 透明度下沉，抬起平滑恢复；**文字与图表始终在最上层，永不被模糊或扭曲**。
- **数值平滑**：CPU 占用、温度、内存、GPU 等数值插值滚动，避免每秒跳变。
- **滚动**：卡片带缓慢流动的光泽；拉到顶/底有柔和回弹。
- **无障碍**：跟随系统「关闭动画」（Reduce Motion）自动直切。

### 提权（可选，纯读取）

原则是「**能读多少读多少，永远不写**」，优先级 **Root > Shizuku > 免提权**：

| 档位 | 额外可读 |
|---|---|
| 免提权 | 电量 / 电池温度 / 电压、内存、传感器、部分 GPU 占用、多数机型 CPU 频率 |
| **Shizuku**（推荐） | 热区温度、cooling、CPU 占用、充电器输入侧 V/I、更多 sysfs 节点 |
| Root | 再解锁部分被 SELinux 拒绝的节点（如某些机型的 Adreno GPU 频率） |

未授权时相关卡片显示空态并给出「去授权」入口，**不会整页空白**。

### 数据导出

CSV 三段式：**META**（时刻 / 机型 / 版本）、**RAW**（内核原始单位、零换算、含来源路径）、**RENDERED**（同 UI 换算、含页面归属）。UTF-8 BOM + 全字段引号转义，写入「下载/Mio」，只保留最近 5 个。

---

## 安装

1. 到 [Releases](../../releases) 下载最新 `.apk`；
2. 允许「安装未知来源应用」后直接安装（**Android 8.0 及以上**）；
3. 想拿到更完整的数据（热区 / CPU 占用 / 充电器输入侧），再装 [Shizuku](https://shizuku.rikka.app/) 并在 App 内一键授权 —— **不装也能用**。

> 首次启动有 10 页引导，说明三档权限与每个页面能看什么。

---

## 技术栈

| 项 | 值 |
|---|---|
| 语言 / UI | Kotlin 2.0.21 · Jetpack Compose（Material 3 · BOM 2024.09.03） |
| 构建 | AGP 8.7.3 · Gradle 8.10.2 · JDK 17 |
| 最低 / 目标 | minSdk 26（Android 8.0）· targetSdk 34 |
| 架构 | 单 Activity + Compose · ViewModel + 细粒度 StateFlow · 分层回退的硬件 Reader |
| 权限 | 可选 Shizuku（`moe.shizuku.manager.permission.API_V23`）· 可选 Root(`su`) |
| 网络 | 仅局域网：mDNS(NSD) 发现 + TCP 长连接 |

**工程亮点**

- **统一采集口径**：本机 UI 与被监控端调用同一个 `SnapshotCollector`，「本机看到的」与「推给别的手机的」是同一套数据与换算。
- **提权批量读取**：一条 shell 命令取回热区 / 频率 / loadavg / cooling 等，每帧跨进程往返从 11+ 次降到 1 次。
- **分层回退识别硬件**：SoC / GPU / 热区 / 电源节点按平台探测并回退，不为每台机器写死代码。
- **细粒度订阅**：卡片各自订阅所需 Flow，未变不重组；滚动上下文用动态 `compositionLocal`，避免滚动时整页重组。
- **冷启动 / 回前台 <1s**：先出轻快照（电量 / 内存）再补提权富数据，回前台只合并不倒退。

---

## 已知限制（诚实说明）

- **仅只读**：不修改任何系统设置与节点，因此读不到需要写权限的数据；
- **GPU 频率**：部分机型（如中兴 A41 Ultra）对 App 与 shell 都拒绝该节点，需要 Root；
- **触感强度**：少数机型的厂商 HAL 不暴露振幅接口，此时「轻 / 中 / 重」只能靠时长与波形表达；
- **后台保活**：国产 ROM 杀后台较激进，被监控端已内置多重保活与厂商设置引导，仍需系统层面放行；
- **语言**：目前为简体中文（文案内嵌，尚未抽出字符串资源）。

---

## 隐私

- 本机监控**不联网**；
- 双模式**仅在局域网内**向用户自己的设备推送数据，不经过任何外部服务器；
- 不采集、不上传任何个人信息；CSV 只写到本机「下载」目录。

---

## 构建

```bash
# JDK 17 + Android SDK 34
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

---

## 许可

[MIT](LICENSE)

<div align="center"><sub>Mio 澪 · 纯只读硬件监控 · 用数据说话</sub></div>
