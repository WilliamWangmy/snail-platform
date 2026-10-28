"""FastAPI 应用入口。uvicorn 按模块路径 app.main 找到下方名为 app 的实例启动服务。"""

from fastapi import FastAPI

from app.core.config import settings          # 绝对导入,永远从 app. 起(第 3 课铁律)

app = FastAPI(title=settings.app_name)        # ASGI 应用实例,由 uvicorn import 本模块后按变量名取走


@app.get("/health")                           # 注册 GET /health 路由 ≈ @GetMapping("/health")
async def health() -> dict[str, str]:         # 全项目统一 async;返回类型注解同时驱动 /docs 的响应 Schema
    return {"status": "ok"}                   # dict 自动序列化为 JSON