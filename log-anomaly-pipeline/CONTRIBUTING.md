# Contributing

## Development setup

1. Java 21, Maven 3.9+, Docker Desktop, Minikube
2. Clone and build: `mvn clean install`
3. Start local infrastructure: `docker-compose -f docker/docker-compose.yml up`

## Branch strategy

| Branch | Purpose |
|---|---|
| `main` | Stable, deployable |
| `feature/*` | New features |
| `fix/*` | Bug fixes |

## Coding standards

- Package by feature within each service, not by layer
- No Kafka I/O in service classes — producers/consumers are infrastructure; business logic is domain
- All public service methods must have unit tests
- `SlidingWindowZScoreDetector` must remain framework-free (no Spring imports)

## Commit message format

```
<type>(<scope>): <subject>

type: feat | fix | refactor | test | docs | chore
scope: ingestion | detector | alert | k8s | docker
```

Example: `feat(detector): implement sliding window Z-score algorithm`
