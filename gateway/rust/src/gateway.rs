use crate::{
    capture,
    db::{Database, Fields, Record, Result, error, now, one, rows},
    header_value,
    key_seal::KeySeal,
};
use axum::http::HeaderMap;
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use rusqlite::{Connection, params};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::{collections::HashMap, path::Path};
use subtle::ConstantTimeEq;
use time::{OffsetDateTime, format_description::well_known::Rfc3339};

const SESSION_TTL: f64 = 30.;
const LEASE_SECONDS: f64 = 100.;
const SEND_TTL: f64 = 600.;
const SCOPES: [&str; 4] = [
    "groups:read",
    "messages:read",
    "messages:send",
    "members:read",
];
pub type ApiReply = Result<(u16, Value)>;

pub fn digest(value: &str) -> String {
    format!("{:x}", Sha256::digest(value.as_bytes()))
}

fn random<const N: usize>() -> Result<[u8; N]> {
    let mut bytes = [0; N];
    getrandom::fill(&mut bytes).map_err(|_| error(500, "无法生成安全随机数"))?;
    Ok(bytes)
}

fn key() -> Result<String> {
    Ok(URL_SAFE_NO_PAD.encode(random::<32>()?))
}

pub fn id() -> Result<String> {
    let mut bytes = random::<16>()?;
    bytes[6] = bytes[6] & 0x0f | 0x40;
    bytes[8] = bytes[8] & 0x3f | 0x80;
    Ok(format!("{:032x}", u128::from_be_bytes(bytes)))
}

pub fn str_field<'a>(value: &'a Value, label: &str, limit: usize) -> Result<&'a str> {
    value
        .as_str()
        .filter(|text| {
            !text.trim().is_empty() && text.chars().count() <= limit && !text.contains('\0')
        })
        .ok_or_else(|| error(400, &format!("{label} 格式无效")))
}

fn client_id(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 64
        && value
            .bytes()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, b'-' | b'_'))
}

pub fn group(db: &Connection, client: &str, group: &str) -> Result<Record> {
    one(
        db,
        "SELECT * FROM groups WHERE client_id=? AND group_id=?",
        params![client, group],
    )?
    .ok_or_else(|| error(404, "指定客户端下没有此群"))
}

fn online(record: &Record, timestamp: f64) -> bool {
    record.text("admission") == "approved"
        && record.int("enabled") != 0
        && timestamp - record.num("seen") < SESSION_TTL
}

fn query_number(
    query: &HashMap<String, String>,
    key: &str,
    fallback: Option<i64>,
    lower: i64,
    upper: i64,
) -> Result<i64> {
    query
        .get(key)
        .map_or(fallback, |value| value.parse().ok())
        .filter(|value| (lower..=upper).contains(value))
        .ok_or_else(|| error(400, &format!("{key} 格式或范围无效")))
}

fn public_task(task: &Record, timestamp: f64) -> Value {
    let (status, detail) = match task.text("status") {
        "queued" if task.num("expires_at") <= timestamp => ("expired", "等待领取超时"),
        "executing" if task.num("lease_until") <= timestamp => (
            if task.get("reserved_at").is_some_and(Value::is_number) {
                "uncertain"
            } else {
                "blocked"
            },
            "设备执行租约到期",
        ),
        status => (status, task.text("detail")),
    };
    json!({"task_id": task["task_id"], "client_id": task["client_id"], "group_id": task["group_id"],
           "status":status, "detail":detail, "message_id":task["message_id"], "offset":task["offset"],
           "created_at":task["created_at"], "expires_at":task["expires_at"]})
}

fn expire(db: &Connection, client: &str, timestamp: f64) -> Result<()> {
    db.execute("UPDATE tasks SET status='expired',detail='等待领取超时' WHERE client_id=? AND status='queued' AND expires_at<=?",
               params![client, timestamp])?;
    db.execute("UPDATE tasks SET status=CASE WHEN reserved_at IS NULL THEN 'blocked' ELSE 'uncertain' END,
                detail='设备执行租约到期' WHERE client_id=? AND status='executing' AND lease_until<=?",
               params![client, timestamp])?;
    Ok(())
}

fn end_execution(db: &Connection, client: &str, detail: &str) -> Result<()> {
    db.execute(
        "UPDATE tasks SET status=CASE WHEN reserved_at IS NULL THEN 'blocked' ELSE 'uncertain' END,
                detail=? WHERE client_id=? AND status='executing'",
        params![detail, client],
    )?;
    Ok(())
}

pub struct Gateway {
    pub db: Database,
    admin_hash: String,
    key_seal: KeySeal,
}

pub struct Principal {
    id: String,
    grants: Option<Vec<Value>>,
}

impl Principal {
    pub fn web(&self) -> bool {
        self.grants.is_none()
    }

