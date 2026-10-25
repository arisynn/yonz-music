# Room and Media3 provide their own consumer rules. No broad keep rules.
# Python calls this typed callback through Java reflection, including anonymous implementations.
-keep interface com.yonzmusic.app.download.EmbeddedEngine$Callback { *; }
-keepclassmembers class * implements com.yonzmusic.app.download.EmbeddedEngine$Callback { public *; }
# Chaquopy invokes these methods by name. Keep even when release shrinking is enabled.
-keep class com.yonzmusic.engine.YonoEngineClient$Events { *; }
-keep class com.yonzmusic.engine.SafDocumentStore { *; }
