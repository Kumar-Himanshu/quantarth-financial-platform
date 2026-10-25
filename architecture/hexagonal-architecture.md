# Hexagonal Architecture (Ports and Adapters) & Domain-Driven Design (DDD)

## 1. Architectural Philosophy

Every microservice in the Financial Intelligence Platform strictly implements **Hexagonal Architecture** (Cockburn, 2005) coupled with **Domain-Driven Design (DDD)** principles:

1. **Pure Domain Core**: Contains domain entities, aggregates, and domain invariants. It has **zero dependencies** on external frameworks, Spring, JPA, Hibernate, or HTTP libraries.
2. **Ports (Interfaces)**:
   - **Inbound Ports (Driving Ports / Use Cases)**: Define what the application can do (e.g. `CreateUserUseCase`, `PluginLifecycleUseCase`, `EventIngestionUseCase`).
   - **Outbound Ports (Driven Ports)**: Define what external capabilities the application requires (e.g. `UserPersistencePort`, `EventStorePort`, `PluginEventPublisherPort`).
3. **Adapters (Implementations)**:
   - **Inbound Adapters (Driving Adapters)**: REST Controllers, RabbitMQ Event Listeners. They convert HTTP/JSON or AMQP payloads into command records and invoke Inbound Ports.
   - **Outbound Adapters (Driven Adapters)**: Spring Data JPA repositories, MapStruct entity mappers, RabbitTemplate message publishers. They implement Outbound Ports.

```mermaid
graph TD
    subgraph DrivingSide["Driving Side (Inbound)"]
        HTTP["HTTP Client / Gateway"]
        MQ_Consumer["RabbitMQ Message Consumer"]
    end

    subgraph AdaptersIn["Inbound Adapters"]
        RestController["@RestController (e.g. AuthController, PluginController)"]
        RabbitListener["@RabbitListener (e.g. RabbitMQEventListener)"]
    end

    subgraph PortsIn["Inbound Ports (Use Cases)"]
        UseCase["Use Case Interfaces (e.g. AuthenticateUserUseCase, EventIngestionUseCase)"]
    end

    subgraph HexagonCore["DOMAIN CORE (Zero External Dependencies)"]
        DomainEntities["Entities & Aggregates (e.g. User, FinancialEvent, PluginMetadata)"]
        DomainServices["Domain Rules & Business Invariants"]
    end

    subgraph PortsOut["Outbound Ports (SPI)"]
        PersistencePort["Persistence Ports (e.g. UserPersistencePort, EventStorePort)"]
        MessagingPort["Publishing Ports (e.g. EventPublishingPort)"]
    end

    subgraph AdaptersOut["Outbound Adapters"]
        JPAAdapter["JPA Adapter (Spring Data JPA + PostgreSQL)"]
        RabbitAdapter["RabbitMQ Publisher Adapter (RabbitTemplate + Exchange)"]
    end

    subgraph DrivenSide["Driven Side (Outbound)"]
        PostgreSQL[(PostgreSQL Microservice DB)]
        RabbitMQBroker[[RabbitMQ Topic Exchange]]
    end

    HTTP --> RestController
    MQ_Consumer --> RabbitListener

    RestController --> UseCase
    RabbitListener --> UseCase

    UseCase --> DomainEntities
    UseCase --> DomainServices

    DomainServices --> PortsOut

    PersistencePort --> JPAAdapter
    MessagingPort --> RabbitAdapter

    JPAAdapter --> PostgreSQL
    RabbitAdapter --> RabbitMQBroker
```

---

## 2. Inversion of Control & Dependency Rule

Dependencies strictly point **inward** toward the domain core:
```
Adapters In  ──> Inbound Ports ──> Domain Core
Adapters Out ──> Outbound Ports ──> Domain Core
```
- A change to PostgreSQL, Flyway, or RabbitMQ never impacts the business logic.
- Domain models are POJOs / Records without JPA annotations (`@Entity`, `@Table`).
- JPA entities are confined strictly to `adapters/out/persistence`.
- MapStruct handles bi-directional boundary mapping seamlessly.
