import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

/**
 * Standalone load generator for the log-anomaly-pipeline.
 *
 * Run from the load-generator directory:
 *   java LoadGenerator.java
 *   java LoadGenerator.java http://localhost:8080   (custom URL)
 *
 * Simulates 5 services cycling through realistic phases:
 *   NORMAL -> DEGRADING -> SPIKE -> RECOVERY -> NORMAL
 *
 * Each service is offset so alerts arrive staggered, not all at once.
 * Sends one batch (~15 events) per service every second.
 */
public class LoadGenerator {

    public static void main(String[] args) throws InterruptedException {
        String baseUrl = args.length > 0 ? args[0] : "http://localhost:8080";
        System.out.println("Load generator starting — target: " + baseUrl + "/api/v1/logs/batch");
        System.out.println("Press Ctrl+C to stop.\n");

        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        List<ServiceSim> services = List.of(
            new ServiceSim("checkout-service",     Phase.NORMAL,  0),
            new ServiceSim("payment-service",      Phase.NORMAL,  8),
            new ServiceSim("inventory-service",    Phase.NORMAL, 16),
            new ServiceSim("user-auth-service",    Phase.NORMAL, 24),
            new ServiceSim("notification-service", Phase.NORMAL, 32)
        );

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(services.size());

        for (ServiceSim svc : services) {
            scheduler.scheduleAtFixedRate(
                () -> tick(svc, http, baseUrl),
                svc.startOffsetSeconds(), 1, TimeUnit.SECONDS
            );
        }

        Thread.currentThread().join(); // block until Ctrl+C
    }

    // -----------------------------------------------------------------------

    static void tick(ServiceSim svc, HttpClient http, String baseUrl) {
        try {
            svc.advance();
            List<Map<String, Object>> events = svc.generateBatch();
            String json = toJson(events);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/logs/batch"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());

            System.out.printf("[%s] %-26s phase=%-12s events=%2d  HTTP %d%n",
                    Instant.now().toString().substring(11, 19),
                    svc.name(),
                    svc.phase().name(),
                    events.size(),
                    res.statusCode());

        } catch (Exception e) {
            System.err.println("Failed to send for " + svc.name() + ": " + e.getMessage());
        }
    }

    static String toJson(List<Map<String, Object>> events) {
        StringBuilder sb = new StringBuilder("{\"events\":[");
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('{');
            List<Map.Entry<String, Object>> entries = events.get(i).entrySet().stream().toList();
            for (int j = 0; j < entries.size(); j++) {
                if (j > 0) sb.append(',');
                Map.Entry<String, Object> e = entries.get(j);
                sb.append('"').append(e.getKey()).append("\":\"")
                  .append(e.getValue().toString().replace("\\", "\\\\").replace("\"", "\\\""))
                  .append('"');
            }
            sb.append('}');
        }
        sb.append("]}");
        return sb.toString();
    }
}

// ---------------------------------------------------------------------------
// Phases
// ---------------------------------------------------------------------------

enum Phase {
    NORMAL, DEGRADING, SPIKE, RECOVERY;

    double errorRate() { return switch (this) { case NORMAL -> 0.05; case DEGRADING -> 0.25; case SPIKE -> 0.70; case RECOVERY -> 0.15; }; }
    double fatalRate() { return switch (this) { case NORMAL -> 0.01; case DEGRADING -> 0.05; case SPIKE -> 0.20; case RECOVERY -> 0.03; }; }
    int    duration()  { return switch (this) { case NORMAL -> 30;   case DEGRADING -> 15;   case SPIKE -> 20;   case RECOVERY -> 20;   }; }
    Phase  next()      { return switch (this) { case NORMAL -> DEGRADING; case DEGRADING -> SPIKE; case SPIKE -> RECOVERY; case RECOVERY -> NORMAL; }; }
}

// ---------------------------------------------------------------------------
// Service simulator
// ---------------------------------------------------------------------------

