package com.minierp.reporting;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exports the stock ledger (movements in a time range) to .xlsx, for auditors.
 *
 * <p>Uses POI's streaming workbook (SXSSF): only a window of 200 rows is kept in memory and the
 * rest is flushed to a temporary file, so a large export doesn't need a large heap. Rows are
 * written to the sheet as they come back from the database (a {@link RowCallbackHandler}),
 * without building a list first.
 */
@Component
class MovementsExcel {

    /** A hard cap so one request can't export the world. */
    static final int MAX_ROWS = 100_000;

    private static final String[] COLUMNS = {
        "Movement", "Occurred at (UTC)", "Type", "Warehouse", "Bin", "SKU", "On hand change", "Reserved change",
        "Seq", "On hand after", "Reserved after", "Recorded by"};

    private final JdbcClient jdbc;

    MovementsExcel(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public byte[] render(Long warehouseId, Instant from, Instant to) {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(200)) {
            Workbooks.Styles styles = Workbooks.Styles.of(workbook);
            Sheet sheet = workbook.createSheet("Stock movements");
            Row header = sheet.createRow(0);
            for (int i = 0; i < COLUMNS.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(COLUMNS[i]);
                cell.setCellStyle(styles.header());
                sheet.setColumnWidth(i, (i == 1 ? 20 : 14) * 256); // fixed widths: streamed rows can't be measured later
            }
            sheet.createFreezePane(0, 1);

            int[] rowIndex = {1};
            jdbc.sql("""
                            SELECT m.id, m.occurred_at, m.movement_type, w.code AS warehouse, b.code AS bin, p.sku,
                                   m.on_hand_delta, m.reserved_delta, m.level_seq, m.on_hand_after, m.reserved_after, m.recorded_by
                              FROM stock_movements m
                              JOIN warehouses w ON w.id = m.warehouse_id
                              JOIN products p ON p.id = m.product_id
                              LEFT JOIN bin_locations b ON b.id = m.bin_id
                             WHERE (CAST(:warehouse AS BIGINT) IS NULL OR m.warehouse_id = :warehouse)
                               AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR m.occurred_at >= :from)
                               AND (CAST(:to AS TIMESTAMPTZ) IS NULL OR m.occurred_at < :to)
                             ORDER BY m.id
                             LIMIT :limit""")
                    .param("warehouse", warehouseId)
                    .param("from", from == null ? null : OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                    .param("to", to == null ? null : OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
                    .param("limit", MAX_ROWS)
                    .query((RowCallbackHandler) rs -> {
                        Row row = sheet.createRow(rowIndex[0]++);
                        number(row, 0, rs.getLong("id"), styles);
                        Cell at = row.createCell(1);
                        at.setCellValue(rs.getObject("occurred_at", OffsetDateTime.class)
                                .withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime());
                        at.setCellStyle(styles.timestamp());
                        row.createCell(2).setCellValue(rs.getString("movement_type"));
                        row.createCell(3).setCellValue(rs.getString("warehouse"));
                        String bin = rs.getString("bin");
                        row.createCell(4).setCellValue(bin == null ? "" : bin);
                        row.createCell(5).setCellValue(rs.getString("sku"));
                        number(row, 6, rs.getLong("on_hand_delta"), styles);
                        number(row, 7, rs.getLong("reserved_delta"), styles);
                        number(row, 8, rs.getLong("level_seq"), styles);
                        number(row, 9, rs.getLong("on_hand_after"), styles);
                        number(row, 10, rs.getLong("reserved_after"), styles);
                        row.createCell(11).setCellValue(rs.getString("recorded_by"));
                    });
            return Workbooks.toBytes(workbook);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void number(Row row, int column, long value, Workbooks.Styles styles) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(styles.integer());
    }
}
