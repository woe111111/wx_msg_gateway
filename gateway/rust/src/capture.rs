use crate::{
    db::{Fields, Record, Result, error, one, rows},
    gateway::{ApiReply, digest, group, has_mention, id, str_field},
};
use rusqlite::{Connection, params};
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value, json};
use std::collections::VecDeque;

#[derive(Clone, Deserialize, Serialize)]
struct Message {
    sender: Option<String>,
    text: String,
    direction: String,
    #[serde(default = "unknown", skip_serializing_if = "is_unknown")]
    mention_self: String,
}

fn unknown() -> String {
    "unknown".into()
}
fn is_unknown(value: &String) -> bool {
    value == "unknown"
}

impl PartialEq for Message {
    fn eq(&self, other: &Self) -> bool {
        self.sender == other.sender && self.text == other.text && self.direction == other.direction
    }
}

fn overlap<T: PartialEq>(left: &[T], right: &[T]) -> usize {
    (1..=left.len().min(right.len()))
        .rev()
        .find(|length| left[left.len() - length..] == right[..*length])
        .unwrap_or(0)
}

fn contains_rows<T: PartialEq>(haystack: &[T], needle: &[T]) -> bool {
    needle.is_empty()
        || haystack
            .windows(needle.len())
            .any(|window| window == needle)
}

fn unique_anchor<T: PartialEq>(rows: &[T], anchor: &[T]) -> Option<usize> {
    if anchor.is_empty() {
        return None;
    }
    let mut matches = rows
        .windows(anchor.len())
        .enumerate()
        .filter(|(_, window)| *window == anchor);
    let (index, _) = matches.next()?;
    matches.next().is_none().then_some(index)
}

fn remember_event(db: &Connection, client: &str, event: &str, value: &Value) -> Result<bool> {
    let content = digest(&value.to_string());
    if let Some(prior) = one(
        db,
        "SELECT payload_hash FROM device_events WHERE client_id=? AND event_id=?",
        params![client, event],
    )? {
        if prior.text("payload_hash") != content {
            return Err(error(409, "事件编号重复但内容不同"));
        }
        return Ok(false);
    }
    db.execute(
        "INSERT INTO device_events VALUES(?,?,?)",
        params![client, event, content],
    )?;
    Ok(true)
}

pub fn device_results(db: &Connection, client: &str, body: &Value, timestamp: f64) -> ApiReply {
    let mut ack = Vec::new();
    let mut bindings = Map::new();
    if let Some(captures) = body.get("captures") {
        let captures = captures
            .as_array()
            .filter(|items| items.len() <= 8)
            .ok_or_else(|| error(400, "采集批次无效"))?;
        for page in captures {
            let capture_id = str_field(
                page.get("capture_id").unwrap_or(&page["id"]),
                "capture_id",
                240,
            )?;
            let group_id = capture(db, client, capture_id, page, timestamp)?;
            ack.push(capture_id);
            bindings.insert(page["name"].as_str().unwrap().into(), json!(group_id));
        }
    }
    if let Some(routes) = body.get("routes").filter(|value| !value.is_null()) {
        let routes = routes
            .as_object()
            .filter(|routes| routes.len() <= 1000)
            .ok_or_else(|| error(400, "群路由无效"))?;
        db.execute(
            "UPDATE groups SET routable=0 WHERE client_id=? AND routable=1",
            [client],
        )?;
        let mut update = db.prepare_cached(
            "UPDATE groups SET routable=1 WHERE client_id=? AND name=? AND title=?",
        )?;
        for (name, title) in routes {
            str_field(&json!(name), "群名", 240)?;
            update.execute(params![client, name, str_field(title, "群标题", 240)?])?;
        }
    }
    let member_snapshot_ack = body
        .get("member_snapshot")
        .filter(|snapshot| !snapshot.is_null())
        .map(|snapshot| save_members(db, client, snapshot))
        .transpose()?;
    let completion_ack = body
        .get("completion")
        .filter(|item| !item.is_null())
        .map(|completion| complete(db, client, completion))
        .transpose()?;
    let mut event_ack = Vec::new();
    if let Some(events) = body.get("events") {
        let events = events
            .as_array()
            .filter(|items| items.len() <= 30)
            .ok_or_else(|| error(400, "设备事件格式无效"))?;
        for event in events {
            let event_id = str_field(&event["id"], "event_id", 240)?;
            str_field(&event["text"], "事件正文", 1000)?;
            remember_event(db, client, event_id, event)?;
            event_ack.push(event_id);
        }
    }
    Ok((
        200,
        json!({"ack":ack,"bindings":bindings,"completion_ack":completion_ack,"event_ack":event_ack,"member_snapshot_ack":member_snapshot_ack}),
    ))
}

