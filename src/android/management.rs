//! Process-local management transport. No network listener is started.

use anyhow::{bail, Context, Result};
use axum::{body::Body, http::Request};
use once_cell::sync::OnceCell;
use serde_json::{json, Value};
use std::path::{Path, PathBuf};
use tokio::runtime::Runtime;
use tower::ServiceExt;

struct Session {
    root: PathBuf,
    runtime: Runtime,
    state: crate::api::AppState,
}

static SESSION: OnceCell<Session> = OnceCell::new();

fn session(root: &str, native_dir: &str) -> Result<&'static Session> {
    let root = PathBuf::from(root);
    if !root.is_absolute() || !Path::new(native_dir).is_absolute() {
        bail!("Android storage and native library directories must be absolute");
    }
    let session = SESSION.get_or_try_init(|| -> Result<Session> {
        std::fs::create_dir_all(&root)?;
        std::env::set_var("XDG_DATA_HOME", &root);
        std::env::set_var("TMPDIR", root.join("tmp"));
        std::fs::create_dir_all(root.join("tmp"))?;
        std::env::set_var(
            crate::kernel::KernelKind::Mihomo.environment_variable(),
            Path::new(native_dir).join("libmihomo.so"),
        );
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()?;
        let state = runtime.block_on(async {
            let db = crate::db::Database::connect(&root.join("vproxy/vproxy.db").to_string_lossy()).await?;
            sqlx::query("CREATE TABLE IF NOT EXISTS android_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                .execute(&db.pool).await?;
            let migrated: Option<String> = sqlx::query_scalar("SELECT value FROM android_meta WHERE key='v1_migrated'")
                .fetch_optional(&db.pool).await?;
            if migrated.is_none() {
                let legacy_path = root.join("nodes.json");
                if legacy_path.is_file() {
                    let nodes: Vec<crate::models::Node> = serde_json::from_slice(&std::fs::read(&legacy_path)?)
                        .context("Read v1 nodes; the original file has been preserved")?;
                    db.import_nodes(&nodes).await?;
                }
                let mut config = db.get_proxy_config().await?;
                config.mode = crate::proxy::ProxyMode::None;
                config.routing_mode = crate::proxy::RoutingMode::Global;
                db.update_proxy_config(&config).await?;
                db.update_settings(serde_json::from_value(json!({"auto_fetch_enabled":false,
                    "auto_speedtest_enabled":false,"http_server_enabled":false,
                    "auto_prepare_kernel":false,"speedtest_concurrency":4}))?).await?;
                sqlx::query("INSERT INTO android_meta(key,value) VALUES('v1_migrated','1')")
                    .execute(&db.pool).await?;
            }
            let tasks = crate::tasks::TaskTracker::default();
            let collector = crate::collector::Collector::new(db.clone(), tasks.clone())?;
            let speedtester = crate::speedtest::SpeedTester::new(db.clone(), tasks.clone());
            let proxy_manager = crate::proxy::ProxyManager::new(db.get_proxy_config().await?);
            Ok::<_, anyhow::Error>(crate::api::AppState {
                db, collector, speedtester, tasks, proxy_manager,
                kernel_manager: crate::kernel::KernelManager,
            })
        })?;
        Ok(Session { root: root.clone(), runtime, state })
    })?;
    if session.root != root {
        bail!("Android management is already bound to a different private directory");
    }
    Ok(session)
}

pub(super) fn request(
    root: &str,
    native_dir: &str,
    method: &str,
    path: &str,
    body: &str,
) -> Result<String> {
    if body.len() > 8 * 1024 * 1024 {
        bail!("Management request exceeds 8 MiB");
    }
    if !(path.starts_with("/api/") || path.starts_with("/android/")) || path.contains("://") {
        bail!("Invalid local management path");
    }
    let session = session(root, native_dir)?;
    session.runtime.block_on(async {
        let payload: Value = if body.is_empty() { json!({}) } else { serde_json::from_str(body)? };
        if let Some(value) = extra(&session.state, method, path, payload).await? {
            return Ok(json!({"ok":true,"data":value}).to_string());
        }
        if path.starts_with("/android/") { bail!("Unknown Android management operation"); }
        if path.starts_with("/api/proxy/") && !path.starts_with("/api/proxy/config") {
            bail!("Proxy lifecycle is managed by Android VpnService");
        }
        if path.starts_with("/api/kernels/") {
            bail!("Android engines must be packaged with an app update; desktop engine downloads are not executable here");
        }
        let request = Request::builder().method(method).uri(path)
            .header("Content-Type", "application/json").body(Body::from(body.to_owned()))?;
        let response = crate::api::router(session.state.clone(), None).oneshot(request).await?;
        let status = response.status();
        let content_type = response.headers().get("Content-Type").and_then(|s|s.to_str().ok()).unwrap_or("").to_owned();
        let bytes = axum::body::to_bytes(response.into_body(), 16 * 1024 * 1024).await?;
        if content_type.contains("application/json") {
            return Ok(String::from_utf8(bytes.to_vec())?);
        }
        let text = String::from_utf8(bytes.to_vec())?;
        Ok(json!({"ok":status.is_success(),"message":if status.is_success(){"ok"}else{status.as_str()},"data":{"text":text}}).to_string())
    })
}

