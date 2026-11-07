# Rust 网关部署与接入

Rust 网关把 Web 管理页、SQLite 和 HTTPS 支持打包在一个二进制文件中。部署后，先通过 Web 配对安卓设备，再为业务系统创建有指定群权限的接口 Key。完整的请求与响应见 [v1 API 规范](../API_SPEC.md)。

## Linux x86_64 二进制部署

部署文件为 `dist/wecom-gateway-linux-x86_64`。部署目录需要可写，供 SQLite 保存数据库和 WAL。首次部署先按[构建网关](../README.md#构建网关)中的方式生成管理 Key，并在部署目录准备 `gateway.json`。

```sh
chmod 700 wecom-gateway-linux-x86_64
./wecom-gateway-linux-x86_64 --config ./gateway.json --host 0.0.0.0 \
  --port 8765 --device-port 8766
```

安卓 APK 的「服务器端口」填写 `--device-port` 的值，默认是 `8766`。Web 管理页和第三方 API 共用 `--port`，默认是 `8765`。两个端口必须不同，接口发到另一端口会返回 `404`。从单端口版本切换时，在 APK 把已保存的端口改成设备端口并重新连接。

`gateway.json` 包含 `admin_token`。默认数据库是配置旁的 `group-gateway-v1.sqlite3`，也可通过 `--database` 指定现有 v1 数据库。升级已有实例时，先停止当前实例，再由新版二进制打开同一份数据库。默认从服务器本机的 `127.0.0.1` 和 Web 端口访问管理页，也可以通过 SSH 隧道访问。HTTPS 使用 `--cert` 和 `--key`，同时应用于两个端口。

Rust 网关使用 SQLite 旁的 `group-gateway-v1.api-key-seal` 加密保存可查看的接口 Key。文件权限为 `0600`，备份和迁移时与数据库一起保留。旧版仅存哈希的 Key 可用已保存的原值校验补录，或轮换后查看。

Rust 网关将管理会话的哈希保存在 SQLite。同一浏览器在有效期内可直接打开管理页，服务重启也会沿用会话。默认有效期为 12 小时，可在 `gateway.json` 通过 `web_session_days` 配置为 1 至 30 天。更换后台管理 Key 会使原会话失效。

在构建机运行 `bash gateway/build-linux.sh` 生成 Linux x86_64 文件。Mac 交叉构建时设置 `ZIG_BIN` 为 Zig 可执行文件，并安装 Rust 的 `x86_64-unknown-linux-musl` 目标。

## Web 和第三方 API 的路径前缀

在现有 `gateway.json` 中加上对外地址。路径可以有多级，示例中的后台 Key 是占位值。

```json
{
  "admin_token": "<现有后台管理 Key>",
  "public_web_url": "https://ops.example.com/infra/prod/wx_msg_gateway/"
}
```

Web 管理页从上述地址打开。第三方把同一个地址作为基础地址，例如请求 `GET /infra/prod/wx_msg_gateway/v1/clients/test-phone/groups`，并携带自己的 Bearer Key。安卓直接连接设备端口。设备接口的 `/v1/devices/...`、`/v1/device-commands/...` 和 `/v1/device-results` 都从根路径访问。

反向代理将带末尾斜杠的入口转给 Web 端口，去掉完整路径前缀，并保留浏览器原始的 `Host` 和 `Origin`。将不带末尾斜杠的页面入口重定向到带斜杠的地址。管理页要求代理从网关的回环接口连入，并校验配置中的对外主机和写请求来源。对外地址使用 HTTPS 时，管理会话 Cookie 带 `Secure`，即使代理到网关这一段使用 HTTP。

下面的 Nginx 示例使用默认 Web 端口 `8765`。如果部署时修改了 `--port`，将 `proxy_pass` 改为实际 Web 端口。

```nginx
location = /infra/prod/wx_msg_gateway {
    return 301 /infra/prod/wx_msg_gateway/;
}

location ^~ /infra/prod/wx_msg_gateway/ {
    proxy_pass http://127.0.0.1:8765/;
    proxy_set_header Host $http_host;
    proxy_set_header Origin $http_origin;
}
```

## 接入安卓设备

1. 打开 Web 管理页，用配置中的 `admin_token` 登录。
2. 在「设备管理」登记唯一的 `client_id` 和设备名称，保存生成的注册 Key。
3. 在「企微网页收发」填写服务器 IP、设备端口、`client_id` 和注册 Key。按部署方式选择 HTTP 或 HTTPS，启用无障碍服务并发起连接。
4. 回到 Web 点击「通过接入」。待手机重试连接并同步后，进入「群消息」，选择设备和群查看消息、提交回复或查看发送状态。

## 对接第三方业务

在 Web 的「接口密钥」创建业务 Key，选择客户端、群和所需权限。以 Web 地址为 API 基础地址，并保留配置的路径前缀。每次调用携带 `Authorization: Bearer <接口 Key>`。

1. 用 `GET /v1/clients/{client_id}/groups` 获取网关分配的 `group_id`。
2. 用 `GET /v1/clients/{client_id}/groups/{group_id}/messages/recent` 查看最近 30 条消息。持续消费用 `GET /v1/clients/{client_id}/groups/{group_id}/messages?after_offset=0&limit=100`，按群保存处理进度。
3. 用 `POST /v1/clients/{client_id}/groups/{group_id}/send-requests` 发普通回复，或用同一群路径下的 `POST .../alert-send-requests` 发结构化告警。两种请求都要带 `Idempotency-Key`。
4. 取提交结果里的 `task_id`，用 `GET /v1/clients/{client_id}/groups/{group_id}/send-requests/{task_id}` 查询发送状态和关联的群消息编号。

参数范围、权限、响应示例和原生 @ 用法见 [第三方业务接口](../API_SPEC.md#第三方业务接口)。
