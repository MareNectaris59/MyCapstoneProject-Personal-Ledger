package com.example.ledger.common.exception;

import com.example.ledger.common.result.ResultCode;

/**
 * 业务异常。
 *
 * <p>由 Service 层在业务规则不满足时抛出，经 {@code GlobalExceptionHandler}
 * 统一转换为 {@code Result}。业务异常属于"可预期"的错误，日志级别为 WARN，
 * 不打印堆栈。
 */
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ResultCode resultCode;

    public BizException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.resultCode = resultCode;
    }

    /** 覆盖默认提示文案，用于补充上下文（如具体科目名、金额）。 */
    public BizException(ResultCode resultCode, String message) {
        super(message);
        this.resultCode = resultCode;
    }

    public ResultCode getResultCode() {
        return resultCode;
    }

    // ---------- 便捷构造 ----------

    public static BizException of(ResultCode resultCode) {
        return new BizException(resultCode);
    }

    public static BizException of(ResultCode resultCode, String message) {
        return new BizException(resultCode, message);
    }
}
