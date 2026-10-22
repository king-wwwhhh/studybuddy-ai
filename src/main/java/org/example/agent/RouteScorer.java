package org.example.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 意图打分器：否定过滤 + 计分投票 + 歧义判定，替代原先的「正则 first-match」。
 *
 * <p>纯 Java、零 Spring 依赖（便于单独验证）。{@link SupervisorAgent} 只负责编排与 LLM 仲裁。</p>
 *
 * <p>解决三个原实现的问题：</p>
 * <ul>
 *   <li>规则插入顺序被当成优先级：「不要规划，先解释这个公式」曾被送去规划师；</li>
 *   <li>无否定处理：「不要给我出题，解释这道题」曾被送去出题考官；</li>
 *   <li>无歧义判定：多类意图竞争时直接取第一条命中，LLM 仲裁层永远轮不到。</li>
 * </ul>
 */
final class RouteScorer {

    /** 参与路由的 Agent 类型（supervisor 是路由器本身，不参与应答） */
    static final List<String> AGENTS = List.of("chat", "study", "quiz", "planner", "coach");

    /** 关键词 → Agent。插入顺序只用于日志稳定输出，不再决定胜者 */
    private static final Map<Pattern, String> RULES = new LinkedHashMap<>();

    static {
        RULES.put(Pattern.compile("计划|规划|安排|时间表|复习计划|考前|倒计|冲刺安排|每天学"), "planner");
        RULES.put(Pattern.compile("出题|测验|做题|模考|练习|考题|刷题|考考我|自测"), "quiz");
        RULES.put(Pattern.compile("打卡|今天学|进度|监督|拖延|复习得|学了多久|完成情况"), "coach");
        RULES.put(Pattern.compile("解释|讲解|什么意思|怎么理解|这道题|知识点|答疑|概念|公式|为什么"), "study");
    }

    /** 命中词之前的否定窗口（字符数），覆盖「不要给我出题」「不用解释了」这类句式 */
    private static final int NEG_WINDOW = 6;

    private static final Pattern NEGATION = Pattern.compile(
            "不要|不用|不必|不需要|无需|没必要|不想|不是要|不是让你|别说|省掉|用不着|先别|甭|别");

    /** 「别」跟在这些字后面不是否定：特别 / 区别 / 分别 / 级别 / 类别 / 识别 / 性别 */
    private static final String NOT_NEGATION_BEFORE = "特区分类性识道告级";

    /** 指代词：出现即认为当前问题需要上一轮上下文才能判意图 */
    private static final Pattern ANAPHORA =
            Pattern.compile("这个|那个|它|上面|刚才|继续|再来|还有|那道|这题|那题|下一步|接着说");

    /** 歧义且 LLM 不可用时按「错路由副作用最小」兜底：答疑类只产出文本，不落库 */
    private static final List<String> SAFE_PRIORITY = List.of("study", "chat", "quiz", "coach", "planner");

    private RouteScorer() {
    }

    /** 打分结果。agent 只在「单一意图且无竞争」时非空，其余情形交给 LLM 仲裁 */
    static final class Decision {

        final String agent;
        final boolean ambiguous;
        final Map<String, Integer> scores;
        final Map<String, List<String>> evidence;

        Decision(String agent, boolean ambiguous, Map<String, Integer> scores, Map<String, List<String>> evidence) {
            this.agent = agent;
            this.ambiguous = ambiguous;
            this.scores = scores;
            this.evidence = evidence;
        }

        boolean hasEvidence() {
            return !evidence.isEmpty();
        }

        /** 关键词兜底：先比得分，再比证据词长度（更具体的词更可信），最后按安全优先级 */
        String fallbackAgent() {
            String best = null;
            for (String a : AGENTS) {
                int s = scores.getOrDefault(a, 0);
                if (s <= 0) {
                    continue;
                }
                if (best == null) {
                    best = a;
                } else if (s > scores.getOrDefault(best, 0)
                        || (s == scores.getOrDefault(best, 0) && outranks(a, best))) {
                    best = a;
                }
            }
            return best == null ? "chat" : best;
        }

        private boolean outranks(String candidate, String current) {
            int lc = maxKeywordLength(candidate);
            int lcur = maxKeywordLength(current);
            if (lc != lcur) {
                return lc > lcur;
            }
            return safeIndex(candidate) < safeIndex(current);
        }

        private int maxKeywordLength(String agent) {
            int len = 0;
            for (String k : evidence.getOrDefault(agent, List.of())) {
                len = Math.max(len, k.length());
            }
            return len;
        }

        private int safeIndex(String agent) {
            int i = SAFE_PRIORITY.indexOf(agent);
            return i < 0 ? SAFE_PRIORITY.size() : i;
        }

