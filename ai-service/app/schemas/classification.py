from pydantic import BaseModel, Field
from typing import Dict, List, Optional

class ClassificationRequest(BaseModel):
    text: str = Field(min_length=1, description="Document body text or OCR output")

class ClassificationResult(BaseModel):
    predicted_type: str = Field(description="INVOICE, RECEIPT, BANK_STATEMENT, TAX_FILING, PAYSLIP, IDENTITY_DOC")
    confidence: float
    probabilities: Dict[str, float]
