package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模型目录：自动发现并校验「DashScope 原生端点」真正可用的对话模型。
 *
 * <p>解决的问题：代码里硬编码模型名，一旦阿里云改名/下线就会 404
 * {@code {"code":"InvalidParameter","message":"Model not exist."}}。
 * 且光读 {@code /compatible-mode/v1/models} 列表不可靠 —— 列表里的模型
 * 未必支持原生 {@code /services/aigc/text-generation/generation} 端点
 * （实测：某工作空间列表 178 个，原生端点只有 8 个可用）。</p>
 *
 * <p>做法：候选池 = 配置优先 + 从 /models 自动发现 + 内置兜底，
 * 逐个对原生端点发 {@code max_tokens=1} 的探测请求，按「版本从新到旧」取第一个
 * 真正可用的作为默认模型；探测在启动完成后异步进行，不阻塞健康检查；
 * 运行时若请求了不可用模型，自动换成可用模型。</p>
 */
@Service
public class ModelCatalogService {

    private static final Logger logger = LoggerFactory.getLogger(ModelCatalogService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** 内置兜底：这几个名字长期存在，且实测原生端点可用 */
    private static final List<String> BUILTIN_CANDIDATES =
            List.of("qwen-max", "qwen-plus", "qwen-flash");

    /** 聊天类模型名：qwen3.8-max / qwen3.7-max / qwen-max / qwen-plus-latest / qwen-flash */
    private static final Pattern CHAT_LIKE = Pattern.compile(
            "^qwen[0-9.]*-(max|plus|flash)$" +
            "|^qwen-(max|plus|flash)(-latest)?$",
            Pattern.CASE_INSENSITIVE);

    /** 明确排除：向量/图像/语音/多模态/代码/小尺寸等非通用对话模型 */
    private static final Pattern EXCLUDE = Pattern.compile(
            "image|audio|asr|tts|omni|realtime|vl|ocr|embedding|rerank|livetranslate" +
            "|character|deep-|mt-|s2s|deep-search|coder|math|instruct|longcontext" +
            "|^qwen[0-9.]+[0-9]b|^qwen[0-9]+-|^qwen1?[0-9.]*b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern VERSION = Pattern.compile("qwen(\\d+)(?:\\.(\\d+))?");

    /** 每次最多收集多少个可用模型（够用即止，避免探测过多浪费） */
    private static final int MAX_USABLE = 8;
    private static final long TTL_MS = 24L * 3600 * 1000;

    @Value("${spring.ai.dashscope.api-key:}")
    private String apiKey;

    @Value("${spring.ai.dashscope.base-url:https://dashscope.aliyuncs.com}")
    private String baseUrl;

    /** 首选模型（可空）：有值且可用时优先用它 */
    @Value("${chat.model:}")
    private String preferredModel;

    /** 候选模型 CSV（app.models），优先级高于自动发现 */
    @Value("${app.models:}")
    private String candidateCsv;

    /** 关闭后完全走配置值，不探测 */
    @Value("${chat.auto-detect:true}")
    private boolean autoDetect;

    private volatile List<String> usableModels = List.of();
    private volatile String resolvedDefault = null;
    private volatile boolean probed = false;
    private volatile long lastRefreshAt = 0L;
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    // ==================== 对外接口 ====================

    /** 当前应使用的默认模型：探测结果 > 配置首选 > 可用列表首个 > 内置兜底 */
    public String getDefaultModel() {
        String r = resolvedDefault;
        if (r != null) {
            return r;
        }
        if (preferredModel != null && !preferredModel.isBlank()) {
            return preferredModel.trim();
        }
        List<String> u = usableModels;
        if (!u.isEmpty()) {
            return u.get(0);
        }
        return BUILTIN_CANDIDATES.get(0);
    }

    /** 真正可用的模型列表（探测未完成时返回候选，保证前端有内容可选） */
    public List<String> getUsableModels() {
        List<String> u = usableModels;
        if (!u.isEmpty()) {
            return u;
        }
        LinkedHashSet<String> fallback = new LinkedHashSet<>();
        if (preferredModel != null && !preferredModel.isBlank()) {
            fallback.add(preferredModel.trim());
        }
        fallback.addAll(splitCsv(candidateCsv));
        fallback.addAll(BUILTIN_CANDIDATES);
        return new ArrayList<>(fallback);
    }

    public boolean isProbed() {
        return probed;
    }

    /**
     * 请求的模型不在可用集合里就换成默认可用模型。
     * 探测未完成时（usableModels 为空）原样返回，不干扰正常运行。
     */
    public String nearestUsable(String requested) {
        if (requested == null || requested.isBlank()) {
            return getDefaultModel();
        }
        List<String> u = usableModels;
        if (u.isEmpty()) {
            return requested.trim();
        }
        String m = requested.trim();
        return u.contains(m) ? m : getDefaultModel();
    }

    /** 访问时顺带做过期刷新（超过 24h 自动重探，模型上下线自动跟上） */
    public void ensureFresh() {
        if (!autoDetect || !probed) {
            return;
        }
        if (System.currentTimeMillis() - lastRefreshAt < TTL_MS) {
            return;
        }
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                doRefresh();
            } catch (Exception e) {
                logger.warn("模型目录刷新失败: {}", e.toString());
            } finally {
                refreshing.set(false);
            }
        }, "model-catalog-refresh");
        t.setDaemon(true);
        t.start();
    }

    // ==================== 启动/定时 ====================

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!autoDetect) {
            logger.info("模型自动探测已关闭（chat.auto-detect=false），使用配置模型: {}", getDefaultModel());
            return;
        }
        if (apiKey == null || apiKey.isBlank() || apiKey.contains("your-api-key")) {
            logger.warn("未配置 DASHSCOPE_API_KEY，跳过模型探测，使用配置模型: {}", getDefaultModel());
            return;
        }
        logger.info("开始后台探测可用对话模型（候选: {}）", getUsableModels());
        Thread t = new Thread(() -> {
            try {
                doRefresh();
            } catch (Exception e) {
                logger.warn("模型目录探测失败，保留配置模型 {}: {}", getDefaultModel(), e.toString());
            } finally {
                refreshing.set(false);
            }
        }, "model-catalog-probe");
        t.setDaemon(true);
        t.start();
    }

    /** 手动强制刷新（可供运维接口调用） */
    public String refreshNow() {
        if (!refreshing.compareAndSet(false, true)) {
            return "探测已在进行中";
        }
        try {
            doRefresh();
            return "刷新完成，默认模型=" + getDefaultModel() + "，可用=" + usableModels;
        } catch (Exception e) {
            return "刷新失败: " + e;
        } finally {
            refreshing.set(false);
        }
    }

    private void doRefresh() {
        long t0 = System.currentTimeMillis();
        List<String> candidates = buildCandidates();
        List<String> ok = new ArrayList<>();
        for (String m : candidates) {
            if (probe(m)) {
                ok.add(m);
                if (ok.size() >= MAX_USABLE) {
                    break;
                }
            }
        }
        if (!ok.isEmpty()) {
            usableModels = Collections.unmodifiableList(ok);
            resolvedDefault = ok.get(0);
            probed = true;
            lastRefreshAt = System.currentTimeMillis();
            logger.info("模型探测完成({}ms)：默认={} 可用={}", System.currentTimeMillis() - t0, resolvedDefault, usableModels);
        } else {
            logger.warn("模型探测：{} 个候选全部不可用，保留配置模型 {}", candidates.size(), getDefaultModel());
        }
    }

    /**
     * 候选顺序：显式钉死的 CHAT_MODEL 最先 → 其余全部按「版本号从新到旧」排序。
     *
     * <p>关键点：{@code app.models} 只是往候选池里加名字，<b>不决定优先级</b>。
     * 否则配置里写的老模型名会一直压过阿里云新上的模型，
     * 「模型更新自动跟上」就永远生效不了。排序完全交给版本号。</p>
     */
    private List<String> buildCandidates() {
        LinkedHashSet<String> pool = new LinkedHashSet<>();
        pool.addAll(splitCsv(candidateCsv));
        pool.addAll(discoverChatModels());
        pool.addAll(BUILTIN_CANDIDATES);

        List<String> ranked = new ArrayList<>(pool);
        ranked.sort(Comparator.comparingInt(this::versionScore).reversed());

        List<String> ordered = new ArrayList<>();
        if (preferredModel != null && !preferredModel.isBlank()) {
            String pin = preferredModel.trim();
            ordered.add(pin);
            ranked.remove(pin);
        }
        ordered.addAll(ranked);
        return ordered;
    }

    private static List<String> splitCsv(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) {
            return out;
        }
        for (String s : csv.split(",")) {
            String v = s.trim();
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        return out;
    }

    /** 从 OpenAI 兼容的 /models 列表里发现聊天模型，按版本从新到旧排序 */
    private List<String> discoverChatModels() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(compatibleBase() + "/models"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("User-Agent", "workbuddy-model-catalog")
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                logger.debug("发现模型失败 HTTP {}: {}", resp.statusCode(), truncate(resp.body()));
                return List.of();
            }
            JsonNode root = MAPPER.readTree(resp.body());
            List<String> picked = new ArrayList<>();
            for (JsonNode n : root.path("data")) {
                String id = n.path("id").asText("");
                if (id.isEmpty()) {
                    continue;
                }
                if (CHAT_LIKE.matcher(id).matches() && !EXCLUDE.matcher(id).find()) {
                    picked.add(id);
                }
            }
            picked.sort(Comparator.comparingInt(this::versionScore).reversed());
            logger.info("从 /models 自动发现 {} 个聊天候选: {}", picked.size(), picked);
            return picked;
        } catch (Exception e) {
            logger.debug("发现模型异常: {}", e.toString());
            return List.of();
        }
    }

    /** qwen3.8-max → 3*1000+8*10+3(max)=3083；qwen-max 无数字 → 300 */
    private int versionScore(String id) {
        String low = id.toLowerCase();
        int tier = low.contains("max") ? 3 : low.contains("plus") ? 2 : 1;
        Matcher m = VERSION.matcher(low);
        if (m.find()) {
            int major = Integer.parseInt(m.group(1));
            int minor = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
            return major * 1000 + minor * 10 + tier;
        }
        return 300 + tier;
    }

    /** 对「原生端点」实测该模型是否可用（1 token 探测，成本可忽略） */
    private boolean probe(String model) {
        try {
            String body = MAPPER.writeValueAsString(Map.of(
                    "model", model,
                    "input", Map.of("messages", List.of(Map.of("role", "user", "content", "hi"))),
                    "parameters", Map.of("result_format", "message", "max_tokens", 1)));
            HttpRequest req = HttpRequest.newBuilder(URI.create(nativeEndpoint()))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "workbuddy-model-catalog")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            boolean ok = resp.statusCode() == 200;
            if (ok) {
                logger.info("  [可用] {}", model);
            } else {
                logger.info("  [不可用] {} HTTP {} {}", model, resp.statusCode(), truncate(resp.body()));
            }
            return ok;
        } catch (Exception e) {
            logger.info("  [异常] {} {}", model, e.toString());
            return false;
        }
    }

    // ==================== 地址归一化 ====================

    /** 去掉可能的 /api/v1、/compatible-mode/v1 与尾部斜杠，得到纯 host */
    private String hostBase() {
        String b = baseUrl == null ? "" : baseUrl.trim();
        if (b.isEmpty()) {
            b = "https://dashscope.aliyuncs.com";
        }
        b = b.replaceAll("/+$", "");
        b = b.replaceAll("(?i)/compatible-mode/v1$", "");
        b = b.replaceAll("(?i)/compatible-mode$", "");
        b = b.replaceAll("(?i)/api/v1$", "");
        return b;
    }

    private String compatibleBase() {
        return hostBase() + "/compatible-mode/v1";
    }

    private String nativeEndpoint() {
        return hostBase() + "/api/v1/services/aigc/text-generation/generation";
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').trim();
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }
}
