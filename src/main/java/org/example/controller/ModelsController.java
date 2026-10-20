package org.example.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 暴露可用模型列表，供前端「切换模型」下拉使用。
 */
@RestController
@RequestMapping("/api")
public class ModelsController {

    /** 逗号分隔的可用模型 id（可在 application.yml 的 app.models 覆盖） */
    @Value("${app.models:qwen3.7-max,qwen-max,qwen-plus,qwen-flash}")
    private String modelsCsv;

    @Value("${chat.model:qwen3.7-max}")
    private String defaultModel;

    /** 模型元数据：id -> [显示名, 描述] */
    private static final Map<String, String[]> META = new LinkedHashMap<>();
    static {
        META.put("qwen3.7-max", new String[]{"Qwen3.7-Max", "通义千问旗舰，推理与知识最全面"});
        META.put("qwen-max", new String[]{"Qwen-Max", "经典旗舰，稳定可靠"});
        META.put("qwen-plus", new String[]{"Qwen-Plus", "均衡性价比，速度优于 Max"});
        META.put("qwen-flash", new String[]{"Qwen-Flash", "极速响应，适合高频对话"});
    }

    @GetMapping("/models")
    public Map<String, Object> listModels() {
        Set<String> enabled = new LinkedHashSet<>();
        for (String id : modelsCsv.split(",")) {
            id = id.trim();
            if (!id.isEmpty()) {
                enabled.add(id);
            }
        }
        // 若配置未列出默认模型，确保默认模型仍出现在列表中
        enabled.add(defaultModel);

        List<Map<String, String>> models = new ArrayList<>();
        for (String id : enabled) {
            String[] meta = META.getOrDefault(id, new String[]{id, ""});
            Map<String, String> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", meta[0]);
            m.put("description", meta[1]);
            models.add(m);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("current", defaultModel);
        resp.put("models", models);
        return resp;
    }
}