    fn permits(&self, client: &str, group: Option<&str>, scope: &str) -> bool {
        self.grants.as_ref().is_none_or(|grants| {
            grants.iter().any(|grant| {
                grant["client_id"].as_str() == Some(client)
                    && grant["scopes"]
                        .as_array()
                        .is_some_and(|scopes| scopes.iter().any(|item| item == scope))
                    && (group.is_none()
                        || grant["group_ids"] == "*"
                        || grant["group_ids"]
                            .as_array()
                            .is_some_and(|ids| ids.iter().any(|id| id.as_str() == group)))
            })
        })
    }
}

impl Gateway {
    pub fn open(path: &Path, admin_token: String) -> Result<Self> {
        let db = Database::open(path)?;
        let admin_hash = digest(&admin_token);
        db.write(|db| {
            db.execute("DELETE FROM web_sessions WHERE admin_hash<>? OR expires_at<=?",
                       params![admin_hash, now()])?;
            Ok(())
        })?;
        Ok(Self {
            db,
            admin_hash,
            key_seal: KeySeal::open(path)?,
        })
    }

    pub fn browser_session(&self, token: &str, max_age: u32) -> Result<String> {
        if !bool::from(digest(token).as_bytes().ct_eq(self.admin_hash.as_bytes())) {
            return Err(error(401, "后台凭据无效"));
        }
        let session = key()?;
        let timestamp = now();
        self.db.write(|db| {
            db.execute("DELETE FROM web_sessions WHERE expires_at<=?", [timestamp])?;
            db.execute(
                "INSERT INTO web_sessions(session_hash,admin_hash,expires_at) VALUES(?,?,?)",
                params![digest(&session), self.admin_hash, timestamp + f64::from(max_age)],
            )?;
            Ok(())
        })?;
        Ok(session)
    }

    pub fn principal(
        &self,
        db: &Connection,
        bearer: &str,
        cookie: &str,
        browser: bool,
    ) -> Result<Principal> {
        if browser
            && !cookie.is_empty()
            && one(
                db,
                "SELECT 1 FROM web_sessions WHERE session_hash=? AND admin_hash=? AND expires_at>?",
                params![digest(cookie), self.admin_hash, now()],
            )?
            .is_some()
        {
            return Ok(Principal {
                id: "web".into(),
                grants: None,
            });
        }
        if !bearer.is_empty() {
            if let Some(key) = one(
                db,
                "SELECT key_id,grants FROM api_keys WHERE key_hash=?",
                [digest(bearer)],
            )? {
                return Ok(Principal {
                    id: key.text("key_id").into(),
                    grants: Some(serde_json::from_str(key.text("grants"))?),
                });
            }
        }
        Err(error(401, "接口 Key 或后台会话无效"))
    }

