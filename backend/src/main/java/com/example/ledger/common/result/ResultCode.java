package com.example.ledger.common.result;

/**
 * 业务错误码。
 *
 * <p>码段划分见《系统设计说明书》§6.1.4：
 * <pre>
 * 0          成功
 * 1000-1099  认证与权限
 * 1100-1199  参数校验
 * 1200-1299  用户
 * 1300-1399  科目
 * 1400-1499  账务
 * 1500-1599  预算
 * 1900-1999  系统
 * </pre>
 */
public enum ResultCode {

    // ---------- 成功 ----------
    SUCCESS(0, "success"),

    // ---------- 1000-1099 认证与权限 ----------
    TOKEN_EXPIRED(1001, "登录已过期，请重新登录"),
    TOKEN_INVALID(1002, "登录凭证无效"),
    NO_PERMISSION(1003, "没有操作权限"),
    ACCOUNT_DISABLED(1004, "账号已被禁用"),
    UNAUTHENTICATED(1005, "请先登录"),

    // ---------- 1100-1199 参数校验 ----------
    PARAM_INVALID(1101, "请求参数不合法"),
    AMOUNT_INVALID(1102, "金额不合法"),
    DATE_INVALID(1103, "日期不合法"),

    // ---------- 1200-1299 用户 ----------
    EMAIL_EXISTS(1201, "该邮箱已被注册"),
    USER_NOT_FOUND(1202, "用户不存在"),
    PASSWORD_ERROR(1203, "邮箱或密码错误"),

    /**
     * 操作过于频繁（登录失败次数超限）。
     *
     * <p>与 1101 参数错误区分开：前端要据此显示"稍后再试"而不是"输入有误"。
     */
    TOO_MANY_REQUESTS(1204, "尝试次数过多，请稍后再试"),
    CODE_INVALID(1204, "验证码不正确"),
    CODE_EXPIRED(1205, "验证码已过期"),
    CODE_SEND_TOO_FREQUENT(1206, "验证码发送过于频繁，请稍后再试"),

    // ---------- 1300-1399 科目 ----------
    SUBJECT_NOT_FOUND(1301, "科目不存在"),
    SUBJECT_HAS_CHILDREN(1302, "该科目下存在子科目，无法删除"),
    SUBJECT_IN_USE(1303, "该科目已被账目使用，无法删除"),
    SUBJECT_NOT_LEAF(1304, "只能对最末级科目记账"),
    SUBJECT_DIRECTION_INVALID(1305, "该科目不支持此记账方向"),
    SUBJECT_TYPE_INVALID(1306, "科目类型不符合要求"),

    // ---------- 1400-1499 账务 ----------
    ENTRY_NOT_BALANCED(1401, "借贷不平衡，记账已回滚"),
    LEDGER_NOT_FOUND(1402, "账目不存在"),
    LEDGER_ALREADY_REVERSED(1403, "该账目已被完全冲正"),
    REVERSAL_AMOUNT_EXCEEDED(1404, "冲正金额超出可冲正范围"),
    TRANSFER_SAME_ACCOUNT(1405, "转出与转入账户不能相同"),
    BALANCE_NOT_ENOUGH(1406, "账户余额不足"),
    LEDGER_STATE_INVALID(1407, "账目当前状态不允许该操作"),

    // ---------- 1500-1599 预算 ----------
    BUDGET_NOT_FOUND(1501, "预算不存在"),
    BUDGET_PERIOD_OVERLAP(1502, "同一科目在该时间范围内已存在预算"),
    BUDGET_SUBJECT_TYPE_INVALID(1503, "只有费用类科目可以设置预算"),
    BUDGET_AMOUNT_INVALID(1504, "预算额度不合法"),

    // ---------- 1900-1999 系统 ----------
    INTERNAL_ERROR(1901, "系统开小差了，请稍后重试"),
    DB_ERROR(1902, "数据操作失败"),
    CACHE_ERROR(1903, "缓存服务异常"),
    MAIL_SEND_FAILED(1904, "邮件发送失败"),

    /**
     * 请求的路径不存在（HTTP 404）。
     *
     * <p>归在"系统"段而不是"参数校验"段：这不是参数值不合法，而是路径没有
     * 匹配到任何接口（例如 {@code /api/v1/ledgers/abc}，id 位置不是数字）。
     * 客户端拿到这个码应当改 URL，而不是改参数。
     */
    NOT_FOUND(1905, "请求的路径不存在");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
