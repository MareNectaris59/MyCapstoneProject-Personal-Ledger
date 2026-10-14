package com.example.ledger.module.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.ledger.module.user.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 注销账号（软注销）。
     *
     * <p><b>为什么不用 {@code updateById}？</b>{@code SysUser.deleted} 上有
     * {@code @TableLogic}，MyBatis-Plus 会给 update 自动追加 {@code deleted = 0}
     * 条件。而注销恰恰要把 deleted 从 0 改成 1 —— 用 updateById 的话，
     * 第一次调用能成功（此时还是 0），但语义上极其别扭，且任何二次调用都会静默
     * 影响 0 行。这里用显式 SQL 表达意图，也避免依赖 ORM 的隐式行为。
     *
     * <p>{@code email} 由调用方传入改写后的值：{@code uk_email} 是唯一索引，
     * 注销后若仍占用原邮箱，该邮箱就无法再注册新账号。
     *
     * @return 影响行数，恒为 1；为 0 说明用户不存在或已注销
     */
    @Update("UPDATE sys_user SET "
            + "  email = #{maskedEmail}, "
            + "  nickname = '已注销用户', "
            + "  avatar_url = NULL, "
            + "  status = 'DELETED', "
            + "  email_verified = 0, "
            + "  deleted_at = #{deletedAt}, "
            + "  deleted = 1 "
            + "WHERE id = #{userId} AND deleted = 0")
    int markDeleted(@Param("userId") Long userId,
                    @Param("maskedEmail") String maskedEmail,
                    @Param("deletedAt") LocalDateTime deletedAt);
}
