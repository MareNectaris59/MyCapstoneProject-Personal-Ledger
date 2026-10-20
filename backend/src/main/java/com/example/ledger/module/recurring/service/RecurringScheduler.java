package com.example.ledger.module.recurring.service;

import com.example.ledger.module.recurring.dto.RecurringDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定期记账的定时触发器。
 *
 * <p>本项目从第一天就开了 {@code @EnableScheduling}，但一直没有 {@code @Scheduled}
 * 方法 —— 那个注解此前是**死代码**。定期记账是第一个真正需要它的功能。
 *
 * <p><b>为什么放在凌晨 00:10 而不是 00:00</b>：
 * <ul>
 *   <li>避开零点整，那个时刻数据库和系统时钟常有抖动（NTP 校正、备份任务）；</li>
 *   <li>跨零点后 {@code LocalDate.now()} 必然已经是新的一天，
 *       不会因为执行瞬间的日期计算差异把某期记到前一天。</li>
 * </ul>
 *
 * <p><b>为什么它可以随便重跑</b>：执行本身是幂等的（幂等键
 * {@code rec-<规则ID>-<执行日>}），所以哪怕任务被触发两次、
 * 或者用户在界面上点了"立即执行"，都不会重复记账。
 * 这一点是整个设计里最关键的保证 —— 定时任务最怕的就是"跑两次就多记一笔"。
 */
@Component
public class RecurringScheduler {

    private static final Logger log = LoggerFactory.getLogger(RecurringScheduler.class);

    private final RecurringService recurringService;

    public RecurringScheduler(RecurringService recurringService) {
        this.recurringService = recurringService;
    }

    /**
     * 每天 00:10 执行所有到期规则。
     *
     * <p>cron 用 6 段（秒 分 时 日 月 周），Spring 的 {@code @Scheduled} 要求如此。
     * 时区取 JVM 默认时区，与 {@code LocalDate.now()} 的口径一致。
     */
    @Scheduled(cron = "${app.recurring.cron:0 10 0 * * *}")
    public void runDaily() {
        try {
            RecurringDtos.RunResult result = recurringService.runAllDue();
            if (result.generated() > 0 || result.skipped() > 0) {
                log.info("定期记账定时任务完成 新增={} 跳过={} 涉及规则={}",
                        result.generated(), result.skipped(), result.advanced());
            }
        } catch (RuntimeException ex) {
            // 定时任务里的异常必须吞掉并记录：抛出去不会有人处理，
            // 只会让这一天的记账全部中断，且下次执行前无人知晓。
            log.error("定期记账定时任务失败", ex);
        }
    }
}
