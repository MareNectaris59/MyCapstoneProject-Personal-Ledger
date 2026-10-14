package com.example.ledger.module.subject.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.ledger.module.subject.entity.AccountMeta;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

@Mapper
public interface AccountMetaMapper extends BaseMapper<AccountMeta> {

    /**
     * 原子增减账户余额缓存。
     *
     * <p>依赖 InnoDB 行锁：{@code balance = balance + delta} 是原子表达式，
     * 无需显式 {@code SELECT ... FOR UPDATE}。
     *
     * <p><b>调用方必须按 {@code subjectId} 升序依次更新</b>，保证所有事务加锁
     * 顺序一致，从根上消除死锁（《系统设计说明书》§7.7.1）。
     *
     * @return 影响行数，恒为 1；为 0 说明科目不存在或已被删除，应抛异常回滚
     */
    @Update("UPDATE account_meta SET balance = balance + #{delta}, updated_at = NOW(3) "
            + "WHERE subject_id = #{subjectId}")
    int applyDelta(@Param("subjectId") Long subjectId, @Param("delta") BigDecimal delta);
}