        /** 最强证据，形如 study:解释；无证据返回 null */
        String topEvidence() {
            String bestKeyword = null;
            String bestAgent = null;
            for (Map.Entry<String, List<String>> e : evidence.entrySet()) {
                for (String k : e.getValue()) {
                    if (bestKeyword == null || k.length() >= bestKeyword.length()) {
                        bestKeyword = k;
                        bestAgent = e.getKey();
                    }
                }
            }
            return bestAgent == null ? null : bestAgent + ":" + bestKeyword;
        }

        /** 各 Agent 的非零得分，形如 planner=2,study=1；全零返回 none */
        String scoreText() {
            StringBuilder sb = new StringBuilder();
            for (String a : AGENTS) {
                int s = scores.getOrDefault(a, 0);
                if (s > 0) {
                    if (sb.length() > 0) {
                        sb.append(',');
                    }
                    sb.append(a).append('=').append(s);
                }
            }
            return sb.length() == 0 ? "none" : sb.toString();
        }
    }

    /**
     * 给问题打分。
     *
     * @param question        当前问题
     * @param recentUserTurns 最近两轮用户提问，最近的在前（用于短句/指代词的上下文补齐）
     */
    static Decision score(String question, List<String> recentUserTurns) {
        Map<String, Integer> scores = new LinkedHashMap<>();
        Map<String, List<String>> evidence = new LinkedHashMap<>();
        for (String a : AGENTS) {
            scores.put(a, 0);
        }

        String text = matchText(question, recentUserTurns);
        if (text != null && !text.isEmpty()) {
            for (Map.Entry<Pattern, String> rule : RULES.entrySet()) {
                Matcher m = rule.getKey().matcher(text);
                while (m.find()) {
                    if (negated(text, m.start())) {
                        continue; // 「不要出题」不算出题意图
                    }
                    scores.merge(rule.getValue(), 1, Integer::sum);
                    List<String> hits = evidence.computeIfAbsent(rule.getValue(), k -> new ArrayList<>());
                    String hit = m.group();
                    if (!hits.contains(hit)) {
                        hits.add(hit); // 同一关键词重复出现只记一次证据
                    }
                }
            }
        }

        int top = 0;
        for (int s : scores.values()) {
            top = Math.max(top, s);
        }
        if (top == 0) {
            return new Decision(null, false, scores, evidence);
        }

        List<String> tops = new ArrayList<>();
        int second = 0;
        for (String a : AGENTS) {
            int s = scores.getOrDefault(a, 0);
            if (s == top) {
                tops.add(a);
            } else if (s > second) {
                second = s;
            }
        }
        // 只有一个 Agent 有证据 → 快路径；存在竞争（并列或多家命中）→ 歧义，交给 LLM 仲裁
        if (tops.size() == 1 && second == 0) {
            return new Decision(tops.get(0), false, scores, evidence);
        }
        return new Decision(null, true, scores, evidence);
    }

    /** 短句或含指代词时，把最近一轮用户问题一并纳入匹配，避免「那第一章呢」这类无关键词句无从判断 */
    private static String matchText(String question, List<String> recentUserTurns) {
        boolean hasHistory = recentUserTurns != null && !recentUserTurns.isEmpty();
        if (question == null) {
            return hasHistory ? recentUserTurns.get(0) : "";
        }
        if (!hasHistory) {
            return question;
        }
        boolean needContext = question.trim().length() < 8 || ANAPHORA.matcher(question).find();
        return needContext ? recentUserTurns.get(0) + "｜" + question : question;
    }

    /** 命中位置前 NEG_WINDOW 个字符内出现否定词 → 视为否定命中，不计分 */
    private static boolean negated(String text, int matchStart) {
        int from = Math.max(0, matchStart - NEG_WINDOW);
        String ctx = text.substring(from, matchStart);
        Matcher m = NEGATION.matcher(ctx);
        while (m.find()) {
            if (!isFalseFriend(ctx, m.start())) {
                return true;
            }
        }
        return false;
    }

    /** 「别」只有在不是 特别/区别/分别/级别/类别/识别… 且不是「别人」「别的」时才算否定 */
    private static boolean isFalseFriend(String ctx, int pos) {
        if (!"别".equals(ctx.substring(pos, pos + 1))) {
            return false;
        }
        if (pos > 0 && NOT_NEGATION_BEFORE.indexOf(ctx.charAt(pos - 1)) >= 0) {
            return true;
        }
        return pos + 1 < ctx.length()
                && (ctx.charAt(pos + 1) == '的' || ctx.charAt(pos + 1) == '人');
    }
}