    pub fn admin(&self, db: &Connection, method: &str, parts: &[&str], body: &Value) -> ApiReply {
        match (method, parts) {
            ("GET", ["clients"]) => {
                let timestamp = now();
                let mut clients = rows(
                    db,
                    "SELECT client_id,label,seen,status,admission,scan_paused,resume_until,
                            key_hash IS NOT NULL AS enabled
                                            FROM clients ORDER BY client_id",
                    [],
                )?;
                for client in &mut clients {
                    client.insert("online".into(), json!(online(client, timestamp)));
                    let pending = client.num("resume_until") > timestamp;
                    client.remove("resume_until");
                    client.insert("resume_pending".into(), json!(pending));
                }
                Ok((200, json!({"clients":clients})))
            }
            ("POST", ["clients"]) => {
                let client = str_field(&body["client_id"], "client_id", 64)?;
                if !client_id(client) {
                    return Err(error(400, "client_id 仅支持字母、数字、短横线和下划线"));
                }
                let label = if let Some(value) = body.get("label") {
                    str_field(value, "设备名称", 240)?
                } else {
                    client
                };
                let secret = key()?;
                let updated = db.execute(
                    "INSERT OR IGNORE INTO clients(client_id,label,key_hash) VALUES(?,?,?)",
                    params![client, label, digest(&secret)],
                )?;
                if updated == 0 {
                    return Err(error(409, "client_id 已存在"));
                }
                Ok((201, json!({"client_id":client,"registration_key":secret})))
            }
            ("POST", ["clients", client, "approve"]) => {
                let record = one(
                    db,
                    "SELECT admission FROM clients WHERE client_id=?",
                    [client],
                )?
                .ok_or_else(|| error(404, "设备不存在"))?;
                if !matches!(record.text("admission"), "pending" | "approved") {
                    return Err(error(409, "请先在安卓端申请接入"));
                }
                db.execute(
                    "UPDATE clients SET admission='approved' WHERE client_id=?",
                    [client],
                )?;
                Ok((200, json!({"client_id":client,"admission":"approved"})))
            }
            ("POST", ["clients", client, "resume-scan"]) => {
                let timestamp = now();
                let device = one(db, "SELECT *,key_hash IS NOT NULL AS enabled FROM clients WHERE client_id=?",
                                 [client])?.ok_or_else(|| error(404, "设备不存在"))?;
                if !online(&device, timestamp) || device.int("scan_paused") != 1 {
                    return Err(error(409, "设备不在线或未报告扫描暂停"));
                }
                if device.num("resume_until") <= timestamp {
                    db.execute("UPDATE clients SET resume_id=?,resume_until=? WHERE client_id=?",
                               params![id()?, timestamp + SESSION_TTL, client])?;
                }
                Ok((202, json!({"resume_pending":true})))
            }
            ("POST", ["clients", client, "key", "rotate"])
            | ("DELETE", ["clients", client, "key"]) => {
                let secret = (method == "POST").then(key).transpose()?;
                let admission = if secret.is_some() {
                    "registered"
                } else {
                    "revoked"
                };
                let updated = db.execute(
                    "UPDATE clients SET key_hash=?,session=NULL,instance=NULL,seen=0,
                                          scan_paused=-1,resume_id=NULL,resume_until=0,
                                          epoch=epoch+1,admission=? WHERE client_id=?",
                    params![secret.as_deref().map(digest), admission, client],
                )?;
                if updated == 0 {
                    return Err(error(404, "设备不存在"));
                }
                end_execution(db, client, "设备凭据已更新")?;
                match secret {
                    Some(secret) => {
                        Ok((200, json!({"client_id":client,"registration_key":secret})))
                    }
                    None => Ok((204, Value::Null)),
                }
            }
            ("GET", ["api-keys"]) => {
                let mut keys = rows(
                    db,
                    "SELECT key_id,label,grants,key_hash IS NOT NULL AS enabled,
                            key_hash IS NOT NULL AND key_ciphertext IS NOT NULL AS revealable
                     FROM api_keys ORDER BY label,key_id",
                    [],
                )?;
                for record in &mut keys {
                    record.insert(
                        "grants".into(),
                        serde_json::from_str(record.text("grants"))?,
                    );
                }
                Ok((200, json!({"keys":keys})))
            }
            ("POST", ["api-keys"]) => {
                let label = str_field(&body["label"], "应用名称", 240)?;
                let grants = body["grants"]
                    .as_array()
                    .filter(|items| !items.is_empty() && items.len() <= 100)
                    .ok_or_else(|| error(400, "请指定客户端和权限"))?;
                for grant in grants {
                    let client = str_field(&grant["client_id"], "client_id", 64)?;
                    if one(db, "SELECT 1 FROM clients WHERE client_id=?", [client])?.is_none() {
                        return Err(error(404, "授权客户端不存在"));
                    }
                    if !grant["scopes"].as_array().is_some_and(|scopes| {
                        !scopes.is_empty()
                            && scopes.iter().all(|scope| {
                                scope.as_str().is_some_and(|scope| SCOPES.contains(&scope))
                            })
                    }) {
                        return Err(error(400, "授权权限无效"));
                    }
                    if grant["group_ids"] != "*" {
                        let groups = grant["group_ids"]
                            .as_array()
                            .filter(|ids| !ids.is_empty())
                            .ok_or_else(|| error(400, "请选择授权群"))?;
                        for group_id in groups {
                            group(db, client, str_field(group_id, "group_id", 240)?)?;
                        }
                    }
                }
                let (id, secret) = (id()?, key()?);
                db.execute(
                    "INSERT INTO api_keys(key_id,label,key_hash,grants,key_ciphertext) VALUES(?,?,?,?,?)",
                    params![id, label, digest(&secret), body["grants"].to_string(), self.key_seal.encrypt(&id, &secret)?],
                )?;
                Ok((201, json!({"key_id":id,"api_key":secret,"revealable":true})))
            }
            ("GET", ["api-keys", key_id, "secret"]) => {
                let record = one(db, "SELECT key_hash,key_ciphertext FROM api_keys WHERE key_id=? AND key_hash IS NOT NULL", [key_id])?
                    .ok_or_else(|| error(404, "有效的接口 Key 不存在"))?;
                let encrypted = record["key_ciphertext"]
                    .as_str()
                    .ok_or_else(|| error(409, "此接口 Key 仅保存了哈希，请轮换后查看"))?;
                let secret = self.key_seal.decrypt(key_id, encrypted)?;
                if !bool::from(
                    digest(&secret)
                        .as_bytes()
                        .ct_eq(record.text("key_hash").as_bytes()),
                ) {
                    return Err(error(409, "此接口 Key 已更新，请轮换后查看"));
                }
                Ok((200, json!({"key_id":key_id,"api_key":secret})))
            }
            ("POST", ["api-keys", key_id, "secret"]) => {
                let secret = str_field(&body["api_key"], "接口 Key", 160)?;
                let record = one(
                    db,
                    "SELECT key_hash FROM api_keys WHERE key_id=? AND key_hash IS NOT NULL",
                    [key_id],
                )?
                .ok_or_else(|| error(404, "有效的接口 Key 不存在"))?;
                let hash = digest(secret);
                if !bool::from(hash.as_bytes().ct_eq(record.text("key_hash").as_bytes())) {
                    return Err(error(409, "接口 Key 与现有记录不匹配"));
                }
                db.execute(
                    "UPDATE api_keys SET key_ciphertext=? WHERE key_id=? AND key_hash=? AND key_ciphertext IS NULL",
                    params![self.key_seal.encrypt(key_id, secret)?, key_id, hash],
                )?;
                Ok((204, Value::Null))
            }
            ("POST", ["api-keys", key_id, "rotate"]) | ("DELETE", ["api-keys", key_id]) => {
                let secret = (method == "POST").then(key).transpose()?;
                let encrypted = secret
                    .as_deref()
                    .map(|value| self.key_seal.encrypt(key_id, value))
                    .transpose()?;
                let updated = db.execute(
                    "UPDATE api_keys SET key_hash=?,key_ciphertext=? WHERE key_id=?",
                    params![secret.as_deref().map(digest), encrypted, key_id],
                )?;
                if updated == 0 {
                    return Err(error(404, "接口 Key 不存在"));
                }
                match secret {
                    Some(secret) => Ok((
                        200,
                        json!({"key_id":key_id,"api_key":secret,"revealable":true}),
                    )),
                    None => Ok((204, Value::Null)),
                }
            }
            ("DELETE", ["api-keys", key_id, "record"]) => {
                let deleted = db.execute(
                    "DELETE FROM api_keys WHERE key_id=? AND key_hash IS NULL",
                    [key_id],
                )?;
                if deleted != 0 {
                    Ok((204, Value::Null))
                } else if one(db, "SELECT 1 FROM api_keys WHERE key_id=?", [key_id])?.is_some() {
                    Err(error(409, "请先撤销接口 Key"))
                } else {
                    Err(error(404, "接口 Key 不存在"))
                }
            }
            _ => Err(error(404, "接口不存在")),
        }
    }

