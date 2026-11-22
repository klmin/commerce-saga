package com.klmin.commerce.tools.dlt;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * DLT 조회·재처리 CLI. 처리할 수 없어 {@code <토픽>.DLT} 로 옮겨진 메시지를 원인을 고친 뒤 원래 토픽으로 다시 보낸다.
 *
 * <pre>
 * dlt list   &lt;토픽&gt;.DLT                       보존된 메시지와 실패 원인
 * dlt replay &lt;토픽&gt;.DLT --offset P:O[,P:O]    고른 메시지만 다시 보냄
 * dlt replay &lt;토픽&gt;.DLT --all                 전부 다시 보냄
 *     --dry-run                                 보내지 않고 대상만 출력
 * 환경 변수 KAFKA_BOOTSTRAP_SERVERS (기본 localhost:9092)
 * </pre>
 *
 * 다시 보내도 메시지 id 가 그대로라, 이미 처리된 메시지가 섞여 있거나 같은 것을 두 번 재처리해도 컨슈머가 한 번만 반영한다.
 * 원인을 고치지 않고 다시 보내면 같은 이유로 다시 DLT 로 온다.
 */
public final class DltTool {

    static final int OK = 0;
    static final int FAILED = 1;
    static final int USAGE = 2;

    private static final int VALUE_PREVIEW = 300;

    private final DltClient client;
    private final PrintStream out;

    DltTool(DltClient client, PrintStream out) {
        this.client = client;
        this.out = out;
    }

    public static void main(String[] args) {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
        String bootstrap = System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
        int code;
        try (DltClient client = new DltClient(bootstrap)) {
            code = new DltTool(client, System.out).run(args);
        } catch (RuntimeException e) {
            System.err.println("FAIL: " + e.getMessage());
            code = FAILED;
        }
        System.exit(code);
    }

    int run(String[] args) {
        if (args.length < 2 || !args[1].endsWith(DltRecord.DLT_SUFFIX)) {
            usage();
            return USAGE;
        }
        String topic = args[1];
        List<String> options = Arrays.asList(args).subList(2, args.length);
        return switch (args[0]) {
            case "list" -> list(topic);
            case "replay" -> replay(topic, options);
            default -> {
                usage();
                yield USAGE;
            }
        };
    }

    private int list(String topic) {
        List<DltRecord> records = client.readAll(topic);
        for (DltRecord r : records) {
            out.printf("[%s] key=%s type=%s id=%s from=%s%n", r.position(), r.key(), r.type(), r.messageId(),
                    r.origin());
            out.println("    error: " + r.error());
            out.println("    value: " + preview(r.value()));
        }
        out.printf("%d message(s) in %s%n", records.size(), topic);
        return OK;
    }

    private int replay(String topic, List<String> options) {
        boolean all = options.contains("--all");
        boolean dryRun = options.contains("--dry-run");
        Set<String> positions = offsets(options);
        if (all == (positions != null)) {
            usage();
            return USAGE;
        }

        List<DltRecord> records = client.readAll(topic);
        List<DltRecord> targets = all ? records
                : records.stream().filter(r -> positions.contains(r.position())).toList();
        if (!all) {
            Set<String> missing = new HashSet<>(positions);
            targets.forEach(r -> missing.remove(r.position()));
            if (!missing.isEmpty()) {
                out.println("FAIL: not found in " + topic + ": " + missing);
                return FAILED;
            }
        }

        for (DltRecord r : targets) {
            String target = r.toReplay().topic();
            if (dryRun) {
                out.printf("[%s] would replay -> %s (key=%s type=%s id=%s)%n", r.position(), target, r.key(),
                        r.type(), r.messageId());
            } else {
                var meta = client.replay(r);
                out.printf("[%s] replayed -> %s/%d@%d (key=%s type=%s id=%s)%n", r.position(), target,
                        meta.partition(), meta.offset(), r.key(), r.type(), r.messageId());
            }
        }
        out.printf("%d message(s) %s from %s%n", targets.size(), dryRun ? "selected" : "replayed", topic);
        return OK;
    }

    /** --offset 1:42,0:7 -> {"1:42", "0:7"}. 옵션이 없으면 null */
    private static Set<String> offsets(List<String> options) {
        int i = options.indexOf("--offset");
        if (i < 0) {
            return null;
        }
        if (i + 1 >= options.size()) {
            throw new IllegalArgumentException("--offset needs P:O[,P:O...]");
        }
        Set<String> result = new HashSet<>();
        for (String p : options.get(i + 1).split(",")) {
            if (!p.matches("\\d+:\\d+")) {
                throw new IllegalArgumentException("invalid position (expected partition:offset): " + p);
            }
            result.add(p);
        }
        return result;
    }

    private static String preview(String value) {
        if (value == null) {
            return "null";
        }
        return value.length() <= VALUE_PREVIEW ? value : value.substring(0, VALUE_PREVIEW) + "...";
    }

    private void usage() {
        out.println("""
                usage:
                  dlt list   <topic>.DLT
                  dlt replay <topic>.DLT --offset P:O[,P:O...] [--dry-run]
                  dlt replay <topic>.DLT --all [--dry-run]
                env: KAFKA_BOOTSTRAP_SERVERS (default localhost:9092)""");
    }
}
