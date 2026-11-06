package com.saqib.finance;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import androidx.core.app.NotificationCompat;

public class ReminderReceiver extends BroadcastReceiver {
    private static final String ACTION_DISMISSED = "com.saqib.finance.REMINDER_DISMISSED";
    private static final String ACTION_REPEAT = "com.saqib.finance.REMINDER_REPEAT";
    private static final String PREFS = "MainActivity_preferences";

    private static String channelId(boolean sound, boolean vibration, String tone) {
        return "buy_reminders_s" + (sound ? "1" : "0") + "_v" + (vibration ? "1" : "0") + "_t" + Math.abs(tone.hashCode());
    }

    public static void schedule(Context context, long id, String item, long triggerAt) {
        Intent intent = new Intent(context, ReminderReceiver.class);
        intent.setAction("com.saqib.finance.SHOW_REMINDER");
        intent.putExtra("reminder_id", id);
        intent.putExtra("reminder_item", item);
        PendingIntent pi = PendingIntent.getBroadcast(context, (int)id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        setAlarm(am, triggerAt, pi);
    }

    private static void scheduleRepeat(Context context, long id, String item, int minutes) {
        Intent intent = new Intent(context, ReminderReceiver.class);
        intent.setAction(ACTION_REPEAT);
        intent.putExtra("reminder_id", id);
        intent.putExtra("reminder_item", item);
        PendingIntent pi = PendingIntent.getBroadcast(context, (int)(id ^ 0x2468), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        setAlarm(am, System.currentTimeMillis() + minutes * 60L * 1000L, pi);
    }

    private static void setAlarm(AlarmManager am, long at, PendingIntent pi) {
        if (am == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setExact(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException denied) {
            if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    public static void cancel(Context context, long id) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent normal = new Intent(context, ReminderReceiver.class);
        normal.setAction("com.saqib.finance.SHOW_REMINDER");
        PendingIntent first = PendingIntent.getBroadcast(context, (int)id, normal,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (am != null) am.cancel(first);
        first.cancel();

        Intent repeat = new Intent(context, ReminderReceiver.class);
        repeat.setAction(ACTION_REPEAT);
        PendingIntent again = PendingIntent.getBroadcast(context, (int)(id ^ 0x2468), repeat,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (am != null) am.cancel(again);
        again.cancel();
    }

    private boolean reminderIsPending(Context context, long id) {
        MainActivity.DB helper = new MainActivity.DB(context);
        Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT id FROM reminders WHERE id=? AND done=0",
                new String[]{String.valueOf(id)});
        boolean pending = c.moveToFirst();
        c.close();
        helper.close();
        return pending;
    }

    private void createChannel(Context context, String id, Uri toneUri, boolean vibrationEnabled) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm.getNotificationChannel(id) == null) {
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
                NotificationChannel channel = new NotificationChannel(id, "Buy reminders", NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription("Scheduled purchase reminders");
                channel.setSound(toneUri, toneUri == null ? null : attrs);
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

        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int repeatMinutes = prefs.getInt("reminder_repeat_minutes", 0);
        String action = intent.getAction();

        if (ACTION_DISMISSED.equals(action)) {
            if (repeatMinutes > 0 && reminderIsPending(context, id)) {
                scheduleRepeat(context, id, item, repeatMinutes);
            }
            return;
        }
        if (ACTION_REPEAT.equals(action) && repeatMinutes <= 0) return;
        if (!reminderIsPending(context, id)) return;

        boolean soundEnabled = prefs.getBoolean("reminder_sound_enabled", true);
        boolean vibrationEnabled = prefs.getBoolean("reminder_vibration_enabled", true);
        String savedTone = prefs.getString("reminder_ringtone_uri", "");
        Uri toneUri = null;
        if (!"silent".equals(savedTone)) {
            if (savedTone != null && !savedTone.isEmpty()) toneUri = Uri.parse(savedTone);
            else {
                toneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                if (toneUri == null) toneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            }
        }
        if (!soundEnabled) toneUri = null;
        String activeChannelId = channelId(soundEnabled, vibrationEnabled, toneUri == null ? "silent" : toneUri.toString());
        createChannel(context, activeChannelId, toneUri, vibrationEnabled);

        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(context, (int)(id ^ 0x5A5A), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent dismissed = new Intent(context, ReminderReceiver.class);
        dismissed.setAction(ACTION_DISMISSED);
        dismissed.putExtra("reminder_id", id);
        dismissed.putExtra("reminder_item", item);
        PendingIntent delete = PendingIntent.getBroadcast(context, (int)(id ^ 0x1357), dismissed,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification publicNotification = new NotificationCompat.Builder(context, activeChannelId)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Saqib Finance")
                .setContentText(" ")
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, activeChannelId)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Time to buy")
                .setContentText(item)
                .setStyle(new NotificationCompat.BigTextStyle().bigText("Reminder: buy " + item))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicNotification)
                .setAutoCancel(true)
                .setContentIntent(content)
                .setDeleteIntent(delete);

        if (Build.VERSION.SDK_INT < 26) {
            if (toneUri != null) builder.setSound(toneUri);
            if (vibrationEnabled) builder.setVibrate(new long[]{0, 500, 250, 500});
        }

        NotificationManager nm = (NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            nm.notify((int)id, builder.build());
        }
    }
}
