package org.example.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 正文可用性判定单测：拒绝只有封面/标题的文件，放行正常文档。 */
class TextQualityTest {

    @Test
    @DisplayName("只有标题+网址的封面 PDF → 判为不可用")
    void rejectsCoverOnly() {
        String cover = "编程导航原创项目 https://www.codefather.cn";
        assertFalse(TextQuality.usable(cover), "34 字符的封面必须被拒绝");
        assertEquals(33, TextQuality.usefulChars(cover), "去空白后 33 个字符");
    }

    @Test
    @DisplayName("大段空行不算正文字数")
    void ignoresWhitespace() {
        String padded = "短标题" + " ".repeat(500) + "\n".repeat(100);
        assertEquals(3, TextQuality.usefulChars(padded));
        assertFalse(TextQuality.usable(padded), "空白不能凑数");
    }

    @Test
    @DisplayName("正常文档放行")
    void acceptsRealDocument() {
        String doc = "向量检索通过计算查询向量与库中向量的距离来召回最相似的内容。" +
                "常用度量有 L2 距离与余弦相似度。索引可选 IVF_FLAT（聚类倒排剪枝）或 HNSW（多层图导航）。" +
                "检索结果还需经过阈值过滤与重排序，才能作为证据交给大模型生成回答。";
        assertTrue(TextQuality.usable(doc));
    }

    @Test
    @DisplayName("边界：恰好 79 拒绝、80 通过")
    void boundary() {
        assertFalse(TextQuality.usable("a".repeat(TextQuality.MIN_USEFUL_CHARS - 1)));
        assertTrue(TextQuality.usable("a".repeat(TextQuality.MIN_USEFUL_CHARS)));
    }

    @Test
    @DisplayName("空/null 判为不可用；拒绝文案含实际字符数")
    void emptyAndMessage() {
        assertFalse(TextQuality.usable(null));
        assertFalse(TextQuality.usable(""));
        String msg = TextQuality.rejectionMessage("abc");
        assertTrue(msg.contains("仅 3 个有效字符"), msg);
        assertTrue(msg.contains("OCR"), "应给出可操作建议: " + msg);
    }
}
