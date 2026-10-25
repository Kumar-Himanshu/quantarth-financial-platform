from fastapi import APIRouter
from app.schemas.classification import ClassificationRequest, ClassificationResult
from app.schemas.common import StandardApiResponse
from app.services.classifier import document_classifier

router = APIRouter()

@router.post("/classify", response_model=StandardApiResponse[ClassificationResult], summary="Classify document type")
async def classify_document_text(request: ClassificationRequest):
    result = document_classifier.classify(request.text)
    return StandardApiResponse(data=result, message="Document classified successfully")
