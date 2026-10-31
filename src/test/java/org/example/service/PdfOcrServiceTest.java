package org.example.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** OCR 服务单测：可用性判定与失败降级（不发起真实网络请求）。 */
class PdfOcrServiceTest {

    private PdfOcrService svcWith(String key, boolean enabled) {
        PdfOcrService svc = new PdfOcrService();
        set(svc, "apiKey", key);
        set(svc, "enabled", enabled);
        set(svc, "model", "qwen-vl-ocr");
        set(svc, "maxPages", 20);
        set(svc, "dpi", 150);
        return svc;
    }

    private static void set(Object o, String field, Object v) {
        try {
            var f = o.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(o, v);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    @DisplayName("未配置 Key 时不可用")
    void unavailableWithoutKey() {
        assertFalse(svcWith("", true).isAvailable());
        assertFalse(svcWith(null, true).isAvailable());
    }

    @Test
    @DisplayName("占位 Key（your-...）视为未配置")
    void placeholderKeyTreatedAsMissing() {
        assertFalse(svcWith("your-api-key-here", true).isAvailable());
    }

    @Test
    @DisplayName("关闭开关时不可用，即使有 Key")
    void disabledBySwitch() {
        assertFalse(svcWith("sk-real-key", false).isAvailable());
    }

    @Test
    @DisplayName("正常 Key + 开启 → 可用")
    void available() {
        assertTrue(svcWith("sk-real-key", true).isAvailable());
    }

    @Test
    @DisplayName("不可用时 ocrPdf 返回 null（由上层回落为明确报错，不静默入库）")
    void returnsNullWhenUnavailable() {
        assertNull(svcWith("", true).ocrPdf(new byte[]{1, 2, 3}, "scan.pdf"));
    }

    @Test
    @DisplayName("非法 PDF 字节流 → 返回 null，不抛异常")
    void handlesBrokenPdf() {
        assertNull(svcWith("sk-real-key", true).ocrPdf("not a pdf".getBytes(), "broken.pdf"));
    }
}
