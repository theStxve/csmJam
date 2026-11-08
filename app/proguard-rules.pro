# CSM Jam – R8 rules (only used if isMinifyEnabled is switched on for release builds)

# Java-WebSocket and NanoHTTPD are used reflection-free, but keep their public API intact
-keep class org.java_websocket.** { *; }
-keep class fi.iki.elonen.** { *; }
-dontwarn org.slf4j.**

# ZXing scanner activity is started by class name from the library manifest
-keep class com.journeyapps.barcodescanner.** { *; }
