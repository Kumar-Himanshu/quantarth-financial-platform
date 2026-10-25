from fastapi import APIRouter
from app.schemas.merchant import MerchantRequest, MerchantResult
from app.schemas.common import StandardApiResponse
from app.services.merchant_engine import merchant_engine

router = APIRouter()

@router.post("/merchant", response_model=StandardApiResponse[MerchantResult], summary="Detect merchant entity & MCC code")
async def detect_merchant_entity(request: MerchantRequest):
    result = merchant_engine.detect_merchant(request.raw_text)
    return StandardApiResponse(data=result, message="Merchant detection and normalization completed")