fn save_members(db: &Connection, client: &str, snapshot: &Value) -> Result<String> {
    let snapshot_id = str_field(&snapshot["id"], "snapshot_id", 240)?;
    let group_id = str_field(&snapshot["group_id"], "group_id", 240)?;
    let group = group(db, client, group_id)?;
    if snapshot["title"] != group["title"] {
        return Err(error(400, "成员快照与群标题不匹配"));
    }
    let names = snapshot["names"]
        .as_array()
        .filter(|names| names.len() <= 200)
        .ok_or_else(|| error(400, "成员快照无效"))?;
    let mut counts = std::collections::BTreeMap::new();
    for value in names {
        let name = str_field(value, "成员显示名", 120)?;
        *counts.entry(name).or_insert(0) += 1;
    }
    let roster: Vec<_> = counts
        .iter()
        .flat_map(|(name, count)| std::iter::repeat_n(*name, *count))
        .collect();
    let revision = digest(&json!(roster).to_string());
    let members: Vec<_> = counts
        .into_iter()
        .filter(|(_, count)| *count == 1)
        .map(|(name, _)| {
            json!({"member_id":format!("mem_{}",digest(&format!("{group_id}\n{revision}\n{name}"))),
                               "display_name":name})
        })
        .collect();
    if remember_event(db, client, snapshot_id, snapshot)? {
        db.execute(
            "INSERT INTO member_snapshots(group_id,title,members) VALUES(?,?,?)
            ON CONFLICT(group_id) DO UPDATE SET title=excluded.title,members=excluded.members",
            params![
                group_id,
                group.text("title"),
                json!({"members":members,"roster":roster}).to_string()
            ],
        )?;
    }
    Ok(snapshot_id.to_owned())
}

fn normalized_rows(page: &Value) -> Result<Vec<Message>> {
    let rows = page["rows"]
        .as_array()
        .filter(|rows| rows.len() <= 300)
        .ok_or_else(|| error(400, "消息窗口无效"))?;
    rows.iter()
        .map(|row| {
            let text = str_field(&row["text"], "消息正文", 12000)?.to_owned();
            let sender = row
                .get("sender")
                .filter(|value| !value.is_null())
                .map(|value| str_field(value, "发送人", 240).map(str::to_owned))
                .transpose()?;
            let direction = row
                .get("direction")
                .map(Value::as_str)
                .unwrap_or(Some("unknown"))
                .filter(|dir| matches!(*dir, "incoming" | "outgoing" | "unknown"))
                .ok_or_else(|| error(400, "消息方向无效"))?
                .to_owned();
            let mention_self = row
                .get("mention_self")
                .map_or(Some("unknown"), Value::as_str)
                .filter(|state| matches!(*state, "unknown" | "none" | "direct" | "all"))
                .filter(|state| direction == "incoming" || matches!(*state, "unknown" | "none"))
                .ok_or_else(|| error(400, "提及状态无效"))?
                .to_owned();
            Ok(Message {
                sender,
                text,
                direction,
                mention_self,
            })
        })
        .collect()
}

