package com.minierp;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base class for integration tests: the whole application against a real Postgres container,
 * called over HTTP (MockMvc) with real signed tokens. All subclasses share one Spring context,
 * and so one database.
 *
 * <p>Isolation without cleanup: every test creates its own warehouse, products and partners
 * (random codes) and only asserts on those. Global checks (the reconciliation) hold anyway,
 * because every test leaves the database consistent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, IntegrationTest.TestBeans.class})
public abstract class IntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        /** Replaces the system clock everywhere in the app. */
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }

        /** Trusts tokens signed by {@link TestTokens} (instead of Keycloak's keys). */
        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return TestTokens.decoder();
        }
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JsonMapper json;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected MutableClock clock;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    /** For things that happen in event listeners, after commit, on another thread. */
    protected static ConditionFactory eventually() {
        return Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(50));
    }

    protected static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }

    // ---- master data ----------------------------------------------------------------------------

    /** A warehouse with its RECEIVING bin and two shelves, A-01 and B-01. */
    protected record Warehouse(long id, String code, long receivingBin, long binA, long binB) {}

    protected Warehouse warehouse() throws Exception {
        String code = unique("W");
        JsonNode created = post("/api/warehouses", TestUsers.ADMIN, Map.of("code", code, "name", "Warehouse " + code));
        long id = created.get("id").asLong();
        long receiving = created.get("bins").get(0).get("id").asLong();
        long a = post("/api/warehouses/" + id + "/bins", TestUsers.ADMIN, Map.of("code", "A-01")).get("id").asLong();
        long b = post("/api/warehouses/" + id + "/bins", TestUsers.ADMIN, Map.of("code", "B-01")).get("id").asLong();
        return new Warehouse(id, code, receiving, a, b);
    }

    /** A product counted in EA, also sold in BOX of 12, with 20 % tax. */
    protected long product() throws Exception {
        return product(unique("SKU-"), "EA", Map.of("BOX", 12L), 2000);
    }

    protected long product(String sku, String baseUom, Map<String, Long> units, int taxRateBp) throws Exception {
        List<Map<String, Object>> unitList = new ArrayList<>();
        units.forEach((uom, factor) -> unitList.add(Map.of("uom", uom, "baseUnitsPerUom", factor)));
        return post("/api/products", TestUsers.ADMIN, Map.of("sku", sku, "name", "Product " + sku, "baseUom", baseUom,
                "taxRateBp", taxRateBp, "units", unitList)).get("id").asLong();
    }

    protected long supplier() throws Exception {
        String code = unique("S");
        return post("/api/suppliers", TestUsers.ADMIN, Map.of("code", code, "name", "Supplier " + code, "currency", "EUR",
                "paymentTermsDays", 30)).get("id").asLong();
    }

    protected long customer() throws Exception {
        String code = unique("C");
        return post("/api/customers", TestUsers.ADMIN, Map.of("code", code, "name", "Customer " + code,
                "addressLine1", "2 Test Lane", "postalCode", "1234", "city", "Testville", "countryCode", "NL",
                "currency", "EUR", "paymentTermsDays", 14)).get("id").asLong();
    }

    /** Puts stock into a bin with a stock adjustment (an opening balance). */
    protected void stockUp(long warehouseId, long binId, long productId, long quantity) throws Exception {
        post("/api/stock/adjustments", TestUsers.MANAGER, Map.of("warehouseId", warehouseId, "binId", binId,
                "productId", productId, "quantity", quantity, "reason", "Opening balance"));
    }

    // ---- documents --------------------------------------------------------------------------------

    /** A DRAFT sales order with one line per (productId, quantity in base units) pair, at 10.00 each. */
    protected JsonNode salesOrder(long customerId, long warehouseId, long... productAndQuantity) throws Exception {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (int i = 0; i < productAndQuantity.length; i += 2) {
            lines.add(Map.of("productId", productAndQuantity[i], "quantity", productAndQuantity[i + 1], "unitPriceMinor", 1000));
        }
        return post("/api/sales-orders", TestUsers.SALES, Map.of("customerId", customerId, "warehouseId", warehouseId,
                "lines", lines));
    }

    protected JsonNode act(String document, long id, String action, TestUsers.User user) throws Exception {
        return post("/api/" + document + "/" + id + "/actions/" + action, user, Map.of());
    }

    protected JsonNode stockLevel(long warehouseId, long productId) throws Exception {
        JsonNode levels = get("/api/stock/levels?warehouseId=" + warehouseId + "&productId=" + productId, TestUsers.WAREHOUSE);
        if (levels.isEmpty()) {
            return json.readTree("{\"onHand\":0,\"reserved\":0,\"available\":0}");
        }
        return levels.get(0);
    }

    protected static BigDecimal qty(String value) {
        return new BigDecimal(value);
    }

    // ---- HTTP helpers -----------------------------------------------------------------------------

    protected JsonNode get(String path, TestUsers.User user) throws Exception {
        return read(mvc.perform(MockMvcRequestBuilders.get(path).with(user.bearer())).andExpect(status().isOk()));
    }

    protected JsonNode post(String path, TestUsers.User user, Object body) throws Exception {
        return read(perform(path, user, body).andExpect(status().is2xxSuccessful()));
    }

    protected ResultActions perform(String path, TestUsers.User user, Object body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(path).with(user.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    protected ResultActions performPut(String path, TestUsers.User user, Object body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.put(path).with(user.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    protected JsonNode read(ResultActions result) throws Exception {
        String content = result.andReturn().getResponse().getContentAsString();
        return content.isEmpty() ? json.createObjectNode() : json.readTree(content);
    }

    // ---- concurrency ------------------------------------------------------------------------------

    /**
     * Runs the tasks on separate threads, released at the same moment by a latch, and returns
     * each one's outcome (the result, or the exception it threw).
     */
    protected static <T> List<Object> race(List<Callable<T>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(tasks.size())) {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return task.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(future.get());
            }
            return outcomes;
        }
    }
}
