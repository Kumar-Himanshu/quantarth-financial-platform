from pydantic import BaseModel, Field
from typing import Optional, List

class MerchantRequest(BaseModel):
    raw_text: str = Field(min_length=1, description="Raw transaction narration or document text")

class MerchantResult(BaseModel):
    normalized_name: str
    original_string: str
    mcc_code: str = Field(description="4-digit Merchant Category Code, e.g. 5734 (Software), 5411 (Grocery)")
    category: str
    confidence: float
    website: Optional[str] = None
