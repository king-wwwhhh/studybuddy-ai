package org.example.agent;

import org.example.util.Mappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.ChatModelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 主管 Agent（路由决策器）。
 * 这是「多 Agent 编排」的入口：根据用户意图选择最合适的学习 Agent。
 *
 * <p>决策顺序：显式指定 → 关键词计分（否定过滤 + 歧义判定）→ LLM 意图分类 → 兜底。</p>
 * <p>与旧实现的差别：关键词不再「命中即返回」。多类意图竞争时标记歧义、交给 LLM 仲裁，
 * 这样「不要给我出题，解释这道题」不会再被送去出题考官；显式选择仍然优先，但冲突会被记录
 * 并在系统提示词里软性提醒，而不是静默改派用户的选择。</p>
 */
@Component
public class SupervisorAgent implements Agent {

    private static final Logger logger = LoggerFactory.getLogger(SupervisorAgent.class);
    private static final ObjectMapper MAPPER = Mappers.JSON;

    /** 路由来源：便于日志聚合与回归分析 */
    public static final String SRC_EXPLICIT = "explicit";
    public static final String SRC_KEYWORD = "keyword";
    public static final String SRC_LLM = "llm";
    public static final String SRC_FALLBACK = "fallback";

    private final ChatModelFactory chatModelFactory;

    @Autowired
    public SupervisorAgent(ChatModelFactory chatModelFactory) {
        this.chatModelFactory = chatModelFactory;
    }

    /** Agent 类型 id（自身是路由者，不参与最终应答） */
    @Override
    public String getType() {
        return "supervisor";
    }

    @Override
    public String getName() {
        return "主管路由";
    }

    @Override
    public String getDescription() {
        return "根据用户意图把请求分发给最合适的学习 Agent（规划/答疑/测验/教练/闲聊）。";
    }

    @Override
    public String getSystemPrompt() {
        return "你是一个意图分类器。根据用户输入，从 {chat, study, quiz, planner, coach} 中选一个最匹配的标签。";
    }

    @Override
    public Object[] getMethodTools() {
        return new Object[0];
    }

    /** 一次路由的完整决策：Agent + 来源 + 证据，便于日志回归与前端提示 */
    public static final class RouteDecision {

        public final String agent;
        /** 命中的路由层：explicit / keyword / llm / fallback */
        public final String source;
        /** 最强证据，形如 study:解释；无证据为 null */
        public final String evidence;
        /** 各 Agent 的非零得分，形如 planner=2,study=1 */
        public final String scores;
        /** 是否因多类意图竞争而进入仲裁 */
        public final boolean ambiguous;
        /** 显式选择与问题意图冲突时，关键词判定的意图（否则 null） */
        public final String conflictAgent;

        RouteDecision(String agent, String source, String evidence, String scores,
                      boolean ambiguous, String conflictAgent) {
            this.agent = agent;
            this.source = source;
            this.evidence = evidence;
            this.scores = scores;
            this.ambiguous = ambiguous;
            this.conflictAgent = conflictAgent;
        }
    }

    /**
     * 兼容旧调用：只要最终 Agent 类型。
     *
     * @param recentUserTurns 最近两轮用户提问，最近的在前（用于指代消歧，可为 null）
     */
    public String decide(String override, String question, List<String> recentUserTurns) {
        return decideDetailed(override, question, recentUserTurns).agent;
    }

