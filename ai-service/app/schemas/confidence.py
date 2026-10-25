from pydantic import BaseModel, Field
from typing import Dict, List, Optional

class ConfidenceEvaluationRequest(BaseModel):
    ocr_confidence: float
    yolo_confidence: float
    ner_confidence: float
    classification_confidence: float
    fields_extracted_count: int
    fields_expected_count: int = 5

class ConfidenceEvaluationResult(BaseModel):
    overall_confidence: float = Field(description="Weighted harmonic aggregate confidence [0.0 - 1.0]")
    grade: str = Field(description="HIGH_CONFIDENCE, MEDIUM_CONFIDENCE, REQUIRES_MANUAL_REVIEW")
    requires_human_in_the_loop: bool
    component_scores: Dict[str, float]
