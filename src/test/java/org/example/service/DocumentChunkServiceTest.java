package org.example.service;

import org.example.config.DocumentChunkConfig;
import org.example.dto.DocumentChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DocumentChunkService 单测：表格块走行对齐通道，普通文本走原有通道，顺序与编号都要对。 */
class DocumentChunkServiceTest {

    private static DocumentChunkService newService(int maxSize, int overlap) {
        DocumentChunkService service = new DocumentChunkService();
        DocumentChunkConfig config = new DocumentChunkConfig();
        config.setMaxSize(maxSize);
        config.setOverlap(overlap);
        service.chunkConfig = config;
        return service;
    }

    private static final String DOC = String.join("\n",
            "# 第一节 成绩",
            "",
            "这是表格前面的说明文字。",
            "",
            "【表：成绩单】",
            "| 姓名 | 分数 |",
            "| --- | --- |",
            "| 张三 | 90 |",
            "| 李四 | 75 |",
            "| 王五 | 60 |",
            "",
            "这是表格后面的收尾文字。");

    @Test
    @DisplayName("表格块：默认预算下一片装完，title 记录表名，内容含表头")
    void tableSingleChunk() {
        List<DocumentChunk> chunks = newService(800, 100).chunkDocument(DOC, "doc.md");

        DocumentChunk tableChunk = chunks.stream()
                .filter(c -> c.getContent().startsWith(TableChunker.MARKER_PREFIX))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有产出表格分片: " + chunks));

        assertEquals("【表：成绩单】", tableChunk.getTitle());
        assertTrue(tableChunk.getContent().contains("| 姓名 | 分数 |"));
        assertTrue(tableChunk.getContent().contains("| 王五 | 60 |"));

        // 表格前后的普通文本都还在
        assertTrue(chunks.stream().anyMatch(c -> c.getContent().contains("表格前面的说明文字")));
        assertTrue(chunks.stream().anyMatch(c -> c.getContent().contains("表格后面的收尾文字")));
    }

    @Test
    @DisplayName("表格块：预算收紧后切成多片，每片都带表名+表头，数据行不丢")
    void tableMultipleChunksKeepHeader() {
        // 12 行数据，确保超过「表头 + 120 字符」的单片预算
        StringBuilder doc = new StringBuilder("【表：成绩单】\n| 姓名 | 分数 |\n| --- | --- |\n");
        for (int i = 1; i <= 12; i++) {
            doc.append("| 学生").append(i).append(" | ").append(50 + i).append(" |\n");
        }
        doc.append("\n收尾文字");

        List<DocumentChunk> all = newService(200, 20).chunkDocument(doc.toString(), "doc.md");

        List<DocumentChunk> tableChunks = all.stream()
                .filter(c -> c.getContent().startsWith(TableChunker.MARKER_PREFIX))
                .toList();
        assertTrue(tableChunks.size() >= 2, "预算 200 应把表格切成多片，实际 " + tableChunks.size());

        for (DocumentChunk c : tableChunks) {
            assertTrue(c.getContent().contains("【表：成绩单】"), "分片缺表名: " + c.getContent());
            assertTrue(c.getContent().contains("| 姓名 | 分数 |"), "分片缺表头: " + c.getContent());
            assertEquals("【表：成绩单】", c.getTitle());
        }

        // 十二条数据行必须都出现（且各出现一次）
        String joined = tableChunks.stream().map(DocumentChunk::getContent).reduce("", (a, b) -> a + "\n" + b);
        for (int i = 1; i <= 12; i++) {
            String row = "| 学生" + i + " | " + (50 + i) + " |";
            assertEquals(1, joined.split(java.util.regex.Pattern.quote(row), -1).length - 1,
                    "数据行出现次数不为 1: " + row + "\n" + joined);
        }
    }

