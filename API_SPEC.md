# 群消息网关 v1 需求与三端接口

本文定义安卓 APK、Web 管理端和第三方调用端的 v1 接口。启动与测试方式见 [gateway/README.md](gateway/README.md)，手机命令的执行约束见 [PROTOCOL.md](PROTOCOL.md)。

## 共用规则

- 一台网关支持多个安卓客户端同时在线。每台设备登记唯一的 `client_id`，使用各自的注册 Key。网关按 `client_id` 独立管理在线状态、执行会话、群绑定和任务队列。一个 `client_id` 同一时刻只有一个会话可以执行命令，重连时先核对未完成任务再恢复派发。
- 网关为每个确认过的群分配独立的 `group_id`。查某个群、读消息、发送和查回执都明确指定 `client_id + group_id`。服务端验证绑定，同名群分别识别。
- 服务器保留每个群全部已采集的文字历史。消息归并后在事务中分配该群的 `offset`，同一 `client_id + group_id` 下按入库顺序递增。重传同一批次或同一条已归并的消息沿用原记录。补采的历史消息获得新的 `offset`。
- 手机首次接入后回补手机上仍可访问的历史。后续扫描根据未读提示和预览变化进入已知群，并向历史方向寻找上一次采集的重合序列。无法确认重合或历史不可访问时，标记采集缺口。业务读取进度由每个调用方按群独立保存。
- 同一台手机逐条执行发送任务，不同手机可以并行。Web 和第三方使用同一套队列、幂等规则和回执。
- 安卓使用注册 Key（设备 Key），Web 使用后台登录会话，第三方使用独立接口 Key。注册 Key 绑定 `client_id`。接口 Key 绑定调用应用和授权范围，分别授予查群、查群成员、读正文和发消息权限。生产接口使用 HTTPS。

## 安卓 APK 接口

APK 启动时使用四项必填配置。首次启动填写，之后可使用已保存的配置重新连接。

| 启动配置 | 用途 | 示例 |
| --- | --- | --- |
| 服务器 IP `server_ip` | 网关所在机器的 IP | `192.0.2.10` |
| 服务器端口 `server_port` | Rust 网关的设备接入端口，由 `--device-port` 设置 | `8766` |
| 注册 Key `registration_key` | Web 登记设备后生成，用于校验该设备身份 | `<注册 Key>` |
| 客户端编号 `client_id` | 该 APK 所属设备在网关里的唯一编号 | `phone-a` |

APK 用 IP 和设备接入端口连接网关，再调用 `POST /v1/devices/session`。注册 Key 放进 `Authorization: Bearer <注册 Key>`，`client_id` 和设备启动实例编号 `instance_id` 放进请求体。网关确认 Key 与客户端编号绑定后，将申请置为 `pending`，返回 `202` 和 `pending_approval`。管理员在 Web 通过后，APK 再次注册获得 `200` 和执行会话，随后才开始同步和领任务。轮换注册 Key 后，设备重新申请并审批。

下面的接口均使用注册 Key 鉴权。建立会话后，请求带 `X-Client-Id`、`X-Device-Session` 和 `X-Lease-Epoch`。待审批设备访问执行接口时返回 `403`。涉及特定群的命令和回传携带 `group_id`，首次发现群时由服务器分配。手机获得服务器持久化确认后才能清除本地待回传数据。

