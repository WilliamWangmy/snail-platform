"""FastAPI 应用入口。uvicorn 按模块路径 app.main 找到下方名为 app 的实例启动服务。"""

import logging

from fastapi import Depends, FastAPI, HTTPException, Security, status
from fastapi.security.api_key import APIKeyHeader

from app.core.config import settings          # 绝对导入,永远从 app. 起(第 3 课铁律)

# 日志按 debug 开关定级:上线接 LLM/后端调用前先把日志骨架立好,异常面扩大前可观测
logging.basicConfig(
    level=logging.DEBUG if settings.debug else logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
LOG = logging.getLogger("snail-agent")

# ---- 认证骨架:业务路由默认挂 verify_api_key 依赖,/health 单独豁免 ----
# 任何要对接 Java 后端 / SSE / LLM 的路由都必须在带鉴权的 router 下,
# 不要等路由写完再回头补——无鉴权的 LLM 代理等于把成本刷量口子敞开。
_API_KEY_HEADER = "X-API-Key"
_api_key_header = APIKeyHeader(name=_API_KEY_HEADER, auto_error=False)

# 部署时注入;为空表示"尚未配置密钥",此时业务路由一律拒绝而不是裸奔
API_KEY: str | None = None  # 由部署环境注入(env/secret 管理),占位值禁止入库


async def verify_api_key(api_key: str | None = Security(_api_key_header)) -> None:
    """API Key 依赖:未配置密钥或密钥不符一律 401,业务路由默认挂载。"""
    if not API_KEY or not api_key or api_key != API_KEY:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="缺少或无效的 API Key",
            headers={"WWW-Authenticate": "ApiKey"},
        )


# 交互式文档只在 debug 暴露:FastAPI 不读 settings.debug,不显式关闭就生产裸奔,
# /openapi.json 会把全部路由结构端给攻击者
app = FastAPI(
    title=settings.app_name,
    docs_url="/docs" if settings.debug else None,
    redoc_url="/redoc" if settings.debug else None,
    openapi_url="/openapi.json" if settings.debug else None,
)


@app.get("/health")                           # 注册 GET /health 路由 ≈ @GetMapping("/health")
async def health() -> dict[str, str]:         # 全项目统一 async;返回类型注解同时驱动 /docs 的响应 Schema
    return {"status": "ok"}                   # dict 自动序列化为 JSON
