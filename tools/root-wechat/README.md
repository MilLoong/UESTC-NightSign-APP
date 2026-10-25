# Android 本机 Root 微信桥 🔐

## 1. 实现结构 📱

本仓库的 `rootbridge/` 是独立的 LSPosed 模块，主 APP 通过 Root 入口调用它，无需电脑或服务器常驻

- **WechatHook**：只在微信 `appbrand` 子进程加载，捕获目标小程序的登录接口及上下文
- **主动登录**：APP 请求到达后，在原调用线程触发一次真实的 Android 微信登录实现，等待当前请求的回调
- **BridgeCli**：通过 `app_process` 运行，只连接微信的小程序进程，返回本次请求的新 code
- **通信**：仅使用 Android 本地 Unix 套接字，不开放 HTTP、TCP 或广播接口
- **Root 校验**：微信桥只接受 UID 为 `0` 的客户端，客户端同时检查对端 UID 属于微信
- **身份绑定**：仅支持 appId `wxaa78bf2905f01cee`，使用随机 nonce 绑定请求和响应，不读取学校 Cookie、聊天或联系人

当前实现匹配 `JsApiLogin` 的三参数异步接口、组件 `getAppId()` 及 `callback(int, String)`。没有连接设备进行版本适配时，不保证所有 Android 微信版本均符合这些签名，接口形状不同需要针对实际版本调整

## 2. 构建与安装 🛠️

需要已 Root 的主用户 Android 手机，以及支持传统 Xposed API 82 的 LSPosed 等框架。当前入口按主用户的微信安装处理，不支持工作资料或应用分身

在项目根目录构建：

```powershell
.\gradlew.bat :app:assembleDebug :rootbridge:assembleDebug :app:testDebugUnitTest :rootbridge:testDebugUnitTest
```

产物：

- 主 APP：`app/build/outputs/apk/debug/UESTC-NightSign.apk`
- 微信桥：`rootbridge/build/outputs/apk/debug/rootbridge-debug.apk`

通过 USB 连接手机并授权 ADB 后安装桥：

```powershell
.\tools\root-wechat\Install-Bridge.ps1
# 多台设备时指定序列号
.\tools\root-wechat\Install-Bridge.ps1 -Serial '<设备序列号>'
```

脚本只安装模块 APK 和 `/data/adb/uestc-nightsign/bin/wx-login` 入口，不更改微信数据，不自动开启 LSPosed 作用域，不关闭 SELinux，不自动停止微信。已有入口保留为 `wx-login.previous`

安装后由本人完成：

1. 在 LSPosed 中启用 **晚安成电微信桥**，作用域只勾选 **微信**
2. 手动重新启动微信，打开 **成电智慧学工**
3. 进入一次正常的小程序登录链路，让模块捕获目标组件，然后保持小程序可用
4. 安装并打开主 APP，在设置中启用 **本机 Root 取码**
5. 点击 **仅测试授权**，在 Root 管理器中授权主 APP
6. 学校授权成功后再使用手动签到或开启后台签到

如果已有有效学校会话导致小程序不执行新的登录，请通过小程序自身的正常入口重新触发登录，不要清理整个微信数据。微信进程重启后需要重新建立该上下文

ADB 仅用于首次安装和调试，安装完成后的取码使用同一手机的 Root 组件，不需要 USB 连接

## 3. 固定调用协议 🔌

主 APP 使用 `su -c` 校验执行 UID，再运行固定入口：

```sh
/data/adb/uestc-nightsign/bin/wx-login login \
  --app-id wxaa78bf2905f01cee \
  --nonce 0123456789abcdef0123456789abcdef
```

- `nonce` 由 APP 每次随机生成，示例值不能用于正式请求
- 组件和目录由 root 管理，不能放在其他应用可写的位置
- 不接受用户提供的命令、执行路径、脚本或其他 appId
- APP 总超时为 30 秒，桥内登录等待为 15 秒，响应限制为 4096 字节
- CLI 一旦发出登录请求，超时后不会换进程重试，避免产生多个不可关联的登录请求
- code 只通过本次调用的私密管道返回，不写入日志或临时凭证文件

响应包含 `ok`、`appId`、`nonce` 和成功时的 `code`。失败只返回错误码，不返回原始异常、账号或会话数据

## 4. 并发与生命周期 🔄

- 同一微信子进程仅允许一次主动登录，其他请求返回 `BUSY`
- 使用弱引用保存小程序组件，不阻止微信正常回收页面
- 模块仅捕获自然登录时的对象和方法，不窃取自然登录的 code
- 主动请求使用独立回调编号，只截获对应组件和编号的回调
- 主动回调不会发送给原页面，超时后的迟到回调也在保留期内隔离
- 未初始化、微信进程退出或签名不匹配时停止当前请求，不使用缓存 code

## 5. 排错与停用 🔧

- **缺少 Root 入口**：重新运行安装脚本，确认主 APP 与 ADB 分别获得必要的 Root 授权
- **TARGET_NOT_OPEN**：确认 LSPosed 模块作用域、微信进程已重启、目标小程序已触发一次自然登录
- **WX_LOGIN_TIMEOUT**：检查目标小程序是否仍可用，恢复上下文后只做授权检查
- **BRIDGE_UNAVAILABLE**：检查模块安装、主用户微信目录权限及本地通信限制，不要全局关闭 SELinux
- **版本不匹配**：记录微信版本和模块诊断，再针对运行时签名调整，不套用其他平台的原生偏移
- **停用**：在 LSPosed 中关闭模块并手动重启微信，主 APP 可切回服务取码模式
- **更新**：构建后重跑安装脚本，模块升级后手动重启微信，重新建立目标小程序上下文

模拟测试覆盖协议边界、身份绑定、调用形状、Root 失败及异常响应，不向学校提交签到。真实取码仍需在 Root 设备确认

## 6. API 参考 📚

- [XposedBridge 方法 Hook 与原实现调用](https://api.xposed.info/reference/de/robv/android/xposed/XposedBridge.html)
- [Xposed 加载入口](https://api.xposed.info/reference/de/robv/android/xposed/IXposedHookLoadPackage.html)
