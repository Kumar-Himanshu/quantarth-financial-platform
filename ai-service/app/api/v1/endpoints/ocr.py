from fastapi import APIRouter, UploadFile, File, HTTPException
from app.schemas.ocr import OCRResult
from app.schemas.common import StandardApiResponse
from app.services.ocr_engine import ocr_engine

router = APIRouter()

@router.post("/ocr", response_model=StandardApiResponse[OCRResult], summary="Perform optical character recognition (OCR)")
async def extract_ocr(file: UploadFile = File(..., description="Image or PDF financial document")):
    if not file.filename:
        raise HTTPException(status_code=400, detail="Filename missing in file upload request.")
    
    content = await file.read()
    if len(content) == 0:
        raise HTTPException(status_code=400, detail="Empty file uploaded.")
        
    result = ocr_engine.process_document(file.filename, content)
    return StandardApiResponse(data=result, message="OCR text extraction completed")
