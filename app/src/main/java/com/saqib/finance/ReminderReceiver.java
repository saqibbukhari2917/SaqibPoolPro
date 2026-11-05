package com.saqib.finance;

import android.app.AlarmManager;
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
    public static final String CHANNEL_ID = "buy_reminders";
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
    private void createChannel(Context context) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                Uri alarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                if (alarm == null) alarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Buy reminders", NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription("Scheduled purchase reminders with alarm sound");
                channel.setSound(alarm, attrs);
                channel.enableVibration(true);
                nm.createNotificationChannel(channel);
            }
        }
    }
    @Override public void onReceive(Context context, Intent intent) {
        long id = intent.getLongExtra("reminder_id", 0);
        String item = intent.getStringExtra("reminder_item");
        if (item == null || item.trim().isEmpty()) item = "Your planned purchase";
        createChannel(context);
        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(context, (int)(id ^ 0x5A5A), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Time to buy")
                .setContentText(item)
                .setStyle(new NotificationCompat.BigTextStyle().bigText("Reminder: buy " + item))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true).setContentIntent(content);
        NotificationManager nm = (NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            nm.notify((int)id, builder.build());
        }
    }
}
