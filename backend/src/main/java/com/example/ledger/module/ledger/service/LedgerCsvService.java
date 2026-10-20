package com.example.ledger.module.ledger.service;

import com.example.ledger.common.constant.LedgerType;
import com.example.ledger.common.constant.SubjectType;
import com.example.ledger.common.exception.BizException;
import com.example.ledger.common.result.ResultCode;
import com.example.ledger.common.util.MoneyUtil;
import com.example.ledger.module.ledger.dto.LedgerRequests;
import com.example.ledger.module.ledger.mapper.LedgerCsvMapper;
import com.example.ledger.module.subject.entity.Subject;
import com.example.ledger.module.subject.mapper.SubjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/**
 * 台账的 CSV 导出与导入。
 *
 * <p><b>格式设计</b>（六列，导出与导入共用一份）：
 * <pre>
 *   日期,类型,金额,科目编码,对方账户编码,备注
 *   2026-10-05,EXPENSE,30.00,50010102,100104,午饭
 * </pre>
 * <ul>
 *   <li>EXPENSE：科目=费用科目，对方账户=付款资产科目</li>
 *   <li>INCOME ：科目=收入科目，对方账户=收款资产科目</li>
 *   <li>TRANSFER：科目=转出账户，对方账户=转入账户</li>
 * </ul>
 *
 * <p><b>为什么用科目编码而不是名称</b>：名称可以改、可以重名，
 * 编码是本系统里稳定且唯一的标识。用名称做导入键，用户在界面上改个分类名
 * 就会让昨天的导出文件导不进来。
 *
 * <p><b>为什么只覆盖三种类型</b>：期初余额是"账户初始化"、冲正是"审计修正"，
 * 都不是可以复制粘贴的交易。导出时已排除（见 {@link LedgerCsvMapper}），
 * 导入时也不接受 —— 硬塞进来会破坏账务语义。
 *
 * <p><b>导入的原子性</b>：先把整份文件解析并校验完，只要有一行不合格就
 * 整份拒绝、一条都不写。财务数据最怕"导了一半" —— 用户既不知道导到哪，
 * 也不好回退。
 *
 * <p><b>重复导入是安全的</b>：幂等键由「文件内容哈希 + 行号」确定性生成，
 * 同一份文件导两次，第二次全部计为 skipped，不会产生重复账目。
 */
@Service
public class LedgerCsvService {

    private static final Logger log = LoggerFactory.getLogger(LedgerCsvService.class);

    /** 导出上限：超过就提示缩小日期范围，避免一次拉出几万行 */
    private static final int EXPORT_LIMIT = 5000;
    /** 单次导入上限 */
    private static final int IMPORT_LIMIT = 2000;

    private static final String HEADER = "日期,类型,金额,科目编码,对方账户编码,备注";

    private final LedgerCsvMapper csvMapper;
    private final SubjectMapper subjectMapper;
    private final LedgerCoreService ledgerCoreService;

    public LedgerCsvService(LedgerCsvMapper csvMapper,
                            SubjectMapper subjectMapper,
                            LedgerCoreService ledgerCoreService) {
        this.csvMapper = csvMapper;
        this.subjectMapper = subjectMapper;
        this.ledgerCoreService = ledgerCoreService;
    }

    // ================================================================ 导出

    /**
     * 导出台账为 CSV。
     *
     * <p>带 UTF-8 BOM —— 与分析模块的导出同一理由：不带 BOM 的 UTF-8 CSV
     * 被 Excel 按本地编码解释，中文列全乱码。
     */
    public byte[] exportCsv(Long userId, LocalDate startDate, LocalDate endDate) {
        List<Map<String, Object>> rows = csvMapper.selectExportRows(userId, startDate, endDate);

        // 按凭证分组：每张业务凭证恰好两条分录（一借一贷）
        Map<Long, List<Map<String, Object>>> byLedger = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Long ledgerId = toLong(row.get("ledger_id"));
            byLedger.computeIfAbsent(ledgerId, k -> new ArrayList<>()).add(row);
        }
        if (byLedger.size() > EXPORT_LIMIT) {
            throw BizException.of(ResultCode.PARAM_INVALID,
                    "记录过多（" + byLedger.size() + " 笔），请缩小日期范围后分批导出");
        }

