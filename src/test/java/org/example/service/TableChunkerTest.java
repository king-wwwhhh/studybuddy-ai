package org.example.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TableChunker 单测：表格块定位 + 行对齐分片（每片必须带表名与表头）。 */
class TableChunkerTest {

    private static final String TABLE = String.join("\n",
            "【表：成绩单】",
            "| 姓名 | 分数 | 备注 |",
            "| --- | --- | --- |",
            "| 张三 | 90 | 优秀 |",
            "| 李四 | 75 | 及格 |",
            "| 王五 | 60 | 临界 |");

    @Test
    @DisplayName("findBlocks：定位表格块，保留位置/表名/表头/数据行")
    void findBlocks() {
        String content = "前言文字\n\n" + TABLE + "\n\n结尾文字";
        List<TableChunker.Block> blocks = TableChunker.findBlocks(content);

        assertEquals(1, blocks.size());
        TableChunker.Block b = blocks.get(0);
        assertEquals("【表：成绩单】", b.marker);
        assertEquals(2, b.header.size());
        assertEquals("| 姓名 | 分数 | 备注 |", b.header.get(0));
        assertEquals("| --- | --- | --- |", b.header.get(1));
        assertEquals(3, b.rowCount());

        // 偏移必须能切回原文（FileIngestService 用它算 startIndex/endIndex）
        String slice = content.substring(b.start, b.end);
        assertTrue(slice.startsWith("【表：成绩单】"), slice);
        assertTrue(slice.endsWith("| 王五 | 60 | 临界 |"), slice);
    }

    @Test
    @DisplayName("findBlocks：无表格 / 空内容都返回空列表")
    void findBlocksEmpty() {
        assertTrue(TableChunker.findBlocks("").isEmpty());
        assertTrue(TableChunker.findBlocks(null).isEmpty());
        assertTrue(TableChunker.findBlocks("# 只有标题\n没有表格").isEmpty());
        // 只有标记行没有表格行 → 不算表格块
        assertTrue(TableChunker.findBlocks("【表：空】\n普通文本").isEmpty());
    }

    @Test
    @DisplayName("split：预算够大时整表一片，且带表名 + 表头")
    void splitWholeTable() {
        TableChunker.Block b = TableChunker.findBlocks(TABLE).get(0);
        List<String> chunks = TableChunker.split(b, 1000);

        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).startsWith("【表：成绩单】"));
        assertTrue(chunks.get(0).contains("| 姓名 | 分数 | 备注 |"));
        assertTrue(chunks.get(0).contains("| 王五 | 60 | 临界 |"));
    }

    @Test
    @DisplayName("split：预算小 → 多片，每片都带表名+表头，行不丢不重，且没有半行")
    void splitRowAligned() {
        TableChunker.Block b = TableChunker.findBlocks(TABLE).get(0);
        List<String> chunks = TableChunker.split(b, 80); // 强制切成多片

        assertTrue(chunks.size() > 1, "预算 80 应该切出多片，实际 " + chunks.size());

        List<String> collected = new ArrayList<>();
        for (String c : chunks) {
            assertTrue(c.startsWith("【表：成绩单】"), "分片缺表名：" + c);
            assertTrue(c.contains("| 姓名 | 分数 | 备注 |"), "分片缺表头：" + c);
            assertTrue(c.contains("| --- | --- | --- |"), "分片缺分隔行：" + c);

            for (String line : c.split("\n")) {
                if (TableChunker.isTableRow(line)) {
                    assertTrue(line.endsWith("|"), "出现半行：" + line);   // 行未被切断
                }
            }
            // 收集数据行（跳过标记行与表头两行）
            String[] lines = c.split("\n");
            for (int i = 3; i < lines.length; i++) {
                collected.add(lines[i]);
            }
        }
        assertEquals(List.of(
                "| 张三 | 90 | 优秀 |",
                "| 李四 | 75 | 及格 |",
                "| 王五 | 60 | 临界 |"), collected, "数据行应不丢不重且保持顺序");
    }

    @Test
    @DisplayName("split：单行超过预算时该行独占一片，不被切成两半")
    void splitOversizedRow() {
        String longCell = "长".repeat(300);
        String table = String.join("\n",
                "【表：巨表】",
                "| 列A | 列B |",
                "| --- | --- |",
                "| 短 | " + longCell + " |",
                "| 短2 | 值 |");
        TableChunker.Block b = TableChunker.findBlocks(table).get(0);
        List<String> chunks = TableChunker.split(b, 80);

        assertEquals(2, chunks.size(), "超长行独占一片、其余行合成另一片：" + chunks);
        for (String c : chunks) {
            for (String line : c.split("\n")) {
                if (TableChunker.isTableRow(line)) {
                    assertTrue(line.endsWith("|"), "出现半行：" + line);
                }
            }
        }
        assertTrue(chunks.get(0).contains(longCell), "超长行内容应完整保留：" + chunks.get(0).substring(0, 40));
    }

    @Test
    @DisplayName("split：没有数据行时只返回表头片")
    void splitHeaderOnly() {
        String table = "【表：空表】\n| 列A | 列B |\n| --- | --- |";
        TableChunker.Block b = TableChunker.findBlocks(table).get(0);
        List<String> chunks = TableChunker.split(b, 200);

        assertEquals(1, chunks.size());
        assertEquals("【表：空表】\n| 列A | 列B |\n| --- | --- |", chunks.get(0));
        assertFalse(chunks.get(0).contains("| 数据 |"));
    }
}