    /**
     * 决策最终应答 Agent，并返回决策依据。
     *
     * @param override        前端显式指定的 Agent（非空且非 supervisor 时优先采纳）
     * @param question        当前问题
     * @param recentUserTurns 最近两轮用户提问，最近的在前（用于指代消歧，可为 null）
     */
    public RouteDecision decideDetailed(String override, String question, List<String> recentUserTurns) {
        RouteScorer.Decision kw = RouteScorer.score(question, recentUserTurns);

        // 1) 显式指定优先：不静默改派用户的选择，只记录冲突（提示词里再软性提醒）
        if (override != null && !override.isBlank() && !"supervisor".equals(override)) {
            String conflict = null;
            if (kw.hasEvidence()) {
                String intended = kw.agent != null ? kw.agent : kw.fallbackAgent();
                if (!override.equals(intended)) {
                    conflict = intended;
                }
            }
            if (conflict != null) {
                logger.info("显式选择与意图冲突: override={} 意图更像={} 分数[{}] 证据[{}]",
                        override, conflict, kw.scoreText(), kw.topEvidence());
            } else {
                logger.info("显式指定 Agent: {}", override);
            }
            return new RouteDecision(override, SRC_EXPLICIT, kw.topEvidence(), kw.scoreText(),
                    kw.ambiguous, conflict);
        }

        // 2) 关键词快路径：单一意图、无竞争，直接采纳（零延迟零成本）
        if (kw.agent != null) {
            logger.info("关键词路由: {} 分数[{}] 证据[{}]", kw.agent, kw.scoreText(), kw.topEvidence());
            return new RouteDecision(kw.agent, SRC_KEYWORD, kw.topEvidence(), kw.scoreText(), false, null);
        }

        // 3) 歧义或无命中：LLM 仲裁。把候选分数一并给它，避免二次瞎猜
        String classified = classifyByLlm(question, kw);
        if (classified != null) {
            logger.info("LLM 仲裁路由: {} 分数[{}] 歧义={}", classified, kw.scoreText(), kw.ambiguous);
            return new RouteDecision(classified, SRC_LLM, kw.topEvidence(), kw.scoreText(),
                    kw.ambiguous, null);
        }

        // 4) 兜底：有证据按得分/安全优先级，无证据走 chat
        String fallback = kw.fallbackAgent();
        logger.warn("路由兜底: {} 分数[{}] 证据[{}]", fallback, kw.scoreText(), kw.topEvidence());
        return new RouteDecision(fallback, SRC_FALLBACK, kw.topEvidence(), kw.scoreText(),
                kw.ambiguous, null);
    }

    /** LLM 意图分类：明确「否定句按真正想要的那件事归类、多意图按主诉求」，降低自相矛盾 */
    private String classifyByLlm(String question, RouteScorer.Decision kw) {
        try {
            String system = "你是意图分类器。只从以下标签中选一个最匹配用户请求的：\n" +
                    "chat=闲聊/通用问答；study=知识点答疑讲解；quiz=出题测验自测；" +
                    "planner=制定学习计划/时间安排；coach=学习进度监督/每日打卡/鼓励。\n" +
                    "判定规则：1) 否定句按用户真正想要的那件事归类，例如「不要出题，解释这道题」应归 study；" +
                    "2) 一句话含多类意图时按主诉求归类，不要被次要动作带走；" +
                    "3) 确实无法判断时输出 chat。\n" +
                    "只输出一个 JSON：{\"agent\":\"标签\"}，不要任何额外文字。";
            StringBuilder user = new StringBuilder("用户请求：").append(question);
            if (kw.ambiguous && kw.hasEvidence()) {
                user.append("\n（关键词初判存在多类意图竞争：").append(kw.scoreText())
                        .append("，请判断用户的主诉求）");
            }
            String raw = chatModelFactory.complete(system, user.toString(), 0.0, 256).trim();
            JsonNode node = MAPPER.readTree(raw.replaceAll("(?s)^[^{]*", "").replaceAll("(?s)}.*$", "}"));
            String agent = node.path("agent").asText("");
            if (RouteScorer.AGENTS.contains(agent) && !"supervisor".equals(agent)) {
                return agent;
            }
            logger.warn("LLM 路由输出非法标签: {}", agent);
        } catch (Exception e) {
            logger.warn("LLM 路由失败: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public double getTemperature() {
        return 0.0;
    }
}