    pub fn device(
        &self,
        db: &Connection,
        method: &str,
        parts: &[&str],
        body: &Value,
        bearer: &str,
        headers: &HeaderMap,
    ) -> ApiReply {
        let header_client = header_value(headers, "x-client-id");
        let client = if header_client.is_empty() {
            body["client_id"].as_str().unwrap_or("")
        } else {
            header_client
        };
        if !client_id(client) || body.get("client_id").is_some_and(|value| value != client) {
            return Err(error(400, "client_id 无效"));
        }
        let device = one(db, "SELECT * FROM clients WHERE client_id=?", [client])?
            .filter(|device| {
                !bearer.is_empty()
                    && !device.text("key_hash").is_empty()
                    && bool::from(
                        digest(bearer)
                            .as_bytes()
                            .ct_eq(device.text("key_hash").as_bytes()),
                    )
            })
            .ok_or_else(|| error(401, "注册 Key 与客户端不匹配"))?;
        let timestamp = now();
        if method == "POST" && parts == ["devices", "session"] {
            let instance = str_field(&body["instance_id"], "instance_id", 240)?;
            if device.text("admission") != "approved" {
                db.execute("UPDATE clients SET admission='pending',status='等待 Web 审批' WHERE client_id=?", [client])?;
                return Ok((202, json!({"status":"pending_approval"})));
            }
            let active =
                !device.text("session").is_empty() && timestamp - device.num("seen") < SESSION_TTL;
            if active && device.text("instance") != instance {
                return Err(error(409, "该客户端已有活动会话"));
            }
            let (session, epoch) = if active {
                (device.text("session").to_owned(), device.int("epoch"))
            } else {
                end_execution(db, client, "设备重新连接，请核对旧任务")?;
                db.execute("UPDATE clients SET scan_paused=-1,resume_id=NULL,resume_until=0 WHERE client_id=?",
                           [client])?;
                (key()?, device.int("epoch") + 1)
            };
            let native_mentions = body["capabilities"]
                .as_array()
                .is_some_and(|values| values.iter().any(|value| value == "SEND_NATIVE_MENTION"));
            let mention_sender = body["capabilities"]
                .as_array()
                .is_some_and(|values| values.iter().any(|value| value == "MENTION_MESSAGE_SENDER"));
            db.execute(
                "UPDATE clients SET instance=?,session=?,epoch=?,seen=?,native_mentions=?,mention_sender=? WHERE client_id=?",
                params![instance, session, epoch, timestamp, native_mentions, mention_sender, client],
            )?;
            return Ok((
                200,
                json!({"device_session_id":session,"lease_epoch":epoch}),
            ));
        }
        if device.text("admission") != "approved" {
            return Err(error(403, "设备尚未通过 Web 审批"));
        }
        if device.text("session").is_empty()
            || timestamp - device.num("seen") >= SESSION_TTL
            || header_value(headers, "x-device-session") != device.text("session")
            || header_value(headers, "x-lease-epoch").parse::<i64>().ok()
                != Some(device.int("epoch"))
        {
            return Err(error(409, "设备会话已失效，请重新注册"));
        }
        if method == "POST" && parts != ["devices", "heartbeat"] {
            db.execute(
                "UPDATE clients SET seen=? WHERE client_id=? AND seen<?",
                params![timestamp, client, timestamp - 1.],
            )?;
        }
        match (method, parts) {
            ("POST", ["devices", "heartbeat"]) => {
                let status: String = body["status"]
                    .as_str()
                    .unwrap_or("")
                    .chars()
                    .take(200)
                    .collect();
                let paused = body["scan_paused"].as_bool().map_or(-1, i64::from);
                let resume_id = device.text("resume_id");
                let pending = paused == 1
                    && !resume_id.is_empty()
                    && device.num("resume_until") > timestamp
                    && body["resume_ack"] != resume_id;
                db.execute(
                    "UPDATE clients SET status=?,seen=?,scan_paused=?,
                         resume_id=CASE WHEN ? THEN resume_id END,
                         resume_until=CASE WHEN ? THEN resume_until ELSE 0 END WHERE client_id=?",
                    params![status, timestamp, paused, pending, pending, client],
                )?;
                Ok((200, if pending {
                    json!({"ok":true,"resume_scan_id":resume_id})
                } else {
                    json!({"ok":true})
                }))
            }
            ("GET", ["devices", "config"]) => {
                let mut groups = rows(
                    db,
                    "SELECT group_id,name,title,head,history_status FROM groups WHERE client_id=?",
                    [client],
                )?;
                for group in &mut groups {
                    let head: Vec<Value> = serde_json::from_str(group.text("head"))?;
                    group.insert("head".into(), json!(&head[head.len().saturating_sub(12)..]));
                }
                Ok((200, json!({"config_revision":1,"groups":groups})))
            }
            ("POST", ["device-results"]) => capture::device_results(db, client, body, timestamp),
            ("POST", ["device-commands", "claim"]) => {
                claim(db, client, device.text("session"), body, timestamp)
            }
            (
                "POST",
                [
                    "device-commands",
                    task_id,
                    operation @ ("renew" | "click-reservation"),
                ],
            ) => {
                let task = one(
                    db,
                    "SELECT * FROM tasks WHERE task_id=? AND client_id=?",
                    params![task_id, client],
                )?
                .filter(|task| body["group_id"] == task["group_id"])
                .ok_or_else(|| error(404, "命令与设备或群不匹配"))?;
                if task.text("status") != "executing"
                    || task["session"] != device["session"]
                    || timestamp >= task.num("lease_until")
                {
                    return Err(error(409, "命令已失效"));
                }
                if *operation == "renew" {
                    db.execute(
                        "UPDATE tasks SET lease_until=? WHERE task_id=?",
                        params![timestamp + LEASE_SECONDS, task_id],
                    )?;
                    return Ok((
                        200,
                        json!({"lease_remaining_ms":(LEASE_SECONDS*1000.) as u64}),
                    ));
                }
                let group = group(db, client, task.text("group_id"))?;
                if group.int("routable") == 0
                    || group["title"] != body["title"]
                    || digest(task.text("text")) != body["body_digest"]
                {
                    return Err(error(409, "群路由或正文核对失败"));
                }
                let payload: Value = serde_json::from_str(task.text("payload"))?;
                let sent_text = if let Some(member) = resolve_mention_target(db, &group, &payload)?
                {
                    if member["member_id"] != body["member_id"]
                        || member["source_message_id"] != body["source_message_id"]
                    {
                        return Err(error(409, "原生提及对象核对失败"));
                    }
                    Some(format!(
                        "{}   @{}",
                        task.text("text"),
                        member["display_name"].as_str().unwrap()
                    ))
                } else {
                    None
                };
                let updated = db.execute(
                    "UPDATE tasks SET reserved_at=?,sent_text=? WHERE task_id=? AND reserved_at IS NULL",
                    params![timestamp, sent_text, task_id],
                )?;
                Ok((200, json!({"may_click":updated == 1})))
            }
            _ => Err(error(404, "接口不存在")),
        }
    }

