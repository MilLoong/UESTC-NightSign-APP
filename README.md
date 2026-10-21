# 晚安成电 🌙

晚安成电签到 APP，支持 **自动监听 是否有 签到任务**
此处为 **客户端** 逻辑，后续看看有没有时间做一下 **服务端** 来监听
最低系统 Android 8.0

## 功能 📱

- **我的**
  - **账号登录**、**网页登录**、**退出登录**
  - 登录并刷新后显示 `学号：`、`姓名：`
  - **关于** 里查看当前版本，并 **检查更新**
  - **开源** 打开 [UESTC-NightSign-APP](https://github.com/MilLoong/UESTC-NightSign-APP)
- **签到**：读取当天状态、平台时段、允许地点及半径，确认后提交，再复查结果
- **设置**
  - **打开地图** 或 **当前位置**，同时填入 GCJ-02 坐标和地址
  - 保存后才会用于签到
  - 本机后台签到默认关闭
  - 主题可选天空蓝、清新绿、樱花粉
- **记录**：本机最近 40 条运行记录

## 使用步骤 ⚙️

1. 打开 **我的**，点 **账号登录**，按提示完成短信验证
2. 需要图形验证时，点 **网页登录**，在学校页面完成后点 **导入会话**
3. 打开 **设置**，用 **打开地图** 或 **当前位置** 填入坐标和地址，再点 **保存设置**
4. 在 **签到** 查看平台状态和允许地点
5. 在允许时段内点 **立即签到**，或在设置里开启后台签到

**自动续登**：只有登录时勾选 **加密保存账号，用于后台续登** 才保存密码。后台遇到二次认证会暂停并通知，不会自动发送短信

**后台限制**：WorkManager 约每 15 分钟检查一次，且需要网络。省电策略、断网和强行停止应用都可能导致延迟或漏签。目标时间必须落在平台签到时段内。请留意通知，并在截止前确认结果

**检查更新**：启动后约每 12 小时自动检查一次 GitHub Releases。**关于** 里可以手动检查。有新版本时可选 **前往下载** 或 **稍后**，稍后不再提示同一个版本

## 构建与测试 🛠️

- **环境**：JDK 17、Android SDK 36、Gradle Wrapper
- **可选配置**：在 `local.properties` 增加 `tencent.map.key=腾讯 WebService Key`
- **构建和测试**：
  ```powershell
  .\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
  ```
- **安装包**：`app/build/outputs/apk/debug/app-debug.apk`
- **测试范围**：本地 MockWebServer 接口测试、签到流程、时段规则、GCJ-02 转换、腾讯地理编码解析、版本号比较。测试不向学校发送签到请求
- **需要在设备上确认**：实际登录、学校风控变化、真机后台调度、真实签到结果

## 发布

推送 `v*` 标签后，GitHub Action 会打包 `UESTC-NightSign.apk` 并创建 Release。说明写在标签上，会成为 Release 正文

先按 `keystore.properties.example` 配好签名，并在仓库 Secrets 里放上 `UPLOAD_KEYSTORE_BASE64`、`UPLOAD_STORE_PASSWORD`、`UPLOAD_KEY_ALIAS`

```powershell
.\scripts\release.ps1 -Notes "这次发布的说明"
.\scripts\release.ps1 -Version 0.1.1 -Notes "这次发布的说明"
```

`-Version` 会把 `versionName` 改成该版本，`versionCode` 加 1，提交后再推送标签

## 许可

本项目采用 [MIT License](LICENSE) 开源。


