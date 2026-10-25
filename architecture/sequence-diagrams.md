# Financial Intelligence Platform (FIP) - Sequence Diagrams

## Sequence 1: Authentication & Token Rotation Flow

Demonstrates login credential validation, JWT access token generation with embedded RBAC claims, and refresh token rotation.

```mermaid
sequenceDiagram
    autonumber
    actor Client as Consumer App / User
    participant Gateway as API Gateway (:8080)
    participant AuthCtrl as AuthController (:8081)
    participant AuthService as AuthService
    participant UserRepo as UserPersistenceAdapter
    participant TokenRepo as RefreshTokenPersistenceAdapter
    participant DB as PostgreSQL (fip_auth)
    participant Rabbit as RabbitMQ (Exchange)

    Client->>Gateway: POST /api/v1/auth/login {username, password}
    Gateway->>AuthCtrl: Forward Request
    AuthCtrl->>AuthService: authenticate(Command)
    AuthService->>UserRepo: findByUsername(username)
    UserRepo->>DB: SELECT * FROM users WHERE username = ?
    DB-->>UserRepo: UserJpaEntity with Roles & Permissions
    UserRepo-->>AuthService: User Domain Object
    
    AuthService->>AuthService: passwordEncoder.matches(raw, hashed)
    alt Password Invalid
        AuthService-->>AuthCtrl: throw UnauthorizedException
        AuthCtrl-->>Client: 401 Unauthorized (RFC 7807 ProblemDetail)
    else Password Valid
        AuthService->>AuthService: generateAccessToken(user claims)
        AuthService->>TokenRepo: save(RefreshToken.create())
        TokenRepo->>DB: INSERT INTO refresh_tokens
        AuthService->>Rabbit: publish(CanonicalEvent "UserLoggedIn")
        AuthService-->>AuthCtrl: Response(accessToken, refreshToken, expiresIn)
        AuthCtrl-->>Client: 200 OK + StandardResponse<Response>
    end

    Note over Client, TokenRepo: Later: Access Token expires, Client requests refresh
    Client->>Gateway: POST /api/v1/auth/refresh {refreshToken}
    Gateway->>AuthCtrl: Forward Request
    AuthCtrl->>AuthService: refreshToken(Command)
    AuthService->>TokenRepo: findByTokenHash(refreshToken)
    TokenRepo->>DB: SELECT * FROM refresh_tokens WHERE token_hash = ?
    DB-->>TokenRepo: RefreshTokenJpaEntity
    AuthService->>TokenRepo: revoke(oldTokenId)
    AuthService->>TokenRepo: save(newToken)
    AuthService-->>Client: 200 OK + Rotated Tokens
```

---

## Sequence 2: Third-Party Plugin Registration & Sandboxed Execution Flow

Demonstrates dynamic plugin registry, bytecode verification, and safe sandbox invocation.

```mermaid
sequenceDiagram
    autonumber
    actor Dev as Developer / ArthQuant Admin
    participant Gateway as API Gateway
    participant PluginCtrl as PluginController (:8082)
    participant PluginService as PluginLifecycleService
    participant Loader as URLClassLoader Sandbox
    participant PluginDB as PostgreSQL (fip_plugins)
    participant Rabbit as RabbitMQ (fip.financial.exchange)

    Dev->>Gateway: POST /api/v1/plugins (with Bearer JWT)
    Gateway->>Gateway: Validate JWT & Claims (Require fip:admin)
    Gateway->>PluginCtrl: Forward Plugin Registration Command
    PluginCtrl->>PluginService: registerPlugin(Command)
    PluginService->>Loader: Inspect JAR & verify FipPlugin implementation
    alt Classloader Verification Success
        Loader-->>PluginService: Plugin class valid
    else Fallback Sandbox Emulation
        PluginService->>PluginService: Register Sandbox Engine
    end
    PluginService->>PluginDB: INSERT INTO plugins (status='ACTIVE')
    PluginService->>Rabbit: publish(CanonicalEvent "PluginRegistered")
    PluginService-->>PluginCtrl: PluginMetadata (ACTIVE)
    PluginCtrl-->>Dev: 201 Created + StandardResponse

    Note over Dev, Rabbit: Execution Phase (e.g. Risk Calculation)
    Dev->>Gateway: POST /api/v1/plugins/ArthQuantRiskEngine/execute {action, payload}
    Gateway->>PluginCtrl: Forward Execution Request
    PluginCtrl->>PluginService: executePlugin(name, action, payload, tenantId)
    PluginService->>Loader: plugin.execute(PluginExecutionRequest, PluginContext)
    Loader-->>PluginService: PluginExecutionResult
    PluginService->>Rabbit: publish(CanonicalEvent "PluginExecuted")
    PluginService-->>PluginCtrl: Execution Output
    PluginCtrl-->>Dev: 200 OK + StandardResponse
```

