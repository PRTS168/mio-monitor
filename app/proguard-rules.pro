# A41 Probe — R8 规则
# Compose / Shizuku 库自带 consumer rules；此处仅保留项目级兜底。

# Shizuku 通过 Binder 跨进程调用，保留公开 API 入口以防反射删名
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }

# 数据模型字段被序列化/反射使用处保留（CSV 导出走显式代码，无需 keep，兜底注释）
