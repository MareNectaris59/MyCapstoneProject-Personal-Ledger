package com.example.ledger.module.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 认证相关请求与响应体。
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    /**
     * 注册请求。
     *
     * <p>{@code code} 为邮箱验证码。开发环境可开启
     * {@code app.auth.skip-email-verify=true} 跳过校验，方便本地联调
     * （邮件服务未接入时不必卡住）。
     */
    public record RegisterRequest(
            @NotBlank(message = "邮箱不能为空")
            @Email(message = "邮箱格式不正确")
            String email,

            @NotBlank(message = "密码不能为空")
            @Size(min = 8, max = 64, message = "密码长度需在 8-64 位之间")
            String password,

            @Size(max = 50, message = "昵称不能超过 50 字")
            String nickname,

            String code
    ) {
    }

    /** 登录请求。 */
    public record LoginRequest(
            @NotBlank(message = "邮箱不能为空")
            @Email(message = "邮箱格式不正确")
            String email,

            @NotBlank(message = "密码不能为空")
            String password
    ) {
    }

    /** 刷新令牌请求。 */
    public record RefreshRequest(
            @NotBlank(message = "刷新令牌不能为空")
            String refreshToken
    ) {
    }

    /** 登录成功响应。 */
    public record LoginResponse(
            String accessToken,
            String refreshToken,
            long expiresIn,
            UserInfo user
    ) {
    }

    /** 当前用户信息。 */
    public record UserInfo(
            Long userId,
            String email,
            String nickname,
            String avatarUrl,
            boolean emailVerified,
            Integer weekStart,
            boolean strictBalanceMode,
            String budgetWarnThreshold
    ) {
    }

    /** 发送验证码请求。 */
    public record SendCodeRequest(
            @NotBlank(message = "邮箱不能为空")
            @Email(message = "邮箱格式不正确")
            String email,

            /** REGISTER 或 RESET_PWD */
            @NotBlank(message = "场景不能为空")
            String scene
    ) {
    }

    /** 当前用户信息（含可用科目类型提示，供前端渲染筛选器）。 */
    public record MeResponse(UserInfo user, List<String> availableSubjectTypes) {
    }

    /**
     * 注销账号请求。
     *
     * <p>必须提供密码：注销不可逆、且会清除个人信息，属于高风险操作。
     * 不能只凭一个已登录的会话就执行 —— 否则设备被他人短暂接触即可销毁账号。
     */
    public record DeleteAccountRequest(
            @NotBlank(message = "请输入密码以确认注销")
            String password,

            @Size(max = 255, message = "原因不能超过 255 字")
            String reason
    ) {
    }

    /**
     * 注销结果。
     *
     * <p>明确告知账务流水的处理方式（保留），避免用户误以为已全部抹除。
     */
    public record DeleteAccountResponse(
            String deletedAt,
            String message
    ) {
    }
}
