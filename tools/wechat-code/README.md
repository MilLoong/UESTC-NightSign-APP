# Windows 微信取码联调 🔐

本机运行已登录的微信与 `WMPFDebugger`，取码服务仅返回目标小程序的一次性 `code`

- **固定小程序**：`wxaa78bf2905f01cee`
- **固定监听**：`127.0.0.1:18765`，不绑定局域网或公网地址
- **学校会话**：由 Android APP 持有，只发送到学校域名
- **配对密钥**：电脑端保存在忽略提交的 `.local/pairing-token`，APP 端使用 Android Keystore 加密保存
- **凭据处理**：不缓存、不打印 `code` 或 `ticket`，每次取码均调用 `wx.login`
- **运行条件**：Node.js 22 或更新版本，无额外 npm 运行时依赖

## 1. 启动电脑服务 🖥️

- **准备调试器**
  - 单独安装上游 `WMPFDebugger`，使用匹配当前 WMPF 的偏移配置
  - 将调试器的两个 WebSocket 服务都绑定到 `127.0.0.1`
  - 已验证的本机配置为 WMPF `25799`，配置在本次研究目录的 `frida/config/win32/addresses.25799.json`
  - 上游调试器单独使用，未将其 GPL 源码或二进制打包到本工具
- **启动取码服务**
  - 打开电脑微信并登录
  - 在 PowerShell 执行，替换调试器目录

    ```powershell
    .\tools\wechat-code\Start-Local.ps1 -DebuggerPath '<WMPFDebugger 绝对目录>'
    ```

  - 出现 `script loaded` 后重新进入“成电智慧学工”小程序
  - 保持服务窗口和电脑微信运行，关闭服务窗口会停止本次启动的调试器
  - 若提示端口已占用，先检查已有取码服务，不要重复启动或终止无关进程

## 2. 连接手机 📱

- **USB 转发**
  - 用 USB 连接手机，手动开启 USB 调试并确认设备授权
  - 在 Android SDK 的 `platform-tools` 目录执行

    ```powershell
    .\adb.exe devices
    .\adb.exe reverse tcp:18765 tcp:18765
    ```

  - 多台设备时必须使用 `-s <设备序列号>` 指定手机
  - 不需要开放 Windows 防火墙端口或腾讯云安全组
- **APP 配置**
  - 安装新版 APP，保留原有学校登录会话
  - 在“设置 → 微信取码授权”填写 `http://127.0.0.1:18765`
  - 从电脑 `.local/pairing-token` 复制配对密钥，保存配置
  - 点击“仅测试授权”，此操作只获取 `code` 并向学校换取 `ticket`，不提交签到
  - 需要学校账号与电脑微信身份匹配，最终以学校响应为准

## 3. 请求边界 🧩

- **取码接口**：`POST /v1/code`
  - 使用请求头 `Authorization: Bearer <配对密钥>`
  - 请求体仅允许 `{"appId":"wxaa78bf2905f01cee"}`
  - 串行取码，成功后冷却 3 秒
  - 拒绝浏览器 `Origin`、额外请求字段、其他小程序和查询参数
- **学校授权**
  - APP 使用自身会话调用 `/platform-app/api/auth/checkin-ticket`
  - `ticket` 仅在本次提交内存中使用，发送到学校的 `X-Checkin-Ticket` 请求头
  - 取码或取票失败时停止提交，不退回无票请求，不自动重试真实提交
- **远程连接**
  - 远程取码地址必须使用有效证书的 HTTPS
  - 不允许明文公网 HTTP、URL 内嵌密钥或关闭证书校验
  - 本轮只实现并验证本机链路，尚未配置服务器中转

## 4. 测试与限制 🧪

- **本地测试命令**

  ```powershell
  cd tools/wechat-code
  npm test
  ```

- **验证范围**：鉴权、输入限制、错误脱敏、并发限制、目标上下文选择、真实本机取码
- **待真机验证**：APP 自身 CAS 会话能否换票、`ticket` 跨会话规则及真实提交结果
- **后台依赖**：USB 转发、本机服务、调试器与微信任一中断，APP 取码都会失败
- **升级维护**：微信更新后须重新确认 WMPF 偏移，不能假定旧配置继续有效