| 方法和路径 | 请求和返回 |
| --- | --- |
| `POST /v1/devices/session` | 用注册 Key 验证 `client_id`，同时上报 APK 版本和能力。首次接入返回待审批，审批通过后返回 `device_session_id`、`lease_epoch`。 |
| `POST /v1/devices/heartbeat` | 刷新在线状态。支持远程恢复的 APK 上报 `scan_paused`，领取 `resume_scan_id` 后核对原群草稿，并用 `resume_ack` 回执。 |
| `GET /v1/devices/config` | 读取设备的配置版本、采集策略和已有群绑定。 |
| `POST /v1/device-commands/claim` | 领取一条发送命令。返回 `command_id`、目标群和执行租约。无命令返回 `204`。读窗口由 APK 按配置和群未读状态主动采集。 |
| `POST /v1/device-commands/{command_id}/renew` | 续租当前执行命令，维持原命令编号。 |
| `POST /v1/device-commands/{command_id}/click-reservation` | 发送前核对目标群和正文摘要，为该命令预留一次点击。 |
| `POST /v1/device-results` | 上报群发现、采集窗口、缺口和发送阶段或结果。采集带稳定的 `capture_id`，阶段事件带 `event_id`。分页回补用 `sweep_id` 和 `older_than` 串联窗口，最后一页上报 `complete`、`anchored` 或 `gap`。返回已持久化的编号，供手机确认和重传。 |

手机在发送前重新核对群名和绑定。点击意图先记录到本地，崩溃、断线或点击结果不明时进入待核对流程。同一命令重复投递时复用本地执行记录。

## Web 管理端接口

Rust 网关的 Web 管理端和第三方业务接口共用 `--port`，默认 `8765`。设备接入接口由单独的 `--device-port` 提供。请求发到另一端口时返回 `404`。

Web 页面包括群消息、设备管理和接口密钥管理。设备管理页同时展示多个客户端和各自的在线状态。群消息页先选 `client_id` 再选群，展示群 ID、历史文字、采集状态和任务回执。设备注册 Key 在创建或轮换时展示。接口密钥页显示应用名称、授权设备、群和权限，有效接口 Key 可通过「显示 Key」查看。

| 方法和路径 | 页面用途 |
| --- | --- |
| `POST /v1/admin/session` | 用后台管理 Key 换取 Web 登录会话。 |
| `GET /v1/admin/clients` | 列出设备、`client_id`、在线状态、最后心跳和扫描暂停状态。 |
| `POST /v1/admin/clients` | 登记设备和 `client_id`，生成安卓启动时使用的注册 Key。 |
| `POST /v1/admin/clients/{client_id}/approve` | 通过安卓已提交的接入申请，允许建立执行会话。 |
| `POST /v1/admin/clients/{client_id}/resume-scan` | 对在线且报告扫描暂停的手机提交恢复请求，返回 `202`。手机确认原群和空输入框后恢复扫描。 |
| `POST /v1/admin/clients/{client_id}/key/rotate` | 生成该设备的新 Key，使旧 Key 失效。 |
| `DELETE /v1/admin/clients/{client_id}/key` | 撤销该设备的 Key，使该设备需要重新配对。 |
| `GET /v1/admin/api-keys` | 列出接口 Key 的标识、调用应用、权限和状态。 |
| `POST /v1/admin/api-keys` | 为调用应用创建接口 Key，配置可访问客户端、群和操作权限。 |
| `POST /v1/admin/api-keys/{key_id}/rotate` | 保留调用应用和授权范围，生成新的接口 Key。 |
| `GET /v1/admin/api-keys/{key_id}/secret` | 管理员按需查看有效接口 Key。列表接口仅返回 `revealable` 标记。 |
| `POST /v1/admin/api-keys/{key_id}/secret` | 管理员提交仍持有的旧 Key（`api_key`），与现有哈希核对后加密保存。 |
| `DELETE /v1/admin/api-keys/{key_id}` | 撤销指定接口 Key。 |
| `DELETE /v1/admin/api-keys/{key_id}/record` | 删除已撤销的接口 Key 记录。仍有效的 Key 返回 `409`。发送任务和回执继续保留。 |
| `GET /v1/clients/{client_id}/groups/{group_id}/send-requests` | 查询该群最近 20 个发送任务，Web 群消息页使用。 |
| 下节的群查询、消息读取、发送和回执接口 | Web 通过登录会话调用。页面发起发送时自动生成 `Idempotency-Key`。 |

## 第三方业务接口

