import re
from app.schemas.merchant import MerchantResult

class MerchantEngine:
    # Catalog of known enterprise merchants and MCC codes
    KNOWN_MERCHANTS = [
        {"regex": r"aws|amazon web services", "name": "Amazon Web Services Inc", "mcc": "5734", "category": "CLOUD_INFRASTRUCTURE", "website": "https://aws.amazon.com"},
        {"regex": r"google cloud|gcp|google workspace", "name": "Google Cloud Platform", "mcc": "5734", "category": "CLOUD_INFRASTRUCTURE", "website": "https://cloud.google.com"},
        {"regex": r"microsoft|azure|msft", "name": "Microsoft Corporation", "mcc": "5734", "category": "SOFTWARE_SAAS", "website": "https://azure.microsoft.com"},
        {"regex": r"acme", "name": "ACME Enterprise Cloud Technologies Ltd", "mcc": "5734", "category": "SOFTWARE_SAAS", "website": "https://acme-cloud.example.com"},
        {"regex": r"uber|lyft|grab", "name": "Uber Technologies Inc", "mcc": "4121", "category": "TRAVEL_RIDESHARE", "website": "https://uber.com"},
        {"regex": r"delta|united|lufthansa|air india", "name": "Airline Travel Partners", "mcc": "4511", "category": "TRAVEL_AIRFARE", "website": "https://airline.example.com"},
        {"regex": r"starbucks|costa|dunkin", "name": "Starbucks Coffee", "mcc": "5814", "category": "MEALS_ENTERTAINMENT", "website": "https://starbucks.com"}
    ]

    def detect_merchant(self, raw_text: str) -> MerchantResult:
        lower = raw_text.lower()
        
        for item in self.KNOWN_MERCHANTS:
            if re.search(item["regex"], lower):
                return MerchantResult(
                    normalized_name=item["name"],
                    original_string=raw_text[:60],
                    mcc_code=item["mcc"],
                    category=item["category"],
                    confidence=0.985,
                    website=item["website"]
                )
                
        # Fallback heuristic
        cleaned_name = re.sub(r"[^A-Za-z0-9\s]", "", raw_text).strip().split("\n")[0][:40]
        return MerchantResult(
            normalized_name=cleaned_name if cleaned_name else "Generic Enterprise Vendor",
            original_string=raw_text[:60],
            mcc_code="8999",
            category="PROFESSIONAL_SERVICES",
            confidence=0.750,
            website=None
        )

merchant_engine = MerchantEngine()
