# The installed ECARX SDK calls these application-owned subclasses virtually.
# Keep their callback overrides even though the SDK implementation is compile-only.
-keep class com.shilapi.xcertplay.PrefaceMusicClientBridge { *; }
-keep class com.shilapi.xcertplay.PrefaceMusicClientBridge$* { *; }
-keep class com.shilapi.xcertplay.PrefaceMediaListBridgeKt$* { *; }