fn capture(
    db: &Connection,
    client: &str,
    capture_id: &str,
    page: &Value,
    timestamp: f64,
) -> Result<String> {
    let name = str_field(&page["name"], "群名", 240)?;
    let title = str_field(&page["title"], "群标题", 240)?;
    let content = digest(&page.to_string());
    if let Some(prior) = one(
        db,
        "SELECT payload_hash,group_id FROM captures WHERE client_id=? AND capture_id=?",
        params![client, capture_id],
    )? {
        if prior.text("payload_hash") != content {
            return Err(error(409, "采集编号重复但内容不同"));
        }
        return Ok(prior.text("group_id").into());
    }
    let mut messages = normalized_rows(page)?;
    let group = if let Some(group) = one(
        db,
        "SELECT * FROM groups WHERE client_id=? AND name=?",
        params![client, name],
    )? {
        group
    } else {
        if page["group_id"].as_str().is_some_and(|id| !id.is_empty()) {
            return Err(error(404, "指定客户端下没有此群"));
        }
        let group_id = id()?;
        db.execute(
            "INSERT INTO groups(group_id,client_id,name,title) VALUES(?,?,?,?)",
            params![group_id, client, name, title],
        )?;
        group(db, client, &group_id)?
    };
    let group_id = group.text("group_id");
    if page["group_id"]
        .as_str()
        .is_some_and(|id| !id.is_empty() && id != group_id)
    {
        return Err(error(404, "指定客户端下群绑定不匹配"));
    }
    let sweep = page
        .get("sweep_id")
        .filter(|value| !value.is_null())
        .map(|value| str_field(value, "sweep_id", 240))
        .transpose()?;
    let older = page
        .get("older_than")
        .filter(|value| !value.is_null())
        .map(|value| str_field(value, "older_than", 240))
        .transpose()?;
    let mut end = page
        .get("history_status")
        .filter(|value| !value.is_null())
        .map(|value| {
            value
                .as_str()
                .filter(|end| matches!(*end, "complete" | "anchored" | "gap"))
                .ok_or_else(|| error(400, "采集状态无效"))
        })
        .transpose()?;
    match (sweep, older) {
        (Some(sweep), None) if sweep != capture_id => return Err(error(400, "采集首屏编号与轮次不匹配")),
        (Some(sweep), Some(older)) => {
            if one(db, "SELECT 1 FROM captures WHERE client_id=? AND group_id=? AND capture_id=? AND sweep_id=?",
                   params![client,group_id,older,sweep])?.is_none() {
                return Err(error(409, "上一屏采集尚未确认"));
            }
        }
        (None, Some(_)) => return Err(error(400, "缺少采集轮次")),
        _ => {}
    }
    let normalized = serde_json::to_string(&messages)?;
    db.execute(
        "INSERT INTO captures(client_id,capture_id,payload_hash,group_id,rows,sweep_id,older_than)
                VALUES(?,?,?,?,?,?,?)",
        params![
            client, capture_id, content, group_id, normalized, sweep, older
        ],
    )?;
    let mut newest_len = messages.len();
    if sweep.is_some() {
        if end.is_none() {
            return Ok(group_id.into());
        }
        let (combined, linked, latest_len) = assemble(db, client, group_id, capture_id)?;
        messages = combined;
        newest_len = latest_len;
        if !linked {
            end = Some("gap");
        }
    }
    if title != group.text("title") {
        let old_count = title_count(name, group.text("title"));
        let new_count = title_count(name, title);
        let head: Vec<Message> = serde_json::from_str(group.text("head"))?;
        let anchor = &head[head.len().saturating_sub(12)..];
        let distinct: std::collections::HashSet<_> = anchor
            .iter()
            .map(|row| (&row.sender, &row.text, &row.direction))
            .collect();
        if page["group_id"] != group_id
            || !matches!((old_count, new_count), (Some(old), Some(new)) if old != new)
            || end == Some("gap")
            || distinct.len() < 3
            || unique_anchor(&head, anchor).is_none()
            || unique_anchor(&messages, anchor).is_none()
        {
            db.execute(
                "UPDATE groups SET routable=0,history_status='gap' WHERE group_id=?",
                [group_id],
            )?;
            return Ok(group_id.into());
        }
        db.execute(
            "UPDATE groups SET title=?,routable=0 WHERE group_id=?",
            params![title, group_id],
        )?;
        db.execute("DELETE FROM member_snapshots WHERE group_id=?", [group_id])?;
    }
    let newest = &messages[messages.len() - newest_len..];
    commit_window(db, &group, &messages, newest, end, timestamp)?;
    Ok(group_id.into())
}

fn title_count(name: &str, title: &str) -> Option<u32> {
    let suffix = title.strip_prefix(name)?.trim();
    let count = suffix
        .strip_prefix('(')
        .and_then(|count| count.strip_suffix(')'))
        .or_else(|| {
            suffix
                .strip_prefix('（')
                .and_then(|count| count.strip_suffix('）'))
        })?;
    count.parse().ok().filter(|count| *count > 0)
}

