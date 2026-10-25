# 晚安成电 🌙

晚安成电签到 APP，支持 **自动监听 是否有 签到任务**<br>
此处为 **客户端** 逻辑<br>
最低系统 Android 8.0

## 功能 📱

- **我的**
  - **账号登录**、**网页登录**、**退出登录**
  - 登录 并 刷新 后显示 `学号：`、`姓名：`
  - **关于** 里查看 当前版本，并 **检查更新**
  - **开源** 打开 [UESTC-NightSign-APP](https://github.com/MilLoong/UESTC-NightSign-APP)
- **签到**：读取 当天状态、平台时段、允许地点 及 半径，确认后 提交，再 复查结果
- **设置**
  - **打开地图** 或 **当前位置**，同时填入 GCJ-02 坐标 和 地址
  - 保存后才会用于 签到
  - 本机后台签到 默认关闭
  - 主题可选 天空蓝、清新绿、樱花粉
- **记录**：本机最近 40 条 运行记录

## 微信授权 🔐

签到需要 **微信小程序** 授权，支持 两条路径：

- **Root 手机**：安装 [微信桥模块与 Root 入口](tools/root-wechat/README.md)，在 LSPosed 中 启用模块、作用域 勾选 微信，重启微信 并 打开 **成电智慧学工**。在 APP **设置 → 微信授权** 选择 **本机 Root 取码**，保存后点 **仅测试授权**，首次需授予 APP Root 权限
  - 取码在 手机本机 完成，不需要 电脑常驻，仍需确认 当前微信版本兼容
- **非 Root 手机**：关闭 **本机 Root 取码**，填写 取码服务地址 和 配对密钥，保存后点 **仅测试授权**
  - 可使用 [Windows 本机取码工具](tools/wechat-code/README.md)，通过 `adb reverse` 连接手机，使用时需保持 电脑微信、取码工具 和  USB 转发可用
  - 也可接入已有 HTTPS 取码服务，学校 Cookie 不会发送给 取码服务

**仅测试授权** 只验证 取码 和 学校换票，不提交 签到

## 使用步骤 ⚙️

1. 打开 **我的**，点 **账号登录**，按提示完成 短信验证
2. 需要图形验证时，点 **网页登录**，在 学校页面 完成后点 **导入会话**
3. 打开 **设置**，用 **打开地图** 或 **当前位置** 填入坐标和地址，再点 **保存设置**
4. 按 **Root** 或 **非 Root** 路径配置 微信授权，点 **仅测试授权** 确认 链路可用
5. 在 **签到** 查看 平台状态 和 允许地点
6. 在允许时段内点 **立即签到**，或在设置里开启 后台签到

**自动续登**：只有登录时勾选 **加密保存账号，用于后台续登** 才 保存密码。后台遇到 二次认证 会 暂停并通知，不会 自动发送短信

**后台限制**：WorkManager 约每 15 分钟检查一次，且需要 网络。省电策略、断网 和 强行停止应用 都可能导致 延迟 或 漏签。目标时间 必须落在 平台签到时段 内。请留意 通知，并在截止前 确认结果

**检查更新**：启动后约每 12 小时自动检查一次 GitHub Releases。**关于** 里可以手动检查。有新版本时可选 **前往下载** 或 **稍后**，稍后不再提示 同一个版本

## 构建与测试 🛠️

- **环境**：JDK 17、Android SDK 36、Gradle Wrapper
- **可选配置**：在 `local.properties` 增加 `tencent.map.key=腾讯 WebService Key`
- **构建和测试**：
  
  ```powershell
  .\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
  ```
- **安装包**：`app/build/outputs/apk/debug/app-debug.apk`
- **测试范围**：本地 MockWebServer 接口测试、签到流程、时段规则、GCJ-02 转换、腾讯地理编码解析、版本号比较。测试不向学校发送 签到请求
- **需要在设备上确认**：实际登录、学校风控变化、真机后台调度、真实签到结果

## 发布

推送 `v*` 标签后，GitHub Action 会打包 `UESTC-NightSign.apk` 并创建 Release。说明写在标签上，会成为 Release 正文

先按 `keystore.properties.example` 配好签名，并在仓库 Secrets 里放上 `UPLOAD_KEYSTORE_BASE64`、`UPLOAD_STORE_PASSWORD`、`UPLOAD_KEY_ALIAS`

```powershell
.\scripts\release.ps1 -Notes "这次发布的说明"
.\scripts\release.ps1 -Version 0.1.1 -Notes "这次发布的说明"
```

`-Version` 会把 `versionName` 改成该版本，`versionCode` 加 1，提交后再 推送标签

## 许可

本项目采用 [MIT License](LICENSE) 开源。