    #[allow(clippy::too_many_arguments)]
    pub fn business(
        &self,
        db: &Connection,
        method: &str,
        parts: &[&str],
        query: &HashMap<String, String>,
        body: &Value,
        idem: &str,
        principal: &Principal,
    ) -> ApiReply {
        let ["clients", client, "groups", tail @ ..] = parts else {
            return Err(error(404, "接口不存在"));
        };
        if tail.is_empty() && method == "GET" {
            if !principal.permits(client, None, "groups:read") {
                return Err(error(403, "接口 Key 无此客户端的查群权限"));
            }
            let device = client_summary(db, client)?;
            let limit = query_number(query, "limit", Some(100), 1, 200)? as usize;
            let mut after = query.get("after_group_id").cloned().unwrap_or_default();
            let is_online = online(&device, now());
            let query = "SELECT client_id,group_id,name,title,seen AS last_synced_at,history_status,routable
                         FROM groups WHERE client_id=? AND group_id>? ORDER BY group_id LIMIT ?";
            let page_size = if principal.web() { limit + 1 } else { 256 };
            let mut groups = Vec::new();
            while groups.len() <= limit {
                let page = rows(db, query, params![client, after, page_size as i64])?;
                let count = page.len();
                if let Some(last) = page.last() {
                    after = last.text("group_id").into();
                }
                groups.extend(
                    page.into_iter()
                        .filter(|group| {
                            principal.permits(client, Some(group.text("group_id")), "groups:read")
                        })
                        .take(limit + 1 - groups.len())
                        .map(|group| {
                            public_group(group, is_online, device.int("mention_sender") == 1)
                        }),
                );
                if count < page_size {
                    break;
                }
            }
            let next = if groups.len() > limit {
                groups.pop();
                groups[limit - 1]["group_id"].clone()
            } else {
                Value::Null
            };
            return Ok((200, json!({"groups":groups,"next_after_group_id":next})));
        }
        let Some((group_id, tail)) = tail.split_first() else {
            return Err(error(404, "群不存在"));
        };
        let scope = match tail.first() {
            Some(&"messages") => "messages:read",
            Some(&"members") => "members:read",
            Some(&("send-requests" | "alert-send-requests")) => "messages:send",
            _ => "groups:read",
        };
        let group = one(
            db,
            "SELECT client_id,group_id,name,title,seen,history_status,routable FROM groups
                             WHERE client_id=? AND group_id=?",
            params![client, group_id],
        )?
        .ok_or_else(|| error(404, "指定客户端下没有此群"))?;
        if !principal.permits(client, Some(group_id), scope) {
            return Err(error(403, "接口 Key 无该群的操作权限"));
        }
        let timestamp = now();
        match (method, tail) {
            ("GET", []) => {
                let device = client_summary(db, client)?;
                Ok((
                    200,
                    json!(public_group(
                        group,
                        online(&device, timestamp),
                        device.int("mention_sender") == 1
                    )),
                ))
            }
            ("GET", ["members"]) => {
                let snapshot = group_members(db, &group)?;
                let state = if snapshot.is_some() {
                    "ready"
                } else {
                    "pending_sync"
                };
                Ok((
                    200,
                    json!({"client_id":client,"group_id":group_id,"status":state,
                    "members":snapshot.map_or(json!([]), |snapshot| snapshot["members"].clone())}),
                ))
            }
            ("GET", ["messages"] | ["messages", "recent"]) => {
                let mut messages = if tail.len() == 2 {
                    let mut results = rows(
                        db,
                        "SELECT * FROM messages WHERE group_id=? ORDER BY offset DESC LIMIT 30",
                        [group_id],
                    )?;
                    results.reverse();
                    results
                } else {
                    let after = query_number(query, "after_offset", None, 0, i64::MAX)?;
                    let limit = query_number(query, "limit", None, 1, 1000)?;
                    rows(
                        db,
                        "SELECT * FROM messages WHERE group_id=? AND offset>? ORDER BY offset LIMIT ?",
                        params![group_id, after, limit],
                    )?
                };
                for message in &mut messages {
                    message.insert("client_id".into(), json!(client));
                }
                Ok((200, json!({"messages":messages})))
            }
            ("POST", [kind @ ("send-requests" | "alert-send-requests")]) => {
                enqueue(db, &group, kind, body, idem, principal, timestamp)
            }
            ("GET", ["send-requests"]) if principal.web() => {
                let tasks = rows(
                    db,
                    "SELECT * FROM tasks WHERE client_id=? AND group_id=? ORDER BY created_at DESC LIMIT 20",
                    params![client, group_id],
                )?;
                let items: Vec<_> = tasks
                    .iter()
                    .map(|row| {
                        let mut task = public_task(row, timestamp);
                        task["text"] = row["text"].clone();
                        task
                    })
                    .collect();
                Ok((200, json!({"tasks":items})))
            }
            ("GET", ["send-requests", task_id]) => {
                let task = one(
                    db,
                    "SELECT * FROM tasks WHERE client_id=? AND group_id=? AND task_id=?",
                    params![client, group_id, task_id],
                )?
                .filter(|task| principal.web() || task.text("principal") == principal.id)
                .ok_or_else(|| error(404, "发送任务不存在"))?;
                Ok((200, public_task(&task, timestamp)))
            }
            _ => Err(error(404, "接口不存在")),
        }
    }
}

