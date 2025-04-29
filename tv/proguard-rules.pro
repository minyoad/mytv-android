# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Gson 反射支持
# 保留 Gson 的类型信息
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.reflect.TypeToken { *; }

# 保留 Map 相关的类
-keep class java.util.Map { *; }
-keep class java.util.HashMap { *; }
-keep class java.util.LinkedHashMap { *; }
-keep class java.util.TreeMap { *; }

# 保留 String 和 Integer 类
-keep class java.lang.String { *; }
-keep class java.lang.Integer { *; }

# 保留你使用 Gson 进行序列化和反序列化的模型类
# 例如，如果你有一个 User 类
#-keep class com.yourpackage.model.User { *; }

# 如果你有使用 @SerializedName 注解的字段
-keepattributes Signature
-keepattributes *Annotation

# 保留所有带有 @SerializedName 注解的字段
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# 保留所有 native 方法
-keepclasseswithmembernames class * {
    native <methods>;
}

# 保留 JNI 相关类
-keep class * implements android.os.Parcelable {
    *;
}

# 保留 IJKPlayer 相关类
-keep class tv.danmaku.ijk.media.player.** { *; }
-keep interface tv.danmaku.ijk.media.player.** { *; }

-keep class com.aliyun.rts.network.** { *; }
-keep class com.wangsu.httpclient.** { *; }

# 保留 WsRtcClassLoader 及其相关类
-keep class com.wangsu.httpclient.** { *; }
-keep class com.wangsu.wsrtc.** { *; }