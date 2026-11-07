# 群消息网关

v1 的 APK 配对、多设备隔离、Web 管理和第三方 API 见 [三端接口规范](../API_SPEC.md)。

## Linux x86_64 二进制部署

部署文件为 `dist/wecom-gateway-linux-x86_64`。Web 页面、SQLite 和 TLS 实现都编入可执行文件。部署目录需要可写，供 SQLite 保存数据库和 WAL。

```sh
chmod 700 wecom-gateway-linux-x86_64
./wecom-gateway-linux-x86_64 --config ./gateway.json --host 0.0.0.0 \
  --port 8765 --device-port 8766
```

安卓 APK 的「服务器端口」填写 `--device-port` 的值，默认是 `8766`。Web 管理页和第三方 API 共用 `--port`，默认是 `8765`。两个端口必须不同，接口发到另一端口会返回 `404`。从单端口版本切换时，在 APK 把已保存的端口改成设备端口并重新连接。

`gateway.json` 包含 `admin_token`。默认数据库是配置旁的 `group-gateway-v1.sqlite3`，也可通过 `--database` 指定现有 v1 数据库。切换已有 v1 实例时，先停旧服务，再由二进制打开同一份数据库。默认从服务器本机的 `127.0.0.1` 和 Web 端口访问管理页，也可以通过 SSH 隧道访问。HTTPS 使用 `--cert` 和 `--key`，同时应用于两个端口。

Rust 网关使用 SQLite 旁的 `group-gateway-v1.api-key-seal` 加密保存可查看的接口 Key。文件权限为 `0600`，备份和迁移时与数据库一起保留。旧版仅存哈希的 Key 可用已保存的原值校验补录，或轮换后查看。

Rust 网关将管理会话的哈希保存在 SQLite。同一浏览器在有效期内可直接打开管理页，服务重启也会沿用会话。默认有效期为 12 小时，可在 `gateway.json` 通过 `web_session_days` 配置为 1 至 30 天。更换后台管理 Key 会使原会话失效。

在构建机运行 `bash gateway/build-linux.sh` 生成 Linux x86_64 文件。Mac 交叉构建时设置 `ZIG_BIN` 为 Zig 可执行文件，并安装 Rust 的 `x86_64-unknown-linux-musl` 目标。编译工具只在构建机使用。

用 `GATEWAY_TEST_BINARY=/path/to/wecom-gateway-linux-x86_64 PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s gateway -p test_v1.py -v` 在隔离数据库中测试二进制。测试套件使用 Python 驱动两个模拟客户端，正常部署只启动二进制。

### Web 和第三方 API 的路径前缀

在现有 `gateway.json` 中加上对外地址。路径可以有多级，示例中的后台 Key 是占位值。

```json
{
  "admin_token": "<现有后台管理 Key>",
  "public_web_url": "https://ops.example.com/infra/prod/wx_msg_gateway/"
}
```

Web 管理页从上述地址打开。第三方把同一个地址作为基础地址，例如请求 `GET /infra/prod/wx_msg_gateway/v1/clients/test-phone/groups`，并携带自己的 Bearer Key。安卓仍使用设备端口直接访问 `/v1/devices/...`。

反向代理将带末尾斜杠的入口转给 Web 端口，去掉完整路径前缀，并保留浏览器原始的 `Host` 和 `Origin`。将不带末尾斜杠的页面入口重定向到带斜杠的地址。管理页要求代理从网关的回环接口连入，并校验配置中的对外主机和写请求来源。对外地址使用 HTTPS 时，管理会话 Cookie 带 `Secure`，即使代理到网关这一段使用 HTTP。

下面的 Nginx 片段假定网关的 Web 端口为本机 `18765`。安卓的 `18766` 使用原有接入路径。

```nginx
location = /infra/prod/wx_msg_gateway {
    return 301 /infra/prod/wx_msg_gateway/;
}

location ^~ /infra/prod/wx_msg_gateway/ {
    proxy_pass http://127.0.0.1:18765/;
    proxy_set_header Host $http_host;
    proxy_set_header Origin $http_origin;
}
```

## Python 本地参考实现

1. 在单独目录创建 `gateway.json`，内容为 `{"admin_token":"<自行生成的后台管理 Key>"}`。限制配置文件的读取权限。
2. 运行 `python3 gateway/server.py --config /path/to/gateway.json --port 8765`。在服务器本机访问 `http://127.0.0.1:8765/`，用后台管理 Key 登录。
3. 在「设备管理」登记 `client_id` 并生成注册 Key。构建 `sh build.sh wecom-web`，安装后在手机填写服务器 IP、端口、注册 Key 和 `client_id`，启用无障碍服务并发起接入。使用私有测试网络的 HTTP 网关时取消勾选 HTTPS。
4. 回到 Web 的「设备管理」，点击待审批设备的「通过接入」。APK 接到审批后进入执行会话。服务器按设备展示群消息和任务回执。
5. 在「接口密钥」创建第三方 Key，选择客户端、群和权限。第三方用该 Key 访问 `/v1/clients/{client_id}/groups` 等接口。

