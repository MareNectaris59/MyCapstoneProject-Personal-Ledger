package com.example.ledger.module.ledger.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.ledger.module.ledger.entity.Ledger;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface LedgerMapper extends BaseMapper<Ledger> {

    /**
     * 加行锁读取凭证，用于串行化并发冲正（《系统设计说明书》§7.4.3）。
     *
     * <p>必须在事务内调用。后到的请求会阻塞至前一个事务提交，
     * 从而看到已更新的 {@code reversed_amount}，超额冲正自然被拒绝。
     */
    @Select("SELECT * FROM ledger WHERE id = #{id} FOR UPDATE")
    Ledger selectForUpdate(@Param("id") Long id);
}
