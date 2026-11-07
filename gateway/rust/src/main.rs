mod capture;
mod db;
mod gateway;
mod key_seal;

use axum::{
    Router,
    body::{Body, to_bytes},
    extract::{ConnectInfo, State},
    http::{HeaderMap, Method, Request, StatusCode, Uri, header},
    response::{IntoResponse, Response},
    routing::any,
};
use clap::Parser;
use db::{Error, Result, error};
use gateway::Gateway;
use serde::Deserialize;
use serde_json::{Value, json};
use std::{
    collections::HashMap,
    net::{IpAddr, SocketAddr, TcpListener},
    path::PathBuf,
    sync::Arc,
};
use tokio::sync::Semaphore;

#[derive(Parser)]
struct Args {
    #[arg(long)]
    config: PathBuf,
    #[arg(long)]
    database: Option<PathBuf>,
    #[arg(long, default_value = "0.0.0.0")]
    host: IpAddr,
    /// Web and third-party API port.
    #[arg(long, default_value_t = 8765)]
    port: u16,
    /// Android device API port.
    #[arg(long, default_value_t = 8766)]
    device_port: u16,
    #[arg(long, requires = "key")]
    cert: Option<PathBuf>,
    #[arg(long, requires = "cert")]
    key: Option<PathBuf>,
    #[arg(long, default_value_t = 8, value_parser = clap::value_parser!(u32).range(1..=128))]
    readers: u32,
}

#[derive(Deserialize)]
struct Config {
    admin_token: String,
    public_web_url: Option<String>,
    web_session_days: Option<u32>,
}

struct PublicWeb {
    authority: String,
    origin: String,
    cookie_path: String,
    secure: bool,
}

impl PublicWeb {
    fn parse(value: &str) -> std::result::Result<Self, &'static str> {
        let invalid = "public_web_url 必须是 HTTP(S) 地址，路径使用普通 URL 字符且不能包含空段";
        let uri: Uri = value.parse().map_err(|_| invalid)?;
        let scheme = match uri.scheme_str() {
            Some("http") => "http",
            Some("https") => "https",
            _ => return Err(invalid),
        };
        let authority = uri.authority().ok_or(invalid)?;
        let host = authority.host().to_ascii_lowercase();
        let port = authority.port_u16();
        let raw_authority = match port {
            Some(port) => format!("{host}:{port}"),
            None => host.clone(),
        };
        if host.is_empty()
            || host.contains('%')
            || !authority.as_str().eq_ignore_ascii_case(&raw_authority)
            || port == Some(0)
            || uri.query().is_some()
            || value.contains('#')
        {
            return Err(invalid);
        }

        let prefix = uri.path().strip_suffix('/').unwrap_or(uri.path());
        if !prefix.is_empty()
            && prefix[1..].split('/').any(|segment| {
                segment.is_empty()
                    || matches!(segment, "." | "..")
                    || !segment.bytes().all(|c| c.is_ascii_alphanumeric() || b"-._~".contains(&c))
            })
        {
            return Err(invalid);
        }
        let secure = scheme == "https";
        let default_port = if secure { 443 } else { 80 };
        let authority = if port == Some(default_port) {
            host
        } else {
            raw_authority
        };
        Ok(Self {
            origin: format!("{scheme}://{authority}"),
            authority,
            cookie_path: format!("{prefix}/v1"),
            secure,
        })
    }
}

#[derive(Clone)]
struct App {
    gateway: Arc<Gateway>,
    readers: Arc<Semaphore>,
    writers: Arc<Semaphore>,
    port: u16,
    tls: bool,
    device_only: bool,
    public_web: Option<Arc<PublicWeb>>,
    web_session_max_age: u32,
}

pub(crate) fn header_value<'a>(headers: &'a HeaderMap, name: &str) -> &'a str {
    headers
        .get(name)
        .and_then(|value| value.to_str().ok())
        .unwrap_or("")
}

fn browser(peer: SocketAddr, method: &Method, headers: &HeaderMap, app: &App) -> bool {
    if !peer.ip().is_loopback() {
        return false;
    }
    let host = header_value(headers, "host");
    let origin = header_value(headers, "origin");
    if let Some(public_web) = &app.public_web {
        return host.eq_ignore_ascii_case(&public_web.authority)
            && (origin == public_web.origin
                || (method == Method::GET && !headers.contains_key("origin")));
    }
    let local =
        host == format!("127.0.0.1:{}", app.port) || host == format!("localhost:{}", app.port);
    local
        && (origin.is_empty()
            || origin == format!("{}://{host}", if app.tls { "https" } else { "http" }))
}

