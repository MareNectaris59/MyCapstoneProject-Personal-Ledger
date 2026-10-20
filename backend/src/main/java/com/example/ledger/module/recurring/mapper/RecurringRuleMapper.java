package com.example.ledger.module.recurring.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.ledger.module.recurring.entity.RecurringRule;
import org.apache.ibatis.annotations.Mapper;

/**
 * 定期记账规则 Mapper。
 *
 * <p>查询都用 MyBatis-Plus 的 {@code LambdaQueryWrapper} 组合，
 * 需要自定义 SQL 的地方再补注解 —— 目前没有，所以这个接口是空的。
 */
@Mapper
public interface RecurringRuleMapper extends BaseMapper<RecurringRule> {
}
