from pydantic import BaseModel, Field
from typing import Generic, TypeVar, Optional, List
from datetime import datetime

T = TypeVar("T")

class StandardApiResponse(BaseModel, Generic[T]):
    success: bool = True
    message: str = "Operation completed successfully"
    data: T
    timestamp: datetime = Field(default_factory=datetime.utcnow)

class ProblemDetail(BaseModel):
    type: str = "https://fip.com/errors/ai-processing-error"
    title: str
    status: int
    detail: str
    timestamp: datetime = Field(default_factory=datetime.utcnow)
