from pydantic import BaseModel, Field
from typing import List, Optional

class OCRBoundingBox(BaseModel):
    text: str
    confidence: float
    box: List[float] = Field(description="Normalized coordinates [ymin, xmin, ymax, xmax]")

class OCRResult(BaseModel):
    document_id: str
    extracted_text: str
    confidence: float
    detected_language: str = "en"
    words_count: int
    lines_count: int
    bounding_boxes: List[OCRBoundingBox]
