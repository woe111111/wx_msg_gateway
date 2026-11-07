PRAGMA journal_mode=WAL;
CREATE TABLE IF NOT EXISTS clients (
    client_id TEXT PRIMARY KEY, label TEXT NOT NULL, key_hash TEXT,
    instance TEXT, session TEXT, epoch INTEGER NOT NULL DEFAULT 0,
    seen REAL NOT NULL DEFAULT 0, status TEXT NOT NULL DEFAULT '',
    admission TEXT NOT NULL DEFAULT 'registered', native_mentions INTEGER NOT NULL DEFAULT 0,
    mention_sender INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS api_keys (
    key_id TEXT PRIMARY KEY, label TEXT NOT NULL, key_hash TEXT UNIQUE,
    grants TEXT NOT NULL, key_ciphertext TEXT);
CREATE TABLE IF NOT EXISTS web_sessions (
    session_hash TEXT PRIMARY KEY, admin_hash TEXT NOT NULL, expires_at REAL NOT NULL);
CREATE TABLE IF NOT EXISTS groups (
    group_id TEXT PRIMARY KEY, client_id TEXT NOT NULL, name TEXT NOT NULL, title TEXT NOT NULL,
    seen REAL, history_status TEXT NOT NULL DEFAULT 'pending',
    head TEXT NOT NULL DEFAULT '[]', oldest TEXT NOT NULL DEFAULT '[]', next_offset INTEGER NOT NULL DEFAULT 1,
    routable INTEGER NOT NULL DEFAULT 0, UNIQUE(client_id,name));
CREATE TABLE IF NOT EXISTS captures (
    client_id TEXT NOT NULL, capture_id TEXT NOT NULL, payload_hash TEXT NOT NULL,
    group_id TEXT NOT NULL, rows TEXT NOT NULL, sweep_id TEXT, older_than TEXT,
    PRIMARY KEY(client_id,capture_id));
CREATE TABLE IF NOT EXISTS messages (
    group_id TEXT NOT NULL, offset INTEGER NOT NULL, message_id TEXT NOT NULL UNIQUE,
    sender TEXT, text TEXT NOT NULL, direction TEXT NOT NULL, observed_at REAL NOT NULL,
    backfilled INTEGER NOT NULL DEFAULT 0, mention_self TEXT NOT NULL DEFAULT 'unknown',
    PRIMARY KEY(group_id,offset));
CREATE TABLE IF NOT EXISTS tasks (
    task_id TEXT PRIMARY KEY, group_id TEXT NOT NULL, client_id TEXT NOT NULL,
    principal TEXT NOT NULL, idem TEXT NOT NULL, kind TEXT NOT NULL, payload TEXT NOT NULL,
    text TEXT NOT NULL, status TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '',
    created_at REAL NOT NULL, expires_at REAL NOT NULL, claimed_at REAL,
    session TEXT, lease_until REAL, reserved_at REAL, message_id TEXT UNIQUE, offset INTEGER,
    sent_text TEXT,
    UNIQUE(principal,client_id,group_id,idem));
CREATE INDEX IF NOT EXISTS queue ON tasks(client_id,status,created_at);
CREATE TABLE IF NOT EXISTS device_events (
    client_id TEXT NOT NULL, event_id TEXT NOT NULL, payload_hash TEXT NOT NULL,
    PRIMARY KEY(client_id,event_id));

CREATE TABLE IF NOT EXISTS member_snapshots (
    group_id TEXT PRIMARY KEY, title TEXT NOT NULL, members TEXT NOT NULL);