        StringBuilder csv = new StringBuilder();
        csv.append(HEADER).append('\n');
        for (List<Map<String, Object>> entries : byLedger.values()) {
            csv.append(toCsvLine(entries)).append('\n');
        }

        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = csv.toString().getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, payload, 0, bom.length);
        System.arraycopy(body, 0, payload, bom.length, body.length);
        return payload;
    }

    /** 一张凭证的两条分录 → 一行 CSV。 */
    private String toCsvLine(List<Map<String, Object>> entries) {
        Map<String, Object> first = entries.get(0);
        String type = String.valueOf(first.get("ledger_type"));
        LedgerType ledgerType = LedgerType.valueOf(type);

        // 借方那条是"科目"，贷方那条是"对方账户"。
        // 支出：借费用/贷资产 → 科目=费用、对方=资产 ✓
        // 收入：借资产/贷收入 → 借方是资产，但语义上"科目"应是收入
        // 转账：借转入/贷转出 → 借方是转入，语义上"科目"应是转出
        // 所以不能一律按借方取，要按类型挑。
        String subjectCode;
        String counterCode;
        if (ledgerType == LedgerType.INCOME) {
            subjectCode = codeOf(entries, "CREDIT");
            counterCode = codeOf(entries, "DEBIT");
        } else if (ledgerType == LedgerType.TRANSFER) {
            subjectCode = codeOf(entries, "CREDIT");   // 转出
            counterCode = codeOf(entries, "DEBIT");    // 转入
        } else {
            subjectCode = codeOf(entries, "DEBIT");    // 费用
            counterCode = codeOf(entries, "CREDIT");   // 资产
        }

        return String.join(",",
                csvField(String.valueOf(first.get("occurred_date"))),
                type,
                csvField(MoneyUtil.toPlainString(toDecimal(first.get("amount")))),
                csvField(nullToEmpty(subjectCode)),
                csvField(nullToEmpty(counterCode)),
                csvField(nullToEmpty(first.get("remark")).trim()));
    }

    private String codeOf(List<Map<String, Object>> entries, String direction) {
        for (Map<String, Object> e : entries) {
            if (direction.equals(String.valueOf(e.get("direction")))) {
                return nullToEmpty(e.get("subject_code"));
            }
        }
        return null;
    }

    // ================================================================ 导入

    /**
     * 导入结果。
     *
     * @param imported 实际新增的笔数
     * @param skipped  因幂等键已存在而跳过的笔数（重复导入同一份文件时全部落这里）
     * @param errors   校验失败的行说明，格式「第 N 行：原因」
     */
    public record ImportResult(int imported, int skipped, List<String> errors) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    /**
     * 导入 CSV。
     *
     * <p>两遍扫描：第一遍只解析与校验，收集全部错误；有错就整份拒绝。
     * 第二遍才真正写入，整体在一个事务里。
     */
    @Transactional(rollbackFor = Exception.class)
    public ImportResult importCsv(Long userId, String csv) {
        if (csv == null || csv.isBlank()) {
            throw BizException.of(ResultCode.PARAM_INVALID, "CSV 内容为空");
        }
        // Excel 另存为 CSV 时常带 BOM，先剥掉，否则第一列会多一个不可见字符
        String content = csv.startsWith("\uFEFF") ? csv.substring(1) : csv;
        List<String> lines = splitLines(content);

        Map<String, Subject> byCode = subjectIndex(userId);
        String fileHash = shortHash(content);

        List<ParsedRow> parsed = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        int lineNo = 0;
        for (String raw : lines) {
            lineNo++;
            if (raw.isBlank()) {
                continue;
            }
            if (lineNo == 1 && raw.startsWith("日期")) {
                continue;   // 表头
            }
            try {
                parsed.add(parseRow(raw, lineNo, byCode));
            } catch (BizException ex) {
                errors.add("第 " + lineNo + " 行：" + ex.getMessage());
            } catch (RuntimeException ex) {
                errors.add("第 " + lineNo + " 行：格式不正确（" + ex.getMessage() + "）");
            }
        }

        if (parsed.size() > IMPORT_LIMIT) {
            errors.add("一次最多导入 " + IMPORT_LIMIT + " 行，当前 " + parsed.size() + " 行，请拆分文件");
        }
        if (parsed.isEmpty() && errors.isEmpty()) {
            errors.add("没有可导入的数据行（除表头外为空）");
        }
        if (!errors.isEmpty()) {
            // 整份拒绝：不做部分导入，避免"导了一半"这种最难收拾的状态
            return new ImportResult(0, 0, errors);
        }

        // 语义去重：把账号里已有的业务凭证拼成同一套键。
        // 没有这一层的话，"从本账号导出、又导回本账号"会原样再记一遍 ——
        // 而幂等键按「文件哈希 + 行号」生成，拦不住这种情况（原账目的键不同）。
        Set<String> existing = existingSemanticKeys(userId);

        int imported = 0;
        int skipped = 0;
        for (ParsedRow row : parsed) {
            // 注意：命中后 **不** 把该键加进 existing。
            // 同一份文件里两行完全相同是很正常的需求（同一天买了两瓶水），
            // 那种情况应当两条都记；只有"与导入前已存在的账目重复"才跳过。
            if (existing.contains(row.semanticKey())) {
                skipped++;
                continue;
            }
            String key = "imp-" + fileHash + "-" + row.lineNo();
            if (csvMapper.countByKey(userId, key) > 0) {
                skipped++;
                continue;
            }
            post(userId, row, key);
            imported++;
        }

        log.info("CSV 导入完成 userId={} 新增={} 跳过={} 文件哈希={}",
                userId, imported, skipped, fileHash);
        return new ImportResult(imported, skipped, List.of());
    }

    /** 一行解析结果。 */
    private record ParsedRow(int lineNo, LedgerType type, BigDecimal amount,
                             Long subjectId, Long counterSubjectId,
                             LocalDate occurredDate, String remark,
                             String semanticKey) {
    }

    /** 解析并校验一行。任何不合法都抛 BizException，由调用方记成该行的错误。 */
    private ParsedRow parseRow(String raw, int lineNo, Map<String, Subject> byCode) {
        List<String> cells = splitCsvLine(raw);
        if (cells.size() < 5) {
            throw BizException.of(ResultCode.PARAM_INVALID,
                    "列数不足，需要 6 列（日期,类型,金额,科目编码,对方账户编码,备注），实际 " + cells.size() + " 列");
        }
        String dateText = cells.get(0).trim();
        String typeText = cells.get(1).trim().toUpperCase();
        String amountText = cells.get(2).trim();
        String subjectCode = cells.get(3).trim();
        String counterCode = cells.get(4).trim();
        String remark = cells.size() > 5 ? cells.get(5).trim() : "";

        LocalDate date;
        try {
            date = LocalDate.parse(dateText);
        } catch (DateTimeParseException ex) {
            throw BizException.of(ResultCode.PARAM_INVALID, "日期格式应为 yyyy-MM-dd：" + dateText);
        }

        LedgerType type;
        try {
            type = LedgerType.valueOf(typeText);
        } catch (IllegalArgumentException ex) {
            throw BizException.of(ResultCode.PARAM_INVALID,
                    "类型只能是 EXPENSE / INCOME / TRANSFER：" + typeText);
        }
        if (type == LedgerType.REVERSAL || type == LedgerType.OPENING) {
            throw BizException.of(ResultCode.PARAM_INVALID,
                    "不支持导入 " + typeText + "（冲正与期初余额属于修正/初始化操作，不是交易）");
        }

        BigDecimal amount;
        try {
            amount = new BigDecimal(amountText);
        } catch (NumberFormatException ex) {
            throw BizException.of(ResultCode.PARAM_INVALID, "金额不是合法数字：" + amountText);
        }
        MoneyUtil.validateAmount(amount);

        Subject subject = requireLeaf(byCode, subjectCode, "科目");
        Subject counter = requireLeaf(byCode, counterCode, "对方账户");

        // 科目类型必须与交易类型匹配，避免把收入科目填进支出行这种人祸
        SubjectType expectedMain = type == LedgerType.EXPENSE ? SubjectType.EXPENSE
                : type == LedgerType.INCOME ? SubjectType.INCOME : SubjectType.ASSET;
        SubjectType expectedCounter = type == LedgerType.TRANSFER ? SubjectType.ASSET : SubjectType.ASSET;
        requireType(subject, expectedMain, "科目");
        requireType(counter, expectedCounter, "对方账户");

        // 语义键：与导出行用同一套字段拼装，因此"导出再导入"能识别出是同一条
        String semanticKey = semanticKey(date.toString(), type.name(),
                MoneyUtil.toPlainString(amount), subjectCode, counterCode, remark);
        return new ParsedRow(lineNo, type, amount, subject.getId(), counter.getId(),
                date, remark, semanticKey);
    }

    /** 真正写入，复用记账内核以继承全部校验（借贷平衡、叶子科目、未来日期、幂等）。 */
    private void post(Long userId, ParsedRow row, String idempotencyKey) {
        switch (row.type()) {
            case EXPENSE -> ledgerCoreService.postExpense(userId, new LedgerRequests.Expense(
                    row.amount(), row.subjectId(), row.counterSubjectId(),
                    row.occurredDate(), row.remark(), idempotencyKey));
            case INCOME -> ledgerCoreService.postIncome(userId, new LedgerRequests.Income(
                    row.amount(), row.counterSubjectId(), row.subjectId(),
                    row.occurredDate(), row.remark(), idempotencyKey));
            case TRANSFER -> ledgerCoreService.postTransfer(userId, new LedgerRequests.Transfer(
                    row.amount(), row.subjectId(), row.counterSubjectId(),
                    row.occurredDate(), row.remark(), idempotencyKey));
            default -> throw BizException.of(ResultCode.PARAM_INVALID, "不支持的类型：" + row.type());
        }
    }

    // ================================================================ 内部

    /** 该用户全部科目，按编码索引。 */
    private Map<String, Subject> subjectIndex(Long userId) {
        Map<String, Subject> map = new HashMap<>();
        for (Subject s : subjectMapper.selectList(new LambdaQueryWrapper<Subject>()
                .eq(Subject::getUserId, userId))) {
            if (s.getSubjectCode() != null) {
                map.put(s.getSubjectCode(), s);
            }
        }
        return map;
    }

    private Subject requireLeaf(Map<String, Subject> byCode, String code, String role) {
        if (code == null || code.isBlank()) {
            throw BizException.of(ResultCode.PARAM_INVALID, role + "编码不能为空");
        }
        Subject s = byCode.get(code);
        if (s == null) {
            throw BizException.of(ResultCode.PARAM_INVALID, role + "编码不存在：" + code);
        }
        if (!Boolean.TRUE.equals(s.getIsLeaf())) {
            throw BizException.of(ResultCode.PARAM_INVALID,
                    role + "「" + s.getName() + "」不是末级科目，不能记账");
        }
        return s;
    }

    private void requireType(Subject s, SubjectType expected, String role) {
        if (s.getSubjectType() != expected) {
            throw BizException.of(ResultCode.PARAM_INVALID,
                    role + "「" + s.getName() + "」的类型不对，这里需要" + expected.name());
        }
    }

    /**
     * 账号里已有业务凭证的语义键集合。
     *
     * <p>复用导出用的那条查询 —— 它取出的正是"会被导出的那批凭证"，
     * 语义上恰好等于"导出的文件再导入时应当跳过的那些"。
     */
    private Set<String> existingSemanticKeys(Long userId) {
        Set<String> keys = new HashSet<>();
        Map<Long, List<Map<String, Object>>> byLedger = new LinkedHashMap<>();
        for (Map<String, Object> row : csvMapper.selectExportRows(userId, null, null)) {
            byLedger.computeIfAbsent(toLong(row.get("ledger_id")), k -> new ArrayList<>()).add(row);
        }
        for (List<Map<String, Object>> entries : byLedger.values()) {
            Map<String, Object> first = entries.get(0);
            String type = String.valueOf(first.get("ledger_type"));
            LedgerType ledgerType = LedgerType.valueOf(type);
            String subjectCode;
            String counterCode;
            if (ledgerType == LedgerType.INCOME) {
                subjectCode = codeOf(entries, "CREDIT");
                counterCode = codeOf(entries, "DEBIT");
            } else if (ledgerType == LedgerType.TRANSFER) {
                subjectCode = codeOf(entries, "CREDIT");
                counterCode = codeOf(entries, "DEBIT");
            } else {
                subjectCode = codeOf(entries, "DEBIT");
                counterCode = codeOf(entries, "CREDIT");
            }
            keys.add(semanticKey(String.valueOf(first.get("occurred_date")), type,
                    MoneyUtil.toPlainString(toDecimal(first.get("amount"))),
                    nullToEmpty(subjectCode), nullToEmpty(counterCode),
                    nullToEmpty(first.get("remark")).trim()));
        }
        return keys;
    }

    /**
     * 一条账目的语义键：日期 + 类型 + 金额 + 科目 + 对方账户 + 备注。
     *
     * <p>导出与导入两侧必须用同一个拼装方式，否则"导出再导入"的跳过判断会失效。
     * 用 \u0001 作分隔符（正常备注里不会出现），避免
     * 「a,b」与「a」+「b」拼出同一个键。
     */
    private static String semanticKey(String date, String type, String amount,
                                      String subjectCode, String counterCode, String remark) {
        return String.join("\u0001", date, type, amount, subjectCode, counterCode,
                remark == null ? "" : remark);
    }

    /** 按行切分，兼容 CRLF。 */
    private List<String> splitLines(String content) {
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        List<String> out = new ArrayList<>();
        for (String line : normalized.split("\n")) {
            out.add(line);
        }
        return out;
    }

    /**
     * 解析一行 CSV，支持双引号包裹与内部转义的双引号（RFC 4180）。
     *
     * <p>不用 {@code split(",")}：备注里写「午饭,和同事」是很正常的需求，
     * 直接按逗号切会把一行切成七列。
     */
    private List<String> splitCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString());
        return cells;
    }

    /** CSV 字段转义：含逗号 / 引号 / 换行时整体加引号。 */
    private static String csvField(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static String shortHash(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);   // 16 个十六进制字符
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private static String nullToEmpty(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static Long toLong(Object v) {
        if (v == null) {
            return null;
        }
        return v instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(v));
    }

    private static BigDecimal toDecimal(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        return v instanceof BigDecimal bd ? bd : new BigDecimal(String.valueOf(v));
    }
}