fn string<'a>(value: &'a Value, key: &str) -> Result<&'a str> {
    value[key]
        .as_str()
        .ok_or_else(|| anyhow::anyhow!("Missing {key}"))
}

async fn extra(
    state: &crate::api::AppState,
    method: &str,
    path: &str,
    body: Value,
) -> Result<Option<Value>> {
    let db = &state.db;
    let result = match (method, path) {
        ("GET", "/android/init") => json!({"version":2,"stats":db.stats().await?}),
        ("POST", "/android/import") => {
            let content = string(&body, "content")?;
            let nodes = crate::parser::parse_subscription(content, None);
            if nodes.is_empty() {
                bail!("No supported nodes were found");
            }
            json!({"imported":db.import_nodes(&nodes).await?})
        }
        ("POST", "/android/cancel") => {
            state.speedtester.cancel();
            json!({"cancelled":true})
        }
        ("POST", "/android/compile") => {
            let id = string(&body, "node_id")?;
            let node = db
                .get_node(id)
                .await?
                .ok_or_else(|| anyhow::anyhow!("Selected node no longer exists"))?;
            let mut options = db.get_proxy_config().await?;
            let kernel: crate::kernel::KernelKind =
                serde_json::from_value(body.get("kernel").cloned().unwrap_or(json!("mihomo")))?;
            options.mode = crate::proxy::ProxyMode::None;
            options.controller_secret = uuid::Uuid::new_v4().to_string();
            options.url_test |= body["candidates"].as_bool().unwrap_or(false);
            if options.url_test
                || options
                    .override_yaml
                    .as_deref()
                    .is_some_and(|s| s.contains("proxy-groups"))
            {
                let candidates = db
                    .export_nodes_filtered(None, None, false, None, None, Some(500))
                    .await?;
                options.url_test_nodes = crate::export::nodes_to_clash_proxies(&candidates);
            }
            if let Some(source_id) = &options.subscription_rules_source_id {
                if let Some(source) = db.get_source(source_id).await? {
                    options.subscription_rules_yaml = source.rules_yaml;
                    options.subscription_groups_yaml = source.groups_yaml;
                }
            }
            let mut mappings = Vec::new();
            for mapping in &options.port_mappings {
                if mapping.enabled {
                    if let Some(node) = db.get_node(&mapping.node_id).await? {
                        let name = node.name.clone();
                        let proxy = crate::export::nodes_to_clash_proxies(&[node])
                            .into_iter()
                            .next()
                            .context("Port mapping node is unsupported")?;
                        mappings.push((mapping.port, name, proxy));
                    }
                }
            }
            options.port_mapping_nodes = mappings;
            if options.chain_proxy_enabled {
                let entry = db
                    .get_node(options.chain_entry_node_id.as_deref().unwrap_or(""))
                    .await?;
                let exit = db
                    .get_node(options.chain_exit_node_id.as_deref().unwrap_or(""))
                    .await?;
                options.chain_nodes = Some(Box::new((
                    entry.context("Select a chain entry")?,
                    exit.context("Select a chain exit")?,
                )));
            }
            let uses_geo = options.uses_geo();
            let mut config = crate::proxy::build_kernel_config(kernel, &node, options)?;
            if kernel == crate::kernel::KernelKind::Mihomo {
                config["secret"] = json!(uuid::Uuid::new_v4().to_string());
                if config["mode"] == "global" {
                    config["mode"] = json!("rule");
                    config["rules"] = json!(["MATCH,PROXY"]);
                }
                let policy = config["dns"]["nameserver-policy"].as_object_mut();
                if let Some(policy) = policy.filter(|_| !uses_geo) {
                    policy.retain(|k, _| !k.starts_with("geosite:"));
                }
                config["_vproxy_android_v2"] = json!(true);
                config["_vproxy_geo_dir"] = json!(crate::geo::mihomo_dir());
                config["_vproxy_geo_required"] = json!(uses_geo);
                config["_vproxy_geo_ready"] =
                    json!(crate::geo::geo_ready(&crate::geo::mihomo_dir()));
            }
            json!({"config":config})
        }
        ("POST", "/android/tcp-result") => {
            db.update_node_speed(
                string(&body, "id")?,
                body["ok"].as_bool().unwrap_or(false),
                body["latency_ms"].as_i64(),
                None,
                None,
                Some("tcp_only"),
            )
            .await?;
            json!({"stored":true})
        }
        ("GET", "/android/rules") => {
            let options = db.get_proxy_config().await?;
            json!({"text":crate::proxy::rule_io::export_lines(&options.rules).map_err(anyhow::Error::msg)?.join("\n")})
        }
        ("POST", "/android/rules") => {
            let parsed = crate::proxy::rule_io::parse_rules_text(string(&body, "text")?);
            if !parsed.unsupported.is_empty() || !parsed.unparsed.is_empty() {
                bail!("{}; no rules were changed", parsed.summary());
            }
            if parsed
                .rules
                .iter()
                .any(|r| matches!(r.matcher, crate::proxy::RuleMatcher::ProcessName(_)))
            {
                bail!("Process-name rules are unavailable with ordinary Android app permissions; use app VPN exclusions instead");
            }
            let summary = parsed.summary();
            let mut options = db.get_proxy_config().await?;
            options.rules = parsed.rules;
            db.update_proxy_config(&options).await?;
            state.proxy_manager.update_config(options).await?;
            json!({"message":summary})
        }
        ("POST", "/android/geo") => {
            json!({"message":crate::geo::provision(&crate::geo::mihomo_dir(),None,false).await?})
        }
        ("POST", "/android/warp") => {
            let mut nodes = crate::parser::warp_masque::register_warp_masque_native(None).await?;
            let source = if let Some(source) = db
                .list_sources()
                .await?
                .into_iter()
                .find(|s| s.url_template == "warp://native")
            {
                source
            } else {
                db.create_source(crate::models::CreateSource {
                    name: "Cloudflare WARP MASQUE".into(),
                    url_template: "warp://native".into(),
                    enabled: false,
                    remark: Some("Manual native registration".into()),
                    user_agent: None,
                })
                .await?
            };
            for node in &mut nodes {
                node.source_id = Some(source.id.clone());
            }
            let imported = db.replace_source_nodes(&source.id, &nodes).await?;
            db.mark_source_fetched(&source.id, "ok", imported as i64)
                .await?;
            json!({"imported":imported})
        }
        ("POST", "/android/ip-risk") => {
            let proxy = body["proxy_url"]
                .as_str()
                .context("Connect VPN before checking egress IP")?;
            if !proxy.starts_with("http://127.0.0.1:") {
                bail!("Only the local engine can be used for egress inspection");
            }
            let risk =
                crate::ip_risk::lookup_egress(&crate::ip_risk::direct_client(), proxy).await?;
            json!({"ip":risk.ip,"country":risk.country,"org":risk.org,"datacenter":risk.datacenter,"public_proxy":risk.public_proxy,"verdict":risk.verdict})
        }
        ("POST", "/android/publish") => {
            let settings = db.get_settings().await?;
            let base = settings.publisher_api.trim().trim_end_matches('/');
            let url = url::Url::parse(base).context("Configure a publisher HTTPS URL first")?;
            if url.scheme() != "https"
                && !(url.scheme() == "http"
                    && matches!(url.host_str(), Some("127.0.0.1" | "localhost")))
            {
                bail!("Publisher credentials require HTTPS except loopback");
            }
            if settings.publisher_token.is_empty() {
                bail!("Configure a publisher token first");
            }
            let (nodes, excluded) = db
                .export_pushable_nodes(Some(2000), &settings.exclude_keywords)
                .await?;
            if nodes.is_empty() {
                bail!("No nodes have passed the real proxy verification required for publishing");
            }
            let response = reqwest::Client::new()
                .post(format!("{base}/api/push/nodes"))
                .bearer_auth(&settings.publisher_token)
                .json(&json!({"nodes":nodes}))
                .send()
                .await?
                .error_for_status()?
                .json::<Value>()
                .await?;
            json!({"sent":nodes.len(),"excluded":excluded,"response":response})
        }
        ("GET", "/android/homogeneity") => {
            serde_json::to_value(db.analyze_source_homogeneity().await?)?
        }
        ("POST", "/android/cleanup") => {
            json!({"deduplicated":db.dedupe_nodes().await?,"renamed":db.clean_and_beautify_node_names().await?})
        }
        ("GET", "/android/backup") => {
            let nodes = db
                .export_nodes_filtered(None, None, false, None, None, None)
                .await?;
            json!({"format":"vproxy-android-v2","sources":db.list_sources().await?,
                "nodes":nodes,"proxy":db.get_proxy_config().await?,"settings":db.get_settings().await?})
        }
        ("POST", "/android/restore") => {
            if body["format"] != "vproxy-android-v2" {
                bail!("Unsupported backup format");
            }
            let nodes: Vec<crate::models::Node> = serde_json::from_value(body["nodes"].clone())?;
            let options: crate::proxy::MihomoConfigOptions =
                serde_json::from_value(body["proxy"].clone())?;
            options.validate()?;
            let sources: Vec<crate::models::Source> =
                serde_json::from_value(body["sources"].clone())?;
            let settings: Option<crate::models::UpdateSettings> = if body["settings"].is_object() {
                Some(serde_json::from_value(body["settings"].clone())?)
            } else {
                None
            };
            let mut existing = db.list_sources().await?;
            let mut source_ids = std::collections::HashMap::new();
            for old in sources {
                if !old.is_imported() {
                    let new = if let Some(found) =
                        existing.iter().find(|s| s.url_template == old.url_template)
                    {
                        found.clone()
                    } else {
                        db.create_source(crate::models::CreateSource {
                            name: old.name.clone(),
                            url_template: old.url_template.clone(),
                            enabled: old.enabled,
                            remark: old.remark.clone(),
                            user_agent: old.user_agent.clone(),
                        })
                        .await?
                    };
                    db.update_source(&new.id,serde_json::from_value(json!({"name":old.name,"enabled":old.enabled,"remark":old.remark,"user_agent":old.user_agent,"push_disabled":old.push_disabled}))?).await?;
                    db.update_source_rules_and_groups(
                        &new.id,
                        old.rules_yaml.as_deref(),
                        old.groups_yaml.as_deref(),
                    )
                    .await?;
                    existing.push(new.clone());
                    source_ids.insert(old.id, new.id);
                }
            }
            let mut groups = std::collections::HashMap::<String, Vec<crate::models::Node>>::new();
            let mut local = Vec::new();
            for mut node in nodes {
                if let Some(id) = node.source_id.as_ref().and_then(|id| source_ids.get(id)) {
                    let id = id.clone();
                    node.source_id = Some(id.clone());
                    groups.entry(id).or_default().push(node);
                } else {
                    node.source_id = None;
                    local.push(node);
                }
            }
            let mut imported = db.import_nodes(&local).await?;
            for (id, nodes) in groups {
                imported += db.accumulate_source_nodes(&id, &nodes).await?;
            }
            let mut options = options;
            if let Some(id) = &options.subscription_rules_source_id {
                options.subscription_rules_source_id = source_ids.get(id).cloned();
            }
            db.update_proxy_config(&options).await?;
            state.proxy_manager.update_config(options).await?;
            if let Some(settings) = settings {
                db.update_settings(settings).await?;
            }
            json!({"imported":imported,"sources":source_ids.len()})
        }
        ("GET", "/android/history") => {
            let health = db.recent_egress_health(120).await?.into_iter().map(|r|json!({"at":r.at,"ok":r.ok,"latency_ms":r.latency_ms,"node_id":r.node_id,"kind":r.kind})).collect::<Vec<_>>();
            json!({"health":health,"fetches":db.fetch_history_all(30).await?.into_iter().map(|(id,r)|json!({"source_id":id,"at":r.at,"ok":r.ok,"node_count":r.node_count})).collect::<Vec<_>>()})
        }
        ("POST", "/android/health") => {
            db.record_egress_health(
                body["ok"].as_bool().unwrap_or(false),
                body["latency_ms"].as_u64(),
                body["error"].as_str(),
                body["node_id"].as_str(),
                body["node_name"].as_str(),
                "android_proxy",
            )
            .await?;
            json!({"stored":true})
        }
        ("POST", "/android/tick") => {
            let settings = db.get_settings().await?;
            let due = settings
                .last_auto_fetch_at
                .map(|t| {
                    chrono::Utc::now().signed_duration_since(t).num_minutes()
                        >= i64::from(settings.auto_fetch_interval_min)
                })
                .unwrap_or(true);
            if settings.auto_fetch_enabled && due {
                let result = state.collector.fetch_all_enabled().await?;
                db.touch_auto_fetch().await?;
                let verification = if settings.auto_speedtest_enabled {
                    let opts = crate::speedtest::SpeedTestOptions {
                        concurrency: settings.speedtest_concurrency.min(8),
                        timeout_ms: settings.speedtest_timeout_ms,
                        measure_download: settings.speedtest_download,
                        download_test_ms: settings.download_test_ms,
                        tcp_ping_only: settings.speedtest_tcp_ping_only,
                        ..Default::default()
                    };
                    let summary = state.speedtester.test_all_sources(opts).await?;
                    db.touch_auto_speedtest().await?;
                    Some(summary)
                } else {
                    None
                };
                json!({"fetched":result,"verification":verification})
            } else {
                json!({"fetched":[]})
            }
        }
        _ => return Ok(None),
    };
    Ok(Some(result))
}
