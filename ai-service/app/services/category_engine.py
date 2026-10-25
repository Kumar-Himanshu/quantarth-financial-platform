from app.schemas.category import CategoryResult

class CategoryEngine:
    def categorize(self, narration: str, amount: float, merchant: str = None) -> CategoryResult:
        combined = f"{narration} {merchant or ''}".lower()
        
        if any(w in combined for w in ["cloud", "aws", "gcp", "azure", "server", "hosting"]):
            return CategoryResult(
                primary_category="CLOUD_INFRASTRUCTURE",
                sub_category="COMPUTE_STORAGE",
                tax_deductible=True,
                confidence=0.98
            )
        elif any(w in combined for w in ["saas", "software", "license", "subscription", "github", "jira"]):
            return CategoryResult(
                primary_category="SAAS_SOFTWARE",
                sub_category="DEVELOPER_TOOLS",
                tax_deductible=True,
                confidence=0.96
            )
        elif any(w in combined for w in ["uber", "taxi", "flight", "hotel", "travel"]):
            return CategoryResult(
                primary_category="TRAVEL",
                sub_category="BUSINESS_TRAVEL",
                tax_deductible=True,
                confidence=0.94
            )
        elif any(w in combined for w in ["electric", "water", "internet", "broadband", "utility"]):
            return CategoryResult(
                primary_category="UTILITIES",
                sub_category="OFFICE_FACILITIES",
                tax_deductible=True,
                confidence=0.92
            )
        else:
            return CategoryResult(
                primary_category="GENERAL_OPERATIONAL",
                sub_category="MISCELLANEOUS_EXPENSE",
                tax_deductible=True,
                confidence=0.80
            )

category_engine = CategoryEngine()
