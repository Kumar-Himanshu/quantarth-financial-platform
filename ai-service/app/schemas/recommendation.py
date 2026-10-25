from pydantic import BaseModel, Field
from typing import List, Optional

class RecommendationRequest(BaseModel):
    document_type: str
    amount: float
    merchant_name: str
    category: str
    tax_amount: Optional[float] = 0.0

class RecommendationItem(BaseModel):
    code: str = Field(description="e.g. TAX_OPTIMIZATION, RECONCILIATION_MATCH, DUPLICATE_RISK, UNUSUAL_AMOUNT")
    severity: str = Field(description="INFO, WARNING, CRITICAL")
    message: str
    action_suggested: str

class RecommendationResult(BaseModel):
    risk_score: float = Field(description="Normalized 0.0 (safe) to 1.0 (critical risk)")
    auto_approval_recommended: bool
    recommendations: List[RecommendationItem]
