package org.example.agent.tool;

import org.example.util.Mappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.service.CheckinService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 打卡工具（Function Calling）。CoachAgent 读取打卡、写入每日完成情况、计算连续打卡。
 */
@Component
public class CheckinTool {

    private static final Logger logger = LoggerFactory.getLogger(CheckinTool.class);
    public static final String TOOL_NAME = "checkinTool";

    private final CheckinService checkinService;
    private final ObjectMapper mapper = Mappers.JSON;

    @Autowired
    public CheckinTool(CheckinService checkinService) {
        this.checkinService = checkinService;
    }

    @Tool(description = "Record today's check-in for a plan. doneTaskIds is a JSON array of completed task ids, e.g. [3,5]. mood: GREAT/OK/TIRED.")
    public String addCheckin(@ToolParam(description = "Plan id") Long planId,
                             @ToolParam(description = "JSON array of completed task ids, e.g. [3,5]") String doneTaskIds,
                             @ToolParam(description = "Free-text summary of what was studied") String completionText,
                             @ToolParam(description = "Mood: GREAT/OK/TIRED") String mood,
                             @ToolParam(description = "Hours studied today") Double hoursStudied) {
        try {
            return mapper.writeValueAsString(checkinService.addCheckin(
                    planId, LocalDate.now(), doneTaskIds, completionText, mood, hoursStudied));
        } catch (Exception e) {
            return "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}";
        }
    }

    @Tool(description = "Get check-in dashboard for a plan: total check-ins, current streak, total hours.")
    public String dashboard(@ToolParam(description = "Plan id") Long planId) {
        try {
            return mapper.writeValueAsString(checkinService.dashboard(planId));
        } catch (Exception e) {
            return "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}";
        }
    }
}
