package com.example.ledger.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LIKE 通配符转义单元测试。
 *
 * <p>这类"三行工具"最容易被人随手优化掉，而删掉之后**功能不会报错**，
 * 只是搜索结果悄悄变得不对（输入 % 返回全部）。所以把行为逐条钉死。
 */
class SqlLikeTest {

    @Test
    @DisplayName("普通文本原样返回")
    void plainTextUnchanged() {
        assertThat(SqlLike.escape("午饭")).isEqualTo("午饭");
        assertThat(SqlLike.escape("LE20261020")).isEqualTo("LE20261020");
        assertThat(SqlLike.escape("coffee")).isEqualTo("coffee");
    }

    @Test
    @DisplayName("百分号被转义，不再当通配符")
    void percentIsEscaped() {
        // 不转义的话，搜 "%" 会匹配全部记录 —— 用户会以为搜索坏了
        assertThat(SqlLike.escape("%")).isEqualTo("\\%");
        assertThat(SqlLike.escape("50%")).isEqualTo("50\\%");
    }

    @Test
    @DisplayName("下划线被转义（单个任意字符的通配符）")
    void underscoreIsEscaped() {
        assertThat(SqlLike.escape("a_b")).isEqualTo("a\\_b");
        assertThat(SqlLike.escape("_")).isEqualTo("\\_");
    }

    @Test
    @DisplayName("反斜杠先被转义，避免二次转义")
    void backslashEscapedFirst() {
        // 顺序错误会把 a\% 变成 a\\%（匹配"反斜杠 + 任意字符"）而非"字面量百分号"
        assertThat(SqlLike.escape("a\\b")).isEqualTo("a\\\\b");
        assertThat(SqlLike.escape("\\%")).isEqualTo("\\\\\\%");
    }

    @Test
    @DisplayName("首尾空白被去掉")
    void trimsWhitespace() {
        assertThat(SqlLike.escape("  午饭  ")).isEqualTo("午饭");
    }

    @Test
    @DisplayName("null 与纯空白返回 null，调用方据此跳过该过滤条件")
    void nullAndBlankBecomeNull() {
        assertThat(SqlLike.escape(null)).isNull();
        assertThat(SqlLike.escape("")).isNull();
        assertThat(SqlLike.escape("   ")).isNull();
        assertThat(SqlLike.escape("\t\n")).isNull();
    }

    @Test
    @DisplayName("多个通配符混在正常文本里")
    void mixedWildcards() {
        assertThat(SqlLike.escape("100%_done")).isEqualTo("100\\%\\_done");
    }

    @Test
    @DisplayName("中文与数字不受影响（转义只针对三个字符）")
    void unicodeUntouched() {
        assertThat(SqlLike.escape("打车·2026")).isEqualTo("打车·2026");
    }
}
