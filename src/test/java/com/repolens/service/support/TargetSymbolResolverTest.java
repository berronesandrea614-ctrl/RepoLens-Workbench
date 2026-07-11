package com.repolens.service.support;

import com.repolens.domain.entity.CodeSymbolEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TargetSymbolResolverTest {

    private CodeSymbolEntity symbol(long id, String className, String methodName) {
        CodeSymbolEntity s = new CodeSymbolEntity();
        s.setId(id);
        s.setClassName(className);
        s.setMethodName(methodName);
        return s;
    }

    private TargetSymbolResolver indexed() {
        TargetSymbolResolver r = new TargetSymbolResolver();
        r.index(List.of(
                symbol(1, "com.example.UserService", "getUserById"),
                symbol(2, "com.example.UserController", "getUser"),
                symbol(3, "com.example.OrderService", "getUserById") // 同名方法不同类
        ));
        return r;
    }

    @Test
    void resolvesFullyQualifiedClassHashMethod() {
        assertThat(indexed().resolve("com.example.UserService#getUserById")).containsExactly(1L);
    }

    @Test
    void resolvesSimpleClassHashMethod() {
        assertThat(indexed().resolve("UserService#getUserById")).containsExactly(1L);
    }

    @Test
    void resolvesBarePluralMethodNameToAllMatches() {
        assertThat(indexed().resolve("getUserById")).containsExactlyInAnyOrder(1L, 3L);
    }

    @Test
    void resolvesScopedTextMethodByTail() {
        assertThat(indexed().resolve("userService.getUser")).containsExactly(2L);
    }

    @Test
    void returnsEmptyForUnknownExternalTarget() {
        assertThat(indexed().resolve("java.util.List#add")).isEmpty();
    }

    @Test
    void isCaseInsensitive() {
        assertThat(indexed().resolve("USERSERVICE#GETUSERBYID")).containsExactly(1L);
    }

    /** 全限定 target 应精确匹配全限定 className，即使另一个包的同名类有同名方法也不过度匹配。 */
    @Test
    void fullyQualifiedTargetPrefersExactClassOverSameNamedMethod() {
        TargetSymbolResolver r = new TargetSymbolResolver();
        r.index(List.of(
                symbol(1, "com.example.UserService", "getUser"),
                symbol(2, "com.other.UserService", "getUser") // 同简单名不同包 + 同名方法
        ));
        assertThat(r.resolve("com.example.UserService#getUser")).containsExactly(1L);
        assertThat(r.resolve("com.other.UserService#getUser")).containsExactly(2L);
    }

    /** 纯方法名仍解析到所有同名方法（保持旧行为）。 */
    @Test
    void bareMethodNameStillResolvesToAllMatches() {
        TargetSymbolResolver r = new TargetSymbolResolver();
        r.index(List.of(
                symbol(1, "com.example.UserService", "getUser"),
                symbol(2, "com.other.UserService", "getUser")
        ));
        assertThat(r.resolve("getUser")).containsExactlyInAnyOrder(1L, 2L);
    }

    /** 简单类名匹配到多个候选时，resolveBest 只取单个最佳；纯全限定精确匹配依旧单个。 */
    @Test
    void resolveBestReturnsSingleBestForClassQualifiedTarget() {
        TargetSymbolResolver r = new TargetSymbolResolver();
        r.index(List.of(
                symbol(1, "com.example.UserService", "getUser"),
                symbol(2, "com.other.UserService", "getUser")
        ));
        // 全限定精确匹配单个
        assertThat(r.resolveBest("com.example.UserService#getUser")).containsExactly(1L);
        // 简单类名有两个候选，resolveBest 只取一个
        assertThat(r.resolveBest("UserService#getUser")).hasSize(1);
    }

    /** resolveBest 对全限定但类不在索引内的 target 退回常规解析（不无脑丢弃）。 */
    @Test
    void resolveBestFallsBackWhenNoClassMatch() {
        TargetSymbolResolver r = new TargetSymbolResolver();
        r.index(List.of(symbol(1, "com.example.UserService", "getUser")));
        // 类不在索引里但方法名匹配得上，退回常规 resolve（纯方法名）
        assertThat(r.resolveBest("com.absent.Foo#getUser")).containsExactly(1L);
    }
}
