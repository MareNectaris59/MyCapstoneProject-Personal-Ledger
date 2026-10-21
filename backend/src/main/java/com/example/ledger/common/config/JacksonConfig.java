package com.example.ledger.common.config;

import com.example.ledger.common.util.MoneyUtil;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;

/**
 * Jackson 序列化配置。
 *
 * <p>这里管两件事：<b>日期写成 ISO 字符串</b>，以及<b>金额写成字符串</b>。
 *
 * <h2>日期</h2>
 * 默认的 {@code LocalDate} 会被写成 {@code [2025,3,1]}，前端拿到这种结构只能
 * 自己拼字符串，非常容易出错。所以显式指定：
 * <pre>
 * LocalDate     -> "2025-03-01"
 * LocalDateTime -> "2025-03-01T10:23:45"
 * </pre>
 *
 * <h2>金额（这一节的结论是踩过坑之后改的）</h2>
 *
 * <p><b>曾经的错误假设</b>：以为"金额在 DTO 层已经用 {@code toPlainString()}
 * 转成了 String，所以这里不用管"。
 *
 * <p>这对 DTO 端点成立，但对<b>直接返回实体</b>的端点不成立 ——
 * {@code GET /ledgers} 返回的是 {@code PageResult<Ledger>}，而
 * {@code Ledger.amount} 是 {@code BigDecimal}，Jackson 于是把它写成 JSON
 * <b>数字</b> {@code 300.00}。于是同一个系统里出现了两种金额类型：
 * <pre>
 * /analysis/overview  ->  {"expense": "300.00"}   字符串
 * /ledgers            ->  {"amount": 300.00}      数字
 * </pre>
 *
 * <p><b>为什么这个问题长期没被发现</b>：Windows PowerShell 5.1 把 JSON 数字
 * {@code 0.00} 解析成 {@code [decimal]} 并保留标度，打印出来仍是 {@code "0.00"}，
 * 端到端断言一直是绿的；换到 PowerShell 7（CI 用的）解析成 {@code [double]}，
 * 打印出来是 {@code "0"}，断言立刻失败。
 *
 * <p><b>结论</b>：与其在每个 DTO 里手写 String（漏一个就是破口），
 * 不如在这里把 {@code BigDecimal} 全局按字符串写出。
 * {@code BigDecimal} 在本项目里只用于金额与比率，统一成字符串没有副作用，
 * 而且这才是"金额一律字符串"这条约定真正可靠的落实方式。
 */
@Configuration
public class JacksonConfig {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule();
            module.addSerializer(java.time.LocalDate.class, new LocalDateSerializer(DATE));
            module.addDeserializer(java.time.LocalDate.class, new LocalDateDeserializer(DATE));
            module.addSerializer(java.time.LocalDateTime.class, new LocalDateTimeSerializer(DATE_TIME));
            module.addDeserializer(java.time.LocalDateTime.class, new LocalDateTimeDeserializer(DATE_TIME));
            // 金额一律字符串，见类注释
            module.addSerializer(BigDecimal.class, new MoneySerializer());
            builder.modules(module);
            // 未知字段不报错，便于前后端独立演进
            builder.failOnUnknownProperties(false);
        };
    }

    /**
     * 把 BigDecimal 写成字符串。
     *
     * <p>null 仍然写 null —— 前端要靠它区分"没有值"和"值为 0"，
     * 不能偷偷变成 {@code "0.00"}。
     */
    private static final class MoneySerializer extends JsonSerializer<BigDecimal> {

        @Override
        public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            if (value == null) {
                gen.writeNull();
            } else {
                gen.writeString(MoneyUtil.toPlainString(value));
            }
        }
    }
}
