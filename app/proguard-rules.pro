# The metallic renderer and the alarm engine use no reflection, so the defaults
# are almost enough. These rules protect the components the OS instantiates
# directly from the manifest.

-keep class com.ajeet.slotalarmclock.widget.** { *; }
-keep class com.ajeet.slotalarmclock.alarm.** { *; }

# AppWidgetProvider subclasses are referenced from the manifest.
-keep class * extends android.appwidget.AppWidgetProvider { *; }
-keep class * extends android.content.BroadcastReceiver { *; }
-keep class * extends android.app.Service { *; }
