# Room 2.6.1's consumer rule preserves RoomDatabase subclasses but not their constructors.
# WorkManager loads WorkDatabase_Impl reflectively during AndroidX Startup initialization.
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>();
}