fn client_summary(db: &Connection, client: &str) -> Result<Record> {
    one(
        db,
        "SELECT seen,admission,mention_sender,key_hash IS NOT NULL AS enabled FROM clients WHERE client_id=?",
        [client],
    )?
    .ok_or_else(|| error(404, "客户端不存在"))
}

fn public_group(mut group: Record, online: bool, mention_sender: bool) -> Record {
    if let Some(seen) = group.remove("seen") {
        group.insert("last_synced_at".into(), seen);
    }
    group.insert("online".into(), json!(online));
    group.insert("routable".into(), json!(group.int("routable") != 0));
    group.retain(|name, _| {
        matches!(
            name.as_str(),
            "client_id"
                | "group_id"
                | "name"
                | "title"
                | "history_status"
                | "last_synced_at"
                | "routable"
                | "online"
        )
    });
    group.insert("mention_sender_supported".into(), json!(mention_sender));
    group
}

fn claim(db: &Connection, client: &str, session: &str, body: &Value, timestamp: f64) -> ApiReply {
    expire(db, client, timestamp)?;
    if body["ready"] != true {
        return Ok((204, Value::Null));
    }
    let task = if let Some(task) = one(
        db,
        "SELECT * FROM tasks WHERE client_id=? AND status='executing'",
        [client],
    )? {
        if task.text("session") != session || task["reserved_at"].is_number() {
            return Ok((204, Value::Null));
        }
        task
    } else {
        let Some(mut task) = one(
            db,
            "SELECT tasks.* FROM tasks JOIN groups USING(group_id)
            WHERE tasks.client_id=? AND tasks.status='queued' AND groups.routable=1
            ORDER BY tasks.created_at,tasks.task_id LIMIT 1",
            [client],
        )?
        else {
            return Ok((204, Value::Null));
        };
        let lease = timestamp + LEASE_SECONDS;
        db.execute("UPDATE tasks SET status='executing',session=?,lease_until=?,claimed_at=? WHERE task_id=?",
                   params![session,lease,timestamp,task.text("task_id")])?;
        task.insert("lease_until".into(), json!(lease));
        task
    };
    let group = group(db, client, task.text("group_id"))?;
    let mut command = json!({"command_id":task["task_id"],"task_id":task["task_id"],"type":"SEND_TEXT",
        "client_id":client,"group_id":group["group_id"],"name":group["name"],"title":group["title"],
        "text":task["text"],"body_digest":digest(task.text("text")),
        "lease_remaining_ms":((task.num("lease_until")-timestamp)*1000.) as i64});
    let payload: Value = serde_json::from_str(task.text("payload"))?;
    match resolve_mention_target(db, &group, &payload) {
        Ok(Some(member)) if supports_mentions(db, client, &member)? => {
            command["type"] = json!("SEND_NATIVE_MENTION");
            command["mention"] = member;
        }
        Ok(Some(_)) | Err(crate::db::Error(404 | 409, _)) => {
            db.execute("UPDATE tasks SET status='blocked',detail='提及对象变化或设备不支持原生提及' WHERE task_id=?",
                [task.text("task_id")])?;
            return Ok((204, Value::Null));
        }
        Ok(None) => {}
        Err(err) => return Err(err),
    }
    Ok((200, command))
}