class ServiceSim {
    private final String name;
    private Phase phase;
    private int ticksInPhase = 0;
    private final int startOffsetSeconds;
    private final Random rng = new Random();

    ServiceSim(String name, Phase phase, int startOffsetSeconds) {
        this.name = name;
        this.phase = phase;
        this.startOffsetSeconds = startOffsetSeconds;
    }

    String name()               { return name; }
    Phase  phase()              { return phase; }
    int    startOffsetSeconds() { return startOffsetSeconds; }

    void advance() {
        ticksInPhase++;
        if (ticksInPhase >= phase.duration()) {
            Phase prev = phase;
            phase = phase.next();
            ticksInPhase = 0;
            System.out.printf("  --> %-26s %s -> %s%n", name, prev, phase);
        }
    }

    List<Map<String, Object>> generateBatch() {
        int size = 10 + rng.nextInt(10);
        return IntStream.range(0, size).mapToObj(i -> event()).toList();
    }

    private Map<String, Object> event() {
        double r = rng.nextDouble();
        String level;
        String message;

        if (r < phase.fatalRate()) {
            level = "FATAL"; message = fatalMessage();
        } else if (r < phase.fatalRate() + phase.errorRate()) {
            level = "ERROR"; message = errorMessage();
        } else if (r < phase.fatalRate() + phase.errorRate() + 0.12) {
            level = "WARN";  message = warnMessage();
        } else if (r < 0.75) {
            level = "INFO";  message = infoMessage();
        } else {
            level = "DEBUG"; message = debugMessage();
        }

        return Map.of(
            "serviceId", name,
            "level",     level,
            "message",   message,
            "timestamp", Instant.now().toString()
        );
    }

    private String fatalMessage() {
        return pick(
            "OutOfMemoryError: Java heap space — JVM is dying",
            "Database connection pool exhausted — no connections available",
            "Unrecoverable state: corrupted transaction log",
            "Stack overflow in request handler — aborting",
            "Cannot bind to port 8080 — address already in use"
        );
    }

    private String errorMessage() {
        return pick(
            "NullPointerException in " + name + " request handler",
            "HTTP 500 calling downstream dependency",
            "Failed to deserialise response from upstream service",
            "SQLException: deadlock detected on orders table",
            "Connection timeout after 5000ms — retries exhausted",
            "Kafka publish failed: broker unreachable",
            "Invalid JWT token: signature verification failed",
            "Redis cache write failed: connection refused",
            "Request validation failed: required field missing",
            "Circuit breaker OPEN — downstream service unavailable"
        );
    }

    private String warnMessage() {
        return pick(
            "Response time degraded: 1800ms (threshold 1000ms)",
            "Retry attempt 2/3 for downstream call",
            "Cache miss rate elevated: 78%",
            "Thread pool utilisation at 85%",
            "Deprecated API endpoint called by client",
            "GC pause of 320ms detected",
            "Slow query detected: 2.1s on orders table",
            "Rate limit approaching: 850/1000 rps"
        );
    }

    private String infoMessage() {
        return pick(
            "Request processed successfully in 42ms",
            "User session created for account #" + (10000 + rng.nextInt(90000)),
            "Order #" + (100000 + rng.nextInt(900000)) + " placed successfully",
            "Cache refreshed: 1200 entries loaded",
            "Health check passed",
            "Scheduled job completed: processed 340 records",
            "Payment authorised for transaction #" + rng.nextInt(99999),
            "Notification dispatched to 14 subscribers"
        );
    }

    private String debugMessage() {
        return pick(
            "Entering handler: POST /api/v1/orders",
            "DB query executed in 3ms: SELECT * FROM sessions WHERE ...",
            "Cache hit for key: user-profile-" + rng.nextInt(9999),
            "Serialised response payload: 1.2kb",
            "Feature flag 'new-checkout-flow' evaluated: true",
            "Kafka offset committed: partition=2 offset=" + rng.nextInt(100000)
        );
    }

    private String pick(String... options) {
        return options[rng.nextInt(options.length)];
    }
}
