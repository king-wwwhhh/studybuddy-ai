package org.example.util;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 统一 JSON 序列化器。
 * 关键：注册 JavaTimeModule 并把 LocalDate/LocalDateTime 输出为 ISO 字符串（如 2026-09-05）。
 * 否则 Agent 工具（PlanTool/CheckinTool 等）序列化含日期对象时会抛
 * "Java 8 date/time type not supported"，LLM 收到后误以为"日期格式有问题"，
 * 而计划其实已写入数据库，导致重复创建。
 */
public final class Mappers {

    public static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private Mappers() {}
}
