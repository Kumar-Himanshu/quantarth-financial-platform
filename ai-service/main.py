from fastapi import FastAPI, Request, status
from fastapi.responses import JSONResponse
from fastapi.middleware.cors import CORSMiddleware
from prometheus_client import make_asgi_app
import time

from app.core.config import settings
from app.api.v1.router import api_router
from app.schemas.common import ProblemDetail

app = FastAPI(
    title=settings.PROJECT_NAME,
    description="Python FastAPI Microservice running production OCR, YOLO, NER, classification, and financial intelligence pipelines.",
    version=settings.VERSION,
    docs_url="/docs",
    openapi_url="/openapi.json"
)

# CORS Middleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.ALLOWED_ORIGINS,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Correlation and Timing Middleware
@app.middleware("http")
async def correlation_and_timing_middleware(request: Request, call_next):
    start_time = time.time()
    correlation_id = request.headers.get("X-Correlation-ID", "unknown-trace")
    
    response = await call_next(request)
    
    process_time = (time.time() - start_time) * 1000
    response.headers["X-Process-Time-Ms"] = f"{process_time:.2f}"
    response.headers["X-Correlation-ID"] = correlation_id
    return response

# Global RFC 7807 Exception Handler
@app.exception_handler(Exception)
async def global_exception_handler(request: Request, exc: Exception):
    problem = ProblemDetail(
        title="Internal Processing Error",
        status=status.HTTP_500_INTERNAL_SERVER_ERROR,
        detail=str(exc)
    )
    return JSONResponse(
        status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
        content=problem.model_dump(mode="json"),
        headers={"Content-Type": "application/problem+json"}
    )

# Health Check
@app.get("/health", tags=["System Observability"])
async def health_check():
    return {
        "status": "UP",
        "service": "ai-service",
        "version": settings.VERSION
    }

# Prometheus metrics ASGI mount
metrics_app = make_asgi_app()
app.mount("/metrics", metrics_app)

# Include v1 API routes
app.include_router(api_router, prefix=settings.API_V1_STR)

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=settings.DEBUG)
