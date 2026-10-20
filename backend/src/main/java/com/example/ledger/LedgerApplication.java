package com.example.ledger;

import org.mybatis.spring.annotation.MapperScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 个人复式记账与消费分析系统 —— 后端入口。
 *
 * @author capstone
 */
@SpringBootApplication
@MapperScan("com.example.ledger.module.**.mapper")
@EnableScheduling
public class LedgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerApplication.class, args);
    }

    /**
     * 启动时打印日志目录的<b>绝对路径</b>。
     *
     * <p>为什么需要这一行：{@code logback-spring.xml} 里的 {@code LOG_DIR}
     * 默认是相对路径 {@code logs}，也就相对于<b>启动时的工作目录</b>。
     * 于是同一个程序：
     * <ul>
     *   <li>从项目根跑 {@code java -jar backend/target/...} → 写到 {@code 项目根/logs}</li>
     *   <li>从 {@code backend} 目录启动 / 在 IDEA 里运行 → 写到 {@code backend/logs}</li>
     * </ul>
     * 实际踩过：排查问题时在 {@code backend/logs} 里找不到当天的日志，
     * 因为那一次是从项目根启动的，日志在另一处。这一行把"日志到底在哪"
     * 变成启动即可见的事实，不必再猜。
     */
    @Bean
    CommandLineRunner logLocationInfo() {
        return args -> {
            Logger log = LoggerFactory.getLogger(LedgerApplication.class);
            String configured = System.getenv().getOrDefault("LOG_DIR", "logs");
            Path resolved = Paths.get(configured).toAbsolutePath().normalize();
            log.info("日志目录: {}", resolved);
            log.info("（相对路径随启动工作目录变化；需要固定请设置环境变量 LOG_DIR，"
                    + "需要详细日志请设置 APP_LOG_LEVEL=DEBUG）");
        };
    }
}
