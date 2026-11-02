"""全局配置。≈ @ConfigurationProperties:字段即配置项,值按 环境变量 > .env > 默认值 合并。"""

from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict  # 配置基类 + 来源声明

# 以文件位置锚定 .env:相对路径按进程 cwd 解析,systemd/容器换目录启动时会静默读不到配置,
# 将来接入真实密钥时"看似生效实则没读到"这类问题非常隐蔽
BASE_DIR = Path(__file__).resolve().parent.parent.parent


class Settings(BaseSettings):
    """配置项定义。字段名小写下划线,自动映射同名大写环境变量。"""

    app_name: str = "snail-agent"  # 应用名,供 FastAPI title 使用
    debug: bool = False  # 调试开关:控制日志级别,并决定交互式文档是否暴露

    # 配置来源:读取项目根 .env(按文件位置锚定,不依赖启动目录);环境变量天然优先于文件
    model_config = SettingsConfigDict(
        env_file=BASE_DIR / ".env",
        env_file_encoding="utf-8",
    )


settings = Settings()  # 模块级单例:全项目 import 同一份配置
