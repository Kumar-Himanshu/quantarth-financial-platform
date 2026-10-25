from fastapi import APIRouter
from app.api.v1.endpoints import (
    ocr,
    yolo,
    ner,
    classification,
    merchant,
    category,
    recommendation,
    confidence
)

api_router = APIRouter()

api_router.include_router(ocr.router, prefix="/ai", tags=["Optical Character Recognition (OCR)"])
api_router.include_router(yolo.router, prefix="/ai", tags=["YOLO Layout & Object Detection"])
api_router.include_router(ner.router, prefix="/ai", tags=["Named Entity Recognition (NER)"])
api_router.include_router(classification.router, prefix="/ai", tags=["Document Classification"])
api_router.include_router(merchant.router, prefix="/ai", tags=["Merchant Detection & MCC"])
api_router.include_router(category.router, prefix="/ai", tags=["Expense Category Detection"])
api_router.include_router(recommendation.router, prefix="/ai", tags=["Recommendations & Risk Evaluation"])
api_router.include_router(confidence.router, prefix="/ai", tags=["Aggregate Confidence Scoring"])
