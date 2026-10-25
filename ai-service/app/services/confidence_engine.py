from typing import Dict
from app.schemas.confidence import ConfidenceEvaluationResult

class ConfidenceEngine:
    def calculate_aggregate_confidence(
        self,
        ocr_conf: float,
        yolo_conf: float,
        ner_conf: float,
        classification_conf: float,
        fields_extracted: int,
        fields_expected: int = 5
    ) -> ConfidenceEvaluationResult:
        
        # Extraction completeness ratio
        completeness = min(1.0, fields_extracted / max(1, fields_expected))
        
        # Weighted aggregate:
        # OCR: 25%, YOLO Layout: 20%, NER: 25%, Classifier: 15%, Completeness: 15%
        overall = (
            (ocr_conf * 0.25) +
            (yolo_conf * 0.20) +
            (ner_conf * 0.25) +
            (classification_conf * 0.15) +
            (completeness * 0.15)
        )
        overall = round(min(1.0, max(0.0, overall)), 4)
        
        if overall >= 0.88 and completeness >= 0.80:
            grade = "HIGH_CONFIDENCE"
            requires_hitl = False
        elif overall >= 0.70:
            grade = "MEDIUM_CONFIDENCE"
            requires_hitl = False
        else:
            grade = "REQUIRES_MANUAL_REVIEW"
            requires_hitl = True
            
        return ConfidenceEvaluationResult(
            overall_confidence=overall,
            grade=grade,
            requires_human_in_the_loop=requires_hitl,
            component_scores={
                "ocr": ocr_conf,
                "yolo_layout": yolo_conf,
                "ner_entities": ner_conf,
                "document_classification": classification_conf,
                "field_completeness": round(completeness, 4)
            }
        )

confidence_engine = ConfidenceEngine()
