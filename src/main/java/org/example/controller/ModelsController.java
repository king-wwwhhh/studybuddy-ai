package org.example.controller;

import org.example.service.ModelCatalogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 暴露可用模型列表，供前端「切换模型」下拉使用。
 *
 * <p>列表来源是 {@link ModelCatalogService} 启动后的实测结果，
 * 不是写死的常量 —— 阿里云上/下线模型时无需改代码。</p>
 */
@RestController
@RequestMapping("/api")
public class ModelsController {

    @Autowired
    private ModelCatalogService modelCatalog;

    /** 已知模型的展示元数据：id -> [显示名, 描述]；未知模型自动生成 */
    private static final Map<String, String[]> META = new LinkedHashMap<>();

    static {
        META.put("qwen3.7-max", new String[]{"Qwen3.7-Max", "通义千问旗舰，推理与知识最全面"});
        META.put("qwen-max", new String[]{"Qwen-Max", "经典旗舰，稳定可靠"});
        META.put("qwen-plus", new String[]{"Qwen-Plus", "均衡性价比，速度优于 Max"});
        META.put("qwen-flash", new String[]{"Qwen-Flash", "极速响应，适合高频对话"});
    }

    @GetMapping("/models")
    public Map<String, Object> listModels() {
        modelCatalog.ensureFresh();

        List<Map<String, String>> models = new ArrayList<>();
        for (String id : modelCatalog.getUsableModels()) {
            String[] meta = META.get(id);
            if (meta == null) {
                meta = new String[]{displayName(id), "自动发现的可用模型"};
            }
            Map<String, String> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", meta[0]);
            m.put("description", meta[1]);
            models.add(m);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("current", modelCatalog.getDefaultModel());
        resp.put("probed", modelCatalog.isProbed());
        resp.put("models", models);
        return resp;
    }

    /** 手动触发一次模型重探（改了密钥/换空间后无需重启） */
    @PostMapping("/models/refresh")
    public Map<String, Object> refreshModels() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("message", modelCatalog.refreshNow());
        resp.put("current", modelCatalog.getDefaultModel());
        resp.put("models", modelCatalog.getUsableModels());
        return resp;
    }

    /** qwen3.8-max → Qwen3.8-Max；qwen-plus-latest → Qwen-Plus-Latest */
    private static String displayName(String id) {
        StringBuilder sb = new StringBuilder();
        for (String part : id.split("-")) {
            if (sb.length() > 0) {
                sb.append('-');
            }
            if (part.isEmpty()) {
                continue;
            }
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }
}
