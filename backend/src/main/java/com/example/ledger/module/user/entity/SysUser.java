package com.example.ledger.module.user.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户账号（{@code sys_user}）。
 *
 * <p>只放高频查询的账号字段；偏好设置放在 {@code user_profile}，
 * 避免每次查用户都要读一堆配置列。
 */
@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录邮箱，统一存小写 */
    private String email;

    /** BCrypt 密码哈希，绝不存明文 */
    private String passwordHash;

    private String nickname;

    private String avatarUrl;

    /** ACTIVE / DISABLED / DELETED */
    private String status;

    private Boolean emailVerified;

    private LocalDateTime lastLoginAt;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;

    /** 逻辑删除。置 1 时需同时改写 email 以释放唯一约束 */
    @TableLogic
    @TableField(select = false)
    private Integer deleted;

    /**
     * 注销时间。NULL 表示未注销。
     *
     * <p>与 {@code deleted} 的区别：{@code deleted} 回答"这条记录还有效吗"，
     * {@code deletedAt} 回答"什么时候注销的"。两者都需要 ——
     * 数据保留策略要按注销时间计算（见 V2 迁移脚本注释）。
     *
     * <p>{@code select = false}：这是审计字段，业务查询不需要它，
     * 避免每次查用户都多带一列。
     */
    @TableField(select = false)
    private LocalDateTime deletedAt;

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    /*
     * 注意：这里刻意**不**提供 isDeleted()。
     *
     * Lombok 已为 deleted 字段生成 getDeleted()。若再加一个 isDeleted()，
     * MyBatis 的反射层会认为 deleted 属性存在两个类型不一致的 getter，抛出
     *   ReflectionException: Illegal overloaded getter method with ambiguous
     *   type for property 'deleted'
     * 而且这个错误发生在 insert 阶段，会让注册接口直接 500 —— 排查成本很高。
     *
     * 需要判断是否已注销时，用 getDeleted() != null && getDeleted() == 1，
     * 或直接判断 status 是否为 "DELETED"。
     */
}