fn group_members(db: &Connection, group: &Record) -> Result<Option<Value>> {
    one(
        db,
        "SELECT members FROM member_snapshots WHERE group_id=? AND title=?",
        params![group.text("group_id"), group.text("title")],
    )?
    .map(|snapshot| serde_json::from_str(snapshot.text("members")).map_err(Into::into))
    .transpose()
}

fn resolve_mention(db: &Connection, group: &Record, mentions: &Value) -> Result<Value> {
    let items = mentions
        .as_array()
        .filter(|items| items.len() == 1)
        .ok_or_else(|| error(400, "每次发送可指定一位提及成员"))?;
    let target = items[0]
        .as_object()
        .filter(|target| target.len() == 1)
        .ok_or_else(|| error(400, "提及对象只能包含 member_id"))?;
    let member_id = str_field(
        target.get("member_id").unwrap_or(&Value::Null),
        "member_id",
        80,
    )?;
    let snapshot = group_members(db, group)?.unwrap_or(Value::Null);
    let member = snapshot["members"]
        .as_array()
        .and_then(|members| {
            members
                .iter()
                .find(|member| member["member_id"] == member_id)
        })
        .ok_or_else(|| error(409, "成员在当前客户端和群下不可定位，请同步成员名单"))?;
    Ok(
        json!({"member_id":member["member_id"],"display_name":member["display_name"],"roster":snapshot["roster"]}),
    )
}

fn resolve_mention_target(
    db: &Connection,
    group: &Record,
    payload: &Value,
) -> Result<Option<Value>> {
    match (payload.get("mentions"), payload.get("mention_sender_of")) {
        (Some(_), Some(_)) => Err(error(400, "mentions 和 mention_sender_of 只能指定其中一个")),
        (Some(mentions), None) => resolve_mention(db, group, mentions).map(Some),
        (None, Some(source)) => {
            let message_id = str_field(source, "mention_sender_of", 80)?;
            let message = one(
                db,
                "SELECT sender,direction FROM messages WHERE group_id=? AND message_id=?",
                params![group.text("group_id"), message_id],
            )?
            .ok_or_else(|| error(404, "当前客户端和群下没有该原消息"))?;
            let sender = message["sender"].as_str().filter(|name| {
                !name.is_empty()
                    && name.trim() == *name
                    && name.encode_utf16().count() <= 120
                    && !name.contains(['\r', '\n'])
                    && *name != "所有人"
            });
            if message.text("direction") != "incoming" || sender.is_none() {
                return Err(error(409, "原消息的发送人无法确认"));
            }
            Ok(Some(
                json!({"source_message_id":message_id,"display_name":sender}),
            ))
        }
        (None, None) => Ok(None),
    }
}

