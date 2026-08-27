package org.example.agent;

import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.KnowledgeSearchTool;
import org.example.agent.tool.PlanTool;
import org.example.agent.tool.ProfileTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 规划师 Agent：依据考试日期与资料，生成结构化学习计划（阶段/周/日任务）。
 */
@Component
public class PlannerAgent implements Agent {

    private final PlanTool planTool;
    private final KnowledgeSearchTool knowledgeSearchTool;
    private final ProfileTool profileTool;
    private final DateTimeTools dateTimeTools;

    @Autowired
    public PlannerAgent(PlanTool planTool, KnowledgeSearchTool knowledgeSearchTool,
                        ProfileTool profileTool, DateTimeTools dateTimeTools) {
        this.planTool = planTool;
        this.knowledgeSearchTool = knowledgeSearchTool;
        this.profileTool = profileTool;
        this.dateTimeTools = dateTimeTools;
    }

    @Override public String getType() { return "planner"; }
    @Override public String getName() { return "规划师"; }
    @Override public String getDescription() { return "根据考试日期、科目与资料，制定可执行的复习计划。"; }
    @Override public double getTemperature() { return 0.4; }

    @Override
    public String getSystemPrompt() {
        return "你是「规划师」。先调用 getProfile 了解学员的科目、考试日期与目标；可调用 knowledgeSearch " +
                "估算资料体量。然后调用 createPlan 生成结构化计划：按阶段/周/日拆解任务，设定每天可完成的 " +
                "expectedHours 与 scheduledDate，避免前松后紧。计划要具体到可打卡。需要时间时调用 getCurrentDateTime。" +
                "重要：createPlan 的 totalDays 就是总天数，tasks 必须覆盖全部 N 天（day 从 1 到 totalDays，每天至少 1 个任务，" +
                "允许每天 2-3 个），总任务数不得少于 totalDays，不要把多天内容挤到一半的天数里。scheduledDate 用 ISO 格式 YYYY-MM-DD。";
    }

    @Override
    public Object[] getMethodTools() {
        return new Object[]{planTool, profileTool, knowledgeSearchTool, dateTimeTools};
    }
}
