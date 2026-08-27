package org.example.agent;

/**
 * 学习向 Agent 公共契约。
 * 每个 Agent 提供自己的身份、系统提示词与绑定的 Function Calling 工具（methodTools）。
 */
public interface Agent {

    /** Agent 类型 id，与 ChatSession.agent_name 一致：chat / study / quiz / planner / coach */
    String getType();

    /** 人类可读名称，用于 UI 展示，如「答疑导师」 */
    String getName();

    /** 能力描述，供 Supervisor 路由与前端展示 */
    String getDescription();

    /** 系统提示词（角色设定 + 行为约束 + 工具使用指引） */
    String getSystemPrompt();

    /** 该 Agent 绑定的 @Tool 方法对象（Spring Bean 实例数组），供 ReactAgent.methodTools 使用 */
    Object[] getMethodTools();

    /** 采样温度，越低越稳重（规划/测验偏高，闲聊偏低） */
    default double getTemperature() {
        return 0.7;
    }
}
