-keep class com.cashewbridge.app.model.** { *; }
-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}
