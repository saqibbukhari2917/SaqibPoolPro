package com.saqib.finance;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import androidx.documentfile.provider.DocumentFile;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class DriveBackupWorker extends Worker {
    public DriveBackupWorker(Context context, WorkerParameters params) { super(context, params); }

    @Override public Result doWork() {
        Context context=getApplicationContext();
        String tree=context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).getString("backup_tree_uri",null);
        if(tree==null)return Result.failure();
        try {
            DocumentFile folder=DocumentFile.fromTreeUri(context,Uri.parse(tree));
            if(folder==null||!folder.canWrite())return Result.retry();
            JSONObject root=new JSONObject();
            root.put("app","Saqib Finance");root.put("version",1);
            SQLiteDatabase sql=new DB(context).getReadableDatabase();
            JSONArray expenses=new JSONArray();
            Cursor e=sql.rawQuery("SELECT id,category,description,amount,date FROM expenses ORDER BY id",null);
            while(e.moveToNext()){JSONObject x=new JSONObject();x.put("id",e.getLong(0));x.put("category",e.getString(1));x.put("description",e.getString(2));x.put("amount",e.getDouble(3));x.put("date",e.getString(4));expenses.put(x);}e.close();
            JSONArray reminders=new JSONArray();
            Cursor r=sql.rawQuery("SELECT id,item,trigger_at,done,estimated_amount,expense_id FROM reminders ORDER BY id",null);
            while(r.moveToNext()){JSONObject x=new JSONObject();x.put("id",r.getLong(0));x.put("item",r.getString(1));x.put("trigger_at",r.getLong(2));x.put("done",r.getInt(3));x.put("estimated_amount",r.getDouble(4));if(r.isNull(5))x.put("expense_id",JSONObject.NULL);else x.put("expense_id",r.getLong(5));reminders.put(x);}r.close();
            root.put("expenses",expenses);root.put("reminders",reminders);
            DocumentFile file=folder.findFile("SaqibFinanceAutoBackup.json");
            if(file==null)file=folder.createFile("application/json","SaqibFinanceAutoBackup.json");
            if(file==null)return Result.retry();
            try(OutputStream out=context.getContentResolver().openOutputStream(file.getUri(),"wt")){
                if(out==null)return Result.retry();
                out.write(root.toString(2).getBytes(StandardCharsets.UTF_8));out.flush();
            }
            return Result.success();
        } catch(SecurityException ex) { return Result.failure(); }
          catch(Exception ex) { return Result.retry(); }
    }

    private static class DB extends SQLiteOpenHelper {
        DB(Context c){super(c,"saqib_finance.db",null,3);}
        public void onCreate(SQLiteDatabase d){
            d.execSQL("CREATE TABLE expenses(id INTEGER PRIMARY KEY AUTOINCREMENT,category TEXT NOT NULL,description TEXT NOT NULL,amount REAL NOT NULL,date TEXT NOT NULL)");
            d.execSQL("CREATE TABLE reminders(id INTEGER PRIMARY KEY AUTOINCREMENT,item TEXT NOT NULL,trigger_at INTEGER NOT NULL,done INTEGER NOT NULL DEFAULT 0,estimated_amount REAL NOT NULL DEFAULT 0,expense_id INTEGER)");
        }
        public void onUpgrade(SQLiteDatabase d,int oldVersion,int newVersion){
            if(oldVersion<2)d.execSQL("CREATE TABLE IF NOT EXISTS reminders(id INTEGER PRIMARY KEY AUTOINCREMENT,item TEXT NOT NULL,trigger_at INTEGER NOT NULL,done INTEGER NOT NULL DEFAULT 0)");
            if(oldVersion<3){d.execSQL("ALTER TABLE reminders ADD COLUMN estimated_amount REAL NOT NULL DEFAULT 0");d.execSQL("ALTER TABLE reminders ADD COLUMN expense_id INTEGER");}
        }
    }
}