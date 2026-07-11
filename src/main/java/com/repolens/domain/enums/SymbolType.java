package com.repolens.domain.enums;

public enum SymbolType {
    CONTROLLER,
    SERVICE,
    MAPPER,
    ENTITY,
    METHOD,
    CLASS,
    API,
    CONFIG,
    // 多语言（Phase1 TS/JS）泛化符号类型：函数（自由函数/箭头函数）与接口。
    FUNCTION,
    INTERFACE
}
