package org.example.controller;

import org.example.agent.Agent;
import org.example.agent.AgentRegistry;
import org.example.dto.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 列表接口：暴露全部学习 Agent 供前端「Agent 切换 / 指示」使用。
 * 返回 ApiResponse<List<{type,name,description}>>，前端经 api() 解包后为数组。
 */
@RestController
@RequestMapping("/api")
public class AgentsController {

    @Autowired
    private AgentRegistry agentRegistry;

    @GetMapping("/agents")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listAgents() {
        List<Map<String, Object>> agents = new ArrayList<>();
        for (Agent a : agentRegistry.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", a.getType());
            m.put("name", a.getName());
            m.put("description", a.getDescription());
            agents.add(m);
        }
        return ResponseEntity.ok(ApiResponse.success(agents));
    }
}
