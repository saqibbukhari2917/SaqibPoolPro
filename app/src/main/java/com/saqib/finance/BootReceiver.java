package com.saqib.finance;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        java.io.File file = context.getDatabasePath("saqib_finance.db");
        if (!file.exists()) return;
        SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
        Cursor c = db.rawQuery("SELECT id,item,trigger_at FROM reminders WHERE done=0 AND trigger_at>?", new String[]{String.valueOf(System.currentTimeMillis())});
        while (c.moveToNext()) {
            ReminderReceiver.schedule(context, c.getLong(0), c.getString(1), c.getLong(2));
        }
        c.close();
        db.close();
    }
}
