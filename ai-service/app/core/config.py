from pydantic_settings import BaseSettings
from typing import List

class Settings(BaseSettings):
    PROJECT_NAME: str = "Financial Intelligence Platform (FIP) - AI Service"
    VERSION: str = "1.0.0"
    API_V1_STR: str = "/api/v1"
    DEBUG: bool = False
    ALLOWED_ORIGINS: List[str] = ["*"]
    
    # AI Engine Thresholds
    CONFIDENCE_THRESHOLD: float = 0.85
    OCR_MAX_FILE_SIZE_MB: int = 15

    class Config:
        case_sensitive = True
        env_file = ".env"

settings = Settings()
