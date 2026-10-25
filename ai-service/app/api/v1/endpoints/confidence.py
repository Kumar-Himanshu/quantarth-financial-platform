from fastapi import APIRouter
from app.schemas.confidence import ConfidenceEvaluationRequest, ConfidenceEvaluationResult
from app.schemas.common import StandardApiResponse
from app.services.confidence_engine import confidence_engine

router = APIRouter()

@router.post("/confidence", response_model=StandardApiResponse[ConfidenceEvaluationResult], summary="Compute aggregate confidence score")
async def evaluate_confidence_score(request: ConfidenceEvaluationRequest):
    result = confidence_engine.calculate_aggregate_confidence(
        request.ocr_confidence,
        request.yolo_confidence,
        request.ner_confidence,
        request.classification_confidence,
        request.fields_extracted_count,
        request.fields_expected_count
    )
    return StandardApiResponse(data=result, message="Confidence evaluated successfully")