---

## Sequence 3: Financial Event Ingestion & Audit Pipeline Flow

Demonstrates asynchronous event ingestion, deduplication (idempotency), persistence in the Event Store, and broadcast distribution.

```mermaid
sequenceDiagram
    autonumber
    actor Producer as Risk API / External Service
    participant Gateway as API Gateway
    participant EventCtrl as EventController (:8083)
    participant IngestionService as EventIngestionService
    participant EventStore as EventPersistenceAdapter
    participant DB as PostgreSQL (fip_events)
    participant Rabbit as RabbitMQ (Exchange)
    participant Listener as RabbitMQEventListener

    Producer->>Gateway: POST /api/v1/events/publish {eventType, payload}
    Gateway->>EventCtrl: Forward Command
    EventCtrl->>IngestionService: ingestAndPublish(Command)
    IngestionService->>EventStore: save(FinancialEvent)
    EventStore->>DB: INSERT INTO financial_events (event_id, payload, ...)
    IngestionService->>Rabbit: convertAndSend(fip.financial.exchange, routingKey, CanonicalEvent)
    IngestionService-->>EventCtrl: FinancialEvent persisted
    EventCtrl-->>Producer: 201 Created

    Note over Rabbit, Listener: Asynchronous Broker Processing
    Rabbit-->>Listener: Deliver message from fip.audit.queue
    Listener->>EventStore: existsByEventId(canonical.eventId())
    alt Event Already Stored (Idempotency check)
        Listener->>Listener: Log duplicate, skip processing
    else New Event
        Listener->>EventStore: save(FinancialEvent)
        EventStore->>DB: INSERT INTO financial_events
    end
```

---

## Sequence 4: End-to-End Financial Intelligence Pipeline (ArthQuant / Document AI)

Demonstrates how downstream products consume the modular AI services without any product logic living in the platform core.

```mermaid
sequenceDiagram
    autonumber
    actor Client as Document AI SaaS / ArthQuant
    participant Gateway as API Gateway (:8080)
    participant AIService as AI Service (:8000)
    participant EventEngine as Event Engine (:8083)

    Client->>Gateway: POST /api/v1/ai/ocr (Multipart Document Scan)
    Gateway->>AIService: Forward File
    AIService-->>Gateway: OCR Text + Bounding Boxes + Confidence (0.98)
    Gateway-->>Client: 200 OK

    Client->>Gateway: POST /api/v1/ai/yolo (Document Layout Analysis)
    Gateway->>AIService: Forward Document Image
    AIService-->>Gateway: Detected visual objects (Vendor logo, Line items table, Total)
    Gateway-->>Client: 200 OK

    Client->>Gateway: POST /api/v1/ai/ner {text: ocr_text}
    Gateway->>AIService: Extract Entities
    AIService-->>Gateway: Entities (Amounts: $11,800.00, TaxID, Vendor: ACME)
    Gateway-->>Client: 200 OK

    Client->>Gateway: POST /api/v1/ai/classify {text: ocr_text}
    Gateway->>AIService: Run Classifier
    AIService-->>Gateway: Predicted: INVOICE (Confidence: 0.94)
    Gateway-->>Client: 200 OK

    Client->>Gateway: POST /api/v1/ai/merchant {raw_text: vendor_string}
    Gateway->>AIService: Detect Merchant
    AIService-->>Gateway: ACME Enterprise Cloud Technologies Ltd (MCC: 5734)
    Gateway-->>Client: 200 OK

    Client->>Gateway: POST /api/v1/ai/recommendation {amount, merchant, category, tax}
    Gateway->>AIService: Evaluate Risk & Recommendations
    AIService-->>Gateway: RiskScore: 0.25, Tax Offset Eligible, Secondary Signoff
    Gateway-->>Client: 200 OK

    Client->>Gateway: POST /api/v1/events/publish (Audit the completed pipeline)
    Gateway->>EventEngine: Ingest & Persist Canonical Event
    EventEngine-->>Client: 201 Created (Audit trail permanently stored)
```
