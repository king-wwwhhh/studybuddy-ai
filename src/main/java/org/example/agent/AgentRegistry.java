package org.example.agent;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 注册中心：收集所有 Agent 实现，按 type id 索引。
 * ChatService 据此路由与构建 ReactAgent。
 */
@Component
public class AgentRegistry {

    private final Map<String, Agent> agents = new ConcurrentHashMap<>();

    @Autowired
    public AgentRegistry(List<Agent> agentList) {
        for (Agent agent : agentList) {
            agents.put(agent.getType(), agent);
        }
    }

    public Agent get(String type) {
        Agent agent = agents.get(type);
        // 未知类型或 supervisor 自身，回退到通用对话 Agent
        return agent != null ? agent : agents.get("chat");
    }

    /** 全部可选 Agent（不含 supervisor 路由器自身，它不参与应答） */
    public List<Agent> all() {
        List<Agent> list = new ArrayList<>();
        for (Agent a : agents.values()) {
            if (!"supervisor".equals(a.getType())) {
                list.add(a);
            }
        }
        return list;
    }
}
