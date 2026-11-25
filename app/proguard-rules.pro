# PassMan never logs entry data; strip any android.util.Log calls from release builds anyway.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}
# BouncyCastle is used only for Argon2 (direct references, no reflection).
-dontwarn javax.naming.**
-dontwarn org.bouncycastle.**
