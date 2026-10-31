package org.example.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

/**
 * 扫描版 PDF 的 OCR（页面渲染 + 多模态模型识别）。
 *
 * <p><b>为什么这样做</b>：文本层抽取对扫描件无能为力（PDFBox 只能拿到 0~几十个字符）。
 * 这里把每页渲染成图片，交给百炼 `qwen-vl-ocr` 识别后拼回文本，
 * 让扫描件也能进入 RAG 管线。</p>
 *
 * <p><b>成本与限制</b>：</p>
 * <ul>
 *   <li>每页一次 API 调用（实测 150 DPI 约 2100 image_tokens/页）；</li>
 *   <li>受 {@code ocr.max-pages} 限制，避免超大文档烧配额；</li>
 *   <li>只在	extbf{文本层抽取失败}时调用（见 FileIngestService 的质量闸门）；</li>
 *   <li>未配置 API Key 或调用失败时返回 null，由上层回落到"明确报错"，不静默入库。</li>
 * </ul>
 */
@Service
public class PdfOcrService {

    private static final Logger logger = LoggerFactory.getLogger(PdfOcrService.class);

    private static final String ENDPOINT =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";

    private final Gson gson = new Gson();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20)).build();

    @Value("${spring.ai.dashscope.api-key:}")
    private String apiKey;

    @Value("${ocr.enabled:true}")
    private boolean enabled;

    @Value("${ocr.model:qwen-vl-ocr}")
    private String model;

    /** 最多识别页数：防止超大扫描件一次性消耗过多配额 */
    @Value("${ocr.max-pages:20}")
    private int maxPages;

    /** 渲染 DPI：150 在精度与体积间较均衡 */
    @Value("${ocr.dpi:150}")
    private int dpi;

    public boolean isAvailable() {
        return enabled && apiKey != null && !apiKey.isBlank() && !apiKey.startsWith("your-");
    }

    /**
     * 对 PDF 做 OCR。
     *
     * @return 识别出的完整文本；不可用/失败/无内容时返回 {@code null}
     */
    public String ocrPdf(byte[] pdfData, String fileName) {
        if (!isAvailable()) {
            logger.info("OCR 不可用（未启用或缺 API Key），跳过: {}", fileName);
            return null;
        }
        try (PDDocument doc = PDDocument.load(pdfData)) {
            int total = doc.getNumberOfPages();
            int pages = Math.min(total, Math.max(1, maxPages));
            logger.info("开始 OCR: {}（共 {} 页，本次处理 {} 页，DPI={}）", fileName, total, pages, dpi);

            PDFRenderer renderer = new PDFRenderer(doc);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < pages; i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, dpi, ImageType.RGB);
                String text = ocrImage(image, fileName, i + 1);
                if (text != null && !text.isBlank()) {
                    sb.append(text.trim()).append("\n\n");
                }
                logger.info("OCR 进度: {}/{} 页（本页 {} 字）", i + 1, pages,
                        text == null ? 0 : text.length());
            }
            String result = sb.toString().trim();
            logger.info("OCR 完成: {} -> {} 字", fileName, result.length());
            return result.isEmpty() ? null : result;
        } catch (Exception e) {
            logger.warn("OCR 失败（将回落到明确报错）: {} -> {}", fileName, e.getMessage());
            return null;
        }
    }

    /** 单页识别：图片 -> base64 -> qwen-vl-ocr */
    private String ocrImage(BufferedImage image, String fileName, int pageNo) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            String b64 = Base64.getEncoder().encodeToString(out.toByteArray());

            JsonObject content = new JsonObject();
            content.addProperty("image", "data:image/png;base64," + b64);
            JsonArray contentArr = new JsonArray();
            contentArr.add(content);

            JsonObject message = new JsonObject();
            message.addProperty("role", "user");
            message.add("content", contentArr);
            JsonArray messages = new JsonArray();
            messages.add(message);

            JsonObject input = new JsonObject();
            input.add("messages", messages);

            JsonObject params = new JsonObject();
            params.addProperty("max_tokens", 2000);

            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.add("input", input);
            body.add("parameters", params);

            HttpRequest req = HttpRequest.newBuilder(URI.create(ENDPOINT))
                    .timeout(Duration.ofSeconds(120))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                logger.warn("OCR 第 {} 页失败 HTTP {}: {}", pageNo, resp.statusCode(),
                        truncate(resp.body()));
                return null;
            }
            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
            JsonArray choices = root.getAsJsonObject("output").getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                return null;
            }
            JsonArray parts = choices.get(0).getAsJsonObject()
                    .getAsJsonObject("message").getAsJsonArray("content");
            if (parts == null || parts.isEmpty()) {
                return null;
            }
            JsonObject first = parts.get(0).getAsJsonObject();
            if (first.has("text")) {
                return first.get("text").getAsString();
            }
            if (first.has("ocr_result")) {
                JsonObject ocr = first.getAsJsonObject("ocr_result");
                return ocr.has("processed_text") ? ocr.get("processed_text").getAsString() : null;
            }
            return null;
        } catch (Exception e) {
            logger.warn("OCR 第 {} 页异常: {}", pageNo, e.getMessage());
            return null;
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
