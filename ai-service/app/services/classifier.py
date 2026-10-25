from app.schemas.classification import ClassificationResult

class DocumentClassifier:
    def classify(self, text: str) -> ClassificationResult:
        lower_text = text.lower()
        
        # Scoring based on financial vocabulary density
        invoice_score = sum(w in lower_text for w in ["invoice", "tax invoice", "bill to", "subtotal", "due date"]) * 0.25
        receipt_score = sum(w in lower_text for w in ["receipt", "cash", "pos", "terminal", "change", "store"]) * 0.25
        bank_score = sum(w in lower_text for w in ["bank", "statement", "account balance", "debit", "credit", "ledger"]) * 0.25
        tax_score = sum(w in lower_text for w in ["w-2", "1099", "gstin", "tax filing", "assessment year", "withholding"]) * 0.25
        
        scores = {
            "INVOICE": min(0.98, max(0.05, invoice_score + 0.30 if "invoice" in lower_text else 0.10)),
            "RECEIPT": min(0.95, max(0.05, receipt_score)),
            "BANK_STATEMENT": min(0.95, max(0.05, bank_score)),
            "TAX_FILING": min(0.95, max(0.05, tax_score)),
            "PAYSLIP": 0.05,
            "IDENTITY_DOC": 0.02
        }
        
        total = sum(scores.values())
        normalized_probs = {k: round(v / total, 4) for k, v in scores.items()}
        
        best_type = max(normalized_probs, key=normalized_probs.get)
        confidence = normalized_probs[best_type]
        
        return ClassificationResult(
            predicted_type=best_type,
            confidence=confidence,
            probabilities=normalized_probs
        )

document_classifier = DocumentClassifier()