fn assemble(
    db: &Connection,
    client: &str,
    group: &str,
    capture_id: &str,
) -> Result<(Vec<Message>, bool, usize)> {
    let mut windows = Vec::new();
    let mut next = Some(capture_id.to_owned());
    while let Some(id) = next {
        let page = one(db, "SELECT rows,older_than FROM captures WHERE client_id=? AND group_id=? AND capture_id=?",
                       params![client,group,id])?.ok_or_else(|| error(409, "上一屏采集尚未确认"))?;
        windows.push(serde_json::from_str::<Vec<Message>>(page.text("rows"))?);
        next = page["older_than"].as_str().map(str::to_owned);
    }
    let newest = windows.pop().unwrap();
    let newest_len = newest.len();
    let mut prefix: Vec<_> = newest.iter().take(300).cloned().collect();
    let mut parts = VecDeque::from([newest]);
    let mut linked = true;
    while let Some(mut older) = windows.pop() {
        let boundary = overlap(&older, &prefix);
        if boundary == 0 {
            linked = false;
            break;
        }
        older.truncate(older.len() - boundary);
        prefix = older.iter().chain(&prefix).take(300).cloned().collect();
        parts.push_front(older);
    }
    Ok((parts.into_iter().flatten().collect(), linked, newest_len))
}

