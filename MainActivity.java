package com.saqib.poolpro;
import android.app.*; import android.os.*; import android.content.*; import android.net.Uri; import android.provider.Settings; import android.view.*; import android.widget.*; import android.graphics.*;
public class MainActivity extends Activity {
 WindowManager wm; View bubble;
 public void onCreate(Bundle b){super.onCreate(b); LinearLayout l=new LinearLayout(this); l.setPadding(24,40,24,24); l.setOrientation(1);
 TextView t=new TextView(this); t.setText("Saqib Pool Pro"); t.setTextSize(26); l.addView(t);
 TextView d=new TextView(this); d.setText("Floating overlay demo. Real ball detection, aim lines and shot prediction are not included yet."); d.setTextSize(16); l.addView(d);
 Button a=new Button(this); a.setText("Allow overlay / Show demo"); a.setOnClickListener(v->{if(!Settings.canDrawOverlays(this)){startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));}else showBubble();}); l.addView(a); setContentView(l);}
 void showBubble(){if(bubble!=null)return; wm=(WindowManager)getSystemService(WINDOW_SERVICE); TextView v=new TextView(this); v.setText("POOL PRO\nDemo"); v.setTextColor(Color.WHITE); v.setGravity(Gravity.CENTER); v.setPadding(20,14,20,14); v.setBackgroundColor(Color.rgb(20,110,70)); int ty=Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE; WindowManager.LayoutParams p=new WindowManager.LayoutParams(-2,-2,ty,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT); p.gravity=Gravity.TOP|Gravity.END; p.x=25;p.y=200; bubble=v; wm.addView(v,p);}
 protected void onDestroy(){super.onDestroy();if(bubble!=null&&wm!=null)try{wm.removeView(bubble);}catch(Exception e){}}
}