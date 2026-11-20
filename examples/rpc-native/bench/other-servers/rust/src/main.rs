// axum on tokio: POST /example.rpc.TodoApi/list, same JSON and gzip rule (1 KB and up) as uni
use axum::{Json, Router, extract::State, routing::post};
use serde::Serialize;
use std::{collections::BTreeMap, sync::{Arc, RwLock}};
use tower_http::compression::{CompressionLayer, predicate::SizeAbove};

#[derive(Clone, Serialize)]
struct Todo { id: u64, title: String, done: bool }

type Todos = Arc<RwLock<BTreeMap<u64, Todo>>>;

async fn list(State(todos): State<Todos>, Json(_req): Json<serde_json::Value>) -> Json<Vec<Todo>> {
    // Sorted by id, as the uni example sorts its TrieMap's values
    Json(todos.read().unwrap().values().cloned().collect())
}

#[tokio::main]
async fn main() {
    let port: u16 = std::env::args().nth(1).unwrap().parse().unwrap();
    let items: u64 = std::env::var("ITEMS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let todos: Todos = Arc::new(RwLock::new((1..=items).map(|i| (i, Todo {
        id: i,
        title: if i == 1 { "x".to_string() } else { format!("todo item number {} with some text", i - 1) },
        done: false,
    })).collect()));
    let app = Router::new()
        .route("/example.rpc.TodoApi/list", post(list))
        .layer(CompressionLayer::new().gzip(true).compress_when(SizeAbove::new(1024)))
        .with_state(todos);
    let listener = tokio::net::TcpListener::bind(("127.0.0.1", port)).await.unwrap();
    axum::serve(listener, app).await.unwrap();
}