生产 HTTPS 用 `--cert` 和 `--key` 指定证书和私钥，证书须覆盖 APK 配置的服务器 IP。管理页由服务器本机打开。v1 数据写入配置旁的 `group-gateway-v1.sqlite3`。同一服务器配置里还可以保留试验版 `token`，从 `/legacy` 查看原有试验库。已有试验群尚未绑定到 v1 的 `client_id`，保留在原库。

双客户端测试使用隔离的 SQLite 和 HTTP 模拟手机。运行 `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s gateway -v`。

## 本地试验版

两个历史版本的 APK 共用 `/legacy` 页面和一份 SQLite 数据库。

| 平台 | APK | 手机入口 |
| --- | --- | --- |
| 企业微信 5.0.13（84802） | 旧版 `wecom-web-probe.apk`（0.11） | 启用「企微网页收发」无障碍服务，点「连接网页并打开企业微信」，从「消息 → 全部」页开始 |
| 微信 8.0.80（3220） | `dist/weixin-test-probe.apk` | 启用「微信收发测试」无障碍服务，点「连接网页并打开微信」，从「微信」会话列表开始 |

保持手机解锁、目标应用亮屏前台。APK 会遍历当前账号会话列表中可定位的群，无需录入群名。企微 APK 会在本机记住已核对的非群会话，后续扫描跳过；页面未识别或打开超时的会话继续留待核对。企微每次启动采集会先打开群建立消息预览基线。之后仅在会话列表的预览变化或识别到未读提示时自动进群，网页提交的发送任务仍会主动定位并核对目标群。每个群同步可见的普通文字及界面显示的发送人。每台手机同一时刻操作一个前台应用，两台分别运行时可同时接入。

网页左栏选择群聊，右侧显示可取得的发送人、正文和群编号。发送按钮提交当前选中群的一条任务，结果区区分排队、执行、聊天区回读和结果待核对。手机界面按顺序执行采集和发送。

网页底部的「运行日志」显示最近 50 条连接、界面识别、采集入库及任务事件。手机 App 的「最近诊断」也可离线查看。页面未识别时，日志会列出会话列表、会话入口、聊天区和输入框的控件数量。消息正文在对应群的消息区查看。

## 本机启动

从仓库根目录运行以下命令。配对配置保存在当前 Mac 的用户缓存目录。

```sh
python3 gateway/server.py \
  --config "$HOME/Library/Caches/wecom-android-probe/weixin-gateway.json"
```

旧版微信 APK 使用打包时写入的局域网地址。在私有配对配置中更新 `url` 后重新构建。

```sh
WEIXIN_GATEWAY_CONFIG="$HOME/Library/Caches/wecom-android-probe/weixin-gateway.json" \
  sh build.sh weixin

```

## 业务接口

[安卓、Web 和第三方 @ 成员操作示例](../API_SPEC.md#原生提及成员)覆盖成员同步、发送、任务回执以及读取提及标注。

| 方法和路径 | 用法 |
| --- | --- |
| `GET /api/state` | 列出群编号、群名、平台和各平台手机状态 |
| `GET /api/messages?group_id=…&after=0` | 按 `seq` 递增返回已同步消息。把最后处理的 `seq` 保存为下次的 `after` |
| `POST /api/send` | 传入 `group_id`、`text`、`request_id`。相同编号和负载返回原任务 |
| `GET /api/state?group_id=…` | 查询该群近期发送任务及结果 |

`group_id` 是保存在本地 SQLite 的系统编号，企微和微信的同名群分别编号。旧微信数据及群编号在数据库迁移时保留。APK 使用会话列表中的名称及聊天页标题核对去向。重名或标题变化时，当前版本会限制路由。两个客户端的原生群标识目前无法从已观察到的界面节点取得，同名同人数的群被替换时，当前匹配方式也可能无法区分。`sender` 为界面显示名，未显示时为空。`observed` 是服务端接收时间。`quality` 为 `baseline`、`ui_overlap` 或 `unlinked`，表示首次采集、窗口已对齐或未对齐。

对接模型时按 `group_id` 分隔上下文，使用 `seq` 管理消费进度，回复沿用输入记录的 `group_id` 并生成稳定的 `request_id`。发送任务的 `visible` 表示手机发送流程在聊天区读回正文，`observed` 表示回执超时后扫描到唯一匹配的本机出站消息，`observed_seq` 指向对应的消息序号。仍为 `uncertain` 的任务需要查看群页面核对。

## 重复消息

- 同一个平台的相同采集批次 ID 只写入一次。手机重试时沿用原 ID。
- 同一群内按发送人、正文和消息顺序对齐相邻窗口，只追加重合部分之后的消息。上下文可区分时保留连续发送的相同正文。
- 找不到重合时仍保留这次观测，标为 `unlinked`，网页显示「与上次窗口未对齐」。界面没有原生消息 ID，窗口完全相同或断开时仍可能漏判或重复，业务接口会保留质量字段供下游使用。

手机 APK 使用打包时配对的局域网 HTTP 地址。网页与业务接口限 Mac 本机访问。每个平台使用一个活动采集账号。
