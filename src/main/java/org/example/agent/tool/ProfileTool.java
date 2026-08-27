package org.example.agent.tool;

import org.example.util.Mappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.entity.LearnerProfile;
import org.example.service.ProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 学习者画像工具（Function Calling）。CoachAgent / PlannerAgent 读取画像以个性化，并回写学习目标。
 */
@Component
public class ProfileTool {

    private static final Logger logger = LoggerFactory.getLogger(ProfileTool.class);
    public static final String TOOL_NAME = "profileTool";

    private final ProfileService profileService;
    private final ObjectMapper mapper = Mappers.JSON;

    @Autowired
    public ProfileTool(ProfileService profileService) {
        this.profileService = profileService;
    }

    @Tool(description = "Read the learner profile: subjects, exam date, learning style, goals, weak subjects. Use it to personalize plans and coaching.")
    public String getProfile() {
        try {
            return mapper.writeValueAsString(profileService.get());
        } catch (Exception e) {
            return "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}";
        }
    }

    @Tool(description = "Update the learner profile. Pass JSON fields you want to set, e.g. {\"subjects\":\"[\\\"数据结构\\\",\\\"算法\\\"]\",\"examDate\":\"2026-09-01\",\"goals\":\"保研面试\"}.")
    public String saveProfile(@ToolParam(description = "Profile fields as JSON") String profileJson) {
        try {
            LearnerProfile p = mapper.readValue(profileJson, LearnerProfile.class);
            profileService.save(p);
            return "{\"status\":\"ok\"}";
        } catch (Exception e) {
            return "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}";
        }
    }
}