每个请求用 `Authorization: Bearer <接口 Key>` 鉴权。群列表只返回指定客户端下有权限查看的群。查单群、读消息、发送和查回执都使用路径中的客户端与群绑定。

| 方法和路径 | 用途 |
| --- | --- |
| `GET /v1/clients/{client_id}/groups` | 分页列出该客户端下有权限访问的群。 |
| `GET /v1/clients/{client_id}/groups/{group_id}` | 查询群信息、最近同步时间、历史回补进度和采集缺口状态。 |
| `GET /v1/clients/{client_id}/groups/{group_id}/messages/recent` | 取该群 `offset` 最大的 30 条，再按 `offset` 升序返回。 |
| `GET /v1/clients/{client_id}/groups/{group_id}/messages?after_offset=130&limit=20` | 取该群 `offset > 130` 的消息，按 `offset` 升序返回，最多 20 条。 |
| `POST /v1/clients/{client_id}/groups/{group_id}/send-requests` | 提交普通文字回复。 |
| `POST /v1/clients/{client_id}/groups/{group_id}/alert-send-requests` | 提交结构化告警，网关校验并排版成群消息。 |
| `GET /v1/clients/{client_id}/groups/{group_id}/send-requests/{task_id}` | 查询两类发送任务的状态和回执。 |

两种消息接口均返回 `client_id`、`group_id`、`message_id`、`offset`、`sender` 和 `text`。最近 30 条用于快速展示。从头读取历史使用 `after_offset=0`。持续同步保存该群最后一条已处理的消息的 `offset`，空页保留原进度。

```json
{
  "messages": [
    {
      "client_id": "phone-a",
      "group_id": "grp_001",
      "message_id": "msg_8f42",
      "offset": 131,
      "sender": "值班员",
      "text": "告警已恢复。"
    }
  ]
}
```

### 两类发送内容

普通回复请求体只有 `text`。结构化告警首版必填如下。

| 字段 | 含义 |
| --- | --- |
| `sn` | 设备序列号，用字符串保留前导零。 |
| `problem` | 问题现象。 |
| `started_at` | 实际开始时间，包含时区。 |
| `suggestion` | 处理建议。 |

可选字段为 `severity`、`source`、`alert_id` 和 `detail`，分别表示级别、来源系统、告警事件编号和判断依据。需要发送恢复通知时，可用 `status` 区分触发与恢复，并通过 `recovered_at` 提供带时区的实际恢复时间。触发和恢复共用告警事件编号，各自使用独立的发送幂等标记。网关保存原始结构化字段和排版后的文字。

两类接口都要求请求头 `Idempotency-Key`。Web 或第三方程序在创建发送请求时生成它。同一调用应用、客户端和群内，同一个 Key 与相同类型和内容的重试返回原任务。同一个 Key 搭配不同类型或内容返回 `409`。网关在首次受理时生成 `task_id`，返回 `202` 和任务状态。重试先查原任务，再决定是否入队。

```http
POST /v1/clients/phone-a/groups/grp_001/alert-send-requests
Authorization: Bearer <接口 Key>
Idempotency-Key: monitor:alarm-042:triggered
Content-Type: application/json

{
  "sn": "001827",
  "problem": "节点连续丢包",
  "started_at": "2026-11-07T14:28:00+08:00",
  "suggestion": "先核对交换机端口和链路状态。",
  "source": "monitor",
  "alert_id": "alarm-042"
}
```

### 发送状态和错误

| 状态 | 含义 |
| --- | --- |
| `queued` | 已入队。手机离线时等到任务过期时间。 |
| `executing` | 手机已领取，正在执行。 |
| `confirmed` | 手机在目标群读回新发出的文字气泡。 |
| `observed` | 原回执超时，后续扫描读到匹配的出站消息。 |
| `blocked` | 发送前被拦截，例如无法唯一核对群或已有草稿。 |
| `expired` | 等待领取超时。 |
| `uncertain` | 进入点击阶段后无法确认结果。原任务保留待核对状态，后续回执或观测关联到原任务。 |

