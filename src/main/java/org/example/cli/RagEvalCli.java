package org.example.cli;

import org.example.service.EvalReporter;
import org.example.service.RetrievalEvaluator;
import org.example.service.RetrievalLogParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 检索评测入口：日志文件（+ 可选标注文件）→ recall@k / MRR / 阈值扫描 / 推荐阈值。
 *
 * <p>这是第 5 轮评估器与第 10 轮日志解析器的「最后一公里」：之前能力都有，但需要人写代码调用；
 * 现在一条命令即可跑，输出可以整段贴进文档或工单。</p>
 *
 * <p>用法：</p>
 * <pre>
 * java -cp target/classes:target/dependency/* org.example.cli.RagEvalCli \
 *      logs/app.log labels.json
 * </pre>
 *
 * <p>日志需包含 `检索明细: {"query":...}`（见 {@link RetrievalLogParser}，DEBUG 级别），
 * 标注文件形如 `{"query":[分片id,...]}`，缺省时只输出阈值行为（recall 恒为 0）。</p>
 */
public final class RagEvalCli {

    private RagEvalCli() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.out.println(usage());
            return;
        }
        Path logPath = Path.of(args[0]);
        if (!Files.isReadable(logPath)) {
            System.out.println("日志文件不可读: " + logPath);
            System.out.println(usage());
            return;
        }
        List<String> lines = Files.readAllLines(logPath, StandardCharsets.UTF_8);
        Map<String, List<String>> labels = Map.of();
        if (args.length >= 2) {
            Path labelPath = Path.of(args[1]);
            if (Files.isReadable(labelPath)) {
                labels = EvalReporter.parseLabels(Files.readString(labelPath, StandardCharsets.UTF_8));
            } else {
                System.out.println("标注文件不可读，按无标注处理: " + labelPath);
            }
        }

        List<RetrievalEvaluator.Sample> samples = RetrievalLogParser.parseLines(lines, labels);
        System.out.print(EvalReporter.render(samples, EvalReporter.DEFAULT_KS,
                EvalReporter.DEFAULT_THRESHOLDS, 0.9));
    }

    /** 帮助文本（也用于参数缺失时提示） */
    public static String usage() {
        return String.join("\n",
                "用法: RagEvalCli <日志文件> [标注文件.json]",
                "  日志要求: 包含 `检索明细: {\"query\":...,\"candidates\":[{\"id\":...,\"score\":...}]}`（DEBUG 级别）",
                "  标注格式: {\"query 文本\": [\"分片id\", ...]}（可选；缺省时 recall/MRR 恒为 0）",
                "  运行: java -cp target/classes:<依赖> org.example.cli.RagEvalCli <日志> [标注]");
    }
}
