from fastapi import APIRouter
from app.schemas.ner import NERRequest, NERResult
from app.schemas.common import StandardApiResponse
from app.services.ner_extractor import ner_extractor

router = APIRouter()

@router.post("/ner", response_model=StandardApiResponse[NERResult], summary="Extract financial Named Entities (NER)")
async def extract_named_entities(request: NERRequest):
    result = ner_extractor.extract_entities(request.text)
    return StandardApiResponse(data=result, message="NER entities successfully extracted")
