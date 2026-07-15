# retry-worker public API — keep for R8/ProGuard consumers.
-keep class com.retryjournal.worker.** { *; }
-keepclassmembers class com.retryjournal.worker.** { *; }

# WorkManager instantiates workers by reflection via the default WorkerFactory.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
