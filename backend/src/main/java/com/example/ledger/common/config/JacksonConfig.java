package com.example.ledger.common.config;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.format.DateTimeFormatter;

/**
 * Jackson 序列化配置。
 *
 * <p>关键点：<b>日期必须序列化为 ISO 字符串，而不是数组</b>。
 * 默认的 {@code LocalDate} 会被写成 {@code [2025,3,1]}，
 * 前端拿到这种结构只能自己拼字符串，非常容易出错。
 *
 * <pre>
 * LocalDate     -> "2025-03-01"
 * LocalDateTime -> "2025-03-01T10:23:45"
 * </pre>
 *
 * <p>金额字段不用在这里配置：它们本身就是 {@code String} 类型
 * （DTO 层已用 {@code toPlainString()} 转换），从源头规避浮点精度问题。
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
            builder.modules(module);
            // 未知字段不报错，便于前后端独立演进
            builder.failOnUnknownProperties(false);
        };
    }
}
