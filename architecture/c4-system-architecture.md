# C4 System Architecture - Financial Intelligence Platform (FIP)

## 1. System Context Diagram (C4 Level 1)

The Financial Intelligence Platform (FIP) serves as the core foundational engine for diverse fintech products. FIP is domain-agnostic and enforces clean separation: product-specific applications consume FIP APIs without product logic leaking into the platform.

```mermaid
C4Context
    title System Context Diagram - Financial Intelligence Platform (FIP)

    Person(analyst, "Financial Analyst / Risk Officer", "Analyzes fraud, evaluates portfolio risks, and audits intelligence traces.")
    Person(admin, "Platform Administrator", "Manages tenants, user RBAC permissions, and approves dynamic plugins.")
    
    System_Boundary(consumer_boundary, "Downstream Consumer Products") {
        System(arthquant, "ArthQuant AI", "Algorithmic quant trading, risk scoring, and automated asset allocation.")
        System(docai, "Document AI SaaS", "Automated enterprise document extraction, invoice ingestion, and receipts OCR.")
        System(riskapi, "Risk APIs", "Corporate credit risk evaluation, transaction fraud scoring, dual-signoff gates.")
        System(merchantapi, "Merchant Intelligence APIs", "Transaction enrichment, MCC categorization, and vendor graph analytics.")
    }

    System(fip, "Financial Intelligence Platform (FIP)", "Enterprise-grade microservices platform providing auth, plugin sandboxing, canonical event storage, and AI processing pipelines.")

    Rel(analyst, arthquant, "Interacts via UI / Dashboards")
    Rel(analyst, docai, "Uploads invoices and statements")
    Rel(admin, fip, "Administers RBAC, plugins, and tenants")

    Rel(arthquant, fip, "Invokes AI & Plugin APIs", "REST / JSON, Bearer JWT")
    Rel(docai, fip, "Submits documents for OCR & NER", "REST / Multipart, Bearer JWT")
    Rel(riskapi, fip, "Publishes & queries financial events", "REST / RabbitMQ")
    Rel(merchantapi, fip, "Enriches transaction merchants", "REST / JSON")
```

---

## 2. Container Diagram (C4 Level 2)

```mermaid
graph TD
    subgraph ClientLayer["Consumer Layer"]
        Clients["Web Apps / SDKs / Microservices<br/>(ArthQuant, Doc AI, Risk APIs)"]
    end

    subgraph PlatformBoundary["Financial Intelligence Platform (FIP)"]
        Gateway["<b>API Gateway</b><br/>Port: 8080<br/>Spring Cloud Gateway<br/>JWT Validation, Rate Limiting, CORS, Tracing"]

        subgraph CoreServices["Microservices (Independently Deployable)"]
            Auth["<b>Auth Service</b><br/>Port: 8081<br/>Spring Boot 3, Security, JWT, RBAC<br/>PostgreSQL: fip_auth"]
            Plugin["<b>Plugin Framework</b><br/>Port: 8082<br/>Spring Boot 3, Classloader Sandbox<br/>PostgreSQL: fip_plugins"]
            EventEngine["<b>Event Engine</b><br/>Port: 8083<br/>Spring Boot 3, Event Sourcing / Store<br/>PostgreSQL: fip_events"]
            AIService["<b>AI Service</b><br/>Port: 8000<br/>Python FastAPI, OCR, YOLO, NER, Classifiers"]
        end

        subgraph EventBrokers["Messaging & Persistence"]
            RabbitMQ["<b>RabbitMQ Cluster</b><br/>Topic Exchange: fip.financial.exchange<br/>Dead Letter Exchange: fip.dlx.exchange"]
            PG_Auth[(fip_auth DB)]
            PG_Plugins[(fip_plugins DB)]
            PG_Events[(fip_events DB)]
        end

        subgraph Telemetry["Observability"]
            Prometheus["Prometheus Metrics<br/>Port: 9090"]
            Grafana["Grafana Dashboards<br/>Port: 3000"]
        end
    end

    Clients -->|HTTPS / REST| Gateway
    Gateway -->|Route /api/v1/auth/**| Auth
    Gateway -->|Route /api/v1/plugins/**| Plugin
    Gateway -->|Route /api/v1/events/**| EventEngine
    Gateway -->|Route /api/v1/ai/**| AIService

    Auth -->|Read/Write| PG_Auth
    Plugin -->|Read/Write| PG_Plugins
    EventEngine -->|Read/Write| PG_Events

    Auth -.->|Publish Canonical Events| RabbitMQ
    Plugin -.->|Publish Audit Events| RabbitMQ
    EventEngine -.->|Consume & Persist All Queues| RabbitMQ
    EventEngine -.->|Publish Events| RabbitMQ

    Prometheus -->|Scrape Actuator & Metrics| Gateway
    Prometheus -->|Scrape Actuator| Auth
    Prometheus -->|Scrape Actuator| Plugin
    Prometheus -->|Scrape Actuator| EventEngine
    Prometheus -->|Scrape /metrics| AIService
    Grafana -->|Query Metrics| Prometheus
```
