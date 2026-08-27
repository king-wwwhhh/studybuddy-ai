package org.example.agent;

import org.example.agent.tool.CheckinTool;
import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.PlanTool;
import org.example.agent.tool.ProfileTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 教练 Agent：监督执行、每日打卡反馈、发现拖延自动重排。
 */
@Component
public class CoachAgent implements Agent {

    private final PlanTool planTool;
    private final CheckinTool checkinTool;
    private final ProfileTool profileTool;
    private final DateTimeTools dateTimeTools;

    @Autowired
    public CoachAgent(PlanTool planTool, CheckinTool checkinTool,
                      ProfileTool profileTool, DateTimeTools dateTimeTools) {
        this.planTool = planTool;
        this.checkinTool = checkinTool;
        this.profileTool = profileTool;
        this.dateTimeTools = dateTimeTools;
    }

    @Override public String getType() { return "coach"; }
    @Override public String getName() { return "督导教练"; }
    @Override public String getDescription() { return "监督学习进度、每日打卡、鼓励与重排计划。"; }
    @Override public double getTemperature() { return 0.7; }

    @Override
    public String getSystemPrompt() {
        return "你是「督导教练」，像靠谱的私教：关心进度、给予鼓励、发现拖延就推动调整。\n" +
                "流程：调用 getProfile 了解目标；调用 planTool.getDashboard 看进度；若 getDashboard 返回 " +
                "autoReplanned=true（带 coachNote），向学员说明系统已自动重排剩余任务并鼓励继续；引导学员用 " +
                "checkinTool.addCheckin 打卡（写入当天完成的任务 id、时长、心情）；若学员说跟不上/想放慢，调用 " +
                "planTool.replan 重排。打卡后可用 profileTool 更新学习目标。语气真诚、不唠叨。需要时间时调用 getCurrentDateTime。";
    }

    @Override
    public Object[] getMethodTools() {
        return new Object[]{planTool, checkinTool, profileTool, dateTimeTools};
    }
}
