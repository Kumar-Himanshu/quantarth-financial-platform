from fastapi import APIRouter
from app.schemas.category import CategoryRequest, CategoryResult
from app.schemas.common import StandardApiResponse
from app.services.category_engine import category_engine

router = APIRouter()

@router.post("/category", response_model=StandardApiResponse[CategoryResult], summary="Detect transaction & expense category")
async def detect_expense_category(request: CategoryRequest):
    result = category_engine.categorize(request.narration, request.amount, request.merchant_name)
    return StandardApiResponse(data=result, message="Expense categorization completed")
