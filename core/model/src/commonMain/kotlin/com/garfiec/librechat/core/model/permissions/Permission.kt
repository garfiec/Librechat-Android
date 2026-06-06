package com.garfiec.librechat.core.model.permissions

enum class Permission(val serverKey: String) {
    USE("USE"),
    CREATE("CREATE"),
    SHARE("SHARE"),
    SHARE_PUBLIC("SHARE_PUBLIC"),
}