fn cookie(headers: &HeaderMap) -> &str {
    header_value(headers, "cookie")
        .split(';')
        .filter_map(|part| part.trim().split_once('='))
        .find_map(|(name, value)| (name == "gateway_session").then_some(value))
        .unwrap_or("")
}

struct Reply {
    status: u16,
    data: Value,
    cookie: Option<String>,
}
impl Reply {
    fn into_response(self) -> Response {
        let status = StatusCode::from_u16(self.status).unwrap_or(StatusCode::INTERNAL_SERVER_ERROR);
        let mut response = if status == StatusCode::NO_CONTENT {
            status.into_response()
        } else {
            (status, axum::Json(self.data)).into_response()
        };
        if let Some(cookie) = self.cookie {
            response
                .headers_mut()
                .insert(header::SET_COOKIE, cookie.parse().unwrap());
        }
        response
    }
}

async fn handle(
    State(app): State<App>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    req: Request<Body>,
) -> Response {
    let mut response = match route(app, peer, req).await {
        Ok(response) => response,
        Err(Error(status, message)) => Reply {
            status,
            data: json!({"error": message}),
            cookie: None,
        }
        .into_response(),
    };
    response
        .headers_mut()
        .insert(header::CACHE_CONTROL, "no-store".parse().unwrap());
    response
        .headers_mut()
        .insert(header::X_CONTENT_TYPE_OPTIONS, "nosniff".parse().unwrap());
    response
}

async fn route(app: App, peer: SocketAddr, req: Request<Body>) -> Result<Response> {
    let (request, body) = req.into_parts();
    let is_browser = !app.device_only && browser(peer, &request.method, &request.headers, &app);
    if request.method == Method::GET && request.uri.path() == "/" {
        if app.device_only {
            return Err(error(404, "接口不存在"));
        }
        if !is_browser {
            let message = if app.public_web.is_some() {
                "请使用配置的管理页地址"
            } else {
                "仅供本机访问"
            };
            return Err(error(403, message));
        }
        return Ok(axum::response::Html(include_str!("../../v1.html")).into_response());
    }
    let path = request
        .uri
        .path()
        .trim_end_matches('/')
        .strip_prefix("/v1/")
        .filter(|path| !path.is_empty())
        .ok_or_else(|| error(404, "接口不存在"))?
        .to_owned();
    let device_route = matches!(
        path.split('/').next(),
        Some("devices" | "device-commands" | "device-results")
    );
    if device_route != app.device_only {
        return Err(error(404, "接口不存在"));
    }
    let method = request.method;
    let data: Value = if method == Method::POST {
        if header_value(&request.headers, "content-type")
            .split(';')
            .next()
            != Some("application/json")
        {
            return Err(error(400, "请求格式无效"));
        }
        let bytes = to_bytes(body, 2_000_000)
            .await
            .map_err(|_| error(400, "请求体过大或不完整"))?;
        serde_json::from_slice::<Value>(&bytes)
            .ok()
            .filter(Value::is_object)
            .ok_or_else(|| error(400, "请求格式无效"))?
    } else {
        json!({})
    };
    let query: HashMap<String, String> =
        form_urlencoded::parse(request.uri.query().unwrap_or("").as_bytes())
            .into_owned()
            .collect();
    let headers = request.headers;
    let writable = method != Method::GET;
    let permit = if writable {
        app.writers.clone()
    } else {
        app.readers.clone()
    }
    .acquire_owned()
    .await
    .map_err(|_| error(503, "服务暂忙"))?;
    let reply = tokio::task::spawn_blocking(move || {
        let _permit = permit;
        let gateway = &app.gateway;
        let bearer = header_value(&headers, "authorization").strip_prefix("Bearer ").unwrap_or("");
        let parts: Vec<&str> = path.split('/').collect();
        if is_browser && method == Method::POST && parts == ["admin", "session"] {
            let max_age = app.web_session_max_age;
            let session = gateway.browser_session(bearer, max_age)?;
            let (path, secure) = app.public_web.as_ref().map_or(("/v1", app.tls), |web| {
                (web.cookie_path.as_str(), web.secure)
            });
            return Ok(Reply { status: 200, data: json!({"ok":true}), cookie: Some(format!(
                "gateway_session={session}; Path={path}; HttpOnly; SameSite=Strict; Max-Age={max_age}{}",
                if secure { "; Secure" } else { "" })) });
        }
        let run = |db: &rusqlite::Connection| {
            let (status, data) = if device_route {
                gateway.device(db, method.as_str(), &parts, &data, bearer, &headers)?
            } else {
                let principal = gateway.principal(db, bearer, cookie(&headers), is_browser)?;
                if parts[0] == "admin" {
                    if !principal.web() { return Err(error(403, "需要后台会话")); }
                    gateway.admin(db, method.as_str(), &parts[1..], &data)?
                } else {
                    gateway.business(db, method.as_str(), &parts, &query, &data,
                                     header_value(&headers, "idempotency-key"), &principal)?
                }
            };
            Ok(Reply { status, data, cookie: None })
        };
        if writable { gateway.db.write(run) } else { gateway.db.read(run) }
    }).await.map_err(|_| error(500, "请求处理失败"))??;
    Ok(reply.into_response())
}