    @Test
    @DisplayName("chunkIndex 连续唯一；空内容返回空列表")
    void indicesAndEmpty() {
        List<DocumentChunk> chunks = newService(200, 20).chunkDocument(DOC, "doc.md");
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).getChunkIndex());
        }
        assertTrue(newService(800, 100).chunkDocument("   ", "empty.md").isEmpty());
        assertTrue(newService(800, 100).chunkDocument(null, "null.md").isEmpty());
    }

    @Test
    @DisplayName("纯文本文档：不产生表格分片，行为与改造前一致")
    void plainTextUnaffected() {
        String doc = "# 标题\n\n第一段内容。\n\n第二段内容。";
        List<DocumentChunk> chunks = newService(800, 100).chunkDocument(doc, "plain.md");

        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().noneMatch(c -> c.getContent().startsWith(TableChunker.MARKER_PREFIX)));
        assertTrue(chunks.stream().anyMatch(c -> c.getContent().contains("第一段内容")));
    }

    @Test
    @DisplayName("heading_path：嵌套标题形成章节路径；表格分片路径 = 章节路径 > 表名")
    void headingPaths() {
        String doc = String.join("\n",
                "# 第一章 基础",
                "",
                "章的内容。",
                "",
                "## 1.1 概念",
                "",
                "节的内容。",
                "",
                "# 第二章 进阶",
                "",
                "## 2.1 成绩表",
                "",
                "下面这张表是期中成绩。",
                "",
                "【表：期中成绩】",
                "| 姓名 | 分数 |",
                "| --- | --- |",
                "| 张三 | 90 |");

        List<DocumentChunk> chunks = newService(800, 100).chunkDocument(doc, "doc.md");

        assertTrue(chunks.stream().anyMatch(c -> "第一章 基础".equals(c.getHeadingPath())),
                "章级分片路径不对: " + chunks.stream().map(DocumentChunk::getHeadingPath).toList());
        assertTrue(chunks.stream().anyMatch(c -> "第一章 基础 > 1.1 概念".equals(c.getHeadingPath())),
                "节级分片应带完整路径");
        assertTrue(chunks.stream().anyMatch(c -> "第二章 进阶 > 2.1 成绩表".equals(c.getHeadingPath())),
                "第二章下的节路径不对");

        DocumentChunk table = chunks.stream()
                .filter(c -> c.getContent().startsWith(TableChunker.MARKER_PREFIX))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有表格分片"));
        assertEquals("第二章 进阶 > 2.1 成绩表 > 【表：期中成绩】", table.getHeadingPath());
    }

    @Test
    @DisplayName("标题行不进分片正文：省 token；标题信息仍在 title / headingPath 里")
    void headingLineStripped() {
        String doc = String.join("\n",
                "# 第一章 基础",
                "",
                "第一章的正文内容。",
                "",
                "## 1.1 概念",
                "",
                "1.1 的正文内容。",
                "",
                "### 1.1.1 定义",
                "",
                "定义正文。");

        List<DocumentChunk> chunks = newService(800, 100).chunkDocument(doc, "doc.md");

        for (DocumentChunk c : chunks) {
            assertFalse(c.getContent().contains("# 第一章 基础"), "标题行不应出现在正文: " + c.getContent());
            assertFalse(c.getContent().contains("## 1.1 概念"), "子标题行不应出现在正文: " + c.getContent());
            assertFalse(c.getContent().contains("### 1.1.1 定义"), "三级标题行不应出现在正文: " + c.getContent());
        }
        // 标题信息改由 title / headingPath 承载，完整保留
        assertTrue(chunks.stream().anyMatch(c -> "1.1 概念".equals(c.getTitle())));
        assertTrue(chunks.stream().anyMatch(c -> "第一章 基础".equals(c.getHeadingPath())));
        assertTrue(chunks.stream().anyMatch(c -> "第一章 基础 > 1.1 概念 > 1.1.1 定义".equals(c.getHeadingPath())));
        // 正文内容本身仍然存在
        assertTrue(chunks.stream().anyMatch(c -> c.getContent().contains("定义正文")));
    }
}
