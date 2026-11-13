package com.example.jarvis.tool;

import org.springframework.stereotype.Component;

import java.math.BigInteger;

/**
 * 乘法工具：两个整数相乘。
 *
 * <p>使用 {@link BigInteger} 而非 {@code long}：模型传来的参数不应被无条件信任，
 * 大整数相乘在 long 范围内可能溢出，BigInteger 避免该问题。</p>
 */
@Component
public class MultiplyTool {

    /**
     * 两个整数相乘。
     *
     * @param a 乘数
     * @param b 乘数
     * @return 乘积
     * @throws IllegalArgumentException 任一乘数为 null
     */
    public BigInteger multiply(BigInteger a, BigInteger b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("乘数不能为空");
        }

        return a.multiply(b);
    }
}
