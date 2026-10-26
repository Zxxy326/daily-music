# JNA 通过反射调用 Rust 导出的 C 符号，保留其类与接口
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class com.dailymusic.app.native.** { *; }
