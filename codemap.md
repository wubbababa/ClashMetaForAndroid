# Hola (Clash Meta for Android) — Code Map

> 本文档为 AI 助手与开发者提供项目架构速查。**修改代码前请先阅读本文档。**

## 项目定位

基于 [CMFA](https://github.com/MetaCubeX/ClashMetaForAndroid) 改造的 Android 应用，核心功能：

1. Clash Meta 代理（原有功能）
2. **白名单防火墙（新增）**：只允许白名单中的应用上网，其余应用全部断网

## 模块结构

| 模块 | 职责 |
|---|---|
| `app` | Activity 层：UI 编排、权限申请、启动服务 |
| `design` | ViewBinding UI 组件 + `preference` DSL + 多语言 strings |
| `service` | 前台服务进程：`TunService`(VpnService)、配置加载、Room 数据库、ServiceStore |
| `core` | JNI 封装：`Clash.kt` ↔ `libbridge.so` ↔ Go 核心 |
| `core/src/main/golang/native/` | Go 侧桥接层（delegate/tun/app 等） |
| `core/src/foss/golang/clash` | **git 子模块**: mihomo Alpha 内核 |
| `common` | 常量(Intents/Components/Permissions)、Store 基础设施 |
| `hideapi` | 隐藏 API 存根 |

## 关键链路

### 启动流程
```
MainActivity.startClash() → startClashService() → TunService(VpnService)
  └─ clashRuntime: TunModule(open TUN) + ConfigurationModule(加载配置) + ...
```

### 配置加载（防火墙注入点）
```
ConfigurationModule.run()
  → FirewallProcessor.process()   ← 防火墙开启时改写 config.yaml
  → Clash.load(dir)               → mihomo 解析并热生效
```

### 防火墙数据流（白名单上网）
```
任意 App 流量 → TUN(捕获全部应用, 含 IPv6)
  → mihomo 规则匹配:
      PROCESS-NAME,<包名>,DIRECT   ← 白名单应用: 放行直连
      MATCH,REJECT                 ← 其余应用: 断网
  进程识别链路:
      process.DefaultPackageNameResolver (delegate/init.go)
        → QuerySocketUid: API≥29 getConnectionOwnerUid / API<29 /proc/net 扫描
        → QueryAppByUid: AppListCacheModule 注入的 uid→包名表
```

### 防火墙安全语义
- TUN 捕获**所有**应用流量（跳过 AccessControl 的 allow/disallow）
- 强制捕获 IPv6（防双栈绕过）、禁用 allowBypass（防 VPN 逃逸）
- 本应用自身始终加入 DIRECT（保证管理界面/订阅更新可用）
- 配置改写失败 → 服务启动失败报错，**绝不静默放行**

## 持久化

- `ServiceStore`(SharedPreferences): `firewall_enabled`, `firewall_whitelist_packages`,
  `access_control_*`, `active_profile` 等
- Profile 数据库(Room): 仅订阅元数据；profile 正文在 `filesDir/imported/<uuid>/config.yaml`
- 白名单变更 → `sendProfileChanged(uuid)` 广播 → ConfigurationModule 热重载（无需重启 VPN）
- 防火墙开关变更 → 需重启服务（TUN 参数变化）

## 主要文件索引

| 文件 | 说明 |
|---|---|
| `service/clash/FirewallProcessor.kt` | ★ snakeyaml 重写 config.yaml 注入白名单规则 |
| `service/clash/module/ConfigurationModule.kt` | 配置加载循环（防火墙接入点） |
| `service/TunService.kt` `open()` | VpnService.Builder 参数（防火墙分支） |
| `service/store/ServiceStore.kt` | 防火墙开关与白名单持久化 |
| `app/FirewallWhitelistActivity.kt` | ★ 白名单应用选择页（复用 AccessControlDesign） |
| `design/NetworkSettingsDesign.kt` | 设置页（防火墙开关入口） |
| `core/src/main/golang/native/delegate/init.go` | uid→包名解析器注入 |

## 构建方法

```bash
git submodule update --init --recursive   # 拉取 mihomo 内核
# 需要: OpenJDK 11+ Android SDK NDK CMake Golang
./gradlew app:assembleAlphaRelease        # 或 assembleMetaRelease
```

包名可用 `local.properties` 的 `custom.application.id` 定制。
