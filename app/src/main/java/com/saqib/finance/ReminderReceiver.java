package com.saqib.finance;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import androidx.core.app.NotificationCompat;

public class ReminderReceiver extends BroadcastReceiver {
    // Channel IDs vary with sound/vibration preferences because Android channels are immutable.
    private static String channelId(boolean sound, boolean vibration) {
        return "buy_reminders_s" + (sound ? "1" : "0") + "_v" + (vibration ? "1" : "0");
    }

    public static void schedule(Context context, long id, String item, long triggerAt) {
        Intent intent = new Intent(context, ReminderReceiver.class);
        intent.putExtra("reminder_id", id);
        intent.putExtra("reminder_item", item);
        PendingIntent pi = PendingIntent.getBroadcast(context, (int)id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            try {
                if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
                else am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } catch (SecurityException denied) {
                if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
        }
    }

    public static void cancel(Context context, long id) {
        Intent intent = new Intent(context, ReminderReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(context, (int)id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pi);
        pi.cancel();
    }

    private void createChannel(Context context, String id, boolean soundEnabled, boolean vibrationEnabled) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm.getNotificationChannel(id) == null) {
                Uri alarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                if (alarm == null) alarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
                NotificationChannel channel = new NotificationChannel(id, "Buy reminders", NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription("Scheduled purchase reminders");
                channel.setSound(soundEnabled ? alarm : null, soundEnabled ? attrs : null);
                channel.enableVibration(vibrationEnabled);
                channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
                nm.createNotificationChannel(channel);
            }
        }
    }

    @Override public void onReceive(Context context, Intent intent) {
        long id = intent.getLongExtra("reminder_id", 0);
        String item = intent.getStringExtra("reminder_item");
        if (item == null || item.trim().isEmpty()) item = "Your planned purchase";
        android.content.SharedPreferences prefs = context.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
        boolean soundEnabled = prefs.getBoolean("reminder_sound_enabled", true);
        boolean vibrationEnabled = prefs.getBoolean("reminder_vibration_enabled", true);
        String activeChannelId = channelId(soundEnabled, vibrationEnabled);
        createChannel(context, activeChannelId, soundEnabled, vibrationEnabled);

        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(context, (int)(id ^ 0x5A5A), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification publicNotification = new NotificationCompat.Builder(context, activeChannelId)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Saqib Finance")
                .setContentText(" ")
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Time to buy")
                .setContentText(item)
                .setStyle(new NotificationCompat.BigTextStyle().bigText("Reminder: buy " + item))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicNotification)
                .setAutoCancel(true).setContentIntent(content);

        NotificationManager nm = (NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            nm.notify((int)id, builder.build());
        }
    }
}
