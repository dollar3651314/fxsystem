package com.falconx.canary;

import com.falconx.canary.command.CanaryCommand;
import com.falconx.canary.report.CanaryReport;
import java.util.List;
import java.util.stream.Collectors;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * STAGE-11-OBS-RECON §11.2 canary CLI 入口。
 *
 * <p>用法：{@code java -jar falconx-canary.jar <command> [args...]}
 *
 * <p>支持命令见 {@link CanaryCommand#name()}：login / trade / deposit-listen / withdraw /
 * price-alert / recon / health-all。
 *
 * <p>退出码：0=PASS / 1=FAIL / 2=PARTIAL_TIMEOUT；JSON 报告输出到 stdout。
 */
@SpringBootApplication
public class CanaryApplication implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CanaryApplication.class);

    private final List<CanaryCommand> commands;
    private final ObjectMapper objectMapper;
    private final ConfigurableApplicationContext context;

    public CanaryApplication(List<CanaryCommand> commands,
                             ObjectMapper objectMapper,
                             ConfigurableApplicationContext context) {
        this.commands = commands;
        this.objectMapper = objectMapper;
        this.context = context;
    }

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(CanaryApplication.class);
        app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        System.exit(SpringApplication.exit(app.run(args)));
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> nonOption = args.getNonOptionArgs();
        if (nonOption.isEmpty()) {
            printUsage();
            context.close();
            System.exit(1);
            return;
        }
        String commandName = nonOption.get(0);
        CanaryCommand command = commands.stream()
                .filter(c -> c.name().equals(commandName))
                .findFirst()
                .orElse(null);
        if (command == null) {
            log.error("canary.unknown-command name={}", commandName);
            printUsage();
            context.close();
            System.exit(1);
            return;
        }
        CanaryReport report;
        try {
            report = command.run(nonOption.subList(1, nonOption.size()));
        } catch (Exception ex) {
            log.error("canary.command.exception name={} message={}", commandName, ex.getMessage(), ex);
            report = CanaryReport.fail(commandName, "uncaught exception: " + ex.getMessage());
        }
        try {
            System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        } catch (Exception ex) {
            log.error("canary.report.serialize-failed message={}", ex.getMessage());
        }
        context.close();
        System.exit(report.exitCode());
    }

    private void printUsage() {
        String available = commands.stream()
                .map(CanaryCommand::name)
                .sorted()
                .collect(Collectors.joining(" / "));
        log.error("canary.usage commands={}", available);
        System.err.println("Usage: java -jar falconx-canary.jar <command> [args...]");
        System.err.println("Available commands: " + available);
    }
}
