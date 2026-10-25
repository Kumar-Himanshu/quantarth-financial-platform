from pydantic import BaseModel, Field
from typing import Dict, List, Optional

class CategoryRequest(BaseModel):
    narration: str
    amount: float
    merchant_name: Optional[str] = None

class CategoryResult(BaseModel):
    primary_category: str = Field(description="TRAVEL, SAAS_SOFTWARE, CLOUD_INFRASTRUCTURE, UTILITIES, OFFICE_SUPPLIES, PAYROLL, LOGISTICS")
    sub_category: str
    tax_deductible: bool
    confidence: float
