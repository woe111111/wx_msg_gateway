use rusqlite::{Connection, Params, TransactionBehavior, types::ValueRef};
use serde_json::{Map, Value};
use std::{
    path::{Path, PathBuf},
    sync::Mutex,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

pub type Record = Map<String, Value>;
pub type Result<T> = std::result::Result<T, Error>;

#[derive(Debug)]
pub struct Error(pub u16, pub String);

impl From<rusqlite::Error> for Error {
    fn from(error: rusqlite::Error) -> Self {
        eprintln!("SQLite: {error}");
        match error.sqlite_error_code() {
            Some(rusqlite::ErrorCode::DatabaseBusy | rusqlite::ErrorCode::DatabaseLocked) => {
                Self(503, "数据库暂忙".into())
            }
            _ => Self(500, "数据库操作失败".into()),
        }
    }
}

impl From<serde_json::Error> for Error {
    fn from(error: serde_json::Error) -> Self {
        eprintln!("Stored JSON: {error}");
        Self(500, "存储数据格式无效".into())
    }
}

pub fn error(status: u16, message: &str) -> Error {
    Error(status, message.into())
}

pub fn now() -> f64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs_f64()
}

pub fn rows(db: &Connection, sql: &str, values: impl Params) -> Result<Vec<Record>> {
    let mut stmt = db.prepare_cached(sql)?;
    let names: Vec<String> = stmt.column_names().into_iter().map(str::to_owned).collect();
    let mut result = stmt.query(values)?;
    let mut records = Vec::new();
    while let Some(row) = result.next()? {
        let mut record = Map::new();
        for (i, name) in names.iter().enumerate() {
            let value = match row.get_ref(i)? {
                ValueRef::Null => Value::Null,
                ValueRef::Integer(n) => Value::from(n),
                ValueRef::Real(n) => Value::from(n),
                ValueRef::Text(text) => Value::from(String::from_utf8_lossy(text).into_owned()),
                ValueRef::Blob(_) => return Err(error(500, "数据库字段格式无效")),
            };
            record.insert(name.clone(), value);
        }
        records.push(record);
    }
    Ok(records)
}

pub fn one(db: &Connection, sql: &str, values: impl Params) -> Result<Option<Record>> {
    Ok(rows(db, sql, values)?.into_iter().next())
}

pub trait Fields {
    fn text(&self, key: &str) -> &str;
    fn num(&self, key: &str) -> f64;
    fn int(&self, key: &str) -> i64;
}

impl Fields for Record {
    fn text(&self, key: &str) -> &str {
        self.get(key).and_then(Value::as_str).unwrap_or("")
    }
    fn num(&self, key: &str) -> f64 {
        self.get(key).and_then(Value::as_f64).unwrap_or(0.)
    }
    fn int(&self, key: &str) -> i64 {
        self.get(key).and_then(Value::as_i64).unwrap_or(0)
    }
}

pub struct Database {
    path: PathBuf,
    readers: Mutex<Vec<Connection>>,
    writer: Mutex<Connection>,
}

fn open(path: &Path) -> Result<Connection> {
    let conn = Connection::open(path)?;
    conn.busy_timeout(Duration::from_secs(5))?;
    Ok(conn)
}

impl Database {
    pub fn open(path: &Path) -> Result<Self> {
        let mut writer = open(path)?;
        writer.execute_batch(include_str!("schema.sql"))?;
        let tx = writer.transaction_with_behavior(TransactionBehavior::Immediate)?;
        for (table, column, declaration) in [
            ("clients", "admission", "TEXT NOT NULL DEFAULT 'registered'"),
            ("groups", "oldest", "TEXT NOT NULL DEFAULT '[]'"),
            ("captures", "sweep_id", "TEXT"),
            ("captures", "older_than", "TEXT"),
            ("messages", "backfilled", "INTEGER NOT NULL DEFAULT 0"),
            (
                "messages",
                "mention_self",
                "TEXT NOT NULL DEFAULT 'unknown'",
            ),
            ("clients", "native_mentions", "INTEGER NOT NULL DEFAULT 0"),
            ("clients", "mention_sender", "INTEGER NOT NULL DEFAULT 0"),
            ("clients", "scan_paused", "INTEGER NOT NULL DEFAULT -1"),
            ("clients", "resume_id", "TEXT"),
            ("clients", "resume_until", "REAL NOT NULL DEFAULT 0"),
            ("tasks", "sent_text", "TEXT"),
            ("api_keys", "key_ciphertext", "TEXT"),
        ] {
            if !rows(&tx, &format!("PRAGMA table_info({table})"), [])?
                .iter()
                .any(|row| row.text("name") == column)
            {
                tx.execute_batch(&format!(
                    "ALTER TABLE {table} ADD COLUMN {column} {declaration}"
                ))?;
                if column == "admission" {
                    tx.execute_batch("UPDATE clients SET session=NULL,seen=0,epoch=epoch+1")?;
                }
            }
        }
        tx.execute_batch("CREATE INDEX IF NOT EXISTS group_client_page ON groups(client_id,group_id);
                          CREATE INDEX IF NOT EXISTS task_group_history ON tasks(group_id,created_at);
                          CREATE INDEX IF NOT EXISTS task_group_reservation ON tasks(group_id,reserved_at)
                              WHERE reserved_at IS NOT NULL;
                          CREATE INDEX IF NOT EXISTS message_outgoing ON messages(group_id,observed_at)
                              WHERE direction='outgoing' AND backfilled=0;")?;
        tx.commit()?;
        Ok(Self {
            path: path.into(),
            readers: Mutex::default(),
            writer: Mutex::new(writer),
        })
    }

    pub fn read<T>(&self, run: impl FnOnce(&Connection) -> Result<T>) -> Result<T> {
        let conn = self.readers.lock().unwrap().pop();
        let mut conn = match conn {
            Some(conn) => conn,
            None => {
                let conn = open(&self.path)?;
                conn.execute_batch("PRAGMA query_only=ON")?;
                conn
            }
        };
        let result = (|| {
            let tx = conn.transaction()?;
            let value = run(&tx)?;
            tx.commit()?;
            Ok(value)
        })();
        self.readers.lock().unwrap().push(conn);
        result
    }

    pub fn write<T>(&self, run: impl FnOnce(&Connection) -> Result<T>) -> Result<T> {
        let mut conn = self.writer.lock().unwrap();
        let tx = conn.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let value = run(&tx)?;
        tx.commit()?;
        Ok(value)
    }
}
