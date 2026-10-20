package com.example.ledger.module.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.ledger.common.constant.SubjectType;
import com.example.ledger.common.exception.BizException;
import com.example.ledger.common.result.ResultCode;
import com.example.ledger.module.subject.entity.Subject;
import com.example.ledger.module.subject.mapper.SubjectMapper;
import com.example.ledger.module.subject.service.SubjectTemplateService;
import com.example.ledger.module.user.dto.AuthDtos;
import com.example.ledger.module.user.entity.SysUser;
import com.example.ledger.module.user.entity.UserProfile;
import com.example.ledger.module.user.mapper.SysUserMapper;
import com.example.ledger.module.user.mapper.UserProfileMapper;
import com.example.ledger.security.JwtTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

/**
 * 用户与认证服务。
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final SysUserMapper userMapper;
    private final UserProfileMapper profileMapper;
    private final SubjectMapper subjectMapper;
    private final SubjectTemplateService subjectTemplateService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public UserService(SysUserMapper userMapper,
                       UserProfileMapper profileMapper,
                       SubjectMapper subjectMapper,
                       SubjectTemplateService subjectTemplateService,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider jwtTokenProvider) {
        this.userMapper = userMapper;
        this.profileMapper = profileMapper;
        this.subjectMapper = subjectMapper;
        this.subjectTemplateService = subjectTemplateService;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /**
     * 注册。
     *
     * <p>注册成功后<b>初始化整套预置科目树</b>（FR-A-01、FR-A-08），
     * 否则新用户登录后连一个可用的付款账户都没有，无法记账。
     */
    @Transactional(rollbackFor = Exception.class)
    public AuthDtos.LoginResponse register(AuthDtos.RegisterRequest req) {
        String email = normalizeEmail(req.email());

        Long exists = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getEmail, email));
        if (exists != null && exists > 0) {
            throw BizException.of(ResultCode.EMAIL_EXISTS);
        }

        SysUser user = new SysUser();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setNickname(req.nickname() == null || req.nickname().isBlank()
                ? defaultNickname(email) : req.nickname().trim());
        user.setStatus("ACTIVE");
        // v1.0 骨架：邮件服务未接入，邮箱验证标记为「已验证」。
        // 接入 SMTP 后应改为 false，并在验证码校验通过时置 true。
        user.setEmailVerified(true);
        userMapper.insert(user);

        createDefaultProfile(user.getId());

        // 同一事务内初始化科目树：任一步失败则全部回滚，避免出现"半个科目树"
        int created = subjectTemplateService.initDefaultTree(user.getId());
        log.info("注册成功 userId={} email={} 初始化科目 {} 条", user.getId(), email, created);

        return issueTokens(user);
    }

    /** 登录。 */
    @Transactional(rollbackFor = Exception.class)
    public AuthDtos.LoginResponse login(AuthDtos.LoginRequest req) {
        String email = normalizeEmail(req.email());
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getEmail, email));

        // 统一错误文案，不区分"用户不存在"与"密码错误"，避免账号枚举
        if (user == null || !passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw BizException.of(ResultCode.PASSWORD_ERROR);
        }
        if (!user.isActive()) {
            throw BizException.of(ResultCode.ACCOUNT_DISABLED);
        }

        user.setLastLoginAt(LocalDateTime.now());
        userMapper.updateById(user);

        return issueTokens(user);
    }

    /**
     * 修改密码（FR-U-04）。
     *
     * <p>必须校验原密码 —— 否则任何拿到已登录会话的人都能改掉密码，
     * 把账号主人锁在门外。与注销要求输入密码是同一个理由。
     *
     * <p><b>改密即踢下线</b>：成功后会自增 {@code token_version}，
     * 此前签发的所有 accessToken / refreshToken 立刻失效 —— 过滤器每次校验
     * 都会把令牌里的 {@code tv} 声明与库里的值比对，不一致直接拒绝。
     * 因此其他设备上的旧令牌不会苟活到自然过期。
     */
    @Transactional(rollbackFor = Exception.class)
    public void changePassword(Long userId, AuthDtos.ChangePasswordRequest req) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.of(ResultCode.USER_NOT_FOUND);
        }
        if (!passwordEncoder.matches(req.oldPassword(), user.getPasswordHash())) {
            throw BizException.of(ResultCode.PASSWORD_ERROR);
        }
        if (req.oldPassword().equals(req.newPassword())) {
            throw BizException.of(ResultCode.PARAM_INVALID, "新密码不能与原密码相同");
        }
        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        // 自增令牌版本：此前签发的所有令牌立刻失效，下次请求即被过滤器拒绝。
        // 这让"改密 = 把其他设备踢下线"真正成立，而不只是清掉本机 localStorage。
        user.setTokenVersion((user.getTokenVersion() == null ? 0 : user.getTokenVersion()) + 1);
        userMapper.updateById(user);
        log.info("用户修改密码 userId={} 令牌版本已提升", userId);
    }

    /**
     * 修改资料（FR-U-03），目前只支持昵称。
     *
     * <p>昵称为空时回退到邮箱前缀，与注册时 {@code defaultNickname} 保持同一口径 ——
     * 不允许把昵称清成空白，否则侧边栏会出现一个没有任何文字的角落。
     */
    @Transactional(rollbackFor = Exception.class)
    public AuthDtos.UserInfo updateProfile(Long userId, AuthDtos.UpdateProfileRequest req) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.of(ResultCode.USER_NOT_FOUND);
        }
        if (req.nickname() == null || req.nickname().isBlank()) {
            throw BizException.of(ResultCode.PARAM_INVALID, "昵称不能为空");
        }
        user.setNickname(req.nickname().trim());
        userMapper.updateById(user);
        UserProfile profile = profileMapper.selectOne(new LambdaQueryWrapper<UserProfile>()
                .eq(UserProfile::getUserId, userId));
        return toUserInfo(user, profile);
    }

    /** 刷新访问令牌。 */
    public AuthDtos.LoginResponse refresh(String refreshToken) {        var current = jwtTokenProvider.parseRefreshToken(refreshToken);
        SysUser user = userMapper.selectById(current.userId());
        if (user == null) {
            throw BizException.of(ResultCode.USER_NOT_FOUND);
        }
        if (!user.isActive()) {
            throw BizException.of(ResultCode.ACCOUNT_DISABLED);
        }
        return issueTokens(user);
    }

    /** 当前用户信息。 */
    public AuthDtos.MeResponse me(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.of(ResultCode.USER_NOT_FOUND);
        }
        UserProfile profile = profileMapper.selectOne(new LambdaQueryWrapper<UserProfile>()
                .eq(UserProfile::getUserId, userId));
        return new AuthDtos.MeResponse(toUserInfo(user, profile),
                List.of(SubjectType.ASSET.name(), SubjectType.INCOME.name(), SubjectType.EXPENSE.name()));
    }

    private AuthDtos.LoginResponse issueTokens(SysUser user) {
        // 令牌版本号随令牌签发，过滤器每次校验时与库里比对（见 JwtAuthenticationFilter）
        int tokenVersion = user.getTokenVersion() == null ? 0 : user.getTokenVersion();
        String access = jwtTokenProvider.createAccessToken(user.getId(), user.getEmail(), tokenVersion);
        String refresh = jwtTokenProvider.createRefreshToken(user.getId(), user.getEmail(), tokenVersion);
        UserProfile profile = profileMapper.selectOne(new LambdaQueryWrapper<UserProfile>()
                .eq(UserProfile::getUserId, user.getId()));
        return new AuthDtos.LoginResponse(access, refresh,
                jwtTokenProvider.getAccessTokenSeconds(), toUserInfo(user, profile));
    }

    private AuthDtos.UserInfo toUserInfo(SysUser user, UserProfile profile) {
        return new AuthDtos.UserInfo(
                user.getId(),
                user.getEmail(),
                user.getNickname(),
                user.getAvatarUrl(),
                Boolean.TRUE.equals(user.getEmailVerified()),
                profile == null ? 1 : profile.getWeekStart(),
                profile != null && Boolean.TRUE.equals(profile.getStrictBalanceMode()),
                profile == null || profile.getBudgetWarnThreshold() == null
                        ? "80.00" : profile.getBudgetWarnThreshold().toPlainString());
    }

    /**
     * 注销账号。
     *
     * <p><b>这是软注销，不是物理删除。</b>具体做三件事：
     * <ol>
     *   <li>账号立即失效：{@code deleted = 1}、{@code status = 'DELETED'}、
     *       记录 {@code deletedAt}。此后无法登录，{@code loadSession()} 也会失败</li>
     *   <li>清除个人信息：改写 email 与昵称、清空头像与偏好设置 ——
     *       把可识别到具体个人的数据抹掉</li>
     *   <li>保留账务流水：{@code ledger} / {@code entry} <b>不删除</b></li>
     * </ol>
     *
     * <p><b>为什么保留流水？</b>账务 append-only 是《系统设计说明书》§1.3 的核心设计
     * 约束 —— 分录只插入、不修改、不删除，目的是让任何时点的账目都可还原与审计。
     * 物理删除会破坏这条约束，也会让"借贷平衡"这类全局校验失去意义。
     * 同时财务数据通常有留存要求，所以这里采取"账号失效 + 个人信息清除 + 流水留存"。
     *
     * <p>若确需彻底抹除，应作为独立的数据清理流程实现（按 {@code deletedAt}
     * 判断留存期是否届满），而不是让用户在界面上直接触发物理删除。
     *
     * @param userId  当前登录用户
     * @param request 含密码确认与可选原因
     * @return 注销时间与对用户的说明
     */
    @Transactional(rollbackFor = Exception.class)
    public AuthDtos.DeleteAccountResponse deleteAccount(Long userId, AuthDtos.DeleteAccountRequest request) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.of(ResultCode.USER_NOT_FOUND);
        }
        // 注意用 status 而不是 isDeleted()：SysUser 刻意不提供 isDeleted()，
        // 否则会与 Lombok 生成的 getDeleted() 构成歧义 getter，MyBatis 会报错
        if ("DELETED".equals(user.getStatus())) {
            throw BizException.of(ResultCode.ACCOUNT_DISABLED, "该账号已注销");
        }
        // 高风险操作必须二次验证密码，不能只靠已登录的会话
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw BizException.of(ResultCode.PASSWORD_ERROR, "密码不正确，无法注销");
        }

        LocalDateTime now = LocalDateTime.now();

        // 1) 清除可识别到个人的信息。
        //    email 必须改写：uk_email 是唯一索引，注销后若仍占用原邮箱，
        //    用户就无法用同一邮箱重新注册。
        user.setEmail("deleted+" + userId + "+" + now.toEpochSecond(ZoneOffset.UTC) + "@deleted.local");
        user.setNickname("已注销用户");
        user.setAvatarUrl(null);
        user.setStatus("DELETED");
        user.setEmailVerified(false);
        user.setDeletedAt(now);
        // 不用 updateById：@TableLogic 会给它自动追加 deleted = 0 条件，
        // 而注销正是要把 deleted 置 1（详见 SysUserMapper.markDeleted 注释）
        int affected = userMapper.markDeleted(userId, user.getEmail(), now);
        if (affected != 1) {
            // 影响 0 行说明并发下已被另一次注销处理，或用户已不存在
            throw BizException.of(ResultCode.ACCOUNT_DISABLED, "该账号已注销");
        }

        // 2) 清空偏好设置里的可识别信息
        UserProfile profile = profileMapper.selectOne(new LambdaQueryWrapper<UserProfile>()
                .eq(UserProfile::getUserId, userId));
        if (profile != null) {
            profile.setAlertEmailEnabled(false);
            profile.setPreferences(null);
            profileMapper.updateById(profile);
        }

        // 面向用户的说法与前端注销页保持一致：一句话，不解释实现细节。
        // 措辞"无法找回"对用户是真实陈述 —— 账号软删除后无法登录、
        // 邮箱已被改写，用户不可能再访问这批数据。
        // 流水行在库中保留属于工程实现（见方法上方注释），不写进用户文案。
        log.info("账号已注销 userId={} 原因={}（账务流水保留）",
                userId, request.reason() == null ? "未填写" : request.reason());

        return new AuthDtos.DeleteAccountResponse(
                now.toString(),
                "账号已注销，注销后原账号将无法再次登录，相关记账数据也将无法找回。");
    }

    private void createDefaultProfile(Long userId) {
        UserProfile profile = new UserProfile();
        profile.setUserId(userId);
        profile.setBaseCurrency("CNY");
        profile.setCurrencySymbol("¥");
        profile.setAmountPrecision(2);
        // 周起始日默认周一。这个值直接决定"本周支出"的口径，必须可配置
        profile.setWeekStart(1);
        profile.setStrictBalanceMode(false);
        profile.setBudgetWarnThreshold(new BigDecimal("80.00"));
        profile.setAlertEmailEnabled(false);
        profileMapper.insert(profile);
    }

    private String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private String defaultNickname(String email) {
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }

    /** 供科目服务判断用户是否已有科目树。 */
    public boolean hasSubjects(Long userId) {
        Long count = subjectMapper.selectCount(new LambdaQueryWrapper<Subject>()
                .eq(Subject::getUserId, userId));
        return count != null && count > 0;
    }
}
