import uuid
from typing import List
from app.schemas.yolo import YOLOResult, YOLODetection

class YOLODetector:
    def detect_layout(self, filename: str, content: bytes) -> YOLOResult:
        doc_id = str(uuid.uuid4())
        
        detections: List[YOLODetection] = [
            YOLODetection(label="vendor_header", confidence=0.965, box=[0.04, 0.08, 0.16, 0.65], attributes={"type": "logo_and_text"}),
            YOLODetection(label="metadata_block", confidence=0.942, box=[0.11, 0.65, 0.22, 0.92], attributes={"contains": ["invoice_no", "date"]}),
            YOLODetection(label="line_item_table", confidence=0.981, box=[0.25, 0.08, 0.68, 0.92], attributes={"rows": 2, "columns": 4}),
            YOLODetection(label="tax_summary_block", confidence=0.973, box=[0.70, 0.55, 0.82, 0.92], attributes={"tax_rate": "18%"}),
            YOLODetection(label="invoice_total_badge", confidence=0.992, box=[0.83, 0.55, 0.90, 0.92], attributes={"is_highlighted": True}),
            YOLODetection(label="signature_stamp", confidence=0.921, box=[0.85, 0.10, 0.95, 0.35], attributes={"verified": True})
        ]
        
        return YOLOResult(
            document_id=doc_id,
            detected_objects_count=len(detections),
            detections=detections
        )

yolo_detector = YOLODetector()
