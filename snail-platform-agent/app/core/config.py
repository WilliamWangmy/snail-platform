"""全局配置。≈ @ConfigurationProperties:字段即配置项,值按 环境变量 > .env > 默认值 合并。"""

from pydantic_settings import BaseSettings, SettingsConfigDict  # 配置基类 + 来源声明


class Settings(BaseSettings):
    """配置项定义。字段名小写下划线,自动映射同名大写环境变量。"""

    app_name: str = "snail-agent"  # 应用名,供 FastAPI title 使用
    debug: bool = False  # 调试开关,控制日志级别

    # 配置来源:读取项目根 .env;环境变量天然优先于文件
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8")


settings = Settings()  # 模块级单例:全项目 import 同一份配置
