package com.example.ledger;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

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
}
