import pytest
from fastapi.testclient import TestClient
from main import app

client = TestClient(app)

def test_health_check():
    response = client.get("/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "UP"
    assert data["service"] == "ai-service"

def test_ocr_endpoint():
    files = {"file": ("test_invoice.png", b"fake-invoice-image-bytes", "image/png")}
    response = client.post("/api/v1/ai/ocr", files=files)
    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert "TAX INVOICE" in body["data"]["extracted_text"]
    assert body["data"]["confidence"] > 0.90
    assert len(body["data"]["bounding_boxes"]) > 0

def test_yolo_endpoint():
    files = {"file": ("test_doc.png", b"fake-doc-bytes", "image/png")}
    response = client.post("/api/v1/ai/yolo", files=files)
    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert body["data"]["detected_objects_count"] > 0
    labels = [d["label"] for d in body["data"]["detections"]]
    assert "vendor_header" in labels
    assert "invoice_total_badge" in labels

def test_ner_endpoint():
    payload = {"text": "Invoice from ACME Enterprise Cloud Technologies Ltd dated 2026-10-25 with total $11,800.00."}
    response = client.post("/api/v1/ai/ner", json=payload)
    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    labels = [e["label"] for e in body["data"]["entities"]]
    assert "MONEY" in labels
    assert "DATE" in labels
    assert "ORGANIZATION" in labels

def test_classification_endpoint():
    payload = {"text": "Tax Invoice No INV-990 subtotal 1000 due date 2026-11-01"}
    response = client.post("/api/v1/ai/classify", json=payload)
    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert body["data"]["predicted_type"] == "INVOICE"
    assert body["data"]["confidence"] > 0.50

def test_merchant_endpoint():
    payload = {"raw_text": "Payment received from AWS Cloud Hosting Services London UK"}
    response = client.post("/api/v1/ai/merchant", json=payload)
    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert body["data"]["normalized_name"] == "Amazon Web Services Inc"
    assert body["data"]["mcc_code"] == "5734"

def test_category_endpoint():
    payload = {"narration": "Monthly subscription for GitHub Enterprise", "amount": 250.0, "merchant_name": "GitHub Inc"}
    response = client.post("/api/v1/ai/category", json=payload)
    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert body["data"]["primary_category"] == "SAAS_SOFTWARE"
    assert body["data"]["tax_deductible"] is True

def test_recommendation_endpoint():
    payload = {
        "document_type": "INVOICE",
        "amount": 15000.0,
        "merchant_name": "ACME Cloud",
        "category": "CLOUD_INFRASTRUCTURE",
        "tax_amount": 2700.0
    }
    response = client.post("/api/v1/ai/recommendation", json=payload)
    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert body["data"]["risk_score"] > 0.1
    codes = [r["code"] for r in body["data"]["recommendations"]]
    assert "HIGH_VALUE_TRANSACTION" in codes
    assert "INPUT_TAX_CREDIT_ELIGIBLE" in codes

def test_confidence_endpoint():
    payload = {
        "ocr_confidence": 0.98,
        "yolo_confidence": 0.95,
        "ner_confidence": 0.96,
        "classification_confidence": 0.94,
        "fields_extracted_count": 5,
        "fields_expected_count": 5
    }
    response = client.post("/api/v1/ai/confidence", json=payload)
    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert body["data"]["overall_confidence"] >= 0.90
    assert body["data"]["grade"] == "HIGH_CONFIDENCE"
    assert body["data"]["requires_human_in_the_loop"] is False
