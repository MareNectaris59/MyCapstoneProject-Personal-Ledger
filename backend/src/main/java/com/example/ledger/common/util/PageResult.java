package com.example.ledger.common.util;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;
import java.util.function.Function;

/**
 * 统一分页响应结构（《系统设计说明书》§6.1.2）。
 */
public record PageResult<T>(
        List<T> records,
        long total,
        long page,
        long size,
        long pages
) {

    public static <T> PageResult<T> of(IPage<T> page) {
        return new PageResult<>(page.getRecords(), page.getTotal(),
                page.getCurrent(), page.getSize(), page.getPages());
    }

    /** 把分页中的实体转换为 DTO。 */
    public static <E, T> PageResult<T> of(IPage<E> page, Function<E, T> mapper) {
        return new PageResult<>(page.getRecords().stream().map(mapper).toList(),
                page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }
}
