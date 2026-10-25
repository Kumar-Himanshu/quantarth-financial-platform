from fastapi import APIRouter
from app.schemas.recommendation import RecommendationRequest, RecommendationResult
from app.schemas.common import StandardApiResponse
from app.services.recommender import recommendation_engine

router = APIRouter()

@router.post("/recommendation", response_model=StandardApiResponse[RecommendationResult], summary="Generate intelligence recommendations and risk evaluation")
async def generate_recommendations(request: RecommendationRequest):
    result = recommendation_engine.evaluate(
        request.document_type,
        request.amount,
        request.merchant_name,
        request.category,
        request.tax_amount or 0.0
    )
    return StandardApiResponse(data=result, message="Recommendations generated successfully")
