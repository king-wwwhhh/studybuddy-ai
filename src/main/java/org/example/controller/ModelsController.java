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

    /** 逗号分隔的可用模型 id（可在 application.yml 覆盖） */
    @Value("${app.models:qwen3-max,qwen3-30b-a3b-thinking-2507,qwen3-plus,qwen3-turbo}")
    private String modelsCsv;

    @Value("${chat.model:qwen3-max}")
    private String defaultModel;

    /** 模型元数据：id -> [显示名, 描述] */
    private static final Map<String, String[]> META = new LinkedHashMap<>();
    static {
        META.put("qwen3-max", new String[]{"Qwen3-Max", "通义千问最强旗舰，推理与知识最全面"});
        META.put("qwen3-30b-a3b-thinking-2507", new String[]{"Qwen3-30B 思维版", "轻量思维模型，擅长数学与逻辑推导"});
        META.put("qwen3-plus", new String[]{"Qwen3-Plus", "均衡性价比，速度优于 Max"});
        META.put("qwen3-turbo", new String[]{"Qwen3-Turbo", "极速响应，适合高频对话"});
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
