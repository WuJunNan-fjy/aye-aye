package com.aye.ai.permission;

/** 用户信任级别（影响规则引擎的默认决策）。对应 DeepWork 的 {@code TrustLevel}。 */
public enum TrustLevel {
    TRUSTED,
    STANDARD,
    PARANOID
}