回执包含 `task_id`、`status` 和结果说明。关联到群消息时附上 `message_id` 和 `offset`。正文和查询参数有误返回 `400`，Key 无效返回 `401`，权限不足返回 `403`，客户端、群或任务绑定不匹配返回 `404`。

原生提及任务确认后，网关使用申请点击许可时核对过的成员和正文，在同群中关联唯一的出站消息。群消息晚于回执入库时，后续查询可取得补齐的 `message_id` 和 `offset`。

## 原生提及成员

### 回复原消息并 @ 发送人

Web 在目标消息下点「回复并 @」，填正文后发送。网页自动带上原消息的 `message_id`。第三方从该群的最近消息或按 offset 读取接口取得 `message_id`，把它填入发送请求的 `mention_sender_of`。这个流程不需要同步群成员。

```bash
GROUP_URL="https://<网关地址>/v1/clients/<client_id>/groups/<group_id>"
curl -fsS -X POST "$GROUP_URL/send-requests" \
  -H "Authorization: Bearer ${GATEWAY_API_KEY}" \
  -H "Content-Type: application/json" -H "Idempotency-Key: reply-20261107-01" \
  --data '{"text":"收到，我来处理。","mention_sender_of":"<刚读到的 message_id>"}'
```

服务器只接受同一客户端、同一群内的入站原消息，且采集时已识别到发送人姓名。跨群消息返回 `404`，无法确认发送人或客户端未支持该能力时返回 `409`。APK 到企微原生选人页现场核对显示名，只有在完整名单里唯一匹配才发送。同名、已退群、名单不完整都进入 `blocked`。该接口发的是带原生 `@` 的新消息。任务回执使用 `GET $GROUP_URL/send-requests/<task_id>`。同一个请求重试复用原幂等 Key。

### 按成员编号主动 @

已对接成员接口的业务也可继续使用 `mentions` 按成员 ID 提及，适用于普通回复和结构化告警。普通回复只能在 `mentions` 和 `mention_sender_of` 中选一个。

要登记成员编号，在 APK 点「只读同步群成员」，进入网页已显示的群，在企微手动输入一个 `@` 打开名单。名单上传后，退出选人页、清空草稿，再回 APK 恢复扫描。

当前原生提及适配 Android 9 和企微 5.0.13（84802），每次一人，名单须能一次完整读取。人数变化时，网关须从历史文字确认同一群，随后更新标题、保留群 ID 和 offset。旧成员快照失效，但按原消息 @ 的流程使用手机当场读取的名单。企微选人页提供的是显示名，无法识别同名成员替换前后的真实账号。

#### 按成员编号调用示例

以下命令使用业务接口 Key，所选 `client_id + group_id` 须授权相应的 `members:read`、`messages:send` 或 `messages:read`。先在 Web 的「接口密钥」为业务方选择客户端、群和权限。

```bash
GROUP_URL="https://<网关地址>/v1/clients/<client_id>/groups/<group_id>"
curl -fsS -H "Authorization: Bearer ${GATEWAY_API_KEY}" "$GROUP_URL/members"
```

响应包含 `client_id`、`group_id`、`status` 和 `members`。状态 `pending_sync` 表示暂无可用快照，`ready` 表示已有名单，`members` 仍可能为空。每个可用对象包含 `member_id` 和 `display_name`。成员编号只对当前客户端、群及名单版本有效。名单更新后重新查询。旧 ID 或不属于本群的 ID 返回 `409`。

```bash
curl -fsS -X POST "$GROUP_URL/send-requests" \
  -H "Authorization: Bearer ${GATEWAY_API_KEY}" \
  -H "Content-Type: application/json" -H "Idempotency-Key: reply-20261107-01" \
  --data '{"text":"麻烦确认一下链路状态。","mentions":[{"member_id":"<上一步查到的 member_id>"}]}'
```

