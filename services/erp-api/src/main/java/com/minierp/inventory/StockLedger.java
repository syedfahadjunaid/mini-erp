package com.minierp.inventory;

import com.minierp.shared.Actor;
import com.minierp.shared.BusinessCalendar;
import com.minierp.shared.InsufficientStockException;
import jakarta.persistence.EntityManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The only way stock changes: append movements to {@code stock_movements}.
 *
 * <p>Each insert fires the database trigger {@code apply_stock_movement}, which locks the
 * (warehouse, product) balance row, checks there is enough available stock, updates the
 * balances and records the balances after the movement. So this class stays small: it writes
 * rows, turns the trigger's "not enough stock" errors into 422 responses, and publishes a
 * {@link StockLevelChanged} event per warehouse and product.
 *
 * <p>Written with plain SQL ({@link JdbcClient}) rather than JPA: the ledger is insert-only,
 * and {@code INSERT ... RETURNING} hands back what the trigger computed in the same round trip.
 *
 * <p>{@link Propagation#MANDATORY}: posting only makes sense inside the caller's transaction,
 * so the movements commit or roll back together with the document that caused them.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class StockLedger {

    /** A movement as written, with what the trigger filled in. */
    public record PostedMovement(long id, StockMovement movement, long levelSeq, long onHandAfter, long reservedAfter) {}

    /** Orders movements so concurrent transactions lock balance rows in the same order (no deadlocks). */
    private static final Comparator<StockMovement> LOCK_ORDER = Comparator
            .comparingLong(StockMovement::warehouseId)
            .thenComparingLong(StockMovement::productId)
            .thenComparing(StockMovement::binId, Comparator.nullsFirst(Comparator.naturalOrder()));

    private final JdbcClient jdbc;
    private final EntityManager entityManager;
    private final ApplicationEventPublisher events;
    private final BusinessCalendar calendar;
    private final JsonMapper json;

    StockLedger(JdbcClient jdbc, EntityManager entityManager, ApplicationEventPublisher events, BusinessCalendar calendar,
            JsonMapper json) {
        this.jdbc = jdbc;
        this.entityManager = entityManager;
        this.events = events;
        this.calendar = calendar;
        this.json = json;
    }

    public PostedMovement post(StockMovement movement, Actor actor) {
        return post(List.of(movement), actor).getFirst();
    }

    /**
     * Posts the movements (sorted into lock order) and returns them as written.
     *
     * @throws InsufficientStockException (422) if a movement would make available stock or a
     *         bin negative; the whole transaction is then rolled back
     */
    public List<PostedMovement> post(List<StockMovement> movements, Actor actor) {
        // The movements reference document lines saved through JPA; make sure Hibernate has
        // written everything to the database before plain SQL relies on it (foreign keys).
        entityManager.flush();

        Instant now = calendar.now();
        List<PostedMovement> posted = new ArrayList<>();
        Map<String, PostedMovement> lastPerLevel = new LinkedHashMap<>();
        for (StockMovement movement : movements.stream().sorted(LOCK_ORDER).toList()) {
            PostedMovement result = insert(movement, actor, now);
            posted.add(result);
            lastPerLevel.put(movement.warehouseId() + "/" + movement.productId(), result);
        }
        for (PostedMovement last : lastPerLevel.values()) {
            StockMovement m = last.movement();
            events.publishEvent(new StockLevelChanged(m.warehouseId(), m.productId(), last.onHandAfter(),
                    last.reservedAfter(), last.onHandAfter() - last.reservedAfter(), now));
        }
        return posted;
    }

    private PostedMovement insert(StockMovement m, Actor actor, Instant now) {
        // The source column name comes from an enum (MovementType.sourceColumn), never from input.
        String sql = """
                INSERT INTO stock_movements (movement_type, warehouse_id, bin_id, product_id, on_hand_delta,
                                             reserved_delta, %s, occurred_at, recorded_by)
                VALUES (:type, :warehouse, :bin, :product, :onHand, :reserved, :source, :at, :actor)
                RETURNING id, level_seq, on_hand_after, reserved_after""".formatted(m.type().sourceColumn());
        try {
            return jdbc.sql(sql)
                    .param("type", m.type().name())
                    .param("warehouse", m.warehouseId())
                    .param("bin", m.binId())
                    .param("product", m.productId())
                    .param("onHand", m.onHandDelta())
                    .param("reserved", m.reservedDelta())
                    .param("source", m.sourceId())
                    .param("at", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                    .param("actor", actor.username())
                    .query((rs, row) -> new PostedMovement(rs.getLong("id"), m, rs.getLong("level_seq"),
                            rs.getLong("on_hand_after"), rs.getLong("reserved_after")))
                    .single();
        } catch (DataAccessException e) {
            throw translate(e, m);
        }
    }

    /**
     * The trigger signals "not enough stock" with SQLSTATE ER001 (warehouse available) or ER002
     * (bin on hand) and a JSON DETAIL with the numbers. Anything else is a real error.
     */
    private RuntimeException translate(DataAccessException e, StockMovement m) {
        ServerErrorMessage error = serverError(e);
        if (error == null || error.getSQLState() == null) {
            return e;
        }
        return switch (error.getSQLState()) {
            case "ER001" -> {
                JsonNode d = json.readTree(error.getDetail());
                yield new InsufficientStockException("insufficient_stock",
                        "Not enough available stock of product " + m.productId() + " in warehouse " + m.warehouseId()
                                + ": " + d.path("available").asLong() + " available, " + d.path("requested").asLong() + " needed.",
                        List.of(new InsufficientStockException.Shortage(m.warehouseId(), null, m.productId(),
                                d.path("available").asLong(), d.path("requested").asLong())));
            }
            case "ER002" -> {
                JsonNode d = json.readTree(error.getDetail());
                yield new InsufficientStockException("insufficient_bin_stock",
                        "Not enough stock of product " + m.productId() + " in bin " + m.binId()
                                + ": " + d.path("onHand").asLong() + " there, " + d.path("requested").asLong() + " needed.",
                        List.of(new InsufficientStockException.Shortage(m.warehouseId(), m.binId(), m.productId(),
                                d.path("onHand").asLong(), d.path("requested").asLong())));
            }
            default -> e;
        };
    }

    private static ServerErrorMessage serverError(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException psql) {
                return psql.getServerErrorMessage();
            }
            if (cause instanceof SQLException sql && sql.getNextException() instanceof PSQLException next) {
                return next.getServerErrorMessage();
            }
        }
        return null;
    }
}
