package dev.gateway.tenant;

import dev.gateway.product.ProductRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = dev.gateway.JwtGatewayApplication.class)
@Testcontainers
@DisplayName("Database Connection Pool Sterility Concurrency Test")
class TenantContextConcurrencyTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("gatewaydb")
            .withUsername("postgres")
            .withPassword("postgres");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void overrideDataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "gateway_user");
        registry.add("spring.datasource.password", () -> "local_dev_password_only");
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                     () -> "http://localhost:9999/test-issuer");
    }

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("ThreadLocal strictly isolates tenant configurations across concurrent DB connections without pooling leaks")
    void verifyStrictConcurrencyIsolationUnderLoad() throws InterruptedException, ExecutionException {
        // High thread count ensures HikariCP is maximally stressed and connections are rapidly reused
        int threadCount = 200; 
        
        try (ExecutorService executor = Executors.newFixedThreadPool(threadCount)) {
            CountDownLatch readyLatch = new CountDownLatch(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            List<Callable<Boolean>> tasks = new ArrayList<>();

            for (int i = 0; i < threadCount; i++) {
                final UUID tenantIdForThisThread = UUID.randomUUID();

                tasks.add(() -> {
                    readyLatch.countDown();
                    // Block until all threads are perfectly aligned at the starting line
                    startLatch.await();

                    try {
                        TenantContext.setTenantId(tenantIdForThisThread);
                        
                        // Execute DB transaction!
                        // This proves that Hibernate's MultiTenantConnectionProvider correctly
                        // grabs a connection, sets the tenant, executes, and resets it flawlessly.
                        String dbTenantId = transactionTemplate.execute(status -> 
                            productRepository.getCurrentTenantSetting()
                        );
                        
                        doneLatch.countDown();
                        return tenantIdForThisThread.toString().equals(dbTenantId);
                    } finally {
                        TenantContext.clear();
                    }
                });
            }

            List<Future<Boolean>> futures = new ArrayList<>();
            for (Callable<Boolean> task : tasks) {
                futures.add(executor.submit(task));
            }

            // Wait for ALL threads to reach the barrier before releasing them.
            // Without this await(), the main thread fires startLatch.countDown()
            // before workers have reached readyLatch.countDown(), defeating the
            // purpose of the thundering-herd simultaneous-start pattern.
            readyLatch.await();

            // Release all threads simultaneously — maximize connection pool contention
            startLatch.countDown();

            doneLatch.await();

            for (Future<Boolean> future : futures) {
                assertThat(future.get()).isTrue();
            }
        }
    }
}
