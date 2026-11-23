package com.minierp.reporting;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

/**
 * Shared cell styles for the Excel exports (Apache POI). A workbook can hold only a limited
 * number of styles, so each export creates these once and reuses them for every cell.
 */
final class Workbooks {

    private Workbooks() {}

    /** The styles one export needs, created in its workbook. */
    record Styles(CellStyle header, CellStyle text, CellStyle integer, CellStyle lowStock, CellStyle timestamp) {

        static Styles of(Workbook workbook) {
            Font bold = workbook.createFont();
            bold.setBold(true);
            bold.setColor(IndexedColors.WHITE.getIndex());

            CellStyle header = workbook.createCellStyle();
            header.setFont(bold);
            header.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setBorderBottom(BorderStyle.THIN);

            CellStyle text = workbook.createCellStyle();

            // Real numeric cells with a number format (not text), so Excel can sum and filter them.
            CellStyle integer = workbook.createCellStyle();
            integer.setDataFormat(workbook.createDataFormat().getFormat("#,##0"));

            CellStyle lowStock = workbook.createCellStyle();
            lowStock.setFillForegroundColor(IndexedColors.ROSE.getIndex());
            lowStock.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle timestamp = workbook.createCellStyle();
            timestamp.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"));

            return new Styles(header, text, integer, lowStock, timestamp);
        }
    }

    /**
     * Sets each column's width from the longest value in it. (POI's autoSizeColumn measures text
     * with AWT fonts, which slim server containers may not have; character counts are enough.)
     */
    static void fitColumns(Sheet sheet, int columns) {
        int[] widths = new int[columns];
        for (Row row : sheet) {
            for (int i = 0; i < columns; i++) {
                Cell cell = row.getCell(i);
                if (cell != null) {
                    int length = switch (cell.getCellType()) {
                        case NUMERIC -> String.format("%,.0f", cell.getNumericCellValue()).length() + 1;
                        case STRING -> cell.getStringCellValue().length();
                        default -> 4;
                    };
                    widths[i] = Math.max(widths[i], length);
                }
            }
        }
        for (int i = 0; i < columns; i++) {
            sheet.setColumnWidth(i, Math.min(80, Math.max(6, widths[i] + 2)) * 256); // unit: 1/256 of a character
        }
    }

    static byte[] toBytes(Workbook workbook) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
