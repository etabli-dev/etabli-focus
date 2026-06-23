# Application + activities are referenced from AndroidManifest.xml so AGP
# already keeps them. Compose, Room, DataStore and OkHttp ship consumer
# ProGuard rules so we don't repeat them here. The few app-specific keeps:

# Engine + theme enums are persisted by NAME via valueOf(prefs[Keys.phase]).
# Stripping or renaming them would break state restore across upgrades.
-keepclassmembers enum com.raban.etabli.focus.engine.TimerPhase { *; }
-keepclassmembers enum com.raban.etabli.focus.engine.TimerKind  { *; }
-keepclassmembers enum com.raban.etabli.focus.prefs.ThemePreference { *; }

# Room generated DAOs use reflection on @Dao-annotated interfaces; the
# Room consumer rules cover this, but keep our entity packages explicitly
# to guarantee column-name-derived field lookups continue to work.
-keep class com.raban.etabli.focus.data.** { *; }

# Don't warn about server-side javax classes that Compose / OkHttp pull in
# transitively but don't actually use on Android.
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
