# wx_msg_gateway

企业微信群消息网关。安卓客户端通过无障碍服务读取企微界面中的文字，Rust 网关负责存储、审批和发送队列，Web 页面用于查看消息、提交回复和管理设备。业务系统可用独立的接口 Key 读取消息和提交发送任务。

## 支持范围

- 主要客户端为「企微网页收发」。已验证的设备组合为荣耀 10、Android 9、企业微信 5.0.13（versionCode 84802）。APK 最低安装版本为 Android 7.1（API 25）。原生 @ 目前只在 Android 9 启用。
- 读取依赖当前可见的无障碍节点。设备需要亮屏、解锁并保持企微在前台。历史回补可能遇到缺口。群编号和消息编号由网关分配。
- 只在已授权的设备、账号和群内使用。安卓无障碍权限由设备用户开启，设备还需通过网关管理端审批。

## 构建网关

需要支持 Rust 2024 edition 及锁定依赖的 Rust 工具链，以及 C 编译工具。以下命令从项目根目录运行。

```sh
cargo build --release --locked --manifest-path gateway/rust/Cargo.toml
umask 077
mkdir -p state
python3 -c 'import json,secrets; print(json.dumps({"admin_token":secrets.token_urlsafe(32)}))' > state/gateway.json
./gateway/rust/target/release/wecom-gateway --config state/gateway.json --host 127.0.0.1
```

打开 `http://127.0.0.1:8765/`，使用本机配置里的 `admin_token` 登录。初次配对时，在「设备管理」登记客户端并生成注册 Key。手机接入端口默认是 `8766`。接入真机时，请参照部署说明配置可从手机访问的地址和 TLS。

Linux x86_64 静态二进制、反向代理、Web 会话和设备接入配置见 [网关部署说明](gateway/README.md)。

## 构建安卓客户端

需要 JDK、Android SDK Platform 35、Build Tools 35.0.0 和 Platform Tools。将 `ANDROID_HOME` 指向自己的 SDK，或使用脚本默认的本机 SDK 路径。

```sh
sh build.sh wecom-web
adb install -r dist/wecom-web-probe.apk
```

打开「企微网页收发」，开启该 App 的无障碍服务并填写网关地址、设备端口、客户端编号和注册 Key。提交接入申请后，在 Web 的「设备管理」通过审批。构建脚本生成本地调试签名，项目不分发签名密钥。

`sh build.sh` 可构建单独的只读探针。普通微信的试验版及 Python 参考实现见部署说明。

## 接口与测试

- [API 规范](API_SPEC.md)
- [设备执行协议](PROTOCOL.md)
- `sh build.sh wecom-web` 同时运行 Android 界面提取、原生 @ 和恢复策略的回归测试。
- Rust 网关集成测试使用临时 SQLite 数据库和模拟手机。

```sh
GATEWAY_TEST_BINARY="$PWD/gateway/rust/target/release/wecom-gateway" \
  PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s gateway -v
```

## 许可证

项目源码采用 [MIT 许可证](LICENSE)。第三方依赖沿用各自的许可证。
