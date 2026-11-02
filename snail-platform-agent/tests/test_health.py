"""Agent 服务最小测试基线:健康检查与鉴权依赖。

pytest 基建(pytest-asyncio/respx)早已就位,但用例为零——起步阶段不建立测试基线,
后续接入 LangGraph/MySQL 后补测试的成本会指数上升。先钉死 /health 与认证骨架的行为。
"""

import asyncio

import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient

from app.main import API_KEY, app, verify_api_key

client = TestClient(app)


def test_health_is_public():
    """/health 是存活探针,必须免鉴权可访问。"""
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_docs_disabled_when_not_debug():
    """生产形态(debug=False)下交互式文档与 OpenAPI 必须关闭,不给攻击者路由地图。"""
    if settings_debug():
        pytest.skip("debug 形态下文档按设计是开启的")
    assert app.docs_url is None
    assert app.redoc_url is None
    assert app.openapi_url is None


def test_api_key_dependency_rejects_when_unconfigured():
    """API_KEY 未配置时,挂了鉴权依赖的路由必须 401——宁可拒绝也不裸奔。"""
    if API_KEY:
        pytest.skip("部署环境已注入密钥,该形态由集成环境验证")
    with pytest.raises(HTTPException) as exc_info:
        asyncio.run(verify_api_key(api_key=None))
    assert exc_info.value.status_code == 401


def settings_debug() -> bool:
    from app.core.config import settings

    return settings.debug
