package org.example.controller;

import org.example.dto.ApiResponse;
import org.example.entity.LearnerProfile;
import org.example.service.MemoryService;
import org.example.service.ProfileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 记忆与画像接口：学习者长期画像读写 + 短期记忆会话列表。
 */
@RestController
@RequestMapping("/api")
public class MemoryController {

    @Autowired
    private ProfileService profileService;
    @Autowired
    private MemoryService memoryService;

    private static <T> ResponseEntity<ApiResponse<T>> ok(ApiResponse<T> r) {
        return ResponseEntity.ok(r);
    }

    /** 读取学习者长期画像（单例行 id=1） */
    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<LearnerProfile>> getProfile() {
        return ok(ApiResponse.success(profileService.get()));
    }

    /** 保存/更新学习者画像 */
    @PutMapping("/profile")
    public ResponseEntity<ApiResponse<String>> saveProfile(@RequestBody LearnerProfile p) {
        profileService.save(p);
        return ok(ApiResponse.success("ok"));
    }

    /** 列出全部对话会话（短期记忆），供前端历史恢复 */
    @GetMapping("/memory/sessions")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> sessions() {
        return ok(ApiResponse.success(memoryService.listSessions()));
    }

    /** 加载单个会话的全部消息，供前端「切换会话」恢复对话气泡 */
    @GetMapping("/memory/sessions/{id}")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> sessionMessages(@PathVariable String id) {
        return ok(ApiResponse.success(memoryService.loadMessages(id)));
    }
}
