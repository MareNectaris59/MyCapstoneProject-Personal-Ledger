package com.example.ledger.module.user.controller;

import com.example.ledger.common.exception.BizException;
import com.example.ledger.common.result.Result;
import com.example.ledger.common.result.ResultCode;
import com.example.ledger.module.user.dto.AuthDtos;
import com.example.ledger.module.user.service.UserService;
import com.example.ledger.security.CurrentUser;
import com.example.ledger.security.JwtTokenProvider;
import com.example.ledger.security.TokenBlacklistService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（《系统设计说明书》§6.2.1）。
 *
 * <p>{@code /api/v1/auth/**} 在 SecurityConfig 中被列为公开路径。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserService userService;
    private final JwtTokenProvider jwtTokenProvider;
    private final TokenBlacklistService tokenBlacklistService;

    public AuthController(UserService userService,
                          JwtTokenProvider jwtTokenProvider,
                          TokenBlacklistService tokenBlacklistService) {
        this.userService = userService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    /** 注册（同时初始化预置科目树） */
    @PostMapping("/register")
    public Result<AuthDtos.LoginResponse> register(@Valid @RequestBody AuthDtos.RegisterRequest req) {
        return Result.success(userService.register(req));
    }

    /** 登录 */
    @PostMapping("/login")
    public Result<AuthDtos.LoginResponse> login(@Valid @RequestBody AuthDtos.LoginRequest req) {
        return Result.success(userService.login(req));
    }

    /** 刷新访问令牌 */
    @PostMapping("/refresh")
    public Result<AuthDtos.LoginResponse> refresh(@Valid @RequestBody AuthDtos.RefreshRequest req) {
        return Result.success(userService.refresh(req.refreshToken()));
    }

    /**
     * 退出登录：把当前 accessToken 加入黑名单。
     *
     * <p>黑名单 TTL 取令牌剩余有效期。本骨架用进程内 Map 实现，
     * 生产环境需换成 Redis（见 TokenBlacklistService 注释）。
     */
    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String token = authorization.substring(7).trim();
            // TTL 取令牌剩余有效期；已过期/非法的令牌 TTL 为 0，加进去也会立刻被清理
            tokenBlacklistService.add(token, jwtTokenProvider.remainingTtlMillis(token));
        }
        return Result.success();
    }

    /**
     * 当前登录用户信息。
     *
     * <p>这个接口<b>需要登录</b>（不在 SecurityConfig 的 PUBLIC_PATHS 里）。
     * 这里的空值判断是最后一道防御：万一将来有人把它误加进白名单，
     * 或者安全链被改坏，也应该返回 401 而不是抛出空指针变成 500 ——
     * 500 会把"配置疏漏"伪装成"服务故障"，掩盖真正的问题。
     */
    @GetMapping("/me")
    public Result<AuthDtos.MeResponse> me(@AuthenticationPrincipal CurrentUser user) {
        return Result.success(userService.me(requireLogin(user).userId()));
    }

    /**
     * 校验当前用户已认证。
     *
     * @throws BizException 未认证时抛出，错误码 1005
     */
    private static CurrentUser requireLogin(CurrentUser user) {
        if (user == null) {
            throw BizException.of(ResultCode.UNAUTHENTICATED);
        }
        return user;
    }

    /**
     * 注销账号（删除账号，不是退出登录）。
     *
     * <p>与 {@code /logout} 的区别必须讲清楚，两者容易被混淆：
     * <ul>
     *   <li>{@code /logout} —— 只让当前令牌失效，账号与数据都完好，可以再登录</li>
     *   <li>{@code /delete} —— <b>注销账号</b>：账号永久失效、个人信息被清除，
     *       无法再登录，同一邮箱可以重新注册</li>
     * </ul>
     *
     * <p>注销成功后立即把当前令牌加入黑名单，避免这个已登录的会话还能继续调接口。
     */
    @PostMapping("/delete")
    public Result<AuthDtos.DeleteAccountResponse> deleteAccount(
            @AuthenticationPrincipal CurrentUser user,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody AuthDtos.DeleteAccountRequest req) {

        AuthDtos.DeleteAccountResponse response = userService.deleteAccount(requireLogin(user).userId(), req);

        // 注销成功后立刻吊销当前令牌，否则这个会话在令牌到期前仍然可用
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String token = authorization.substring(7).trim();
            tokenBlacklistService.add(token, jwtTokenProvider.remainingTtlMillis(token));
        }

        return Result.success(response);
    }

    /**
     * 发送邮箱验证码。
     *
     * <p>邮件服务尚未接通（spring-boot-starter-mail 依赖已在，但没有 SMTP 配置），
     * 因此这里只返回提示，不真正发信。
     * 接入 SMTP 后应在此处调用 MailService，并把验证码写入
     * Redis 键 {@code auth:code:{scene}:{email}}，TTL 5 分钟。
     */
    @PostMapping("/code")
    public Result<String> sendCode(@Valid @RequestBody AuthDtos.SendCodeRequest req) {
        return Result.success("邮件服务尚未接入，暂不发送验证码，可直接注册");
    }
}
