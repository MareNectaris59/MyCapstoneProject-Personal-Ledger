package com.example.ledger.module.subject.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.ledger.common.constant.SubjectType;
import com.example.ledger.module.subject.entity.Subject;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SubjectMapper extends BaseMapper<Subject> {

    /**
     * 查询某用户某类型下<b>全部</b>科目的编码，<b>包含已逻辑删除的行</b>。
     *
     * <p><b>为什么必须包含已删除的行？</b>
     * {@code subject} 上有唯一索引 {@code uk_user_code (user_id, subject_code)}，
     * 而该索引<b>不区分 deleted</b>。逻辑删除只是把 {@code deleted} 置 1，
     * 行的 {@code subject_code} 仍然占用着索引位。
     *
     * <p>因此自动生成编码时，如果只统计未删除的行，就会算出与已删除行重复的编码，
     * 插入时直接撞唯一索引，报出 {@code Duplicate entry} —— 用户看到的是
     * "系统开小差"，而不是可理解的提示。
     *
     * <p>实测踩过：新建账户 → 删除该账户 → 再新建账户，第二次必然 500。
     *
     * <p>BaseMapper 的 {@code selectList} 会因 {@code @TableLogic} 自动追加
     * {@code deleted = 0}，所以这里必须用显式 SQL 绕过逻辑删除过滤。
     */
    @Select("SELECT subject_code FROM subject "
            + "WHERE user_id = #{userId} AND subject_type = #{subjectType}")
    List<String> selectAllCodesIncludingDeleted(@Param("userId") Long userId,
                                                @Param("subjectType") SubjectType subjectType);

    /**
     * 查询某父科目下<b>全部</b>直接子科目的编码，包含已逻辑删除的行。
     *
     * <p>用途与 {@link #selectAllCodesIncludingDeleted} 相同：确定分组段前缀时
     * 也要看得见已删除的兄弟，否则会漏判。
     */
    @Select("SELECT subject_code FROM subject "
            + "WHERE user_id = #{userId} AND parent_id = #{parentId}")
    List<String> selectChildCodesIncludingDeleted(@Param("userId") Long userId,
                                                  @Param("parentId") Long parentId);
}