fn commit_window(
    db: &Connection,
    group: &Record,
    messages: &[Message],
    newest: &[Message],
    mut end: Option<&str>,
    timestamp: f64,
) -> Result<()> {
    let group_id = group.text("group_id");
    let mut head: Vec<Message> = serde_json::from_str(group.text("head"))?;
    let mut oldest: Vec<Message> = serde_json::from_str(group.text("oldest"))?;
    let initial = head.is_empty();
    let (mut old, mut new) = (&messages[..0], &messages[..0]);
    if initial {
        new = messages;
        head = messages[messages.len().saturating_sub(300)..].to_vec();
        oldest = messages.iter().take(12).cloned().collect();
    } else if !messages.is_empty() {
        let anchor = &head[head.len().saturating_sub(12)..];
        if let Some(position) = unique_anchor(messages, anchor) {
            new = &messages[position + anchor.len()..];
        } else if !contains_rows(&head, messages) {
            let boundary = overlap(&head, messages);
            if boundary > 0 {
                new = &messages[boundary..];
            } else {
                end = Some("gap");
                let boundary = overlap(&head, newest);
                let anchor = &newest[..boundary];
                // Older pages can leave a gap even when the newest page continues the stored tail.
                if boundary > 0
                    && unique_anchor(&head, anchor) == Some(head.len() - boundary)
                    && unique_anchor(newest, anchor) == Some(0)
                {
                    new = &newest[boundary..];
                }
            }
        }
        if end == Some("complete") && !oldest.is_empty() {
            if let Some(start) = unique_anchor(messages, &oldest) {
                old = &messages[..start];
                if !old.is_empty() {
                    oldest = old.iter().chain(&oldest).take(12).cloned().collect();
                }
            } else {
                end = Some("gap");
            }
        }
        head.extend_from_slice(new);
        head.drain(..head.len().saturating_sub(300));
    }
    let state = match end {
        Some("gap") => "gap",
        Some("complete") => "complete",
        _ => group.text("history_status"),
    };
    let mut offset = group.int("next_offset");
    let mut insert = db.prepare_cached("INSERT INTO messages(group_id,offset,message_id,sender,text,direction,observed_at,backfilled,mention_self)
                                        VALUES(?,?,?,?,?,?,?,?,?)")?;
    for (index, row) in old.iter().chain(new).enumerate() {
        insert.execute(params![
            group_id,
            offset,
            id()?,
            row.sender,
            row.text,
            row.direction,
            timestamp,
            initial || index < old.len(),
            row.mention_self
        ])?;
        offset += 1;
    }
    db.execute(
        "UPDATE groups SET head=?,oldest=?,seen=?,history_status=?,next_offset=? WHERE group_id=?",
        params![
            serde_json::to_string(&head)?,
            serde_json::to_string(&oldest)?,
            timestamp,
            state,
            offset,
            group_id
        ],
    )?;
    if !new.is_empty() {
        reconcile_observed(db, group_id)?;
    }
    Ok(())
}

fn outgoing(db: &Connection, task: &Record) -> Result<Option<Record>> {
    let mention = task["sent_text"].as_str();
    if mention.is_none() && has_mention(task)? {
        return Ok(None);
    }
    let group_id = task.text("group_id");
    let reserved_at = task.num("reserved_at");
    let mut matched = rows(db, "SELECT m.* FROM messages m WHERE m.group_id=? AND m.direction='outgoing' AND m.backfilled=0
        AND m.observed_at BETWEEN ? AND ? AND NOT EXISTS(SELECT 1 FROM tasks t WHERE t.message_id=m.message_id)",
        params![group_id,reserved_at,reserved_at+300.])?.into_iter()
        .filter(|row| match mention {
            Some(expected) => trim_mention_tail(row.text("text")) == expected,
            None => row.text("text").trim() == task.text("text").trim(),
        });
    let Some(message) = matched.next() else {
        return Ok(None);
    };
    if matched.next().is_some() {
        return Ok(None);
    }
    let observed_at = message.num("observed_at");
    let competing = rows(
        db,
        "SELECT text,sent_text FROM tasks WHERE group_id=? AND task_id!=? AND reserved_at BETWEEN ? AND ?",
        params![
            group_id,
            task.text("task_id"),
            observed_at - 300.,
            observed_at
        ],
    )?;
    if competing.iter().any(|row| match row["sent_text"].as_str() {
        Some(expected) => trim_mention_tail(message.text("text")) == expected,
        None => {
            message.text("text").trim() == row.text("text").trim()
                || (mention.is_some() && row.text("text").trim() == task.text("text").trim())
        }
    }) {
        return Ok(None);
    }
    Ok(Some(message))
}

fn trim_mention_tail(text: &str) -> &str {
    text.trim_end_matches([' ', '\u{a0}', '\u{2005}', '\u{202f}', '\u{3000}'])
}

fn reconcile_observed(db: &Connection, group_id: &str) -> Result<()> {
    for task in rows(
        db,
        "SELECT * FROM tasks WHERE group_id=? AND message_id IS NULL AND reserved_at IS NOT NULL
         AND ((status='uncertain' AND sent_text IS NULL) OR (status='confirmed' AND sent_text IS NOT NULL))",
        [group_id],
    )? {
        if let Some(message) = outgoing(db, &task)? {
            db.execute(
                "UPDATE tasks SET status=?,message_id=?,offset=? WHERE task_id=?",
                params![
                    if task.text("status") == "confirmed" { "confirmed" } else { "observed" },
                    message.text("message_id"),
                    message.int("offset"),
                    task.text("task_id")
                ],
            )?;
        }
    }
    Ok(())
}

fn complete<'a>(db: &Connection, client: &str, result: &'a Value) -> Result<&'a str> {
    let task_id = str_field(&result["id"], "command_id", 240)?;
    let event_id = str_field(&result["event_id"], "event_id", 240)?;
    let task = one(
        db,
        "SELECT * FROM tasks WHERE task_id=? AND client_id=?",
        params![task_id, client],
    )?
    .filter(|task| result["group_id"] == task["group_id"])
    .ok_or_else(|| error(404, "回执与设备或群不匹配"))?;
    let mut status = result["state"]
        .as_str()
        .filter(|value| matches!(*value, "confirmed" | "blocked" | "uncertain"))
        .ok_or_else(|| error(400, "回执状态无效"))?;
    let reserved = task["reserved_at"].is_number();
    if matches!(task.text("status"), "queued" | "expired") || (status == "confirmed" && !reserved) {
        return Err(error(409, "命令状态与回执不匹配"));
    }
    if !remember_event(db, client, event_id, result)? {
        return Ok(task_id);
    }
    if matches!(task.text("status"), "executing" | "uncertain")
        || (task.text("status") == "observed" && status == "confirmed")
    {
        if status == "blocked" && reserved {
            status = "uncertain";
        }
        let message = if status == "confirmed" {
            outgoing(db, &task)?
        } else {
            None
        };
        let detail: String = result["detail"]
            .as_str()
            .unwrap_or("")
            .chars()
            .take(400)
            .collect();
        db.execute("UPDATE tasks SET status=?,detail=?,message_id=COALESCE(?,message_id),offset=COALESCE(?,offset) WHERE task_id=?",
                   params![status,detail,message.as_ref().map(|m| m.text("message_id")),message.as_ref().map(|m| m.int("offset")),task_id])?;
        if status == "uncertain" {
            reconcile_observed(db, task.text("group_id"))?;
        }
    }
    Ok(task_id)
}
