from typing import List
from app.schemas.recommendation import RecommendationResult, RecommendationItem

class RecommendationEngine:
    def evaluate(self, doc_type: str, amount: float, merchant: str, category: str, tax_amount: float = 0.0) -> RecommendationResult:
        recommendations: List[RecommendationItem] = []
        risk_score = 0.05
        
        # 1. Tax deduction check
        if tax_amount > 0:
            recommendations.append(RecommendationItem(
                code="INPUT_TAX_CREDIT_ELIGIBLE",
                severity="INFO",
                message=f"Tax amount of ${tax_amount:,.2f} qualifies for corporate input tax credit offset.",
                action_suggested="Tag for VAT/GST reconciliation filing."
            ))
            
        # 2. Large amount threshold
        if amount > 10000.0:
            risk_score += 0.20
            recommendations.append(RecommendationItem(
                code="HIGH_VALUE_TRANSACTION",
                severity="WARNING",
                message=f"Invoice value of ${amount:,.2f} exceeds dual-signoff threshold ($10,000.00).",
                action_suggested="Route to Financial Controller for secondary authorization."
            ))
            
        # 3. Category alignment
        if category in ["CLOUD_INFRASTRUCTURE", "SAAS_SOFTWARE"]:
            recommendations.append(RecommendationItem(
                code="SUBSCRIPTION_AUDIT",
                severity="INFO",
                message="Recurring SaaS detected. Verify license seat utilization before auto-renewal.",
                action_suggested="Cross-reference with ArthQuant seat analytics."
            ))
            
        # 4. Auto approval flag
        auto_approval = (risk_score < 0.25) and (doc_type in ["INVOICE", "RECEIPT"])
        
        return RecommendationResult(
            risk_score=round(risk_score, 3),
            auto_approval_recommended=auto_approval,
            recommendations=recommendations
        )

recommendation_engine = RecommendationEngine()
