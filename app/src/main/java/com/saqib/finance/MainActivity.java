package com.saqib.finance;

import android.app.*;
import android.os.Bundle;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.database.Cursor;
import android.database.sqlite.*;
import android.content.*;
import android.view.*;
import android.widget.*;
import java.text.*;
import java.util.*;
import androidx.biometric.BiometricPrompt;
import androidx.fragment.app.FragmentActivity;
import androidx.core.content.ContextCompat;
import java.util.concurrent.Executor;
import java.io.*;
import org.json.*;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import com.google.android.gms.auth.api.signin.*;
import com.google.android.gms.common.api.ApiException;
import com.google.firebase.auth.*;
import com.google.firebase.firestore.*;

public class MainActivity extends FragmentActivity {
    private static final int REQUEST_GOOGLE_SIGN_IN=8101;
    private static final String PREF_CLOUD_NETWORK="cloud_network";
    private GoogleSignInClient googleSignInClient;
    private ConnectivityManager.NetworkCallback cloudNetworkCallback;
    private static final String[] CATS={"Kids","Wife","Family","Medicine","Grocery","Transport","Bills","Shopping","Dining","Other"};
    private static final int GREEN=0xFF174F3D, DEEP=0xFF0D3328, GOLD=0xFFE1B45A, MINT=0xFFE5F0E8;
    private DB db; private LinearLayout root,body,nav; private int page=0; private boolean dark=false;
    private Calendar month=Calendar.getInstance(); private String from="",to="",category="All categories";
    private TextView totalText,countText,monthText,pendingCloudText; private LinearLayout historyList;
    private final SimpleDateFormat iso=new SimpleDateFormat("yyyy-MM-dd",Locale.US);
    private final SimpleDateFormat display=new SimpleDateFormat("dd MMM yyyy",Locale.ENGLISH);
    private final SimpleDateFormat monthFmt=new SimpleDateFormat("MMMM yyyy",Locale.ENGLISH);
    private int bg,surface,ink,muted,line; private boolean unlocked=false, lockPromptShowing=false, uiReady=false;
    @Override public void onCreate(Bundle b){super.onCreate(b);dark=getPreferences(0).getBoolean("dark",false);db=new DB(this);setupGoogleSignIn();registerCloudNetworkCallback();applyCloudNetworkPreference();if(FirebaseAuth.getInstance().getCurrentUser()!=null)syncAllCloud();palette();if(getPreferences(0).getBoolean("screen_lock",true)){showLockScreen();showSecurityPrompt();}else{unlocked=true;openMainUI(0);}}
    @Override protected void onResume(){super.onResume();if(getPreferences(0).getBoolean("screen_lock",true)&&!unlocked&&!lockPromptShowing){showLockScreen();showSecurityPrompt();}}
    @Override protected void onStop(){super.onStop();if(!isChangingConfigurations()&&getPreferences(0).getBoolean("screen_lock",true)){unlocked=false;showLockScreen();}}
    private void openMainUI(int targetPage){uiReady=true;palette();buildShell();showPage(targetPage);}
    private void showLockScreen(){
        LinearLayout lock=col();lock.setGravity(Gravity.CENTER);lock.setBackgroundColor(DEEP);lock.setPadding(dp(28),dp(28),dp(28),dp(28));
        ImageView appIcon=new ImageView(this);appIcon.setImageResource(R.drawable.ic_launcher);appIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);lock.addView(appIcon,lp(92,92));
        TextView appName=txt("Saqib Finance",24,Color.WHITE,true);appName.setGravity(Gravity.CENTER);LinearLayout.LayoutParams nameLp=lp(-1,-2);nameLp.topMargin=dp(18);lock.addView(appName,nameLp);
        setContentView(lock);
    }
    private void showSecurityPrompt(){
        lockPromptShowing=true;
        Executor executor=ContextCompat.getMainExecutor(this);
        BiometricPrompt prompt=new BiometricPrompt(this,executor,new BiometricPrompt.AuthenticationCallback(){
            @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result){super.onAuthenticationSucceeded(result);unlocked=true;lockPromptShowing=false;openMainUI(page);}
            @Override public void onAuthenticationError(int errorCode,CharSequence errString){super.onAuthenticationError(errorCode,errString);lockPromptShowing=false;Toast.makeText(MainActivity.this,"Unlock required to open Saqib Finance",Toast.LENGTH_LONG).show();finish();}
            @Override public void onAuthenticationFailed(){super.onAuthenticationFailed();Toast.makeText(MainActivity.this,"Not recognized. Try again.",Toast.LENGTH_SHORT).show();}
        });
        BiometricPrompt.PromptInfo info=new BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Saqib Finance")
            .setSubtitle("Use your fingerprint or your phone's screen lock")
            .setDescription("Your family expense information is protected")
            .setDeviceCredentialAllowed(true)
            .build();
        prompt.authenticate(info);
    }
    private void palette(){bg=dark?0xFF101A16:0xFFF5F7F3;surface=dark?0xFF1B2922:Color.WHITE;ink=dark?0xFFF1F4F0:0xFF24352C;muted=dark?0xFFB0BDB4:0xFF78847B;line=dark?0xFF34443A:0xFFE8EDE8;getWindow().setStatusBarColor(DEEP);getWindow().setNavigationBarColor(DEEP);}
    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView txt(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(null,1);return t;}
    private LinearLayout col(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(0);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h));}
    private String money(double v){return "Rs. "+String.format(Locale.US,"%,.0f",v);}
    private String dateLabel(String s){try{return display.format(iso.parse(s));}catch(Exception e){return s;}}
    private void buildShell(){
        root=col();root.setBackgroundColor(bg);setContentView(root);
        FrameLayout frame=new FrameLayout(this);root.addView(frame,new LinearLayout.LayoutParams(-1,0,1));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);body=col();body.setPadding(0,0,0,dp(22));scroll.addView(body);frame.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        nav=row();nav.setBackgroundColor(DEEP);nav.setPadding(dp(5),dp(8),dp(5),dp(8));root.addView(nav,lp(-1,66));
    }
    private void showPage(int p){
        page=p;body.removeAllViews();nav.removeAllViews();body.setBackgroundColor(bg);
        if(p==0)homePage();else if(p==1)addPage();else if(p==2)historyPage();else if(p==3)settingsPage();else remindersPage();
        String[] labels={"Home","Add","History","Settings","Reminders"};String[] icons={"⌂","+","▤","⚙","◷"};
        for(int i=0;i<5;i++){final int index=i;LinearLayout item=col();item.setGravity(Gravity.CENTER);TextView ic=txt(icons[i],22,i==p?GOLD:0xFFD0DED5,true);ic.setGravity(Gravity.CENTER);TextView label=txt(labels[i],10,i==p?Color.WHITE:0xFFB5C8BD,i==p);label.setGravity(Gravity.CENTER);item.addView(ic);item.addView(label);item.setOnClickListener(v->showPage(index));nav.addView(item,new LinearLayout.LayoutParams(0,-1,1));}
    }
    private void pageHeader(String title,String sub){LinearLayout h=col();h.setPadding(dp(20),dp(22),dp(20),dp(20));h.setBackground(shape(DEEP,0));LinearLayout brand=row();TextView logo=txt("Rs",15,GREEN,true);logo.setGravity(Gravity.CENTER);logo.setBackground(shape(GOLD,13));brand.addView(logo,lp(44,44));LinearLayout names=col();names.setPadding(dp(11),0,0,0);names.addView(txt("SAQIB FINANCE",18,Color.WHITE,true));names.addView(txt("YOUR FAMILY WALLET",9,0xFFBDD2C4,true));brand.addView(names);h.addView(brand);TextView titleView=txt(title,23,Color.WHITE,true);titleView.setPadding(0,dp(22),0,dp(3));h.addView(titleView);h.addView(txt(sub,12,0xFFBDD2C4,false));body.addView(h);}
    private LinearLayout content(){LinearLayout c=col();c.setPadding(dp(17),dp(17),dp(17),0);body.addView(c);return c;}
    private LinearLayout card(LinearLayout parent){LinearLayout c=col();c.setPadding(dp(16),dp(15),dp(16),dp(15));c.setBackground(shape(surface,18));c.setElevation(dp(2));LinearLayout.LayoutParams q=lp(-1,-2);q.bottomMargin=dp(13);parent.addView(c,q);return c;}
    private void homePage(){
        pageHeader("Your money, in control","A clear view of your family's monthly spending");LinearLayout c=content();
        LinearLayout total=card(c);total.setBackground(shape(GREEN,18));total.addView(txt("TOTAL SPENT THIS MONTH",10,0xFFD9E9DF,true));totalText=txt("Rs. 0",31,Color.WHITE,true);totalText.setPadding(0,dp(8),0,dp(4));total.addView(totalText);countText=txt("0 expenses recorded",12,0xFFD9E9DF,false);total.addView(countText);
        LinearLayout m=row();Button prev=smallButton("‹"),next=smallButton("›");monthText=txt(monthFmt.format(month.getTime()),15,ink,true);monthText.setGravity(Gravity.CENTER);m.addView(prev,lp(42,38));m.addView(monthText,new LinearLayout.LayoutParams(0,dp(38),1));m.addView(next,lp(42,38));LinearLayout.LayoutParams mp=lp(-1,-2);mp.bottomMargin=dp(12);c.addView(m,mp);
        prev.setOnClickListener(v->{month.add(Calendar.MONTH,-1);showPage(0);});next.setOnClickListener(v->{month.add(Calendar.MONTH,1);showPage(0);});
        LinearLayout ch=card(c);ch.addView(txt("Spending by category",17,ink,true));Chart chart=new Chart(this);ch.addView(chart,lp(-1,220));loadMonth(chart);
        LinearLayout recent=card(c);recent.addView(txt("Recent expenses",17,ink,true));addRecent(recent,5);
        Button all=primaryButton("View full history");recent.addView(all,lp(-1,44));all.setOnClickListener(v->showPage(2));
    }
    private void loadMonth(Chart chart){String key=new SimpleDateFormat("yyyy-MM",Locale.US).format(month.getTime());Map<String,Double> data=new LinkedHashMap<>();double total=0;int count=0;for(String s:CATS)data.put(s,0d);Cursor q=db.getReadableDatabase().rawQuery("SELECT category,SUM(amount),COUNT(*) FROM expenses WHERE substr(date,1,7)=? GROUP BY category",new String[]{key});while(q.moveToNext()){String cat=q.getString(0);double val=q.getDouble(1);data.put(cat,val);total+=val;count+=q.getInt(2);}q.close();totalText.setText(money(total));countText.setText(count+" expense"+(count==1?"":"s")+" recorded");chart.setData(data,total);}
    private void addRecent(LinearLayout target,int limit){Cursor q=db.getReadableDatabase().rawQuery("SELECT category,description,amount,date FROM expenses ORDER BY date DESC,id DESC LIMIT "+limit,null);if(!q.moveToFirst()){TextView e=txt("No expenses yet. Add your first expense.",13,muted,false);e.setPadding(0,dp(18),0,dp(12));target.addView(e);}else do{String cat=q.getString(0),desc=q.getString(1);LinearLayout r=row();TextView left=txt(cat+(desc.isEmpty()?"":" · "+desc),13,ink,true);r.addView(left,new LinearLayout.LayoutParams(0,-2,1));r.addView(txt(money(q.getDouble(2)),13,GREEN,true));target.addView(r,lp(-1,43));View d=new View(this);d.setBackgroundColor(line);target.addView(d,lp(-1,1));}while(q.moveToNext());q.close();}
    private Button smallButton(String s){Button b=new Button(this);b.setText(s);b.setTextSize(22);b.setTextColor(ink);b.setAllCaps(false);b.setBackground(shape(surface,10));return b;}
    private Button primaryButton(String s){Button b=new Button(this);b.setText(s);b.setTextSize(13);b.setTextColor(Color.WHITE);b.setAllCaps(false);b.setBackground(shape(GREEN,12));return b;}
    private void addPage(){pageHeader("Add expense","Record a purchase for your household");LinearLayout c=content();LinearLayout form=card(c);
        form.addView(txt("CATEGORY  *",11,muted,true));Spinner sp=new Spinner(this);sp.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,CATS));form.addView(sp,lp(-1,48));
        TextView dl=txt("DESCRIPTION (OPTIONAL)",11,muted,true);dl.setPadding(0,dp(17),0,dp(4));form.addView(dl);EditText desc=new EditText(this);desc.setSingleLine(true);desc.setHint("e.g. School shoes");form.addView(desc,lp(-1,52));
        TextView al=txt("AMOUNT (PKR)  *",11,muted,true);al.setPadding(0,dp(17),0,dp(4));form.addView(al);EditText amount=new EditText(this);amount.setSingleLine(true);amount.setInputType(8194);amount.setHint("Enter amount");form.addView(amount,lp(-1,52));
        TextView dateTitle=txt("DATE",11,muted,true);dateTitle.setPadding(0,dp(17),0,dp(4));form.addView(dateTitle);TextView date=txt(display.format(new Date()),14,ink,true);date.setPadding(dp(13),dp(14),dp(13),dp(14));date.setBackground(shape(bg,10));form.addView(date,lp(-1,48));date.setOnClickListener(v->pickDate(date));
        Button save=primaryButton("Save expense");LinearLayout.LayoutParams s=lp(-1,50);s.topMargin=dp(22);form.addView(save,s);save.setOnClickListener(v->{String a=amount.getText().toString().trim();if(a.isEmpty()){amount.setError("Amount is required");return;}try{double val=Double.parseDouble(a);if(val<=0||Double.isInfinite(val)||Double.isNaN(val))throw new Exception();Date dt=display.parse(date.getText().toString());ContentValues cv=new ContentValues();cv.put("category",sp.getSelectedItem().toString());cv.put("description",desc.getText().toString().trim());cv.put("amount",val);cv.put("date",iso.format(dt));long savedId=db.getWritableDatabase().insert("expenses",null,cv);if(savedId>0)pushExpense(savedId);Toast.makeText(this,"Expense saved",Toast.LENGTH_SHORT).show();showPage(0);}catch(Exception e){amount.setError("Enter a valid amount");}});
    }
    private void pickDate(TextView target){Calendar c=Calendar.getInstance();try{c.setTime(display.parse(target.getText().toString()));}catch(Exception e){}new DatePickerDialog(this,(v,y,m,d)->{Calendar x=Calendar.getInstance();x.set(y,m,d);target.setText(display.format(x.getTime()));},c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH)).show();}
    private void historyPage(){pageHeader("Expense history","Find and review your purchases");LinearLayout c=content();LinearLayout filters=card(c);
        LinearLayout dates=row();Button f=smallFilter(from.isEmpty()?"From date":dateLabel(from)),t=smallFilter(to.isEmpty()?"To date":dateLabel(to));dates.addView(f,new LinearLayout.LayoutParams(0,dp(44),1));LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(0,dp(44),1);tp.leftMargin=dp(8);dates.addView(t,tp);filters.addView(dates);
        f.setOnClickListener(v->pickFilter(true));t.setOnClickListener(v->pickFilter(false));
        Spinner cats=new Spinner(this);String[] opts=new String[CATS.length+1];opts[0]="All categories";System.arraycopy(CATS,0,opts,1,CATS.length);cats.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,opts));cats.setSelection(Math.max(0,Arrays.asList(opts).indexOf(category)));LinearLayout.LayoutParams cp=lp(-1,48);cp.topMargin=dp(8);filters.addView(cats,cp);
        LinearLayout actions=row();Button clear=smallFilter("Clear filters");actions.addView(clear);filters.addView(actions);clear.setOnClickListener(v->{from="";to="";category="All categories";showPage(2);});cats.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> a){}public void onItemSelected(AdapterView<?> a,View v,int pos,long id){String selected=opts[pos];if(!selected.equals(category)){category=selected;refreshHistory();}}});
        historyList=card(c);refreshHistory();
    }
    private Button smallFilter(String s){Button b=smallButton(s);b.setTextSize(12);b.setTextColor(GREEN);return b;}
    private void pickFilter(boolean start){String old=start?from:to;Calendar c=Calendar.getInstance();try{if(!old.isEmpty())c.setTime(iso.parse(old));}catch(Exception e){}new DatePickerDialog(this,(v,y,m,d)->{Calendar x=Calendar.getInstance();x.set(y,m,d);String value=iso.format(x.getTime());if(start)from=value;else to=value;if(!from.isEmpty()&&!to.isEmpty()&&from.compareTo(to)>0){if(start)to=from;else from=to;}showPage(2);},c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH)).show();}
    private void refreshHistory(){if(historyList==null)return;historyList.removeAllViews();StringBuilder sql=new StringBuilder("SELECT id,category,description,amount,date FROM expenses WHERE 1=1");ArrayList<String>a=new ArrayList<>();if(!from.isEmpty()){sql.append(" AND date>=?");a.add(from);}if(!to.isEmpty()){sql.append(" AND date<=?");a.add(to);}if(!category.equals("All categories")){sql.append(" AND category=?");a.add(category);}sql.append(" ORDER BY date DESC,id DESC");Cursor q=db.getReadableDatabase().rawQuery(sql.toString(),a.toArray(new String[0]));if(!q.moveToFirst()){historyList.addView(txt("No matching expenses found.",14,muted,false));}else do{final long id=q.getLong(0);String cat=q.getString(1),desc=q.getString(2),date=q.getString(4);double amount=q.getDouble(3);LinearLayout r=row();r.setPadding(0,dp(10),0,dp(10));LinearLayout d=col();d.addView(txt(cat,14,ink,true));d.addView(txt((desc.isEmpty()?"":desc+" · ")+dateLabel(date),11,muted,false));r.addView(d,new LinearLayout.LayoutParams(0,-2,1));r.addView(txt(money(amount),14,GREEN,true));r.setOnClickListener(v->editExpense(id,cat,desc,amount,date));historyList.addView(r);View sep=new View(this);sep.setBackgroundColor(line);historyList.addView(sep,lp(-1,1));}while(q.moveToNext());q.close();}
    private void editExpense(long id,String cat,String desc,double value,String date){LinearLayout box=col();Spinner sp=new Spinner(this);sp.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,CATS));sp.setSelection(Math.max(0,Arrays.asList(CATS).indexOf(cat)));EditText de=new EditText(this);de.setHint("Description (optional)");de.setText(desc);EditText am=new EditText(this);am.setInputType(8194);am.setText(String.valueOf(value));TextView dt=txt(dateLabel(date),14,ink,true);dt.setPadding(dp(10),dp(12),dp(10),dp(12));dt.setOnClickListener(v->pickDate(dt));box.addView(txt("Category",12,muted,true));box.addView(sp);box.addView(txt("Description (optional)",12,muted,true));box.addView(de);box.addView(txt("Amount (PKR)",12,muted,true));box.addView(am);box.addView(txt("Date",12,muted,true));box.addView(dt);new AlertDialog.Builder(this).setTitle("Edit expense").setView(box).setNegativeButton("Cancel",null).setNeutralButton("Delete",(x,y)->new AlertDialog.Builder(this).setMessage("Delete this expense?").setNegativeButton("Cancel",null).setPositiveButton("Delete",(z,w)->{db.getWritableDatabase().delete("expenses","id=?",new String[]{String.valueOf(id)});deleteCloudRecord("expenses",id);refreshHistory();}).show()).setPositiveButton("Save",(x,y)->{try{Date parsed=display.parse(dt.getText().toString());double n=Double.parseDouble(am.getText().toString());if(n<=0)throw new Exception();ContentValues cv=new ContentValues();cv.put("category",sp.getSelectedItem().toString());cv.put("description",de.getText().toString().trim());cv.put("amount",n);cv.put("date",iso.format(parsed));db.getWritableDatabase().update("expenses",cv,"id=?",new String[]{String.valueOf(id)});pushExpense(id);refreshHistory();}catch(Exception e){Toast.makeText(this,"Please enter a valid amount",Toast.LENGTH_SHORT).show();}}).show();}
    private void settingsPage(){
        pageHeader("Settings","Make Saqib Finance feel like yours");
        LinearLayout c=content();
        LinearLayout security=card(c);
        security.addView(txt("SECURITY",10,GOLD,true));
        security.addView(txt("App screen lock",17,ink,true));
        security.addView(txt("Require fingerprint or your phone PIN before showing expenses.",12,muted,false));
        Switch lockSwitch=new Switch(this);lockSwitch.setText("Screen lock");lockSwitch.setTextColor(ink);lockSwitch.setChecked(getPreferences(0).getBoolean("screen_lock",true));lockSwitch.setPadding(0,dp(12),0,0);security.addView(lockSwitch);
        lockSwitch.setOnCheckedChangeListener((button,on)->{
            getPreferences(0).edit().putBoolean("screen_lock",on).apply();
            if(on){unlocked=false;showLockScreen();showSecurityPrompt();}
            else{unlocked=true;lockPromptShowing=false;openMainUI(3);}
        });

        LinearLayout appearance=card(c);
        appearance.addView(txt("APPEARANCE",10,GOLD,true));
        appearance.addView(txt("App theme",17,ink,true));
        appearance.addView(txt("Choose the look that feels comfortable",12,muted,false));
        Switch sw=new Switch(this);sw.setText("Dark theme");sw.setTextColor(ink);sw.setChecked(dark);sw.setPadding(0,dp(12),0,0);appearance.addView(sw);
        sw.setOnCheckedChangeListener((b,on)->{dark=on;getPreferences(0).edit().putBoolean("dark",dark).apply();palette();buildShell();showPage(3);});

        LinearLayout cloud=card(c);
        cloud.addView(txt("GOOGLE ACCOUNT & CLOUD BACKUP",10,GOLD,true));
        cloud.addView(txt("Automatic backup and restore",17,ink,true));
        String email=FirebaseAuth.getInstance().getCurrentUser()==null?null:FirebaseAuth.getInstance().getCurrentUser().getEmail();
        TextView status=txt(email==null?"Not connected to Google":"Connected: "+email,12,muted,false);status.setPadding(0,dp(6),0,dp(5));cloud.addView(status);\n        pendingCloudText=txt("Pending sync: "+pendingCloudCount()+" records",13,pendingCloudCount()>0?GOLD:GREEN,true);pendingCloudText.setPadding(0,dp(3),0,dp(12));cloud.addView(pendingCloudText);
        Button connect=primaryButton(email==null?"Connect Google account":"Sync now");cloud.addView(connect,lp(-1,48));
        connect.setOnClickListener(v->{if(FirebaseAuth.getInstance().getCurrentUser()==null)startGoogleSignIn();else syncAllCloud();});
        if(email!=null){Button disconnect=smallFilter("Disconnect Google account");cloud.addView(disconnect,lp(-1,44));disconnect.setOnClickListener(v->{new AlertDialog.Builder(this).setMessage("Disconnect this phone? Your cloud backup remains in your Google account.").setNegativeButton("Cancel",null).setPositiveButton("Disconnect",(d,w)->{FirebaseAuth.getInstance().signOut();googleSignInClient.signOut();showPage(3);}).show();});}
        TextView netTitle=txt("BACKUP NETWORK",10,GOLD,true);netTitle.setPadding(0,dp(18),0,dp(5));cloud.addView(netTitle);
        cloud.addView(txt("Changes sync automatically when internet is available. Offline changes wait until connection returns.",12,muted,false));
        Spinner network=new Spinner(this);String[] networkOptions={"Wi-Fi + Mobile Data","Wi-Fi only","Off"};network.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,networkOptions));
        String saved=getPreferences(0).getString(PREF_CLOUD_NETWORK,"Wi-Fi + Mobile Data");network.setSelection(Math.max(0,Arrays.asList(networkOptions).indexOf(saved)));cloud.addView(network,lp(-1,48));
        network.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){boolean first=true;public void onNothingSelected(AdapterView<?> x){}public void onItemSelected(AdapterView<?> x,View v,int p,long id){if(first){first=false;return;}getPreferences(0).edit().putString(PREF_CLOUD_NETWORK,networkOptions[p]).apply();applyCloudNetworkPreference();}});
        LinearLayout about=card(c);about.addView(txt("ABOUT",10,GOLD,true));about.addView(txt("Saqib Finance",18,ink,true));about.addView(txt("Family expense tracker · Offline storage · PKR",13,muted,false));about.addView(txt("Your records stay on this phone and sync to your Google account when connected. Sign in on a new phone to restore them.",12,muted,false));
    }
    private void setupGoogleSignIn(){GoogleSignInOptions o=new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestIdToken(getString(R.string.default_web_client_id)).requestEmail().build();googleSignInClient=GoogleSignIn.getClient(this,o);}
    private void startGoogleSignIn(){startActivityForResult(googleSignInClient.getSignInIntent(),REQUEST_GOOGLE_SIGN_IN);}
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){super.onActivityResult(requestCode,resultCode,data);if(requestCode==REQUEST_GOOGLE_SIGN_IN){try{GoogleSignInAccount a=GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException.class);AuthCredential c=GoogleAuthProvider.getCredential(a.getIdToken(),null);FirebaseAuth.getInstance().signInWithCredential(c).addOnSuccessListener(r->{Toast.makeText(this,"Google account connected",Toast.LENGTH_SHORT).show();syncAllCloud();showPage(3);}).addOnFailureListener(e->Toast.makeText(this,"Sign-in failed: "+e.getMessage(),Toast.LENGTH_LONG).show());}catch(ApiException e){Toast.makeText(this,"Google sign-in failed ("+e.getStatusCode()+")",Toast.LENGTH_LONG).show();}}}
    private void registerCloudNetworkCallback(){try{ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);cloudNetworkCallback=new ConnectivityManager.NetworkCallback(){@Override public void onAvailable(Network n){runOnUiThread(()->applyCloudNetworkPreference());}@Override public void onLost(Network n){runOnUiThread(()->applyCloudNetworkPreference());}};cm.registerDefaultNetworkCallback(cloudNetworkCallback);}catch(Exception ignored){}}
    private void applyCloudNetworkPreference(){try{String mode=getPreferences(0).getString(PREF_CLOUD_NETWORK,"Wi-Fi + Mobile Data");FirebaseFirestore fs=FirebaseFirestore.getInstance();if("Off".equals(mode)){fs.disableNetwork();return;}if("Wi-Fi only".equals(mode)){ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);Network n=cm.getActiveNetwork();NetworkCapabilities c=n==null?null:cm.getNetworkCapabilities(n);if(c==null||!c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)){fs.disableNetwork();return;}}fs.enableNetwork();}catch(Exception ignored){}}
    @Override protected void onDestroy(){super.onDestroy();try{if(cloudNetworkCallback!=null)((ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE)).unregisterNetworkCallback(cloudNetworkCallback);}catch(Exception ignored){}}
    private String cloudUid(){return FirebaseAuth.getInstance().getCurrentUser().getUid();}
    private CollectionReference cloudCollection(String name){return FirebaseFirestore.getInstance().collection("users").document(cloudUid()).collection(name);}
    private void syncAllCloud(){if(FirebaseAuth.getInstance().getCurrentUser()==null)return;applyCloudNetworkPreference();cloudCollection("expenses").get().addOnSuccessListener(es->{SQLiteDatabase sql=db.getWritableDatabase();sql.beginTransaction();try{for(DocumentSnapshot d:es.getDocuments()){long id=d.getLong("id")==null?Long.parseLong(d.getId()):d.getLong("id");Cursor c=sql.rawQuery("SELECT id FROM expenses WHERE id=?",new String[]{String.valueOf(id)});boolean exists=c.moveToFirst();c.close();if(!exists){ContentValues v=new ContentValues();v.put("id",id);v.put("category",d.getString("category"));v.put("description",d.getString("description")==null?"":d.getString("description"));v.put("amount",d.getDouble("amount")==null?0:d.getDouble("amount"));v.put("date",d.getString("date"));sql.insert("expenses",null,v);}}sql.setTransactionSuccessful();}finally{sql.endTransaction();}cloudCollection("reminders").get().addOnSuccessListener(rs->{SQLiteDatabase rdb=db.getWritableDatabase();rdb.beginTransaction();try{for(DocumentSnapshot d:rs.getDocuments()){long id=d.getLong("id")==null?Long.parseLong(d.getId()):d.getLong("id");Cursor c=rdb.rawQuery("SELECT id FROM reminders WHERE id=?",new String[]{String.valueOf(id)});boolean exists=c.moveToFirst();c.close();if(!exists){ContentValues v=new ContentValues();v.put("id",id);v.put("item",d.getString("item"));v.put("trigger_at",d.getLong("trigger_at")==null?0:d.getLong("trigger_at"));v.put("done",d.getLong("done")==null?0:d.getLong("done"));v.put("estimated_amount",d.getDouble("estimated_amount")==null?0:d.getDouble("estimated_amount"));if(d.get("expense_id")==null)v.putNull("expense_id");else v.put("expense_id",((Number)d.get("expense_id")).longValue());rdb.insert("reminders",null,v);}}rdb.setTransactionSuccessful();}finally{rdb.endTransaction();}uploadAllLocal();reschedulePendingReminders();if(uiReady)showPage(page);}).addOnFailureListener(e->Toast.makeText(this,"Cloud restore failed: "+e.getMessage(),Toast.LENGTH_LONG).show());}).addOnFailureListener(e->Toast.makeText(this,"Cloud restore failed: "+e.getMessage(),Toast.LENGTH_LONG).show());}
    private void reschedulePendingReminders(){Cursor c=db.getReadableDatabase().rawQuery("SELECT id,item,trigger_at FROM reminders WHERE done=0 AND trigger_at>?",new String[]{String.valueOf(System.currentTimeMillis())});while(c.moveToNext())ReminderReceiver.schedule(this,c.getLong(0),c.getString(1),c.getLong(2));c.close();}
    private void uploadAllLocal(){
        Cursor e=db.getReadableDatabase().rawQuery("SELECT id,category,description,amount,date FROM expenses",null);
        ArrayList<Map<String,Object>> expenseData=new ArrayList<>();
        while(e.moveToNext()){
            Map<String,Object> m=new HashMap<>();
            m.put("id",e.getLong(0));m.put("category",e.getString(1));m.put("description",e.getString(2));m.put("amount",e.getDouble(3));m.put("date",e.getString(4));
            expenseData.add(m);
        }
        e.close();
        Cursor r=db.getReadableDatabase().rawQuery("SELECT id,item,trigger_at,done,estimated_amount,expense_id FROM reminders",null);
        ArrayList<Map<String,Object>> reminderData=new ArrayList<>();
        while(r.moveToNext()){
            Map<String,Object> m=new HashMap<>();
            m.put("id",r.getLong(0));m.put("item",r.getString(1));m.put("trigger_at",r.getLong(2));m.put("done",r.getLong(3));m.put("estimated_amount",r.getDouble(4));m.put("expense_id",r.isNull(5)?null:r.getLong(5));
            reminderData.add(m);
        }
        r.close();
        final int total=expenseData.size()+reminderData.size();
        if(total==0){Toast.makeText(this,"Cloud backup complete — no records to upload",Toast.LENGTH_LONG).show();return;}
        Toast.makeText(this,"Uploading "+total+" records to cloud…",Toast.LENGTH_SHORT).show();
        final int[] completed={0};
        final boolean[] failed={false};
        final String[] firstError={null};
        Runnable finishOne=()->{
            completed[0]++;
            if(completed[0]==total){
                if(failed[0])Toast.makeText(this,"Cloud backup failed: "+firstError[0],Toast.LENGTH_LONG).show();
                else Toast.makeText(this,"Cloud backup complete ("+total+" records)",Toast.LENGTH_LONG).show();
            }
        };
        for(Map<String,Object> m:expenseData){
            String id=String.valueOf(m.get("id"));
            cloudCollection("expenses").document(id).set(m)
                .addOnSuccessListener(v->finishOne.run())
                .addOnFailureListener(err->{failed[0]=true;if(firstError[0]==null)firstError[0]=err.getMessage();finishOne.run();});
        }
        for(Map<String,Object> m:reminderData){
            String id=String.valueOf(m.get("id"));
            cloudCollection("reminders").document(id).set(m)
                .addOnSuccessListener(v->finishOne.run())
                .addOnFailureListener(err->{failed[0]=true;if(firstError[0]==null)firstError[0]=err.getMessage();finishOne.run();});
        }
    }
    private void pushExpense(long id){if(FirebaseAuth.getInstance().getCurrentUser()==null)return;Cursor c=db.getReadableDatabase().rawQuery("SELECT category,description,amount,date FROM expenses WHERE id=?",new String[]{String.valueOf(id)});if(!c.moveToFirst()){c.close();return;}Map<String,Object> m=new HashMap<>();m.put("id",id);m.put("category",c.getString(0));m.put("description",c.getString(1));m.put("amount",c.getDouble(2));m.put("date",c.getString(3));c.close();String key=String.valueOf(id);markCloudPending("expenses",key);cloudCollection("expenses").document(key).set(m).addOnSuccessListener(v->clearCloudPending("expenses",key)).addOnFailureListener(e->refreshPendingCloudText());}
    private void pushReminder(long id){if(FirebaseAuth.getInstance().getCurrentUser()==null)return;Cursor c=db.getReadableDatabase().rawQuery("SELECT item,trigger_at,done,estimated_amount,expense_id FROM reminders WHERE id=?",new String[]{String.valueOf(id)});if(!c.moveToFirst()){c.close();return;}Map<String,Object> m=new HashMap<>();m.put("id",id);m.put("item",c.getString(0));m.put("trigger_at",c.getLong(1));m.put("done",c.getLong(2));m.put("estimated_amount",c.getDouble(3));m.put("expense_id",c.isNull(4)?null:c.getLong(4));c.close();String key=String.valueOf(id);markCloudPending("reminders",key);cloudCollection("reminders").document(key).set(m).addOnSuccessListener(v->clearCloudPending("reminders",key)).addOnFailureListener(e->refreshPendingCloudText());}
    private void deleteCloudRecord(String collection,long id){if(FirebaseAuth.getInstance().getCurrentUser()!=null){String key=String.valueOf(id);markCloudPending("delete-"+collection,key);cloudCollection(collection).document(key).delete().addOnSuccessListener(v->clearCloudPending("delete-"+collection,key)).addOnFailureListener(e->refreshPendingCloudText());}}\n    private Set<String> pendingCloudSet(){return new HashSet<>(getPreferences(0).getStringSet("cloud_pending_records",new HashSet<>()));}\n    private int pendingCloudCount(){return pendingCloudSet().size();}\n    private void markCloudPending(String collection,String id){Set<String> p=pendingCloudSet();p.add(collection+":"+id);getPreferences(0).edit().putStringSet("cloud_pending_records",p).apply();refreshPendingCloudText();}\n    private void clearCloudPending(String collection,String id){Set<String> p=pendingCloudSet();p.remove(collection+":"+id);getPreferences(0).edit().putStringSet("cloud_pending_records",p).apply();refreshPendingCloudText();}\n    private void refreshPendingCloudText(){runOnUiThread(()->{if(pendingCloudText!=null){int n=pendingCloudCount();pendingCloudText.setText("Pending sync: "+n+" records");pendingCloudText.setTextColor(n>0?GOLD:GREEN);}});}
    private class Chart extends View{Map<String,Double>data=new LinkedHashMap<>();double total;Paint p=new Paint(3);int[] colors={0xFF2F8065,0xFFE1B45A,0xFF729AC7,0xFFD97878,0xFF9A83C7,0xFF5EAAA5,0xFFB58A67,0xFF82936B,0xFFCF8DB2,0xFF8B9690};Chart(Context c){super(c);}void setData(Map<String,Double>d,double t){data=new LinkedHashMap<>(d);total=t;invalidate();}@Override protected void onDraw(Canvas c){super.onDraw(c);float w=getWidth(),h=getHeight();if(total<=0){p.setColor(muted);p.setTextSize(dp(13));p.setTextAlign(Paint.Align.CENTER);c.drawText("Your category chart will appear here",w/2,h/2,p);return;}float cx=w*.25f,cy=h*.5f,r=Math.min(w*.21f,h*.39f),start=-90;int i=0;for(String cat:CATS){double v=data.get(cat);if(v>0){p.setColor(colors[i]);c.drawArc(cx-r,cy-r,cx+r,cy+r,start,(float)(v/total*360),true,p);start+=(float)(v/total*360);}i++;}p.setColor(surface);c.drawCircle(cx,cy,r*.57f,p);p.setColor(ink);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(dp(9));c.drawText("TOTAL",cx,cy-dp(3),p);p.setTextSize(dp(10));c.drawText(money(total),cx,cy+dp(13),p);float x=w*.49f,y=dp(19);i=0;p.setTextAlign(Paint.Align.LEFT);p.setTextSize(dp(10));for(String cat:CATS){double v=data.get(cat);if(v>0&&y<h-dp(8)){p.setColor(colors[i]);c.drawCircle(x,y-dp(3),dp(4),p);p.setColor(ink);c.drawText(cat+"  "+money(v),x+dp(9),y,p);y+=dp(19);}i++;}}}
    private void remindersPage(){
        pageHeader("Buy reminders","Never forget what you need to purchase");
        LinearLayout c=content();
        LinearLayout form=card(c);
        form.addView(txt("WHAT DO YOU NEED TO BUY?",11,muted,true));
        EditText item=new EditText(this);item.setSingleLine(true);item.setHint("e.g. Medicine, groceries, school shoes");form.addView(item,lp(-1,52));
        TextView estimateLabel=txt("ESTIMATED AMOUNT (PKR)",11,muted,true);estimateLabel.setPadding(0,dp(12),0,dp(4));form.addView(estimateLabel);
        EditText estimate=new EditText(this);estimate.setSingleLine(true);estimate.setInputType(8194);estimate.setHint("Optional estimated amount");form.addView(estimate,lp(-1,50));
        Calendar selected=Calendar.getInstance();selected.add(Calendar.HOUR_OF_DAY,1);
        TextView date=txt(display.format(selected.getTime()),14,ink,true);date.setPadding(dp(12),dp(14),dp(12),dp(14));date.setBackground(shape(bg,10));
        TextView time=txt(new SimpleDateFormat("hh:mm a",Locale.ENGLISH).format(selected.getTime()),14,ink,true);time.setPadding(dp(12),dp(14),dp(12),dp(14));time.setBackground(shape(bg,10));
        TextView dl=txt("REMINDER DATE",11,muted,true);dl.setPadding(0,dp(14),0,dp(5));form.addView(dl);form.addView(date,lp(-1,48));
        date.setOnClickListener(v->{Calendar x=(Calendar)selected.clone();new DatePickerDialog(this,(dpick,y,m,d)->{selected.set(y,m,d);date.setText(display.format(selected.getTime()));},x.get(Calendar.YEAR),x.get(Calendar.MONTH),x.get(Calendar.DAY_OF_MONTH)).show();});
        TextView tl=txt("ALARM TIME",11,muted,true);tl.setPadding(0,dp(14),0,dp(5));form.addView(tl);form.addView(time,lp(-1,48));
        time.setOnClickListener(v->{new TimePickerDialog(this,(tpick,h,min)->{selected.set(Calendar.HOUR_OF_DAY,h);selected.set(Calendar.MINUTE,min);selected.set(Calendar.SECOND,0);time.setText(new SimpleDateFormat("hh:mm a",Locale.ENGLISH).format(selected.getTime()));},selected.get(Calendar.HOUR_OF_DAY),selected.get(Calendar.MINUTE),false).show();});
        Button save=primaryButton("Save buy reminder");LinearLayout.LayoutParams saveLp=lp(-1,48);saveLp.topMargin=dp(16);form.addView(save,saveLp);
        save.setOnClickListener(v->{String name=item.getText().toString().trim();if(name.isEmpty()){item.setError("Enter what you need to buy");return;}if(selected.getTimeInMillis()<=System.currentTimeMillis()){Toast.makeText(this,"Please choose a future date and time",Toast.LENGTH_LONG).show();return;}if(android.os.Build.VERSION.SDK_INT>=31&&!getSystemService(android.app.AlarmManager.class).canScheduleExactAlarms()){try{startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,android.net.Uri.parse("package:"+getPackageName())));Toast.makeText(this,"Allow alarms, then tap Save reminder again",Toast.LENGTH_LONG).show();}catch(Exception e){}return;}if(android.os.Build.VERSION.SDK_INT>=33&&checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=android.content.pm.PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},901);Toast.makeText(this,"Allow notifications, then tap Save reminder again",Toast.LENGTH_LONG).show();return;}double estimated=0;String estimateText=estimate.getText().toString().trim();if(!estimateText.isEmpty()){try{estimated=Double.parseDouble(estimateText);if(estimated<=0||Double.isInfinite(estimated)||Double.isNaN(estimated))throw new Exception();}catch(Exception ex){estimate.setError("Enter a valid estimate");return;}}ContentValues cv=new ContentValues();cv.put("item",name);cv.put("estimated_amount",estimated);cv.put("trigger_at",selected.getTimeInMillis());cv.put("done",0);long id=db.getWritableDatabase().insert("reminders",null,cv);if(id>0){pushReminder(id);ReminderReceiver.schedule(this,id,name,selected.getTimeInMillis());Toast.makeText(this,"Buy reminder saved",Toast.LENGTH_SHORT).show();showPage(4);}});
        LinearLayout list=card(c);list.addView(txt("UPCOMING REMINDERS",11,GOLD,true));Cursor q=db.getReadableDatabase().rawQuery("SELECT id,item,trigger_at,done,estimated_amount FROM reminders WHERE done=0 ORDER BY trigger_at",null);if(!q.moveToFirst()){list.addView(txt("No upcoming reminders.",13,muted,false));}else do{long id=q.getLong(0),when=q.getLong(2);String name=q.getString(1);double estimateValue=q.getDouble(4);LinearLayout r=row();LinearLayout details=col();details.addView(txt(name,14,ink,true));details.addView(txt(new SimpleDateFormat("dd MMM yyyy · hh:mm a",Locale.ENGLISH).format(new Date(when)),11,muted,false));if(estimateValue>0)details.addView(txt("Estimated: "+money(estimateValue),11,GREEN,true));r.addView(details,new LinearLayout.LayoutParams(0,-2,1));Button done=smallFilter("Done");r.addView(done);done.setOnClickListener(v->confirmReminderPurchase(id,name,estimateValue));r.setOnLongClickListener(v->{new AlertDialog.Builder(this).setMessage("Delete this reminder?").setNegativeButton("Cancel",null).setPositiveButton("Delete",(a,b)->{ReminderReceiver.cancel(this,id);db.getWritableDatabase().delete("reminders","id=?",new String[]{String.valueOf(id)});deleteCloudRecord("reminders",id);showPage(4);}).show();return true;});list.addView(r,lp(-1,62));View sep=new View(this);sep.setBackgroundColor(line);list.addView(sep,lp(-1,1));}while(q.moveToNext());q.close();
    }
    private void confirmReminderPurchase(long reminderId,String itemName,double estimated){
        LinearLayout box=col();box.setPadding(dp(4),dp(4),dp(4),0);
        box.addView(txt("CATEGORY  *",11,muted,true));Spinner cat=new Spinner(this);cat.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,CATS));box.addView(cat,lp(-1,48));
        TextView amountLabel=txt("ACTUAL AMOUNT PAID (PKR)  *",11,muted,true);amountLabel.setPadding(0,dp(12),0,dp(4));box.addView(amountLabel);
        EditText amount=new EditText(this);amount.setSingleLine(true);amount.setInputType(8194);if(estimated>0)amount.setText(String.valueOf(estimated));else amount.setHint("Enter amount actually paid");box.addView(amount,lp(-1,50));
        TextView descLabel=txt("DESCRIPTION (OPTIONAL)",11,muted,true);descLabel.setPadding(0,dp(12),0,dp(4));box.addView(descLabel);
        EditText description=new EditText(this);description.setSingleLine(true);description.setText(itemName);box.addView(description,lp(-1,50));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Confirm purchase").setMessage(estimated>0?"Use the estimated amount or change it to the actual amount paid.":"Enter the amount you actually paid to add this purchase to expenses.").setView(box).setNegativeButton("Cancel",null).setPositiveButton("Confirm Done",null).create();
        dialog.setOnShowListener(v->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{String raw=amount.getText().toString().trim();double paid;try{paid=Double.parseDouble(raw);if(paid<=0||Double.isInfinite(paid)||Double.isNaN(paid))throw new Exception();}catch(Exception ex){amount.setError("Enter a valid amount");return;}
            ContentValues expense=new ContentValues();expense.put("category",cat.getSelectedItem().toString());expense.put("description",description.getText().toString().trim());expense.put("amount",paid);expense.put("date",iso.format(new Date()));long expenseId=db.getWritableDatabase().insert("expenses",null,expense);if(expenseId>0)pushExpense(expenseId);if(expenseId<0){Toast.makeText(this,"Could not save expense",Toast.LENGTH_LONG).show();return;}
            ContentValues completed=new ContentValues();completed.put("done",1);completed.put("expense_id",expenseId);db.getWritableDatabase().update("reminders",completed,"id=?",new String[]{String.valueOf(reminderId)});pushReminder(reminderId);ReminderReceiver.cancel(this,reminderId);dialog.dismiss();Toast.makeText(this,"Purchase added to expense history",Toast.LENGTH_SHORT).show();showPage(4);
        });});dialog.show();
    }
    private static class DB extends SQLiteOpenHelper{DB(Context c){super(c,"saqib_finance.db",null,3);}public void onCreate(SQLiteDatabase d){d.execSQL("CREATE TABLE expenses(id INTEGER PRIMARY KEY AUTOINCREMENT,category TEXT NOT NULL,description TEXT NOT NULL,amount REAL NOT NULL,date TEXT NOT NULL)");d.execSQL("CREATE TABLE reminders(id INTEGER PRIMARY KEY AUTOINCREMENT,item TEXT NOT NULL,trigger_at INTEGER NOT NULL,done INTEGER NOT NULL DEFAULT 0,estimated_amount REAL NOT NULL DEFAULT 0,expense_id INTEGER)");}public void onUpgrade(SQLiteDatabase d,int o,int n){if(o<2)d.execSQL("CREATE TABLE IF NOT EXISTS reminders(id INTEGER PRIMARY KEY AUTOINCREMENT,item TEXT NOT NULL,trigger_at INTEGER NOT NULL,done INTEGER NOT NULL DEFAULT 0)");if(o<3){d.execSQL("ALTER TABLE reminders ADD COLUMN estimated_amount REAL NOT NULL DEFAULT 0");d.execSQL("ALTER TABLE reminders ADD COLUMN expense_id INTEGER");}}}
}