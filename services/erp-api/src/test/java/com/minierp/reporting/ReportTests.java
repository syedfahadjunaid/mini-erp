package com.minierp.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.minierp.IntegrationTest;
import com.minierp.TestUsers;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

/** The exports are real files: parse them back and check what's inside. */
class ReportTests extends IntegrationTest {

    @Test
    void stockReportAsPdfListsTheStockAndFlagsLowLines() throws Exception {
        Warehouse warehouse = warehouse();
        long low = product();
        long fine = product();
        stockUp(warehouse.id(), warehouse.binA(), low, 4);
        stockUp(warehouse.id(), warehouse.binA(), fine, 400);
        performPut("/api/reorder-rules/" + warehouse.id() + "/" + low, TestUsers.MANAGER,
                Map.of("reorderPoint", 5, "reorderQuantity", 50)).andExpect(status().isOk());
        String lowSku = get("/api/products/" + low, TestUsers.WAREHOUSE).get("sku").asString();

        byte[] pdf = download("/api/reports/stock.pdf?warehouseId=" + warehouse.id(), TestUsers.WAREHOUSE, MediaType.APPLICATION_PDF);

        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        String text = new PdfTextExtractor(new PdfReader(pdf)).getTextFromPage(1);
        assertThat(text).contains("Stock report", warehouse.code(), lowSku, "LOW");
    }

    @Test
    void stockReportAsExcelHasRealNumbers() throws Exception {
        Warehouse warehouse = warehouse();
        long product = product();
        stockUp(warehouse.id(), warehouse.binA(), product, 12);
        long order = salesOrder(customer(), warehouse.id(), product, 5).get("id").asLong();
        act("sales-orders", order, "confirm", TestUsers.SALES);

        byte[] xlsx = download("/api/reports/stock.xlsx?warehouseId=" + warehouse.id(), TestUsers.FINANCE, ReportController.XLSX);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = workbook.getSheet("Stock");
            assertThat(sheet.getRow(0).getCell(6).getStringCellValue()).isEqualTo("Available");
            Row row = sheet.getRow(1);
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo(warehouse.code());
            assertThat(row.getCell(4).getNumericCellValue()).isEqualTo(12);  // on hand
            assertThat(row.getCell(5).getNumericCellValue()).isEqualTo(5);   // reserved
            assertThat(row.getCell(6).getNumericCellValue()).isEqualTo(7);   // available
            assertThat(workbook.getSheet("About")).isNotNull();
        }
    }

    @Test
    void ledgerExportContainsEveryMovement() throws Exception {
        Warehouse warehouse = warehouse();
        long product = product();
        stockUp(warehouse.id(), warehouse.binA(), product, 12);
        stockUp(warehouse.id(), warehouse.binA(), product, -2);

        byte[] xlsx = download("/api/reports/stock-movements.xlsx?warehouseId=" + warehouse.id(), TestUsers.MANAGER,
                ReportController.XLSX);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = workbook.getSheet("Stock movements");
            assertThat(sheet.getLastRowNum()).isEqualTo(2);
            assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("ADJUSTMENT");
            assertThat(sheet.getRow(2).getCell(6).getNumericCellValue()).isEqualTo(-2);
            assertThat(sheet.getRow(2).getCell(9).getNumericCellValue()).isEqualTo(10); // on hand after
        }
    }

    @Test
    void invoicePdfShowsTheNumberLinesAndTotals() throws Exception {
        Warehouse warehouse = warehouse();
        long product = product();
        stockUp(warehouse.id(), warehouse.binA(), product, 3);
        JsonNode order = salesOrder(customer(), warehouse.id(), product, 3);
        long orderId = order.get("id").asLong();
        act("sales-orders", orderId, "confirm", TestUsers.SALES);
        long shipment = post("/api/sales-orders/" + orderId + "/shipments", TestUsers.WAREHOUSE,
                Map.of("lines", List.of(Map.of("lineId", order.get("lines").get(0).get("id").asLong(), "quantity", 3))))
                .get("id").asLong();
        JsonNode invoice = post("/api/shipments/" + shipment + "/invoice", TestUsers.FINANCE, Map.of());

        byte[] pdf = download("/api/invoices/" + invoice.get("id").asLong() + "/pdf", TestUsers.FINANCE, MediaType.APPLICATION_PDF);

        String text = new PdfTextExtractor(new PdfReader(pdf)).getTextFromPage(1);
        assertThat(text).contains(invoice.get("invoiceNumber").asString(), "Bill to", "30.00", "6.00", "36.00", "20%");
    }

    @Test
    void exportsNeedTheRightRole() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/api/reports/stock.pdf").with(TestUsers.SALES.bearer())).andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.get("/api/reports/stock-movements.xlsx").with(TestUsers.WAREHOUSE.bearer()))
                .andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.get("/api/reports/stock.pdf")).andExpect(status().isUnauthorized());
    }

    private byte[] download(String path, TestUsers.User user, MediaType type) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(path).with(user.bearer()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", type.toString()))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment;")))
                .andReturn().getResponse().getContentAsByteArray();
    }
}