结构化告警使用 `POST $GROUP_URL/alert-send-requests`，在原有 `sn`、`problem`、`started_at`、`suggestion` 等字段旁加上同样的 `mentions`。普通文字发送省略 `mentions`。同一个业务动作重试要复用原来的幂等 Key，变更成员或正文时使用新的 Key。两个发送接口都返回 `task_id`，使用 `GET $GROUP_URL/send-requests/<task_id>` 查状态。任务拦截或待核对时，先看回执说明和手机界面。

### 接收 @ 消息

最近 30 条和按 offset 读取的接口都返回 `mention_self`，字段描述的是对应手机当前登录的企微账号。

```bash
curl -fsS -H "Authorization: Bearer ${GATEWAY_API_KEY}" "$GROUP_URL/messages/recent"
curl -fsS -H "Authorization: Bearer ${GATEWAY_API_KEY}" "$GROUP_URL/messages?after_offset=30&limit=100"
```

| `mention_self` | 含义 | Web 展示 |
| --- | --- | --- |
| `direct` | 有与该条消息绑定的直接提及本机账号的证据。 | `@我` |
| `all` | 有与该条消息绑定的提及所有人的证据。 | `@所有人` |
| `none` | 有足够证据确认这条消息没有提及本机账号。 | 普通消息 |
| `unknown` | 尚无足够证据，包括旧记录及当前真机采集。 | 提及状态未知 |

荣耀 10 的 APK 1.7 已验收原生 `@我`。企微会话列表显示「[有人@我]」且只有一条未读时，APK 用发件人和完整正文核对最新入站消息，唯一匹配后标为 `direct`。已读后才采集、多个未读、预览与正文不符或疑似 `@所有人` 的消息保持 `unknown`。正文里手写的 `@显示名` 按原文保留，提及字段不参与消息去重及 offset 分配。

### 设备接口字段

- 注册时安卓在 `capabilities` 中声明 `SEND_NATIVE_MENTION` 或 `MENTION_MESSAGE_SENDER`，分别支持按成员编号提及和按原消息提及。群查询返回 `mention_sender_supported` 供 Web 判断是否显示「回复并 @」。
- `POST /v1/device-results` 可包含 `member_snapshot`，字段为 `id`、`group_id`、`title` 和完整的 `names` 数组。服务端确认客户端、群和标题后返回 `member_snapshot_ack`。采集消息行可带 `mention_self`。
- 领到的命令类型为 `SEND_NATIVE_MENTION`，在既有正文和摘要之外附带 `mention.member_id`、`mention.display_name` 和 `mention.roster`。按原消息发送时改为 `mention.source_message_id` 和 `mention.display_name`。申请点击许可时提交对应的 `member_id` 或 `source_message_id`，网关再次核对目标。

## 验收场景

- APK 使用填写的服务器 IP 和端口建立连接。注册 Key 与 `client_id` 匹配时提交审批，审批前拒绝采集上报和领任务。Web 通过后建立会话。交叉使用两台设备的 Key 和 `client_id` 时注册失败。
- 两台手机用各自的 `client_id` 同时连接同一 IP 和端口，独立上报心跳、同步群消息并领取各自的发送任务。A 离线后，B 继续同步和执行。相同 `client_id` 的两个连接至多有一个可以执行命令。
- 两台手机都有同名群时，群列表返回独立 ID。指定一组 `client_id + group_id` 发送，仅对应手机领取。用另一台手机的 ID 查询或发送时请求被拒绝。
- 同一手机的两个群分别从各自的 `offset` 继续读取。读一半时有新消息入库，下一页仍从已处理的最后一条继续。重传批次保持原 `offset`，首次历史回补和采集缺口均可查询。
- 普通回复和结构化告警分别通过对应接口入队，使用同一回执接口查询。重复提交返回同一个 `task_id`。对待核对任务的查询和重复提交均保持原任务。
- 提及扩展在真机上验证原生选人、同名成员处理，以及直接 `@` 本机账号、`@所有人` 和正文手写 `@显示名` 的分类。
