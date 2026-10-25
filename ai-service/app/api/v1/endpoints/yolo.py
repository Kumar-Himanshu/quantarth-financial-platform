from fastapi import APIRouter, UploadFile, File, HTTPException
from app.schemas.yolo import YOLOResult
from app.schemas.common import StandardApiResponse
from app.services.yolo_detector import yolo_detector

router = APIRouter()

@router.post("/yolo", response_model=StandardApiResponse[YOLOResult], summary="Detect document layout & visual boundaries via YOLO")
async def detect_yolo_objects(file: UploadFile = File(..., description="Document image or scan")):
    if not file.filename:
        raise HTTPException(status_code=400, detail="Filename missing in file upload request.")
        
    content = await file.read()
    if len(content) == 0:
        raise HTTPException(status_code=400, detail="Empty file uploaded.")
        
    result = yolo_detector.detect_layout(file.filename, content)
    return StandardApiResponse(data=result, message="YOLO object and layout detection completed")