pub fn has_mention(task: &Record) -> Result<bool> {
    let payload: Value = serde_json::from_str(task.text("payload"))?;
    Ok(payload.get("mentions").is_some() || payload.get("mention_sender_of").is_some())
}

fn supports_mentions(db: &Connection, client: &str, member: &Value) -> Result<bool> {
    let field = if member["source_message_id"].is_string() {
        "mention_sender"
    } else {
        "native_mentions"
    };
    Ok(one(
        db,
        "SELECT native_mentions,mention_sender FROM clients WHERE client_id=?",
        [client],
    )?
    .is_some_and(|device| device.int(field) == 1))
}

fn enqueue(
    db: &Connection,
    group: &Record,
    kind: &str,
    body: &Value,
    idem: &str,
    principal: &Principal,
    timestamp: f64,
) -> ApiReply {
    if idem.trim().is_empty() || idem.len() > 128 {
        return Err(error(400, "Idempotency-Key 格式无效"));
    }
    let (client, group_id) = (group.text("client_id"), group.text("group_id"));
    expire(db, client, timestamp)?;
    if let Some(prior) = one(
        db,
        "SELECT * FROM tasks WHERE principal=? AND client_id=? AND group_id=? AND idem=?",
        params![principal.id, client, group_id, idem],
    )? {
        let stored: Value = serde_json::from_str(prior.text("payload"))?;
        if prior.text("kind") != kind || stored != *body {
            return Err(error(409, "同一个幂等 Key 对应的发送内容不同"));
        }
        return Ok((200, public_task(&prior, timestamp)));
    }
    let fields = body
        .as_object()
        .ok_or_else(|| error(400, "发送请求格式无效"))?;
    let text = if kind == "alert-send-requests" {
        alert_text(body)?
    } else if fields
        .keys()
        .all(|key| matches!(key.as_str(), "text" | "mentions" | "mention_sender_of"))
    {
        str_field(&body["text"], "发送正文", 500)?.to_owned()
    } else {
        return Err(error(400, "普通回复含有未知字段"));
    };
    if text.chars().count() > 500 {
        return Err(error(400, "发送正文格式无效"));
    }
    if group.int("routable") == 0 {
        return Err(error(409, "群入口当前未确认"));
    }
    if let Some(member) = resolve_mention_target(db, group, body)? {
        if !supports_mentions(db, client, &member)? {
            return Err(error(409, "客户端未报告原生提及能力"));
        }
    }
    let task_id = id()?;
    db.execute("INSERT INTO tasks(task_id,group_id,client_id,principal,idem,kind,payload,text,status,created_at,expires_at)
                VALUES(?,?,?,?,?,?,?,?,'queued',?,?)",
               params![task_id,group_id,client,principal.id,idem,kind,body.to_string(),text,timestamp,timestamp+SEND_TTL])?;
    let task = one(db, "SELECT * FROM tasks WHERE task_id=?", [task_id])?.unwrap();
    Ok((202, public_task(&task, timestamp)))
}

fn alert_text(body: &Value) -> Result<String> {
    let fields = [
        ("sn", "SN"),
        ("started_at", "开始时间"),
        ("recovered_at", "恢复时间"),
        ("detail", "判断依据"),
        ("suggestion", "处理建议"),
        ("source", "来源"),
        ("alert_id", "告警编号"),
    ];
    for (key, value) in body.as_object().unwrap() {
        if key == "mentions" {
            continue;
        }
        if let Some(label) = fields
            .iter()
            .find_map(|(field, label)| (*field == key).then_some(*label))
        {
            str_field(value, label, 500)?;
        } else if matches!(key.as_str(), "problem" | "severity" | "status") {
            str_field(value, key, 500)?;
        } else {
            return Err(error(400, "存在未知告警字段"));
        }
    }
    for (key, label) in [
        ("sn", "SN"),
        ("problem", "问题"),
        ("started_at", "开始时间"),
        ("suggestion", "处理建议"),
    ] {
        str_field(&body[key], label, 500)?;
    }
    let status = body["status"].as_str().unwrap_or("triggered");
    let recovered = status == "recovered";
    if !matches!(status, "triggered" | "recovered")
        || body.get("recovered_at").is_some() != recovered
    {
        return Err(error(400, "告警状态与恢复时间不匹配"));
    }
    let parse = |key| {
        OffsetDateTime::parse(body[key].as_str().unwrap(), &Rfc3339)
            .map_err(|_| error(400, "告警时间须包含日期、时间和时区"))
    };
    let started = parse("started_at")?;
    if recovered && parse("recovered_at")? < started {
        return Err(error(400, "恢复时间早于开始时间"));
    }
    let severity = body["severity"]
        .as_str()
        .map_or(String::new(), |value| format!("{value} "));
    let heading = if recovered { "告警恢复" } else { "告警" };
    let mut lines = vec![format!(
        "【{severity}{heading}】{}",
        body["problem"].as_str().unwrap()
    )];
    for (key, label) in fields {
        if let Some(value) = body[key].as_str() {
            lines.push(format!("{label}：{value}"));
        }
    }
    Ok(lines.join("\n"))
}