#[tokio::main]
async fn main() -> std::result::Result<(), Box<dyn std::error::Error>> {
    let args = Args::parse();
    if args.port != 0 && args.port == args.device_port {
        return Err("Web 端口与设备接入端口必须不同".into());
    }
    let config: Config = serde_json::from_slice(&std::fs::read(&args.config)?)?;
    if config.admin_token.trim().is_empty() {
        return Err("admin_token 不能为空".into());
    }
    let public_web = config.public_web_url.as_deref().map(PublicWeb::parse).transpose()?;
    let web_session_max_age = match config.web_session_days {
        None => 43_200,
        Some(days @ 1..=30) => days * 86_400,
        _ => return Err("web_session_days 必须在 1 到 30 之间".into()),
    };
    let path = args
        .database
        .unwrap_or_else(|| args.config.with_file_name("group-gateway-v1.sqlite3"));
    let gateway = Gateway::open(&path, config.admin_token).map_err(|error| error.1)?;
    let web_listener = TcpListener::bind((args.host, args.port))?;
    let device_listener = TcpListener::bind((args.host, args.device_port))?;
    web_listener.set_nonblocking(true)?;
    device_listener.set_nonblocking(true)?;
    let web_address = web_listener.local_addr()?;
    let device_address = device_listener.local_addr()?;
    let tls = if let (Some(cert), Some(key)) = (args.cert, args.key) {
        rustls::crypto::ring::default_provider()
            .install_default()
            .map_err(|_| "无法初始化 TLS")?;
        Some(axum_server::tls_rustls::RustlsConfig::from_pem_file(cert, key).await?)
    } else {
        None
    };
    let web_app = App {
        gateway: Arc::new(gateway),
        readers: Arc::new(Semaphore::new(args.readers as usize)),
        writers: Arc::new(Semaphore::new(1)),
        port: web_address.port(),
        tls: tls.is_some(),
        device_only: false,
        public_web: public_web.map(Arc::new),
        web_session_max_age,
    };
    let device_app = App {
        port: device_address.port(),
        device_only: true,
        ..web_app.clone()
    };
    let scheme = if tls.is_some() { "https" } else { "http" };
    println!("listening on {scheme}://{web_address}");
    println!("device listening on {scheme}://{device_address}");
    tokio::try_join!(
        serve(web_listener, web_app, tls.clone()),
        serve(device_listener, device_app, tls),
    )?;
    Ok(())
}

async fn serve(
    listener: TcpListener,
    app: App,
    tls: Option<axum_server::tls_rustls::RustlsConfig>,
) -> std::io::Result<()> {
    let app = Router::new().fallback(any(handle)).with_state(app);
    let make = app.into_make_service_with_connect_info::<SocketAddr>();
    if let Some(config) = tls {
        axum_server::from_tcp_rustls(listener, config)?
            .serve(make)
            .await
    } else {
        axum::serve(tokio::net::TcpListener::from_std(listener)?, make).await
    }
}
