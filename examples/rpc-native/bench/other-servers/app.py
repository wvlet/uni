# FastAPI on uvicorn: POST /example.rpc.TodoApi/list, same JSON and gzip rule as uni
import os
from fastapi import FastAPI, Request
from fastapi.middleware.gzip import GZipMiddleware

items = int(os.environ.get("ITEMS", "1"))
todos = {i: {"id": i, "title": "x" if i == 1 else f"todo item number {i - 1} with some text", "done": False}
         for i in range(1, items + 1)}
app = FastAPI()
app.add_middleware(GZipMiddleware, minimum_size=1024)

@app.post("/example.rpc.TodoApi/list")
async def list_todos(request: Request):
    await request.json()
    return sorted(todos.values(), key=lambda t: t["id"])
