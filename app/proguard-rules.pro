# security-crypto bringt Tink mit; dessen Compile-Annotationen fehlen zur Laufzeit.
# Ohne diese Regeln bricht der R8-Release-Build mit "Missing class" ab.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**

# Debug-Logs im Release entfernen.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
