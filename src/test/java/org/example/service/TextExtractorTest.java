package org.example.service;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TextExtractor 单测：表格必须输出成「标记行 + Markdown 表头 + 数据行」，
 * 这样 DocumentChunkService 才能按行对齐切分并注入表头。
 */
class TextExtractorTest {

    private final TextExtractor extractor = new TextExtractor();

    @Test
    @DisplayName("xlsx：单 sheet → 【表：sheet名】 + Markdown 表头（表头取首行）")
    void xlsxToMarkdownTable() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("成绩");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("姓名");
            header.createCell(1).setCellValue("分数");
            Row row1 = sheet.createRow(1);
            row1.createCell(0).setCellValue("张三");
            row1.createCell(1).setCellValue(90);
            wb.write(out);
            bytes = out.toByteArray();
        }

        String text = extractor.extract(bytes, "score.xlsx").text;

        assertTrue(text.contains("【表：成绩】"), text);
        assertTrue(text.contains("| 姓名 | 分数 |"), text);
        assertTrue(text.contains("| --- | --- |"), text);
        assertTrue(text.contains("| 张三 | 90 |"), text);
        assertFalse(text.contains("\t"), "表格不应再输出 tab 分隔文本: " + text);
    }

    @Test
    @DisplayName("docx：表格 → 【表：表1】 + Markdown，正文段落保留")
    void docxTableToMarkdown() throws Exception {
        byte[] bytes;
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("下面是成绩表：");
            XWPFTable table = doc.createTable(2, 2);
            XWPFTableRow row0 = table.getRow(0);
            row0.getCell(0).setText("姓名");
            row0.getCell(1).setText("科目");
            XWPFTableRow row1 = table.getRow(1);
            row1.getCell(0).setText("张三");
            row1.getCell(1).setText("数学");
            doc.write(out);
            bytes = out.toByteArray();
        }

        String text = extractor.extract(bytes, "score.docx").text;

        assertTrue(text.contains("【表：表1】"), text);
        assertTrue(text.contains("| 姓名 | 科目 |"), text);
        assertTrue(text.contains("| 张三 | 数学 |"), text);
        assertTrue(text.contains("下面是成绩表"), text);
    }

    @Test
    @DisplayName("单元格里的竖线要转义，不能破坏 Markdown 列结构")
    void pipeInCellEscaped() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("A");
            sheet.createRow(0).createCell(0).setCellValue("列");
            sheet.createRow(1).createCell(0).setCellValue("A|B");
            wb.write(out);
            bytes = out.toByteArray();
        }

        String text = extractor.extract(bytes, "pipe.xlsx").text;

        assertTrue(text.contains("A\\|B"), "竖线应转义为 \\| : " + text);
    }

    @Test
    @DisplayName("纯文本/Markdown 不受影响，仍原样输出")
    void markdownPassthrough() {
        String text = extractor.extract("# 标题\n正文内容".getBytes(StandardCharsets.UTF_8), "note.md").text;
        assertEquals("# 标题\n正文内容", text);
    }
}
