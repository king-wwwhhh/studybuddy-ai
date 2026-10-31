package org.example.service;

import org.example.config.DocumentChunkConfig;
import org.example.dto.DocumentChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 回归测试：整篇没有一个空行的长文本（简历/表格型 PDF）必须被切分，
 * 不能因为"段落本身超限但 currentChunk 为空"而整段塞进一个分片。
 */
class LongParagraphChunkTest {

    private DocumentChunkService service(int maxSize, int overlap) throws Exception {
        DocumentChunkService svc = new DocumentChunkService();
        DocumentChunkConfig cfg = new DocumentChunkConfig();
        set(cfg, "maxSize", maxSize);
        set(cfg, "overlap", overlap);
        Field f = DocumentChunkService.class.getDeclaredField("chunkConfig");
        f.setAccessible(true);
        f.set(svc, cfg);
        return svc;
    }

    private static void set(Object o, String name, Object v) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, v);
    }

    @Test
    @DisplayName("整篇无空行的长文本必须切成多片")
    void singleGiantParagraphIsSplit() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 120; i++) {
            sb.append("第").append(i).append("条内容用于测试分片。");
        }
        String text = sb.toString().replace("\n", "");
        assertTrue(text.length() > 800, "测试前提：正文应超过 maxSize，实际 " + text.length());

        List<DocumentChunk> chunks = service(800, 100).chunkDocument(text, "resume.pdf");
        assertTrue(chunks.size() > 1, "应切成多片，实际 " + chunks.size() + " 片");
        for (DocumentChunk c : chunks) {
            assertTrue(c.getContent().length() <= 900,
                    "单片不应远超 maxSize，实际 " + c.getContent().length());
        }
    }

    @Test
    @DisplayName("短文本仍然只切成一片")
    void shortTextSingleChunk() throws Exception {
        String text = "这是一段很短的文字，不需要切分。";
        assertEquals(1, service(800, 100).chunkDocument(text, "short.md").size());
    }

    @Test
    @DisplayName("切分不丢内容（拼接后应覆盖原文所有非空白字符）")
    void doesNotLoseContent() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            sb.append("内容片段").append(i).append("。");
        }
        String text = sb.toString();
        List<DocumentChunk> chunks = service(200, 0).chunkDocument(text, "x.md");
        String joined = chunks.stream().map(DocumentChunk::getContent).reduce("", String::concat);
        for (int i = 0; i < 80; i++) {
            assertTrue(joined.contains("内容片段" + i), "丢失片段 " + i);
        }
    }
}
