# Kotlin 原生协议移植 🛠️

APP 实际签到取码已接入 Kotlin 协议实现，Python 源码仅用于离线对照，不参与构建和运行，不修改服务器配置

## 已实现 ⚙️

- Android／JVM 标准加密 API：P-256 ECDH、SHA-256、HMAC、HKDF、AES-GCM、MMTLS 方向密钥及票据密钥派生
- 协议编码：有界 Protobuf、LZ4、WPKG 头、MMTLS Record、ClientHello、PSK ClientHello、短连接包及目标小程序 js-login 明文
- 测试向量：由固定随机种子的 Python 参考实现生成，仅包含合成数据，没有个人凭证或线上会话
- 实现目录：`app/src/main/java/edu/uestc/nightsign/data/wechat/nativeprotocol/`
- 完整链路：扫码授权、凭证刷新、HTTPDNS 节点发现、MMTLS 握手、ManualAuth／Hybrid ECDH、0-RTT 小程序取码、现有 CAS 学校签票入口

## 测试 🧪

在仓库根目录运行，不请求学校或微信接口：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*NativeProtocolTest'
```

需要更新参考向量时，用已安装 `cryptography` 的 Python 3.12 运行 `tools/native-protocol/generate_vectors.py`，审查输出后更新测试资源 `app/src/test/resources/native-protocol-vectors.json`

## 真机测试 🔧

- 在 APP 中完成学校 CAS 登录，进入设置点击本地授权，确认生成二维码、扫码及学校换票结果
- 既有本地 Python 授权凭证格式保持兼容，首次测试可以先验证本地授权，失效或不兼容时再清除授权重新扫码
- 全程不自动回退 Python 或外部服务，失败时通过本机记录中的固定阶段码定位问题
- 原生组件自检只执行离线算法，不能替代在线取码和学校换票测试

原生代码依据项目内 Python 协议实现移植，沿用 `app/src/main/assets/licenses/wechat-protocol-sources.txt` 中的来源及再分发说明
