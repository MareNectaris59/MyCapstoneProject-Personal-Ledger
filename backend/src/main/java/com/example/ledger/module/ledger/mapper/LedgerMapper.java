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

    /**
     * 取某用户某日已存在的最大凭证号，用于 {@code buildLedgerNo} 的序号兜底。
     *
     * <p><b>为什么必须有它？</b>凭证号里的流水号主体来自进程内存计数
     * （{@link java.util.concurrent.ConcurrentHashMap} 原子自增）。纯内存计数
     * 在应用重启后会从 0 重来，与当天已存在的号码冲突，导致记账失败 ——
     * 例如当天已记到 {@code LE202503050003}，重启后再记一笔会再生成
     * {@code LE202503050001}，撞 {@code uk_user_no}。
     *
     * <p>调用方拿这个值与内存计数取较大者，即可在重启后无缝接上。
     *
     * <p>{@code ledger_no} 形如 {@code LE202503050001}，按字符串比较与按数值
     * 比较结果一致（定长、前缀相同、末段零填充），所以 {@code MAX} 可以直接用。
     *
     * <p>索引可用性：{@code uk_user_no (user_id, ledger_no)} 的最左前缀是
     * {@code user_id}，因此该查询能走索引，代价可接受。
     *
     * @param userId 用户 ID
     * @param prefixLike 形如 {@code LE20250305%} 的 LIKE 模式
     * @return 最大凭证号；当天没有记录时为 {@code null}
     */
    @Select("SELECT MAX(ledger_no) FROM ledger "
            + "WHERE user_id = #{userId} AND ledger_no LIKE #{prefixLike}")
    String selectMaxLedgerNo(@Param("userId") Long userId,
                             @Param("prefixLike") String prefixLike);
}
