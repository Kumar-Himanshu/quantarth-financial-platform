from pydantic import BaseModel, Field
from typing import List, Optional

class NERRequest(BaseModel):
    text: str = Field(min_length=1, description="Raw financial text or extracted OCR text")
    language: Optional[str] = "en"

class NamedEntity(BaseModel):
    text: str
    label: str = Field(description="ORGANIZATION, MONEY, DATE, INVOICE_NUMBER, TAX_ID, IBAN, PERSON")
    start_offset: int
    end_offset: int
    confidence: float

class NERResult(BaseModel):
    entities_count: int
    entities: List[NamedEntity]
