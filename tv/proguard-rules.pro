# JNA 通过反射加载 Rust 导出的符号，名字不能被混淆
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn java.awt.**
