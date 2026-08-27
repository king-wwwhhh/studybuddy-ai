package org.example.controller;

import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用 OpenAI 兼容代理：前端把外部模型（DeepSeek / OpenAI 等）的 baseUrl + apiKey + 对话
 * 转发到 provider。用于浏览器直连被 CORS 拦截时，由同源后端中转，避免跨域问题。
 */
@RestController
@RequestMapping("/api/proxy")
public class ProxyController {

    private final RestTemplate rest = new RestTemplate();

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, Object> body) {
        String baseUrl = (String) body.get("baseUrl");
        String apiKey = (String) body.get("apiKey");
        String model = (String) body.get("model");
        Object messages = body.get("messages");

        Map<String, Object> out = new HashMap<>();
        if (baseUrl == null || apiKey == null || model == null || messages == null) {
            out.put("error", "baseUrl / apiKey / model / messages 必填");
            return out;
        }

        String url = baseUrl.replaceAll("/+$", "");
        if (!url.endsWith("/chat/completions")) url += "/chat/completions";

        LinkedMultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        headers.add("Content-Type", "application/json");
        headers.add("Authorization", "Bearer " + apiKey);

        Map<String, Object> payload = new HashMap<>();
        payload.put("model", model);
        payload.put("messages", messages);
        payload.put("temperature", 0.7);
        payload.put("stream", false);

        try {
            HttpEntity<Map<String, Object>> req = new HttpEntity<>(payload, headers);
            ResponseEntity<Map> resp = rest.postForEntity(url, req, Map.class);
            Map data = resp.getBody();
            String content = "";
            if (data != null) {
                Object choicesObj = data.get("choices");
                if (choicesObj instanceof List) {
                    List choices = (List) choicesObj;
                    if (!choices.isEmpty() && choices.get(0) instanceof Map) {
                        Map c0 = (Map) choices.get(0);
                        Object msgObj = c0.get("message");
                        if (msgObj instanceof Map) {
                            Object c = ((Map) msgObj).get("content");
                            content = c == null ? "" : String.valueOf(c);
                        }
                    }
                }
            }
            out.put("answer", content);
            return out;
        } catch (Exception e) {
            out.put("error", e.getMessage());
            return out;
        }
    }
}
