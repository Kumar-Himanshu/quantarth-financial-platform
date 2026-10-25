from pydantic import BaseModel, Field
from typing import List, Dict, Any

class YOLODetection(BaseModel):
    label: str = Field(description="e.g. invoice_total, vendor_header, line_items, signature, tax_table")
    confidence: float
    box: List[float] = Field(description="[ymin, xmin, ymax, xmax] coordinates")
    attributes: Dict[str, Any] = Field(default_factory=dict)

class YOLOResult(BaseModel):
    document_id: str
    detected_objects_count: int
    detections: List[YOLODetection]
