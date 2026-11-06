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
import android.webkit.*;
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
    private boolean cloudSyncInProgress=false;
    private static final String[] CATS={"Kids","Wife","Family","Personal","Medicine","Grocery","Transport","Bills","Shopping","Dining","Other"};
    private static final int GREEN=0xFF2463D9, DEEP=0xFF174A9C, GOLD=0xFF5B8FE8, MINT=0xFFEAF2FF;
    private boolean categoryGridView=true; private GestureDetector swipeDetector; private DB db; private LinearLayout root,body,nav; private int page=0; private boolean dark=false;
    private Calendar month=Calendar.getInstance(); private String from="",to="",category="All categories",selectedAddCategory="Grocery",pendingItemName="";
    private TextView totalText,countText,monthText,pendingCloudText; private LinearLayout historyList;
    private final SimpleDateFormat iso=new SimpleDateFormat("yyyy-MM-dd",Locale.US);
    private final SimpleDateFormat display=new SimpleDateFormat("dd MMM yyyy",Locale.ENGLISH);
    private final SimpleDateFormat monthFmt=new SimpleDateFormat("MMMM yyyy",Locale.ENGLISH);
    private int bg,surface,ink,muted,line; private boolean unlocked=false, lockPromptShowing=false, uiReady=false;
    @Override public void onCreate(Bundle b){super.onCreate(b);swipeDetector=new GestureDetector(this,new GestureDetector.SimpleOnGestureListener(){@Override public boolean onFling(MotionEvent e1,MotionEvent e2,float velocityX,float velocityY){if(e1==null||e2==null)return false;float dx=e2.getX()-e1.getX(),dy=e2.getY()-e1.getY();if(Math.abs(dx)>dp(80)&&Math.abs(dx)>Math.abs(dy)*1.25f&&Math.abs(velocityX)>dp(300)){int current=page==5?1:(page==6?2:page);int next=current+(dx<0?1:-1);if(next>=0&&next<=4){showPage(next);return true;}}return false;}});dark=getPreferences(0).getBoolean("dark",false);db=new DB(this);seedDefaultItems();refreshKnownItemIcons();setupGoogleSignIn();registerCloudNetworkCallback();applyCloudNetworkPreference();if(FirebaseAuth.getInstance().getCurrentUser()!=null)syncAllCloud();else if(getRetentionMonths()>0)cleanupOldRecords(false);palette();if(getPreferences(0).getBoolean("screen_lock",true)){showLockScreen();showSecurityPrompt();}else{unlocked=true;openMainUI(0);}}

    @Override public boolean dispatchTouchEvent(MotionEvent event){if(swipeDetector!=null)swipeDetector.onTouchEvent(event);return super.dispatchTouchEvent(event);}
    @Override protected void onResume(){super.onResume();if(getPreferences(0).getBoolean("screen_lock",true)&&!unlocked&&!lockPromptShowing){showLockScreen();showSecurityPrompt();}}
    @Override protected void onStop(){super.onStop();if(!isChangingConfigurations()&&getPreferences(0).getBoolean("screen_lock",true)&&getPreferences(0).getBoolean("lock_on_recent_apps",false)){unlocked=false;}}
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed(){
        if(!unlocked){super.onBackPressed();return;}
        if(page!=0){
            showPage(0);
            return;
        }
        super.onBackPressed();
    }

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
    private void palette(){bg=dark?0xFF101827:0xFFF4F7FC;surface=dark?0xFF1B2638:0xFFFFFFFF;ink=dark?0xFFF1F5FC:0xFF1C2B45;muted=dark?0xFFAFBDD1:0xFF718096;line=dark?0xFF34445D:0xFFE4EAF3;getWindow().setStatusBarColor(DEEP);getWindow().setNavigationBarColor(DEEP);}
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
        if(p==0)homePage();else if(p==1)addPage();else if(p==2)historyPage();else if(p==3)settingsPage();else if(p==4)remindersPage();else if(p==5)categoryItemsPage(selectedAddCategory);else if(p==6)balancePage();
        String[] labels={"Home","Drafts","History","Settings","Reminders"};String[] icons={"⌂","+","▤","⚙","◷"};
        for(int i=0;i<5;i++){final int index=i;LinearLayout item=col();item.setGravity(Gravity.CENTER);boolean active=(i==p)||(p==5&&i==1);TextView ic=txt(icons[i],22,active?GOLD:0xFFD0DED5,true);ic.setGravity(Gravity.CENTER);TextView label=txt(labels[i],10,active?Color.WHITE:0xFFB5C8BD,active);label.setGravity(Gravity.CENTER);item.addView(ic);item.addView(label);item.setOnClickListener(v->showPage(index));nav.addView(item,new LinearLayout.LayoutParams(0,-1,1));}
    }
    private void pageHeader(String title,String sub){LinearLayout h=col();h.setPadding(dp(16),dp(14),dp(16),dp(18));GradientDrawable gd=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{0xFF78A5E8,0xFF6997E2,0xFF5C89D4});gd.setCornerRadii(new float[]{0,0,0,0,dp(24),dp(24),dp(24),dp(24)});h.setBackground(gd);LinearLayout brand=row();ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.ic_launcher);logo.setScaleType(ImageView.ScaleType.FIT_CENTER);logo.setContentDescription("Saqib Finance app icon");brand.addView(logo,lp(78,78));LinearLayout names=col();names.setGravity(Gravity.CENTER_VERTICAL);names.setPadding(dp(10),0,0,0);TextView brandName=txt("Saqib Finance",27,Color.WHITE,true);brandName.setSingleLine(true);brandName.setIncludeFontPadding(false);names.addView(brandName);TextView brandTagline=txt("Track · Manage · Save",12,0xFFDCE9FF,true);brandTagline.setPadding(0,dp(5),0,0);names.addView(brandTagline);brand.addView(names,new LinearLayout.LayoutParams(0,-2,1));TextView gear=txt("⚙",28,Color.WHITE,true);gear.setGravity(Gravity.CENTER);gear.setOnClickListener(v->showPage(3));brand.addView(gear,lp(42,52));if(!title.equals("Home")){TextView titleView=txt(title,19,Color.WHITE,true);titleView.setPadding(0,dp(12),0,dp(2));h.addView(titleView);h.addView(txt(sub,11,0xFFDCE9FF,false));}body.addView(h);}
    private LinearLayout content(){LinearLayout c=col();c.setPadding(dp(17),dp(17),dp(17),0);body.addView(c);return c;}
    private LinearLayout card(LinearLayout parent){LinearLayout c=col();c.setPadding(dp(16),dp(15),dp(16),dp(15));c.setBackground(shape(surface,18));c.setElevation(dp(2));LinearLayout.LayoutParams q=lp(-1,-2);q.bottomMargin=dp(13);parent.addView(c,q);return c;}
    private void homePage(){
        pageHeader("Home","Track · Manage · Save");
        LinearLayout c=content();
        LinearLayout monthRow=row();monthRow.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        Button prev=smallButton("‹"),next=smallButton("›");
        monthText=txt(monthFmt.format(month.getTime()),13,ink,true);monthText.setGravity(Gravity.CENTER);
        monthRow.addView(prev,lp(34,34));monthRow.addView(monthText,lp(112,34));monthRow.addView(next,lp(34,34));
        LinearLayout.LayoutParams mr=lp(-1,-2);mr.bottomMargin=dp(10);c.addView(monthRow,mr);
        prev.setOnClickListener(v->{month.add(Calendar.MONTH,-1);showPage(0);});next.setOnClickListener(v->{month.add(Calendar.MONTH,1);showPage(0);});
        LinearLayout totals=row();LinearLayout.LayoutParams tlp=lp(-1,-2);tlp.bottomMargin=dp(13);c.addView(totals,tlp);
        LinearLayout spent=col();spent.setPadding(dp(13),dp(13),dp(10),dp(13));spent.setBackground(shape(surface,18));spent.setElevation(dp(3));LinearLayout.LayoutParams slp=new LinearLayout.LayoutParams(0,dp(94),1);slp.rightMargin=dp(6);totals.addView(spent,slp);
        LinearLayout spentTop=row();spentTop.addView(txt("👛",23,GREEN,true));TextView spentLabel=txt("Total Expenses",12,ink,true);spentLabel.setPadding(dp(7),0,0,0);spentTop.addView(spentLabel);spent.addView(spentTop);
        totalText=txt("Rs. 0",20,ink,true);totalText.setPadding(dp(2),dp(5),0,0);spent.addView(totalText);
        LinearLayout balance=col();balance.setPadding(dp(13),dp(13),dp(10),dp(13));balance.setBackground(shape(surface,18));balance.setElevation(dp(3));totals.addView(balance,new LinearLayout.LayoutParams(0,dp(94),1));
        balance.addView(txt("🪙  Balance",12,ink,true));TextView balanceSoon=txt(money(getBalanceTotal()),18,GREEN,true);balanceSoon.setPadding(dp(2),dp(8),0,0);balance.addView(balanceSoon);balance.setOnClickListener(v->showPage(6));
        countText=txt("0 expenses recorded",10,muted,false);countText.setPadding(dp(2),dp(2),0,0);spent.addView(countText);updateHomeTotals();
        LinearLayout categories=col();LinearLayout.LayoutParams catLp=lp(-1,-2);catLp.bottomMargin=dp(13);c.addView(categories,catLp);
        addCategoryGrid(categories);
        LinearLayout recent=card(c);LinearLayout recentHead=row();recentHead.addView(txt("◷  Recent Expenses",16,ink,true),new LinearLayout.LayoutParams(0,-2,1));TextView all=txt("View All  →",12,GREEN,true);recentHead.addView(all);all.setOnClickListener(v->showPage(2));recent.addView(recentHead);addRecent(recent,5);
    }
    private double getIncomeTotal(){Cursor c=db.getReadableDatabase().rawQuery("SELECT COALESCE(SUM(amount),0) FROM incomes",null);double v=0;if(c.moveToFirst())v=c.getDouble(0);c.close();return v;}
    private double getExpenseTotal(){Cursor c=db.getReadableDatabase().rawQuery("SELECT COALESCE(SUM(amount),0) FROM expenses",null);double v=0;if(c.moveToFirst())v=c.getDouble(0);c.close();return v;}
    private double getBalanceTotal(){return getIncomeTotal()-getExpenseTotal();}
    private void updateHomeTotals(){String key=new SimpleDateFormat("yyyy-MM",Locale.US).format(month.getTime());Cursor q=db.getReadableDatabase().rawQuery("SELECT COALESCE(SUM(amount),0),COUNT(*) FROM expenses WHERE substr(date,1,7)=?",new String[]{key});double total=0;int count=0;if(q.moveToFirst()){total=q.getDouble(0);count=q.getInt(1);}q.close();totalText.setText(money(total));countText.setText(count+" expense"+(count==1?"":"s")+" recorded");}
    private void loadMonth(Chart chart,TextView historyCount,TextView historyAmount){String key=new SimpleDateFormat("yyyy-MM",Locale.US).format(month.getTime());Map<String,Double> data=new LinkedHashMap<>();double total=0;int count=0;for(String s:CATS)data.put(s,0d);Cursor q=db.getReadableDatabase().rawQuery("SELECT category,SUM(amount),COUNT(*) FROM expenses WHERE substr(date,1,7)=? GROUP BY category",new String[]{key});while(q.moveToNext()){String cat=q.getString(0);double val=q.getDouble(1);data.put(cat,val);total+=val;count+=q.getInt(2);}q.close();totalText.setText(money(total));countText.setText(count+" expense"+(count==1?"":"s")+" recorded");historyCount.setText(count+" records");historyAmount.setText(money(total));chart.setData(data,total);}
    private void addCategoryGrid(LinearLayout parent){
        String[] icons={"🧒","💍","👨‍👩‍👧","🧴","💊","🛒","🏍️","🧾","🛍️","🍽️","📦"};
        int[] tints={0xFFB8D9F8,0xFFF4B8D4,0xFFFFD99A,0xFFF4D7B5,0xFFB9F0D0,0xFFDCC5F4,0xFFBDEBEA,0xFFB8D9F8,0xFFF4B8D4,0xFFC8EDB6,0xFFDCC5F4};
        for(int i=0;i<CATS.length;i+=4){
            LinearLayout lineRow=row();LinearLayout.LayoutParams rowLp=lp(-1,-2);rowLp.bottomMargin=dp(8);parent.addView(lineRow,rowLp);
            for(int j=i;j<Math.min(i+4,CATS.length);j++){
                final String cat=CATS[j];final int idx=j;
                LinearLayout tile=col();tile.setGravity(Gravity.CENTER);tile.setPadding(dp(3),dp(8),dp(3),dp(7));tile.setBackground(shape(tints[idx],19));tile.setElevation(dp(1));
                LinearLayout.LayoutParams tileLp=new LinearLayout.LayoutParams(0,dp(83),1);if(j>i)tileLp.leftMargin=dp(5);lineRow.addView(tile,tileLp);
                TextView symbol=txt(icons[idx],25,ink,true);symbol.setGravity(Gravity.CENTER);tile.addView(symbol,lp(-1,39));
                TextView label=txt(cat,11,Color.BLACK,true);label.setGravity(Gravity.CENTER);label.setSingleLine(true);label.setPadding(0,dp(3),0,0);tile.addView(label);
                tile.setOnClickListener(v->{selectedAddCategory=cat;showPage(5);});
            }
        }
    }
    private void addRecent(LinearLayout target,int limit){Cursor q=db.getReadableDatabase().rawQuery("SELECT category,description,amount,date FROM expenses ORDER BY date DESC,id DESC LIMIT "+limit,null);if(!q.moveToFirst()){TextView e=txt("No expenses yet. Add your first expense.",13,muted,false);e.setPadding(0,dp(18),0,dp(12));target.addView(e);}else do{String cat=q.getString(0),desc=q.getString(1);LinearLayout r=row();TextView left=txt(cat+(desc.isEmpty()?"":" · "+desc),13,ink,true);r.addView(left,new LinearLayout.LayoutParams(0,-2,1));r.addView(txt(money(q.getDouble(2)),13,GREEN,true));target.addView(r,lp(-1,43));View d=new View(this);d.setBackgroundColor(line);target.addView(d,lp(-1,1));}while(q.moveToNext());q.close();}

    private void balancePage(){
        pageHeader("Balance","Money received and money spent");LinearLayout c=content();
        double income=getIncomeTotal(),out=getExpenseTotal(),available=income-out;
        LinearLayout hero=card(c);hero.setBackground(shape(DEEP,20));hero.addView(txt("AVAILABLE BALANCE",11,0xFFBDD2C4,true));hero.addView(txt(money(available),30,Color.WHITE,true));hero.addView(txt("Total money received minus total expenses",11,0xFFBDD2C4,false));
        LinearLayout totals=row();LinearLayout.LayoutParams tr=lp(-1,-2);tr.bottomMargin=dp(13);c.addView(totals,tr);
        LinearLayout in=col();in.setPadding(dp(13),dp(14),dp(8),dp(14));in.setBackground(shape(surface,16));LinearLayout.LayoutParams il=new LinearLayout.LayoutParams(0,dp(100),1);il.rightMargin=dp(6);totals.addView(in,il);in.addView(txt("↙  MONEY IN",11,GREEN,true));in.addView(txt(money(income),18,ink,true));
        LinearLayout outCard=col();outCard.setPadding(dp(13),dp(14),dp(8),dp(14));outCard.setBackground(shape(surface,16));totals.addView(outCard,new LinearLayout.LayoutParams(0,dp(100),1));outCard.addView(txt("↗  MONEY OUT",11,0xFFB36A45,true));outCard.addView(txt(money(out),18,ink,true));
        Button add=primaryButton("+  Add Money In");LinearLayout.LayoutParams addLp=lp(-1,50);addLp.bottomMargin=dp(13);c.addView(add,addLp);add.setOnClickListener(v->showAddIncomeDialog());
        LinearLayout incomeCard=card(c);incomeCard.addView(txt("MONEY IN HISTORY",12,GOLD,true));Cursor ic=db.getReadableDatabase().rawQuery("SELECT id,amount,source,date,note FROM incomes ORDER BY date DESC,id DESC",null);
        if(!ic.moveToFirst())incomeCard.addView(txt("No money received yet. Tap Add Money In to record it.",13,muted,false));else do{long id=ic.getLong(0);double amount=ic.getDouble(1);String source=ic.getString(2),date=ic.getString(3),note=ic.getString(4);LinearLayout r=row();r.setPadding(0,dp(10),0,dp(10));LinearLayout details=col();details.addView(txt(source,14,ink,true));details.addView(txt(dateLabel(date)+(note==null||note.trim().isEmpty()?"":" · "+note),11,muted,false));r.addView(details,new LinearLayout.LayoutParams(0,-2,1));r.addView(txt("+"+money(amount),13,GREEN,true));r.setOnClickListener(v->editIncome(id,amount,source,date,note));incomeCard.addView(r);View sep=new View(this);sep.setBackgroundColor(line);incomeCard.addView(sep,lp(-1,1));}while(ic.moveToNext());ic.close();
        LinearLayout expenseCard=card(c);LinearLayout expenseHead=row();expenseHead.addView(txt("MONEY OUT · EXPENSES",12,GOLD,true),new LinearLayout.LayoutParams(0,-2,1));TextView view=txt("View all →",12,GREEN,true);expenseHead.addView(view);view.setOnClickListener(v->showPage(2));expenseCard.addView(expenseHead);
        Cursor ec=db.getReadableDatabase().rawQuery("SELECT category,description,amount,date,item FROM expenses ORDER BY date DESC,id DESC LIMIT 30",null);if(!ec.moveToFirst())expenseCard.addView(txt("No expenses recorded yet.",13,muted,false));else do{String cat=ec.getString(0),desc=ec.getString(1),date=ec.getString(3),item=ec.getString(4);LinearLayout r=row();r.setPadding(0,dp(9),0,dp(9));LinearLayout details=col();details.addView(txt(cat+(item==null||item.isEmpty()?"":" · "+item),13,ink,true));details.addView(txt(dateLabel(date)+(desc==null||desc.isEmpty()||desc.equals(item)?"":" · "+desc),11,muted,false));r.addView(details,new LinearLayout.LayoutParams(0,-2,1));r.addView(txt("-"+money(ec.getDouble(2)),13,0xFFB36A45,true));expenseCard.addView(r);View sep=new View(this);sep.setBackgroundColor(line);expenseCard.addView(sep,lp(-1,1));}while(ec.moveToNext());ec.close();
    }
    private void showAddIncomeDialog(){
        LinearLayout box=col();box.setPadding(dp(4),dp(4),dp(4),0);EditText amount=new EditText(this);amount.setSingleLine(true);amount.setInputType(8194);amount.setHint("Amount received (PKR)");box.addView(amount,lp(-1,50));
        EditText source=new EditText(this);source.setSingleLine(true);source.setHint("Received from (e.g. Salary, client, family)");box.addView(source,lp(-1,50));
        TextView date=txt(display.format(new Date()),14,ink,true);date.setPadding(dp(12),dp(13),dp(12),dp(13));date.setBackground(shape(bg,10));box.addView(date,lp(-1,48));date.setOnClickListener(v->pickDate(date));
        EditText note=new EditText(this);note.setSingleLine(true);note.setHint("Note (optional)");box.addView(note,lp(-1,50));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Add Money In").setView(box).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{try{double value=Double.parseDouble(amount.getText().toString().trim());if(value<=0||Double.isInfinite(value)||Double.isNaN(value))throw new Exception();String from=source.getText().toString().trim();if(from.isEmpty()){source.setError("Enter who or where money came from");return;}Date dt=display.parse(date.getText().toString());ContentValues cv=new ContentValues();cv.put("amount",value);cv.put("source",from);cv.put("date",iso.format(dt));cv.put("note",note.getText().toString().trim());long id=db.getWritableDatabase().insert("incomes",null,cv);if(id<=0)throw new Exception();pushIncome(id);dialog.dismiss();Toast.makeText(this,"Money received saved",Toast.LENGTH_SHORT).show();showPage(6);}catch(Exception e){amount.setError("Enter a valid amount");}}));dialog.show();
    }
    private void editIncome(long id,double value,String sourceValue,String dateValue,String noteValue){
        LinearLayout box=col();EditText amount=new EditText(this);amount.setInputType(8194);amount.setText(String.valueOf(value));box.addView(amount);EditText source=new EditText(this);source.setText(sourceValue);source.setHint("Received from");box.addView(source);
        TextView date=txt(dateLabel(dateValue),14,ink,true);date.setPadding(dp(10),dp(12),dp(10),dp(12));date.setOnClickListener(v->pickDate(date));box.addView(date);EditText note=new EditText(this);note.setText(noteValue==null?"":noteValue);note.setHint("Note (optional)");box.addView(note);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Edit money in").setView(box).setNegativeButton("Cancel",null).setNeutralButton("Delete",(d,w)->new AlertDialog.Builder(this).setMessage("Delete this money-in record?").setNegativeButton("Cancel",null).setPositiveButton("Delete",(x,y)->{db.getWritableDatabase().delete("incomes","id=?",new String[]{String.valueOf(id)});deleteCloudRecord("incomes",id);showPage(6);}).show()).setPositiveButton("Save",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{try{double n=Double.parseDouble(amount.getText().toString().trim());String from=source.getText().toString().trim();Date dt=display.parse(date.getText().toString());if(n<=0||from.isEmpty())throw new Exception();ContentValues cv=new ContentValues();cv.put("amount",n);cv.put("source",from);cv.put("date",iso.format(dt));cv.put("note",note.getText().toString().trim());db.getWritableDatabase().update("incomes",cv,"id=?",new String[]{String.valueOf(id)});pushIncome(id);dialog.dismiss();showPage(6);}catch(Exception e){amount.setError("Enter a valid amount and source");}}));dialog.show();
    }
    private void categoryItemsPage(String cat){
        pageHeader(cat,"Choose an item or add your own");
        LinearLayout c=content();
        LinearLayout panel=card(c);
        panel.addView(txt("ITEMS IN "+cat.toUpperCase(Locale.US),11,GOLD,true));

        LinearLayout viewBar=row();
        viewBar.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        viewBar.setPadding(0,dp(2),0,dp(10));
        Button gridBtn=smallFilter("▦  Grid View"),listBtn=smallFilter("☰  List View");
        gridBtn.setTextColor(categoryGridView?Color.WHITE:GREEN);
        gridBtn.setBackground(shape(categoryGridView?GREEN:MINT,9));
        listBtn.setTextColor(!categoryGridView?Color.WHITE:GREEN);
        listBtn.setBackground(shape(!categoryGridView?GREEN:MINT,9));
        viewBar.addView(gridBtn,new LinearLayout.LayoutParams(0,dp(40),1));
        LinearLayout.LayoutParams bl=new LinearLayout.LayoutParams(0,dp(40),1);bl.leftMargin=dp(8);
        viewBar.addView(listBtn,bl);panel.addView(viewBar);
        gridBtn.setOnClickListener(v->{categoryGridView=true;showPage(5);});
        listBtn.setOnClickListener(v->{categoryGridView=false;showPage(5);});

        LinearLayout items=col();panel.addView(items);
        ArrayList<Long> ids=new ArrayList<>();
        ArrayList<String> names=new ArrayList<>(),icons=new ArrayList<>();
        Cursor q=db.getReadableDatabase().rawQuery("SELECT id,name,icon FROM category_items WHERE category=? ORDER BY name COLLATE NOCASE",new String[]{cat});
        while(q.moveToNext()){ids.add(q.getLong(0));names.add(q.getString(1));icons.add(q.getString(2));}q.close();

        if(categoryGridView){
            for(int i=0;i<ids.size();i+=2){
                LinearLayout gridRow=row();LinearLayout.LayoutParams rp=lp(-1,-2);rp.topMargin=dp(4);items.addView(gridRow,rp);
                for(int j=i;j<Math.min(i+2,ids.size());j++){
                    final long id=ids.get(j);final String name=names.get(j),ic=icons.get(j);
                    LinearLayout tile=col();tile.setGravity(Gravity.CENTER);tile.setPadding(dp(8),dp(12),dp(8),dp(12));tile.setBackground(shape(MINT,14));
                    LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(0,dp(104),1);if(j>i)tp.leftMargin=dp(8);gridRow.addView(tile,tp);
                    addItemIcon(tile,ic==null?"📦":ic,28,lp(-1,40));
                    TextView label=txt(name,13,ink,true);label.setGravity(Gravity.CENTER);label.setMaxLines(2);tile.addView(label);
                    tile.setOnClickListener(v->{selectedAddCategory=cat;addDraftItem(cat,name);});
                    tile.setOnLongClickListener(v->{showItemOptionsDialog(id,name,cat);return true;});
                }
            }
            LinearLayout addRow=row();LinearLayout.LayoutParams arp=lp(-1,-2);arp.topMargin=dp(8);items.addView(addRow,arp);
            LinearLayout addTile=col();addTile.setGravity(Gravity.CENTER);addTile.setPadding(dp(8),dp(12),dp(8),dp(12));
            addTile.setBackground(shape(surface,14));addTile.setElevation(dp(1));
            addRow.addView(addTile,new LinearLayout.LayoutParams(0,dp(104),1));
            TextView plus=txt("+",32,GREEN,true);plus.setGravity(Gravity.CENTER);addTile.addView(plus,lp(-1,40));
            TextView addLabel=txt("New Item",13,GREEN,true);addLabel.setGravity(Gravity.CENTER);addTile.addView(addLabel);
            addTile.setOnClickListener(v->showNewItemDialog(cat));
        }else{
            for(int i=0;i<ids.size();i++){
                final long id=ids.get(i);final String name=names.get(i),ic=icons.get(i);
                LinearLayout r=row();r.setPadding(dp(5),dp(9),dp(5),dp(9));
                addItemIcon(r,ic==null?"📦":ic,24,lp(42,42));
                r.addView(txt(name,14,ink,true),new LinearLayout.LayoutParams(0,-2,1));
                r.addView(txt("›",24,GREEN,true));
                r.setOnClickListener(v->{selectedAddCategory=cat;addDraftItem(cat,name);});
                r.setOnLongClickListener(v->{showItemOptionsDialog(id,name,cat);return true;});
                items.addView(r);View sep=new View(this);sep.setBackgroundColor(line);items.addView(sep,lp(-1,1));
            }
            LinearLayout addRow=row();addRow.setGravity(Gravity.CENTER_VERTICAL);addRow.setPadding(dp(8),dp(10),dp(8),dp(10));
            addRow.setBackground(shape(MINT,12));
            addRow.addView(txt("+",26,GREEN,true),lp(42,42));
            addRow.addView(txt("New Item",14,GREEN,true),new LinearLayout.LayoutParams(0,-2,1));
            addRow.addView(txt("Add",12,GREEN,true));
            addRow.setOnClickListener(v->showNewItemDialog(cat));items.addView(addRow,lp(-1,58));
        }
        TextView hint=txt("Long-press an item to move it to another category or delete it.",11,muted,false);
        hint.setPadding(dp(4),dp(10),dp(4),dp(12));c.addView(hint);
    }

    private void showNewItemDialog(String cat){
        LinearLayout box=col();box.setPadding(dp(4),dp(4),dp(4),0);
        EditText name=new EditText(this);name.setSingleLine(true);name.setHint("Enter item name");box.addView(name,lp(-1,52));
        LinearLayout iconRow=row();iconRow.setPadding(0,dp(8),0,dp(4));
        TextView preview=txt("📦",34,ink,true);preview.setGravity(Gravity.CENTER);iconRow.addView(preview,lp(58,54));
        LinearLayout iconActions=col();
        Button choose=smallFilter("Choose icon");iconActions.addView(choose,lp(-1,40));
        Button online=smallFilter("Browse online icons");LinearLayout.LayoutParams ol=lp(-1,38);ol.topMargin=dp(5);iconActions.addView(online,ol);
        iconRow.addView(iconActions,new LinearLayout.LayoutParams(0,-2,1));box.addView(iconRow);
        final String[] chosen={suggestItemIcon("")};final boolean[] manuallyChosen={false};
        name.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int st,int count,int after){}
            public void onTextChanged(CharSequence s,int st,int before,int count){if(!manuallyChosen[0]){chosen[0]=suggestItemIcon(s.toString());preview.setText(chosen[0]);}}
            public void afterTextChanged(android.text.Editable e){}
        });
        choose.setOnClickListener(v->showItemIconPicker(chosen,preview,manuallyChosen));
        online.setOnClickListener(v->showOnlineIconPicker(name.getText().toString().trim(),chosen,preview,manuallyChosen));
        new AlertDialog.Builder(this).setTitle("New item · "+cat).setView(box).setNegativeButton("Cancel",null).setPositiveButton("Add",(d,w)->{
            String n=name.getText().toString().trim();
            if(n.isEmpty()){Toast.makeText(this,"Enter an item name",Toast.LENGTH_SHORT).show();return;}
            ContentValues cv=new ContentValues();cv.put("name",n);cv.put("category",cat);cv.put("icon",chosen[0]);
            long id=db.getWritableDatabase().insertWithOnConflict("category_items",null,cv,SQLiteDatabase.CONFLICT_IGNORE);
            if(id==-1)Toast.makeText(this,"Item already exists in this category",Toast.LENGTH_SHORT).show();
            else if(id>0)pushCategoryItem(id);
            showPage(5);
        }).show();
    }

    private void addItemIcon(LinearLayout parent,String icon,int size,LinearLayout.LayoutParams params){
        String value=icon==null?"📦":icon.trim();
        if(value.regionMatches(true,0,"@iconify:",0,9)){
            String key=value.substring(9).trim();
            if(key.isEmpty()){
                TextView fallback=txt("📦",size,ink,true);fallback.setGravity(Gravity.CENTER);parent.addView(fallback,params);return;
            }
            WebView web=new WebView(this);web.setBackgroundColor(Color.TRANSPARENT);web.setVerticalScrollBarEnabled(false);web.setHorizontalScrollBarEnabled(false);
            web.setOverScrollMode(View.OVER_SCROLL_NEVER);web.setLayerType(View.LAYER_TYPE_SOFTWARE,null);
            web.getSettings().setJavaScriptEnabled(false);web.getSettings().setDomStorageEnabled(false);web.getSettings().setLoadsImagesAutomatically(true);
            parent.addView(web,params);
            String src="https://api.iconify.design/"+key.replace(":","/")+".svg?color=%23"+(dark?"F1F4F0":"245B45");
            String html="<html><head><meta name='viewport' content='width=device-width, initial-scale=1'></head><body style='margin:0;padding:0;background:transparent;display:flex;align-items:center;justify-content:center;overflow:hidden'><img alt='' style='width:100%;height:100%;object-fit:contain' src='"+src+"' onerror=\"this.style.display='none';this.parentElement.innerText='📦';this.parentElement.style.color='"+(dark?"#F1F4F0":"#245B45")+"';this.parentElement.style.fontSize='24px';\"></body></html>";
            web.loadDataWithBaseURL("https://api.iconify.design/",html,"text/html","UTF-8",null);
        }else{TextView t=txt(value,size,ink,true);t.setGravity(Gravity.CENTER);parent.addView(t,params);}
    }
    private void showOnlineIconPicker(String initial,String[] chosen,TextView preview,boolean[] manuallyChosen){
        LinearLayout box=col();box.setPadding(dp(8),dp(8),dp(8),dp(4));
        EditText query=new EditText(this);query.setSingleLine(true);query.setHint("Search online icons (e.g. car, home, food)");query.setText(initial);box.addView(query,lp(-1,50));
        Button search=smallFilter("Search icons");LinearLayout.LayoutParams sp=lp(-1,42);sp.topMargin=dp(6);box.addView(search,sp);
        TextView status=txt("Search free icons. Internet required.",11,muted,false);status.setPadding(0,dp(8),0,dp(6));box.addView(status);
        WebView gallery=new WebView(this);gallery.setBackgroundColor(Color.TRANSPARENT);gallery.setVerticalScrollBarEnabled(true);gallery.setHorizontalScrollBarEnabled(false);
        WebSettings settings=gallery.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(false);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(gallery);box.addView(scroll,new LinearLayout.LayoutParams(-1,dp(330)));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Online icon library").setView(box).setNegativeButton("Close",null).create();
        gallery.addJavascriptInterface(new Object(){
            @JavascriptInterface public void select(String key){
                if(key==null||!key.matches("[a-zA-Z0-9_-]+:[a-zA-Z0-9_-]+"))return;
                runOnUiThread(()->{chosen[0]="@iconify:"+key;manuallyChosen[0]=true;preview.setText("🌐");dialog.dismiss();Toast.makeText(MainActivity.this,"Online icon selected",Toast.LENGTH_SHORT).show();});
            }
        },"AndroidPicker");
        Runnable runSearch=()->{
            String term=query.getText().toString().trim();if(term.isEmpty()){query.setError("Enter an icon name");return;}
            status.setText("Searching icons…");
            new Thread(()->{
                ArrayList<String> found=new ArrayList<>();String error=null;
                try{
                    String url="https://api.iconify.design/search?query="+Uri.encode(term)+"&limit=48";
                    java.net.HttpURLConnection c=(java.net.HttpURLConnection)new java.net.URL(url).openConnection();
                    c.setConnectTimeout(10000);c.setReadTimeout(10000);c.setRequestProperty("User-Agent","SaqibFinance/1.0");
                    StringBuilder b=new StringBuilder();try(BufferedReader reader=new BufferedReader(new InputStreamReader(c.getInputStream()))){String line;while((line=reader.readLine())!=null)b.append(line);}finally{c.disconnect();}
                    JSONArray arr=new JSONObject(b.toString()).optJSONArray("icons");if(arr!=null)for(int i=0;i<arr.length();i++){String k=arr.getString(i);if(k.matches("[a-zA-Z0-9_-]+:[a-zA-Z0-9_-]+"))found.add(k);}
                }catch(Exception e){error=e.getMessage();}
                final String err=error;
                runOnUiThread(()->{
                    if(err!=null){status.setText("Icons load nahi huay. Internet check karke dobara try karein.");gallery.loadData("<html><body style='font-family:sans-serif;text-align:center;color:#777'>Search failed. Please try again.</body></html>","text/html","UTF-8");return;}
                    status.setText(found.isEmpty()?"No icons found. Try another word.":"Icon par tap karke select karein · "+found.size()+" results");
                    StringBuilder html=new StringBuilder("<html><head><meta name='viewport' content='width=device-width, initial-scale=1'><style>body{margin:0;padding:6px;font-family:sans-serif;background:transparent}.grid{display:grid;grid-template-columns:repeat(4,1fr);gap:8px}.cell{min-height:82px;border-radius:10px;background:#EAF1E8;display:flex;flex-direction:column;align-items:center;justify-content:center;padding:4px;box-sizing:border-box;overflow:hidden}.cell img{width:36px;height:36px;object-fit:contain}.name{font-size:10px;color:#245B45;text-align:center;word-break:break-all;margin-top:4px}</style></head><body><div class='grid'>");
                    for(String key:found){String src="https://api.iconify.design/"+key.replace(":","/")+".svg?color=%23"+(dark?"123B2D":"245B45");html.append("<div class='cell' onclick=\"AndroidPicker.select('").append(key).append("')\"><img src='").append(src).append("'><div class='name'>").append(key.substring(key.indexOf(':')+1)).append("</div></div>");}
                    html.append("</div></body></html>");gallery.loadDataWithBaseURL("https://api.iconify.design/",html.toString(),"text/html","UTF-8",null);
                });
            }).start();
        };
        search.setOnClickListener(v->runSearch.run());
        dialog.setOnShowListener(v->{dialog.getWindow().setLayout(-1,dp(570));if(!initial.trim().isEmpty())runSearch.run();});
        dialog.show();
    }
    private void showItemIconPicker(String[] chosen,TextView preview,boolean[] manuallyChosen){
        final String[] choices={"📦","⛽","🔧","🛞","🏍️","🚗","🧽","🅿️","🛣️","👟","👕","👗","💍","💄","🧴","🧒","🧸","📚","🎒","🥛","🥚","🍞","🍚","🫗","🧼","🍬","💊","🩺","🧪","🍽️","🎁","🏠","💡","💧","📱","💻","🧾","🛍️","✈️","🐾","⚽","🎮","🪑","🛏️","🔑","💰","❤️","⭐"};
        GridView grid=new GridView(this);grid.setNumColumns(4);grid.setVerticalSpacing(dp(6));grid.setHorizontalSpacing(dp(6));grid.setPadding(dp(8),dp(8),dp(8),dp(8));
        ArrayAdapter<String> adapter=new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,choices){
            @Override public android.view.View getView(int position,android.view.View convert,android.view.ViewGroup parent){
                TextView t=(TextView)super.getView(position,convert,parent);t.setTextSize(27);t.setGravity(Gravity.CENTER);t.setPadding(0,dp(5),0,dp(5));return t;
            }
        };
        grid.setAdapter(adapter);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Choose an icon").setView(grid).setNegativeButton("Cancel",null).create();
        grid.setOnItemClickListener((p,v,pos,id)->{chosen[0]=choices[pos];manuallyChosen[0]=true;preview.setText(chosen[0]);dialog.dismiss();});
        dialog.show();
        dialog.getWindow().setLayout(-1,dp(430));
    }
    private String suggestItemIcon(String name){String n=name.toLowerCase(Locale.US);
        if(n.contains("fuel")||n.contains("petrol")||n.contains("diesel")||n.contains("gas"))return "⛽";
        if(n.contains("maintenance")||n.contains("repair")||n.contains("service")||n.contains("mechanic"))return "🔧";
        if(n.contains("tyre")||n.contains("tire")||n.contains("wheel"))return "🛞";
        if(n.contains("bike")||n.contains("motorcycle")||n.contains("scooter"))return "🏍️";
        if(n.contains("car"))return "🚗";
        if(n.contains("wash")||n.contains("clean"))return "🧽";
        if(n.contains("parking"))return "🅿️";
        if(n.contains("toll"))return "🛣️";
        if(n.contains("shoe"))return "👟";if(n.contains("cloth")||n.contains("dress"))return "👕";
        if(n.contains("milk"))return "🥛";if(n.contains("egg"))return "🥚";if(n.contains("bread"))return "🍞";
        if(n.contains("rice"))return "🍚";if(n.contains("oil"))return "🫗";if(n.contains("soap"))return "🧼";
        if(n.contains("book")||n.contains("school"))return "📚";if(n.contains("toy"))return "🧸";
        if(n.contains("makeup")||n.contains("care"))return "🧴";
        if(n.contains("tablet")||n.contains("syrup")||n.contains("doctor")||n.contains("test"))return "💊";
        if(n.contains("dinner")||n.contains("outing"))return "🍽️";if(n.contains("gift"))return "🎁";return "📦";}
    private void showItemOptionsDialog(long id,String name,String current){
        String[] options={"Move","Rename","Change Icon","Delete"};
        new AlertDialog.Builder(this).setTitle(name).setItems(options,(dialog,which)->{
            if(which==0)moveCategoryItem(id,name,current);
            else if(which==1)renameCategoryItem(id,name,current);
            else if(which==2)changeCategoryItemIcon(id,name,current);
            else deleteCategoryItemSafely(id,name,current);
        }).setNegativeButton("Cancel",null).show();
    }
    private void moveCategoryItem(long id,String name,String current){
        Spinner target=new Spinner(this);target.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,CATS));
        target.setSelection(Math.max(0,Arrays.asList(CATS).indexOf(current)));
        new AlertDialog.Builder(this).setTitle("Move "+name).setMessage("Choose the new category").setView(target).setNegativeButton("Cancel",null).setPositiveButton("Move",(d,w)->{
            String dest=target.getSelectedItem().toString();
            if(!dest.equals(current)){deleteCloudCategoryItem(current,name);ContentValues cv=new ContentValues();cv.put("category",dest);db.getWritableDatabase().update("category_items",cv,"id=?",new String[]{String.valueOf(id)});pushCategoryItem(id);}
            selectedAddCategory=dest;showPage(5);Toast.makeText(this,"Item moved to "+dest,Toast.LENGTH_SHORT).show();
        }).show();
    }
    private void renameCategoryItem(long id,String oldName,String cat){
        EditText input=new EditText(this);input.setSingleLine(true);input.setText(oldName);input.setSelection(input.getText().length());
        new AlertDialog.Builder(this).setTitle("Rename item").setView(input).setNegativeButton("Cancel",null).setPositiveButton("Save",(d,w)->{
            String next=input.getText().toString().trim();if(next.isEmpty()){Toast.makeText(this,"Enter an item name",Toast.LENGTH_SHORT).show();return;}
            if(next.equals(oldName))return;
            Cursor duplicate=db.getReadableDatabase().rawQuery("SELECT id FROM category_items WHERE category=? AND name=? AND id<>?",new String[]{cat,next,String.valueOf(id)});
            boolean exists=duplicate.moveToFirst();duplicate.close();if(exists){Toast.makeText(this,"This item already exists in this category",Toast.LENGTH_LONG).show();return;}
            SQLiteDatabase sql=db.getWritableDatabase();ContentValues item=new ContentValues();item.put("name",next);int changed=sql.update("category_items",item,"id=?",new String[]{String.valueOf(id)});
            if(changed>0){
                ContentValues draftRename=new ContentValues();draftRename.put("item",next);sql.update("expense_drafts",draftRename,"category=? AND item=?",new String[]{cat,oldName});
                deleteCloudCategoryItem(cat,oldName);pushCategoryItem(id);
                Cursor legacy=sql.rawQuery("SELECT id,description FROM expenses WHERE category=? AND (item=? OR (item IS NULL AND description=?))",new String[]{cat,oldName,oldName});
                ArrayList<Long> ids=new ArrayList<>();while(legacy.moveToNext())ids.add(legacy.getLong(0));legacy.close();
                for(long expenseId:ids){ContentValues cv=new ContentValues();cv.put("item",next);cv.put("description","");sql.update("expenses",cv,"id=?",new String[]{String.valueOf(expenseId)});pushExpense(expenseId);}
                selectedAddCategory=cat;showPage(5);Toast.makeText(this,"Item renamed",Toast.LENGTH_SHORT).show();
            }
        }).show();
    }
    private void changeCategoryItemIcon(long id,String name,String cat){
        String[] chosen={"📦"};TextView preview=txt("📦",34,ink,true);preview.setGravity(Gravity.CENTER);
        LinearLayout box=col();box.addView(preview,lp(-1,58));
        Button choose=smallFilter("Choose icon");box.addView(choose,lp(-1,42));
        Button online=smallFilter("Browse online icons");LinearLayout.LayoutParams op=lp(-1,40);op.topMargin=dp(6);box.addView(online,op);
        boolean[] manual={false};
        Cursor c=db.getReadableDatabase().rawQuery("SELECT icon FROM category_items WHERE id=?",new String[]{String.valueOf(id)});if(c.moveToFirst()){chosen[0]=c.getString(0);preview.setText(chosen[0]!=null&&chosen[0].startsWith("@iconify:")?"🌐":chosen[0]);}c.close();
        choose.setOnClickListener(v->showItemIconPicker(chosen,preview,manual));
        online.setOnClickListener(v->showOnlineIconPicker(name,chosen,preview,manual));
        new AlertDialog.Builder(this).setTitle("Change icon · "+name).setView(box).setNegativeButton("Cancel",null).setPositiveButton("Save",(d,w)->{
            ContentValues cv=new ContentValues();cv.put("icon",chosen[0]);db.getWritableDatabase().update("category_items",cv,"id=?",new String[]{String.valueOf(id)});pushCategoryItem(id);selectedAddCategory=cat;showPage(5);Toast.makeText(this,"Icon updated",Toast.LENGTH_SHORT).show();
        }).show();
    }
    private void deleteCategoryItemSafely(long id,String name,String cat){
        Cursor c=db.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM expenses WHERE category=? AND (item=? OR (item IS NULL AND description=?))",new String[]{cat,name,name});
        int count=0;if(c.moveToFirst())count=c.getInt(0);c.close();
        if(count>0){new AlertDialog.Builder(this).setTitle("Item cannot be deleted").setMessage("This item is linked to "+count+" expense record(s). Replace the item in Expense History first. Your records will not be deleted.").setPositiveButton("OK",null).show();return;}
        new AlertDialog.Builder(this).setMessage("Delete "+name+"?").setNegativeButton("Cancel",null).setPositiveButton("Delete",(d,w)->{
            deleteCloudCategoryItem(cat,name);db.getWritableDatabase().delete("category_items","id=?",new String[]{String.valueOf(id)});selectedAddCategory=cat;showPage(5);Toast.makeText(this,"Item deleted",Toast.LENGTH_SHORT).show();
        }).show();
    }
    private void showReplaceExpenseItemDialog(long expenseId,String cat){
        ArrayList<String> names=new ArrayList<>();names.add("No item");
        Cursor c=db.getReadableDatabase().rawQuery("SELECT name FROM category_items WHERE category=? ORDER BY name COLLATE NOCASE",new String[]{cat});while(c.moveToNext())names.add(c.getString(0));c.close();
        if(names.size()==1){Toast.makeText(this,"No items available in "+cat,Toast.LENGTH_SHORT).show();return;}
        Spinner picker=new Spinner(this);picker.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,names));
        new AlertDialog.Builder(this).setTitle("Replace item · "+cat).setMessage("Choose the item to link with this expense. Amount and date will stay unchanged.").setView(picker).setNegativeButton("Cancel",null).setPositiveButton("Replace",(d,w)->{
            String selected=picker.getSelectedItem().toString();SQLiteDatabase sql=db.getWritableDatabase();
            Cursor old=sql.rawQuery("SELECT item,description FROM expenses WHERE id=?",new String[]{String.valueOf(expenseId)});String oldItem=null,description="";if(old.moveToFirst()){oldItem=old.getString(0);description=old.getString(1);}old.close();
            ContentValues cv=new ContentValues();if("No item".equals(selected))cv.putNull("item");else cv.put("item",selected);
            boolean descriptionWasOldItem=oldItem!=null&&description.equals(oldItem);if(oldItem==null&&!description.isEmpty()){Cursor match=sql.rawQuery("SELECT name FROM category_items WHERE category=? AND name=?",new String[]{cat,description});descriptionWasOldItem=match.moveToFirst();match.close();}if(descriptionWasOldItem)cv.put("description","");
            sql.update("expenses",cv,"id=?",new String[]{String.valueOf(expenseId)});pushExpense(expenseId);refreshHistory();Toast.makeText(this,"Expense item replaced",Toast.LENGTH_SHORT).show();
        }).show();
    }
    private void refreshKnownItemIcons(){
        SQLiteDatabase sql=db.getWritableDatabase();
        sql.execSQL("UPDATE category_items SET icon='⛽' WHERE lower(name) LIKE '%fuel%' OR lower(name) LIKE '%petrol%' OR lower(name) LIKE '%diesel%' OR lower(name) LIKE '%gas%'");
        sql.execSQL("UPDATE category_items SET icon='🔧' WHERE lower(name) LIKE '%maintenance%' OR lower(name) LIKE '%repair%' OR lower(name) LIKE '%service%' OR lower(name) LIKE '%mechanic%'");
        sql.execSQL("UPDATE category_items SET icon='🛞' WHERE lower(name) LIKE '%tyre%' OR lower(name) LIKE '%tire%' OR lower(name) LIKE '%wheel%'");
        sql.execSQL("UPDATE category_items SET icon='🏍️' WHERE lower(name) LIKE '%bike%' OR lower(name) LIKE '%motorcycle%' OR lower(name) LIKE '%scooter%'");
        sql.execSQL("UPDATE category_items SET icon='🚗' WHERE lower(name) LIKE '%car%'");
    }
    private void seedDefaultItems(){SQLiteDatabase sql=db.getWritableDatabase();Cursor c=sql.rawQuery("SELECT COUNT(*) FROM category_items",null);c.moveToFirst();boolean empty=c.getInt(0)==0;c.close();if(!empty)return;String[][] data={{"Wife","Clothes","Shoes","Makeup","Jewellery","Personal Care"},{"Kids","Clothes","Shoes","Toys","School","Books","Milk","Baby Care"},{"Grocery","Milk","Eggs","Soap","Bread","Sugar","Rice","Oil"},{"Medicine","Tablets","Syrup","Doctor Fee","Tests"},{"Family","Family Outing","Dinner","Gifts","Other"}};sql.beginTransaction();try{for(String[] g:data)for(int i=1;i<g.length;i++){ContentValues v=new ContentValues();v.put("category",g[0]);v.put("name",g[i]);v.put("icon",suggestItemIcon(g[i]));sql.insertWithOnConflict("category_items",null,v,SQLiteDatabase.CONFLICT_IGNORE);}sql.setTransactionSuccessful();}finally{sql.endTransaction();}}
    private Button smallButton(String s){Button b=new Button(this);b.setText(s);b.setTextSize(22);b.setTextColor(ink);b.setAllCaps(false);b.setBackground(shape(surface,10));return b;}
    private Button primaryButton(String s){Button b=new Button(this);b.setText(s);b.setTextSize(13);b.setTextColor(Color.WHITE);b.setAllCaps(false);b.setBackground(shape(GREEN,12));return b;}
    private void addPage(){
        pageHeader("Drafts","Review items before saving your expenses"); LinearLayout c=content(); LinearLayout panel=card(c);
        Cursor q=db.getReadableDatabase().rawQuery("SELECT id,category,item,amount,date FROM expense_drafts ORDER BY id",null); int count=0; double total=0;
        if(!q.moveToFirst()) panel.addView(txt("No draft items yet.",14,muted,false)); else do {
            count++; long id=q.getLong(0); String cat=q.getString(1),item=q.getString(2),dateValue=q.getString(4); double val=q.getDouble(3); total+=val;
            LinearLayout r=row(); LinearLayout details=col(); details.addView(txt(item,14,ink,true)); details.addView(txt(cat+" · "+dateLabel(dateValue),11,muted,false)); r.addView(details,new LinearLayout.LayoutParams(0,-2,1)); r.addView(txt(money(val),13,GREEN,true));
            r.setOnClickListener(v->editDraftAmount(id,item,val)); r.setOnLongClickListener(v->{new AlertDialog.Builder(this).setMessage("Remove item from Drafts?").setNegativeButton("Cancel",null).setPositiveButton("Remove",(x,y)->{db.getWritableDatabase().delete("expense_drafts","id=?",new String[]{String.valueOf(id)});showPage(1);}).show();return true;}); panel.addView(r,lp(-1,58));
        } while(q.moveToNext()); q.close();
        TextView sum=txt("Draft Total: "+money(total),16,ink,true); sum.setPadding(0,dp(14),0,dp(10)); panel.addView(sum);
        Button more=primaryButton("Add More Items"); panel.addView(more,lp(-1,48)); more.setOnClickListener(v->showPage(0));
        Button done=primaryButton("Done - Save Expenses"); LinearLayout.LayoutParams dl=lp(-1,50);dl.topMargin=dp(9);panel.addView(done,dl);done.setEnabled(count>0);done.setAlpha(count>0?1f:0.45f);done.setOnClickListener(v->saveDraftExpenses());
    }
    private void addDraftItem(String cat,String item){
        EditText amount=new EditText(this);amount.setSingleLine(true);amount.setInputType(8194);amount.setHint("Amount (PKR)");
        new AlertDialog.Builder(this).setTitle("Add "+item).setMessage(cat).setView(amount).setNegativeButton("Cancel",null).setPositiveButton("Add to Draft",(d,w)->{try{double val=Double.parseDouble(amount.getText().toString().trim());if(val<=0||Double.isInfinite(val)||Double.isNaN(val))throw new Exception();ContentValues cv=new ContentValues();cv.put("category",cat);cv.put("item",item);cv.put("amount",val);cv.put("date",iso.format(new Date()));long id=db.getWritableDatabase().insert("expense_drafts",null,cv);if(id>0){Toast.makeText(this,"Added to Drafts",Toast.LENGTH_SHORT).show();showPage(5);}}catch(Exception ex){Toast.makeText(this,"Enter a valid amount",Toast.LENGTH_SHORT).show();}}).show();
    }
    private void editDraftAmount(long id,String item,double old){
        EditText amount=new EditText(this);amount.setSingleLine(true);amount.setInputType(8194);amount.setText(String.valueOf(old));
        new AlertDialog.Builder(this).setTitle("Edit "+item).setView(amount).setNegativeButton("Cancel",null).setPositiveButton("Save",(d,w)->{try{double val=Double.parseDouble(amount.getText().toString().trim());if(val<=0||Double.isInfinite(val)||Double.isNaN(val))throw new Exception();ContentValues cv=new ContentValues();cv.put("amount",val);db.getWritableDatabase().update("expense_drafts",cv,"id=?",new String[]{String.valueOf(id)});showPage(1);}catch(Exception ex){Toast.makeText(this,"Enter a valid amount",Toast.LENGTH_SHORT).show();}}).show();
    }
    private void saveDraftExpenses(){
        SQLiteDatabase sql=db.getWritableDatabase();Cursor q=sql.rawQuery("SELECT category,item,amount,date FROM expense_drafts ORDER BY id",null);ArrayList<ContentValues> rows=new ArrayList<>();while(q.moveToNext()){ContentValues cv=new ContentValues();cv.put("category",q.getString(0));cv.put("item",q.getString(1));cv.put("description","");cv.put("amount",q.getDouble(2));cv.put("date",q.getString(3));rows.add(cv);}q.close();if(rows.isEmpty())return;
        ArrayList<Long> ids=new ArrayList<>();sql.beginTransaction();try{for(ContentValues cv:rows){long id=sql.insert("expenses",null,cv);if(id<0)throw new Exception();ids.add(id);}sql.delete("expense_drafts",null,null);sql.setTransactionSuccessful();}catch(Exception ex){Toast.makeText(this,"Save failed; drafts are kept.",Toast.LENGTH_LONG).show();return;}finally{sql.endTransaction();}
        for(long id:ids)pushExpense(id);Toast.makeText(this,ids.size()+" expenses saved to History",Toast.LENGTH_LONG).show();showPage(0);
    }
    private void pickDate(TextView target){Calendar c=Calendar.getInstance();try{c.setTime(display.parse(target.getText().toString()));}catch(Exception e){}new DatePickerDialog(this,(v,y,m,d)->{Calendar x=Calendar.getInstance();x.set(y,m,d);target.setText(display.format(x.getTime()));},c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH)).show();}
    private void addMonthlyOverview(LinearLayout parent){LinearLayout overview=card(parent);LinearLayout head=row();head.addView(txt("▥  Monthly Overview",17,ink,true),new LinearLayout.LayoutParams(0,-2,1));Button prev=smallButton("‹"),next=smallButton("›");TextView monthName=txt(monthFmt.format(month.getTime()),12,ink,true);monthName.setGravity(Gravity.CENTER);head.addView(prev,lp(32,32));head.addView(monthName,lp(105,32));head.addView(next,lp(32,32));overview.addView(head);Chart chart=new Chart(this);overview.addView(chart,lp(-1,175));LinearLayout legend=col();overview.addView(legend);Runnable refresh=()->{String key=new SimpleDateFormat("yyyy-MM",Locale.US).format(month.getTime());Map<String,Double> data=new LinkedHashMap<>();double total=0;for(String cat:CATS)data.put(cat,0d);Cursor q=db.getReadableDatabase().rawQuery("SELECT category,SUM(amount) FROM expenses WHERE substr(date,1,7)=? GROUP BY category",new String[]{key});while(q.moveToNext()){String cat=q.getString(0);double amount=q.getDouble(1);data.put(cat,amount);total+=amount;}q.close();chart.setData(data,total);monthName.setText(monthFmt.format(month.getTime()));legend.removeAllViews();for(String cat:CATS){double amount=data.get(cat);if(amount<=0)continue;LinearLayout r=row();r.setPadding(0,dp(4),0,dp(4));r.addView(txt("•  "+cat,12,ink,true),new LinearLayout.LayoutParams(0,-2,1));r.addView(txt(money(amount),12,ink,true));legend.addView(r);}if(total<=0)legend.addView(txt("No expenses recorded for this month.",12,muted,false));};prev.setOnClickListener(v->{month.add(Calendar.MONTH,-1);refresh.run();});next.setOnClickListener(v->{month.add(Calendar.MONTH,1);refresh.run();});refresh.run();}
    private void historyPage(){pageHeader("Expense history","Find and review your purchases");LinearLayout c=content();addMonthlyOverview(c);LinearLayout filters=card(c);
        LinearLayout dates=row();Button f=smallFilter(from.isEmpty()?"From date":dateLabel(from)),t=smallFilter(to.isEmpty()?"To date":dateLabel(to));dates.addView(f,new LinearLayout.LayoutParams(0,dp(44),1));LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(0,dp(44),1);tp.leftMargin=dp(8);dates.addView(t,tp);filters.addView(dates);
        f.setOnClickListener(v->pickFilter(true));t.setOnClickListener(v->pickFilter(false));
        Spinner cats=new Spinner(this);String[] opts=new String[CATS.length+1];opts[0]="All categories";System.arraycopy(CATS,0,opts,1,CATS.length);cats.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,opts));cats.setSelection(Math.max(0,Arrays.asList(opts).indexOf(category)));LinearLayout.LayoutParams cp=lp(-1,48);cp.topMargin=dp(8);filters.addView(cats,cp);
        LinearLayout actions=row();Button clear=smallFilter("Clear filters");actions.addView(clear);filters.addView(actions);clear.setOnClickListener(v->{from="";to="";category="All categories";showPage(2);});cats.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> a){}public void onItemSelected(AdapterView<?> a,View v,int pos,long id){String selected=opts[pos];if(!selected.equals(category)){category=selected;refreshHistory();}}});
        historyList=card(c);refreshHistory();
    }
    private Button smallFilter(String s){Button b=smallButton(s);b.setTextSize(12);b.setTextColor(GREEN);return b;}
    private void pickFilter(boolean start){String old=start?from:to;Calendar c=Calendar.getInstance();try{if(!old.isEmpty())c.setTime(iso.parse(old));}catch(Exception e){}new DatePickerDialog(this,(v,y,m,d)->{Calendar x=Calendar.getInstance();x.set(y,m,d);String value=iso.format(x.getTime());if(start)from=value;else to=value;if(!from.isEmpty()&&!to.isEmpty()&&from.compareTo(to)>0){if(start)to=from;else from=to;}showPage(2);},c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH)).show();}
    private void refreshHistory(){if(historyList==null)return;historyList.removeAllViews();StringBuilder sql=new StringBuilder("SELECT id,category,description,amount,date,item FROM expenses WHERE 1=1");ArrayList<String>a=new ArrayList<>();if(!from.isEmpty()){sql.append(" AND date>=?");a.add(from);}if(!to.isEmpty()){sql.append(" AND date<=?");a.add(to);}if(!category.equals("All categories")){sql.append(" AND category=?");a.add(category);}sql.append(" ORDER BY date DESC,id DESC");Cursor q=db.getReadableDatabase().rawQuery(sql.toString(),a.toArray(new String[0]));if(!q.moveToFirst()){historyList.addView(txt("No matching expenses found.",14,muted,false));}else do{final long id=q.getLong(0);String cat=q.getString(1),desc=q.getString(2),date=q.getString(4),item=q.getString(5);double amount=q.getDouble(3);LinearLayout r=row();r.setPadding(0,dp(10),0,dp(10));LinearLayout d=col();d.addView(txt(cat+(item==null||item.isEmpty()?"":" · "+item),14,ink,true));String detail=(!desc.isEmpty()&&!desc.equals(item)?desc+" · ":"")+dateLabel(date);d.addView(txt(detail,11,muted,false));r.addView(d,new LinearLayout.LayoutParams(0,-2,1));r.addView(txt(money(amount),14,GREEN,true));r.setOnClickListener(v->editExpense(id,cat,desc,amount,date));historyList.addView(r);View sep=new View(this);sep.setBackgroundColor(line);historyList.addView(sep,lp(-1,1));}while(q.moveToNext());q.close();}
    private void loadExpenseItems(String cat,Spinner itemSpinner,String selectedItem){
        ArrayList<String> names=new ArrayList<>();names.add("No item");
        Cursor c=db.getReadableDatabase().rawQuery("SELECT name FROM category_items WHERE category=? ORDER BY name COLLATE NOCASE",new String[]{cat});
        while(c.moveToNext())names.add(c.getString(0));c.close();
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names);
        itemSpinner.setAdapter(adapter);
        int selected=names.indexOf(selectedItem==null||selectedItem.isEmpty()?"No item":selectedItem);
        itemSpinner.setSelection(Math.max(0,selected));
    }
    private void editExpense(long id,String cat,String desc,double value,String date){
        SQLiteDatabase sql=db.getReadableDatabase();
        Cursor current=sql.rawQuery("SELECT item FROM expenses WHERE id=?",new String[]{String.valueOf(id)});
        String currentItem=null;if(current.moveToFirst())currentItem=current.getString(0);current.close();
        if(currentItem==null&&!desc.isEmpty()){
            Cursor match=sql.rawQuery("SELECT name FROM category_items WHERE category=? AND name=?",new String[]{cat,desc});
            if(match.moveToFirst())currentItem=match.getString(0);match.close();
        }
        final String initialItem=currentItem;
        LinearLayout box=col();
        Spinner sp=new Spinner(this);sp.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,CATS));sp.setSelection(Math.max(0,Arrays.asList(CATS).indexOf(cat)));
        Spinner itemSpinner=new Spinner(this);loadExpenseItems(cat,itemSpinner,initialItem);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> a){}
            public void onItemSelected(AdapterView<?> a,View v,int pos,long itemId){
                String selectedCategory=CATS[pos];
                String preserve=selectedCategory.equals(cat)?initialItem:null;
                loadExpenseItems(selectedCategory,itemSpinner,preserve);
            }
        });
        EditText de=new EditText(this);de.setHint("Description (optional)");de.setText(desc);
        EditText am=new EditText(this);am.setInputType(8194);am.setText(String.valueOf(value));
        TextView dt=txt(dateLabel(date),14,ink,true);dt.setPadding(dp(10),dp(12),dp(10),dp(12));dt.setOnClickListener(v->pickDate(dt));
        box.addView(txt("Category",12,muted,true));box.addView(sp);
        box.addView(txt("Item",12,muted,true));box.addView(itemSpinner);
        box.addView(txt("Description (optional)",12,muted,true));box.addView(de);
        box.addView(txt("Amount (PKR)",12,muted,true));box.addView(am);
        box.addView(txt("Date",12,muted,true));box.addView(dt);
        new AlertDialog.Builder(this).setTitle("Edit expense").setView(box)
            .setNegativeButton("Cancel",null)
            .setNeutralButton("Delete",(x,y)->new AlertDialog.Builder(this).setMessage("Delete this expense?").setNegativeButton("Cancel",null).setPositiveButton("Delete",(z,w)->{db.getWritableDatabase().delete("expenses","id=?",new String[]{String.valueOf(id)});deleteCloudRecord("expenses",id);refreshHistory();}).show())
            .setPositiveButton("Save",(x,y)->{
                try{
                    Date parsed=display.parse(dt.getText().toString());double n=Double.parseDouble(am.getText().toString());if(n<=0)throw new Exception();
                    ContentValues cv=new ContentValues();String newCategory=sp.getSelectedItem().toString();
                    cv.put("category",newCategory);
                    String selectedItem=itemSpinner.getSelectedItem()==null?"No item":itemSpinner.getSelectedItem().toString();
                    if("No item".equals(selectedItem))cv.putNull("item");else cv.put("item",selectedItem);
                    cv.put("description",de.getText().toString().trim());cv.put("amount",n);cv.put("date",iso.format(parsed));
                    db.getWritableDatabase().update("expenses",cv,"id=?",new String[]{String.valueOf(id)});pushExpense(id);refreshHistory();
                }catch(Exception e){Toast.makeText(this,"Please enter a valid amount",Toast.LENGTH_SHORT).show();}
            }).show();
    }
    private void settingsPage(){
        pageHeader("Settings","Make Saqib Finance feel like yours");
        LinearLayout c=content();
        LinearLayout security=card(c);
        security.addView(txt("SECURITY",10,GOLD,true));
        security.addView(txt("App screen lock",17,ink,true));
        security.addView(txt("Require fingerprint or your phone PIN before showing expenses.",12,muted,false));
        Switch lockSwitch=new Switch(this);lockSwitch.setText("Screen lock");lockSwitch.setTextColor(ink);lockSwitch.setChecked(getPreferences(0).getBoolean("screen_lock",true));lockSwitch.setPadding(0,dp(12),0,0);security.addView(lockSwitch);
        Switch recentLockSwitch=new Switch(this);recentLockSwitch.setText("Lock when returning from Recent Apps");recentLockSwitch.setTextColor(ink);recentLockSwitch.setChecked(getPreferences(0).getBoolean("lock_on_recent_apps",false));recentLockSwitch.setPadding(0,dp(12),0,0);recentLockSwitch.setEnabled(getPreferences(0).getBoolean("screen_lock",true));security.addView(recentLockSwitch);
        security.addView(txt("Off: return to the app without unlocking again. On: ask for fingerprint or phone PIN whenever you leave and reopen the app.",12,muted,false));
        recentLockSwitch.setOnCheckedChangeListener((button,on)->getPreferences(0).edit().putBoolean("lock_on_recent_apps",on).apply());
        lockSwitch.setOnCheckedChangeListener((button,on)->{
            getPreferences(0).edit().putBoolean("screen_lock",on).apply();
            recentLockSwitch.setEnabled(on);
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
        TextView status=txt(email==null?"Not connected to Google":"Connected: "+email,12,muted,false);status.setPadding(0,dp(6),0,dp(5));cloud.addView(status);
        pendingCloudText=txt("Pending sync: "+pendingCloudCount()+" records",13,pendingCloudCount()>0?GOLD:GREEN,true);pendingCloudText.setPadding(0,dp(3),0,dp(12));cloud.addView(pendingCloudText);
        Button connect=primaryButton(email==null?"Connect Google account":"Sync now");cloud.addView(connect,lp(-1,48));
        connect.setOnClickListener(v->{if(FirebaseAuth.getInstance().getCurrentUser()==null)startGoogleSignIn();else syncAllCloud();});
        if(email!=null){Button disconnect=smallFilter("Disconnect Google account");cloud.addView(disconnect,lp(-1,44));disconnect.setOnClickListener(v->{new AlertDialog.Builder(this).setMessage("Disconnect this phone? Your cloud backup remains in your Google account.").setNegativeButton("Cancel",null).setPositiveButton("Disconnect",(d,w)->{FirebaseAuth.getInstance().signOut();googleSignInClient.signOut();showPage(3);}).show();});}
        if(isAdmin()){Button admin=smallFilter("Admin · User overview");cloud.addView(admin,lp(-1,44));admin.setOnClickListener(v->adminUsersPage());}
        TextView netTitle=txt("BACKUP NETWORK",10,GOLD,true);netTitle.setPadding(0,dp(18),0,dp(5));cloud.addView(netTitle);
        cloud.addView(txt("Changes sync automatically when internet is available. Offline changes wait until connection returns.",12,muted,false));
        Spinner network=new Spinner(this);String[] networkOptions={"Wi-Fi + Mobile Data","Wi-Fi only","Off"};network.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,networkOptions));
        String saved=getPreferences(0).getString(PREF_CLOUD_NETWORK,"Wi-Fi + Mobile Data");network.setSelection(Math.max(0,Arrays.asList(networkOptions).indexOf(saved)));cloud.addView(network,lp(-1,48));
        network.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){boolean first=true;public void onNothingSelected(AdapterView<?> x){}public void onItemSelected(AdapterView<?> x,View v,int p,long id){if(first){first=false;return;}getPreferences(0).edit().putString(PREF_CLOUD_NETWORK,networkOptions[p]).apply();applyCloudNetworkPreference();}});
        LinearLayout retention=card(c);
        retention.addView(txt("DATA & RECORD HISTORY",10,GOLD,true));
        retention.addView(txt("Choose how long to keep your records",17,ink,true));
        retention.addView(txt("Select when old money-in records, expenses and completed reminders should be removed automatically. Choose Off to keep all financial records. Upcoming reminders and saved item categories are never removed by this setting.",12,muted,false));
        retention.addView(txt("AUTO-DELETE AFTER",11,muted,true));
        Spinner retentionSpinner=new Spinner(this);
        String[] retentionOptions={"Off — Keep all records","1 month","3 months","6 months","12 months"};
        int[] retentionMonths={0,1,3,6,12};
        ArrayAdapter<String> retentionAdapter=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,retentionOptions);
        retentionSpinner.setAdapter(retentionAdapter);
        int savedMonths=getRetentionMonths(),selectedIndex=0;
        for(int i=0;i<retentionMonths.length;i++)if(retentionMonths[i]==savedMonths)selectedIndex=i;
        retentionSpinner.setSelection(selectedIndex);
        LinearLayout.LayoutParams retentionSpinnerLp=lp(-1,50);retentionSpinnerLp.topMargin=dp(6);retentionSpinnerLp.bottomMargin=dp(8);retention.addView(retentionSpinner,retentionSpinnerLp);
        retentionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            boolean first=true;
            public void onNothingSelected(AdapterView<?> parent){}
            public void onItemSelected(AdapterView<?> parent,View view,int position,long id){
                if(first){first=false;return;}
                int months=retentionMonths[position],oldMonths=getRetentionMonths();
                if(months==oldMonths)return;
                if(months==0){getPreferences(0).edit().putInt("auto_delete_months",0).apply();Toast.makeText(MainActivity.this,"Automatic deletion is off. All records will be kept.",Toast.LENGTH_LONG).show();return;}
                new AlertDialog.Builder(MainActivity.this).setTitle("Set automatic deletion?")
                    .setMessage("Records older than "+months+" month"+(months==1?"":"s")+" will be permanently deleted from this phone and your connected cloud backup. This cannot be undone.")
                    .setNegativeButton("Cancel",(d,w)->retentionSpinner.setSelection(indexForRetention(oldMonths)))
                    .setPositiveButton("Confirm",(d,w)->{getPreferences(0).edit().putInt("auto_delete_months",months).apply();cleanupOldRecords(true);})
                    .setOnCancelListener(d->retentionSpinner.setSelection(indexForRetention(oldMonths))).show();
            }
        });
        Button deleteOld=smallFilter("Delete records older than 3 months now");retention.addView(deleteOld,lp(-1,44));
        deleteOld.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Delete old records?")
            .setMessage("This will permanently delete expense records and completed reminders older than 3 months from this phone and your connected cloud backup.")
            .setNegativeButton("Cancel",null).setPositiveButton("Delete old records",(d,w)->cleanupOldRecords(true,3)).show());
        Button deleteAll=smallFilter("Delete all expense history");retention.addView(deleteAll,lp(-1,44));
        deleteAll.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Delete all expense history?")
            .setMessage("Every expense record on this phone and in your connected cloud backup will be permanently deleted. Reminders and saved item categories will not be deleted.")
            .setNegativeButton("Cancel",null).setPositiveButton("Delete all",(d,w)->deleteAllExpenseHistory()).show());

        LinearLayout about=card(c);about.addView(txt("ABOUT",10,GOLD,true));about.addView(txt("Saqib Finance",18,ink,true));about.addView(txt("Family expense tracker · Offline storage · PKR",13,muted,false));about.addView(txt("Your records stay on this phone and sync to your Google account when connected. Sign in on a new phone to restore them.",12,muted,false));
    }
    private void setupGoogleSignIn(){GoogleSignInOptions o=new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestIdToken(getString(R.string.default_web_client_id)).requestEmail().build();googleSignInClient=GoogleSignIn.getClient(this,o);}
    private void startGoogleSignIn(){startActivityForResult(googleSignInClient.getSignInIntent(),REQUEST_GOOGLE_SIGN_IN);}
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){super.onActivityResult(requestCode,resultCode,data);if(requestCode==REQUEST_GOOGLE_SIGN_IN){try{GoogleSignInAccount a=GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException.class);AuthCredential c=GoogleAuthProvider.getCredential(a.getIdToken(),null);FirebaseAuth.getInstance().signInWithCredential(c).addOnSuccessListener(r->{Toast.makeText(this,"Google account connected",Toast.LENGTH_SHORT).show();syncAllCloud();showPage(3);}).addOnFailureListener(e->Toast.makeText(this,"Sign-in failed: "+e.getMessage(),Toast.LENGTH_LONG).show());}catch(ApiException e){Toast.makeText(this,"Google sign-in failed ("+e.getStatusCode()+")",Toast.LENGTH_LONG).show();}}}
    private void registerCloudNetworkCallback(){try{ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);cloudNetworkCallback=new ConnectivityManager.NetworkCallback(){@Override public void onAvailable(Network n){runOnUiThread(()->{applyCloudNetworkPreference();if(cloudNetworkAllowed())syncAllCloud();});}@Override public void onLost(Network n){runOnUiThread(()->applyCloudNetworkPreference());}};cm.registerDefaultNetworkCallback(cloudNetworkCallback);}catch(Exception ignored){}}
    private boolean cloudNetworkAllowed(){try{String mode=getPreferences(0).getString(PREF_CLOUD_NETWORK,"Wi-Fi + Mobile Data");if("Off".equals(mode))return false;ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);Network n=cm.getActiveNetwork();NetworkCapabilities c=n==null?null:cm.getNetworkCapabilities(n);if(c==null)return false;if("Wi-Fi only".equals(mode))return c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);}catch(Exception e){return false;}}
    private void applyCloudNetworkPreference(){try{String mode=getPreferences(0).getString(PREF_CLOUD_NETWORK,"Wi-Fi + Mobile Data");FirebaseFirestore fs=FirebaseFirestore.getInstance();if("Off".equals(mode)){fs.disableNetwork();return;}if("Wi-Fi only".equals(mode)){ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);Network n=cm.getActiveNetwork();NetworkCapabilities c=n==null?null:cm.getNetworkCapabilities(n);if(c==null||!c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)){fs.disableNetwork();return;}}fs.enableNetwork();}catch(Exception ignored){}}
    @Override protected void onDestroy(){super.onDestroy();try{if(cloudNetworkCallback!=null)((ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE)).unregisterNetworkCallback(cloudNetworkCallback);}catch(Exception ignored){}}
    private String cloudUid(){return FirebaseAuth.getInstance().getCurrentUser().getUid();}
    private CollectionReference cloudCollection(String name){return FirebaseFirestore.getInstance().collection("users").document(cloudUid()).collection(name);}
    private void syncAllCloud(){
         if(FirebaseAuth.getInstance().getCurrentUser()==null||cloudSyncInProgress)return;
         applyCloudNetworkPreference();
         if(!cloudNetworkAllowed())return;
         cloudSyncInProgress=true;
         FirebaseFirestore.getInstance().enableNetwork()
             .addOnSuccessListener(v->processPendingCloudDeletes(new ArrayList<>(pendingCloudSet()),0))
             .addOnFailureListener(e->{cloudSyncInProgress=false;refreshPendingCloudText();Toast.makeText(this,"Cloud sync could not start: "+e.getMessage(),Toast.LENGTH_LONG).show();});
     }
     private void processPendingCloudDeletes(ArrayList<String> pending,int index){
         while(index<pending.size()&&!pending.get(index).startsWith("delete-expenses:")&&!pending.get(index).startsWith("delete-incomes:")&&!pending.get(index).startsWith("delete-reminders:")&&!pending.get(index).startsWith("delete-categoryItems:"))index++;
         if(index>=pending.size()){restoreCloudRecords();return;}
         String entry=pending.get(index);int split=entry.indexOf(':');String operation=entry.substring(0,split),id=entry.substring(split+1);
         String collection=operation.substring("delete-".length());
         Map<String,Object> tombstone=new HashMap<>();
         if("categoryItems".equals(collection)){String decoded=Uri.decode(id);int separator=decoded.indexOf('/');if(separator>=0){tombstone.put("category",decoded.substring(0,separator));tombstone.put("name",decoded.substring(separator+1));}tombstone.put("itemKey",id);}
         else tombstone.put("id",Long.parseLong(id));
         tombstone.put("deleted",true);tombstone.put("deletedAt",FieldValue.serverTimestamp());
         final int next=index+1;
         cloudCollection(collection).document(id).set(tombstone)
             .addOnSuccessListener(v->{clearCloudPending(operation,id);processPendingCloudDeletes(pending,next);})
             .addOnFailureListener(e->{cloudSyncInProgress=false;refreshPendingCloudText();Toast.makeText(this,"Pending delete is waiting for cloud: "+e.getMessage(),Toast.LENGTH_LONG).show();});
     }
     private void restoreCloudRecords(){
         cloudCollection("expenses").get().addOnSuccessListener(es->{
             SQLiteDatabase sql=db.getWritableDatabase();sql.beginTransaction();
             try{
                 for(DocumentSnapshot d:es.getDocuments()){
                     long id=d.getLong("id")==null?Long.parseLong(d.getId()):d.getLong("id");
                     if(Boolean.TRUE.equals(d.getBoolean("deleted"))){sql.delete("expenses","id=?",new String[]{String.valueOf(id)});continue;}
                     Cursor c=sql.rawQuery("SELECT id FROM expenses WHERE id=?",new String[]{String.valueOf(id)});boolean exists=c.moveToFirst();c.close();
                     if(!exists){ContentValues v=new ContentValues();v.put("id",id);v.put("category",d.getString("category"));v.put("description",d.getString("description")==null?"":d.getString("description"));if(d.getString("item")==null)v.putNull("item");else v.put("item",d.getString("item"));v.put("amount",d.getDouble("amount")==null?0:d.getDouble("amount"));v.put("date",d.getString("date"));sql.insert("expenses",null,v);}
                 }
                 sql.setTransactionSuccessful();
             }finally{sql.endTransaction();}
             cloudCollection("reminders").get().addOnSuccessListener(rs->{
                 SQLiteDatabase rdb=db.getWritableDatabase();rdb.beginTransaction();
                 try{
                     for(DocumentSnapshot d:rs.getDocuments()){
                         long id=d.getLong("id")==null?Long.parseLong(d.getId()):d.getLong("id");
                         if(Boolean.TRUE.equals(d.getBoolean("deleted"))){ReminderReceiver.cancel(this,id);rdb.delete("reminders","id=?",new String[]{String.valueOf(id)});continue;}
                         Cursor c=rdb.rawQuery("SELECT id FROM reminders WHERE id=?",new String[]{String.valueOf(id)});boolean exists=c.moveToFirst();c.close();
                         if(!exists){ContentValues v=new ContentValues();v.put("id",id);v.put("item",d.getString("item"));v.put("trigger_at",d.getLong("trigger_at")==null?0:d.getLong("trigger_at"));v.put("done",d.getLong("done")==null?0:d.getLong("done"));v.put("estimated_amount",d.getDouble("estimated_amount")==null?0:d.getDouble("estimated_amount"));if(d.get("expense_id")==null)v.putNull("expense_id");else v.put("expense_id",((Number)d.get("expense_id")).longValue());rdb.insert("reminders",null,v);}
                     }
                     rdb.setTransactionSuccessful();
                 }finally{rdb.endTransaction();}
                 if(getRetentionMonths()>0)cleanupOldRecords(false);
                 restoreCloudCategoryItems();
             }).addOnFailureListener(e->{cloudSyncInProgress=false;Toast.makeText(this,"Cloud reminder restore failed: "+e.getMessage(),Toast.LENGTH_LONG).show();});
         }).addOnFailureListener(e->{cloudSyncInProgress=false;Toast.makeText(this,"Cloud expense restore failed: "+e.getMessage(),Toast.LENGTH_LONG).show();});
     }
     private void restoreCloudCategoryItems(){
         cloudCollection("incomes").get().addOnSuccessListener(incomeDocs->{
             SQLiteDatabase incomeDb=db.getWritableDatabase();incomeDb.beginTransaction();
             try{for(DocumentSnapshot d:incomeDocs.getDocuments()){
                 long id=d.getLong("id")==null?Long.parseLong(d.getId()):d.getLong("id");
                 if(Boolean.TRUE.equals(d.getBoolean("deleted"))){incomeDb.delete("incomes","id=?",new String[]{String.valueOf(id)});continue;}
                 Cursor exists=incomeDb.rawQuery("SELECT id FROM incomes WHERE id=?",new String[]{String.valueOf(id)});boolean found=exists.moveToFirst();exists.close();
                 if(!found){ContentValues v=new ContentValues();v.put("id",id);v.put("amount",d.getDouble("amount")==null?0:d.getDouble("amount"));v.put("source",d.getString("source")==null?"":d.getString("source"));v.put("date",d.getString("date")==null?iso.format(new Date()):d.getString("date"));v.put("note",d.getString("note")==null?"":d.getString("note"));incomeDb.insert("incomes",null,v);}
             }incomeDb.setTransactionSuccessful();}finally{incomeDb.endTransaction();}
             cloudCollection("categoryItems").get().addOnSuccessListener(items->{
                 SQLiteDatabase sql=db.getWritableDatabase();sql.beginTransaction();
                 try{
                     for(DocumentSnapshot d:items.getDocuments()){
                         String cat=d.getString("category"),name=d.getString("name");
                         if(cat==null||name==null)continue;
                         if(Boolean.TRUE.equals(d.getBoolean("deleted"))){sql.delete("category_items","category=? AND name=?",new String[]{cat,name});continue;}
                         String icon=d.getString("icon");if(icon==null||icon.isEmpty())icon="📦";
                         ContentValues values=new ContentValues();values.put("icon",icon);
                         int changed=sql.update("category_items",values,"category=? AND name=?",new String[]{cat,name});
                         if(changed==0){values.put("category",cat);values.put("name",name);sql.insertWithOnConflict("category_items",null,values,SQLiteDatabase.CONFLICT_IGNORE);}
                     }
                     sql.setTransactionSuccessful();
                 }finally{sql.endTransaction();}
                 uploadAllLocal();updateAdminStats();reschedulePendingReminders();cloudSyncInProgress=false;
                 if(uiReady)showPage(page);
             }).addOnFailureListener(e->{cloudSyncInProgress=false;Toast.makeText(this,"Cloud item restore failed: "+e.getMessage(),Toast.LENGTH_LONG).show();});
         }).addOnFailureListener(e->{cloudSyncInProgress=false;Toast.makeText(this,"Cloud income restore failed: "+e.getMessage(),Toast.LENGTH_LONG).show();});
     }
     private int getRetentionMonths(){
        android.content.SharedPreferences p=getPreferences(0);
        return p.getInt("auto_delete_months",p.getBoolean("auto_delete_3_months",false)?3:0);
    }
    private int indexForRetention(int months){
        int[] values={0,1,3,6,12};for(int i=0;i<values.length;i++)if(values[i]==months)return i;return 0;
    }
    private String retentionCutoff(int months){
        Calendar cutoff=Calendar.getInstance();cutoff.add(Calendar.MONTH,-months);return iso.format(cutoff.getTime());
    }
    private void cleanupOldRecords(boolean notify){cleanupOldRecords(notify,Math.max(1,getRetentionMonths()));}
    private void cleanupOldRecords(boolean notify,int months){
        String cutoff=retentionCutoff(months);
        ArrayList<Long> incomeIds=new ArrayList<>();Cursor incomeCursor=db.getReadableDatabase().rawQuery("SELECT id FROM incomes WHERE date<?",new String[]{cutoff});while(incomeCursor.moveToNext())incomeIds.add(incomeCursor.getLong(0));incomeCursor.close();
        ArrayList<Long> expenseIds=new ArrayList<>();
        Cursor e=db.getReadableDatabase().rawQuery("SELECT id FROM expenses WHERE date<?",new String[]{cutoff});
        while(e.moveToNext())expenseIds.add(e.getLong(0));e.close();
        long cutoffMillis;
        try{cutoffMillis=iso.parse(cutoff).getTime();}catch(Exception ex){cutoffMillis=System.currentTimeMillis()-months*30L*24*60*60*1000;}
        ArrayList<Long> reminderIds=new ArrayList<>();
        Cursor r=db.getReadableDatabase().rawQuery("SELECT id FROM reminders WHERE done=1 AND trigger_at<?",new String[]{String.valueOf(cutoffMillis)});
        while(r.moveToNext())reminderIds.add(r.getLong(0));r.close();
        SQLiteDatabase sql=db.getWritableDatabase();sql.beginTransaction();
        try{
            for(long id:incomeIds)sql.delete("incomes","id=?",new String[]{String.valueOf(id)});
            for(long id:expenseIds)sql.delete("expenses","id=?",new String[]{String.valueOf(id)});
            for(long id:reminderIds)sql.delete("reminders","id=?",new String[]{String.valueOf(id)});
            sql.setTransactionSuccessful();
        }finally{sql.endTransaction();}
        for(long id:incomeIds)deleteCloudRecord("incomes",id);
        for(long id:expenseIds)deleteCloudRecord("expenses",id);
        for(long id:reminderIds)deleteCloudRecord("reminders",id);
        if(notify)Toast.makeText(this,"Deleted "+incomeIds.size()+" old money-in records, "+expenseIds.size()+" old expenses and "+reminderIds.size()+" completed reminders",Toast.LENGTH_LONG).show();
        updateAdminStats();
        if(uiReady&&page==2)showPage(2);
    }
    private void deleteAllExpenseHistory(){
        ArrayList<Long> ids=new ArrayList<>();Cursor c=db.getReadableDatabase().rawQuery("SELECT id FROM expenses",null);
        while(c.moveToNext())ids.add(c.getLong(0));c.close();
        db.getWritableDatabase().delete("expenses",null,null);
        for(long id:ids)deleteCloudRecord("expenses",id);
        updateAdminStats();
        Toast.makeText(this,"All expense history deleted ("+ids.size()+" records)",Toast.LENGTH_LONG).show();
        if(uiReady)showPage(2);
    }
    private void reschedulePendingReminders(){Cursor c=db.getReadableDatabase().rawQuery("SELECT id,item,trigger_at FROM reminders WHERE done=0 AND trigger_at>?",new String[]{String.valueOf(System.currentTimeMillis())});while(c.moveToNext())ReminderReceiver.schedule(this,c.getLong(0),c.getString(1),c.getLong(2));c.close();}
    private void uploadAllLocal(){
        Cursor inc=db.getReadableDatabase().rawQuery("SELECT id,amount,source,date,note FROM incomes",null);
        ArrayList<Map<String,Object>> incomeData=new ArrayList<>();
        while(inc.moveToNext()){Map<String,Object> m=new HashMap<>();m.put("id",inc.getLong(0));m.put("amount",inc.getDouble(1));m.put("source",inc.getString(2));m.put("date",inc.getString(3));m.put("note",inc.getString(4));incomeData.add(m);}inc.close();
        Cursor e=db.getReadableDatabase().rawQuery("SELECT id,category,description,amount,date,item FROM expenses",null);
        ArrayList<Map<String,Object>> expenseData=new ArrayList<>();
        while(e.moveToNext()){
            Map<String,Object> m=new HashMap<>();
            m.put("id",e.getLong(0));m.put("category",e.getString(1));m.put("description",e.getString(2));m.put("amount",e.getDouble(3));m.put("date",e.getString(4));m.put("item",e.getString(5));
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
        Cursor ci=db.getReadableDatabase().rawQuery("SELECT name,category,icon FROM category_items",null);
        ArrayList<Map<String,Object>> itemData=new ArrayList<>();
        while(ci.moveToNext()){Map<String,Object> m=new HashMap<>();m.put("name",ci.getString(0));m.put("category",ci.getString(1));m.put("icon",ci.getString(2));itemData.add(m);}
        ci.close();
        final int total=incomeData.size()+expenseData.size()+reminderData.size()+itemData.size();
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
        for(Map<String,Object> m:incomeData){
            String id=String.valueOf(m.get("id"));markCloudPending("incomes",id);
            cloudCollection("incomes").document(id).set(m)
                .addOnSuccessListener(v->{clearCloudPending("incomes",id);finishOne.run();})
                .addOnFailureListener(err->{failed[0]=true;if(firstError[0]==null)firstError[0]=err.getMessage();finishOne.run();});
        }
        for(Map<String,Object> m:expenseData){
            String id=String.valueOf(m.get("id"));
            markCloudPending("expenses",id);
            cloudCollection("expenses").document(id).set(m)
                .addOnSuccessListener(v->{clearCloudPending("expenses",id);finishOne.run();})
                .addOnFailureListener(err->{failed[0]=true;if(firstError[0]==null)firstError[0]=err.getMessage();finishOne.run();});
        }
        for(Map<String,Object> m:reminderData){
            String id=String.valueOf(m.get("id"));
            markCloudPending("reminders",id);
            cloudCollection("reminders").document(id).set(m)
                .addOnSuccessListener(v->{clearCloudPending("reminders",id);finishOne.run();})
                .addOnFailureListener(err->{failed[0]=true;if(firstError[0]==null)firstError[0]=err.getMessage();finishOne.run();});
        }
        for(Map<String,Object> m:itemData){
            String key=categoryItemKey((String)m.get("category"),(String)m.get("name"));
            markCloudPending("categoryItems",key);
            cloudCollection("categoryItems").document(key).set(m)
                .addOnSuccessListener(v->{clearCloudPending("categoryItems",key);finishOne.run();})
                .addOnFailureListener(err->{failed[0]=true;if(firstError[0]==null)firstError[0]=err.getMessage();finishOne.run();});
        }
    }
    private String categoryItemKey(String category,String name){return Uri.encode(category+"/"+name);}
    private void pushCategoryItem(long id){
        if(FirebaseAuth.getInstance().getCurrentUser()==null)return;
        Cursor c=db.getReadableDatabase().rawQuery("SELECT name,category,icon FROM category_items WHERE id=?",new String[]{String.valueOf(id)});
        if(!c.moveToFirst()){c.close();return;}
        String name=c.getString(0),cat=c.getString(1),icon=c.getString(2);c.close();
        String key=categoryItemKey(cat,name);Map<String,Object> data=new HashMap<>();data.put("name",name);data.put("category",cat);data.put("icon",icon==null?"📦":icon);
        markCloudPending("categoryItems",key);
        cloudCollection("categoryItems").document(key).set(data).addOnSuccessListener(v->{clearCloudPending("categoryItems",key);updateAdminStats();}).addOnFailureListener(e->refreshPendingCloudText());
    }
    private void deleteCloudCategoryItem(String category,String name){
        String key=categoryItemKey(category,name),operation="delete-categoryItems";markCloudPending(operation,key);
        if(FirebaseAuth.getInstance().getCurrentUser()!=null){
            Map<String,Object> tombstone=new HashMap<>();tombstone.put("category",category);tombstone.put("name",name);tombstone.put("itemKey",key);tombstone.put("deleted",true);tombstone.put("deletedAt",FieldValue.serverTimestamp());
            cloudCollection("categoryItems").document(key).set(tombstone).addOnSuccessListener(v->clearCloudPending(operation,key)).addOnFailureListener(e->refreshPendingCloudText());
        }
    }
    private void pushIncome(long id){if(FirebaseAuth.getInstance().getCurrentUser()==null)return;Cursor c=db.getReadableDatabase().rawQuery("SELECT amount,source,date,note FROM incomes WHERE id=?",new String[]{String.valueOf(id)});if(!c.moveToFirst()){c.close();return;}Map<String,Object> m=new HashMap<>();m.put("id",id);m.put("amount",c.getDouble(0));m.put("source",c.getString(1));m.put("date",c.getString(2));m.put("note",c.getString(3));c.close();String key=String.valueOf(id);markCloudPending("incomes",key);cloudCollection("incomes").document(key).set(m).addOnSuccessListener(v->{clearCloudPending("incomes",key);updateAdminStats();}).addOnFailureListener(e->refreshPendingCloudText());}
    private void pushExpense(long id){if(FirebaseAuth.getInstance().getCurrentUser()==null)return;Cursor c=db.getReadableDatabase().rawQuery("SELECT category,description,amount,date,item FROM expenses WHERE id=?",new String[]{String.valueOf(id)});if(!c.moveToFirst()){c.close();return;}Map<String,Object> m=new HashMap<>();m.put("id",id);m.put("category",c.getString(0));m.put("description",c.getString(1));m.put("amount",c.getDouble(2));m.put("date",c.getString(3));m.put("item",c.getString(4));c.close();String key=String.valueOf(id);markCloudPending("expenses",key);cloudCollection("expenses").document(key).set(m).addOnSuccessListener(v->{clearCloudPending("expenses",key);updateAdminStats();}).addOnFailureListener(e->refreshPendingCloudText());}
    private void pushReminder(long id){if(FirebaseAuth.getInstance().getCurrentUser()==null)return;Cursor c=db.getReadableDatabase().rawQuery("SELECT item,trigger_at,done,estimated_amount,expense_id FROM reminders WHERE id=?",new String[]{String.valueOf(id)});if(!c.moveToFirst()){c.close();return;}Map<String,Object> m=new HashMap<>();m.put("id",id);m.put("item",c.getString(0));m.put("trigger_at",c.getLong(1));m.put("done",c.getLong(2));m.put("estimated_amount",c.getDouble(3));m.put("expense_id",c.isNull(4)?null:c.getLong(4));c.close();String key=String.valueOf(id);markCloudPending("reminders",key);cloudCollection("reminders").document(key).set(m).addOnSuccessListener(v->{clearCloudPending("reminders",key);updateAdminStats();}).addOnFailureListener(e->refreshPendingCloudText());}
    private void deleteCloudRecord(String collection,long id){String key=String.valueOf(id);String operation="delete-"+collection;markCloudPending(operation,key);if(FirebaseAuth.getInstance().getCurrentUser()!=null){Map<String,Object> tombstone=new HashMap<>();tombstone.put("id",id);tombstone.put("deleted",true);tombstone.put("deletedAt",FieldValue.serverTimestamp());cloudCollection(collection).document(key).set(tombstone).addOnSuccessListener(v->clearCloudPending(operation,key)).addOnFailureListener(e->refreshPendingCloudText());}}
    private long estimateLocalDataBytes(){
        long n=0;Cursor e=db.getReadableDatabase().rawQuery("SELECT category,description,amount,date FROM expenses",null);
        while(e.moveToNext()){for(int i=0;i<4;i++)if(!e.isNull(i))n+=String.valueOf(e.getString(i)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length+8;}e.close();
        Cursor income=db.getReadableDatabase().rawQuery("SELECT source,amount,date,note FROM incomes",null);while(income.moveToNext()){for(int i=0;i<4;i++)if(!income.isNull(i))n+=String.valueOf(income.getString(i)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length+8;}income.close();
        Cursor r=db.getReadableDatabase().rawQuery("SELECT item,trigger_at,done,estimated_amount,expense_id FROM reminders",null);
        while(r.moveToNext()){for(int i=0;i<5;i++)if(!r.isNull(i))n+=String.valueOf(r.getString(i)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length+8;}r.close();return n;
    }
    private void updateAdminStats(){
        FirebaseUser u=FirebaseAuth.getInstance().getCurrentUser();if(u==null)return;
        Map<String,Object> s=new HashMap<>();s.put("displayName",u.getDisplayName()==null?"":u.getDisplayName());s.put("email",u.getEmail()==null?"":u.getEmail());s.put("storageBytes",estimateLocalDataBytes());s.put("lastSyncAt",FieldValue.serverTimestamp());
        FirebaseFirestore.getInstance().collection("users").document(u.getUid()).collection("adminStats").document("summary").set(s);
    }
    private static final String ADMIN_EMAIL="saqibbukhari2917@gmail.com";
    private boolean isAdmin(){FirebaseUser u=FirebaseAuth.getInstance().getCurrentUser();return u!=null&&ADMIN_EMAIL.equalsIgnoreCase(u.getEmail()==null?"":u.getEmail());}
    private void adminUsersPage(){
        if(!isAdmin())return;
        pageHeader("Admin · User overview","Name, Gmail, storage and last sync only");
        LinearLayout c=content();LinearLayout card=card(c);
        card.addView(txt("Privacy: expense and reminder contents are not shown.",12,muted,false));
        TextView status=txt("Loading user summaries…",13,muted,false);card.addView(status);
        FirebaseFirestore.getInstance().collectionGroup("adminStats").get().addOnSuccessListener(result->{
            status.setText(result.isEmpty()?"No user summaries yet":"Users: "+result.size());
            for(DocumentSnapshot d:result.getDocuments()){
                String name=d.getString("displayName"),email=d.getString("email");Long bytes=d.getLong("storageBytes");
                LinearLayout row=col();row.setPadding(0,dp(12),0,dp(12));
                row.addView(txt(name==null||name.isEmpty()?"Name unavailable":name,15,ink,true));
                row.addView(txt(email==null?"":email,12,muted,false));
                row.addView(txt(String.format(Locale.US,"Estimated data: %.2f MB",(bytes==null?0:bytes)/1048576.0),12,GREEN,true));
                Object ts=d.get("lastSyncAt");String last="Not synced";if(ts instanceof com.google.firebase.Timestamp)last=display.format(((com.google.firebase.Timestamp)ts).toDate());
                row.addView(txt("Last sync: "+last,11,muted,false));card.addView(row);
            }
        }).addOnFailureListener(e->status.setText("Could not load users: "+e.getMessage()));
    }
    private Set<String> pendingCloudSet(){return new HashSet<>(getPreferences(0).getStringSet("cloud_pending_records",new HashSet<>()));}
    private int pendingCloudCount(){return pendingCloudSet().size();}
    private void markCloudPending(String collection,String id){Set<String> p=pendingCloudSet();p.add(collection+":"+id);getPreferences(0).edit().putStringSet("cloud_pending_records",p).apply();refreshPendingCloudText();}
    private void clearCloudPending(String collection,String id){Set<String> p=pendingCloudSet();p.remove(collection+":"+id);getPreferences(0).edit().putStringSet("cloud_pending_records",p).apply();refreshPendingCloudText();}
    private void refreshPendingCloudText(){runOnUiThread(()->{if(pendingCloudText!=null){int n=pendingCloudCount();pendingCloudText.setText("Pending sync: "+n+" records");pendingCloudText.setTextColor(n>0?GOLD:GREEN);}});}
    private class Chart extends View{
        Map<String,Double>data=new LinkedHashMap<>();double total;Paint p=new Paint(3);
        int[] colors={0xFF2F8065,0xFFE1B45A,0xFF729AC7,0xFFD97878,0xFF9A83C7,0xFF5EAAA5,0xFFB58A67,0xFF82936B,0xFFCF8DB2,0xFF8B9690};
        Chart(Context c){super(c);}
        void setData(Map<String,Double>d,double t){data=new LinkedHashMap<>(d);total=t;invalidate();}
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);float w=getWidth(),h=getHeight(),cx=w/2f,cy=h/2f;
            float r=Math.min(w*.37f,h*.39f),start=-90;int i=0;
            if(total<=0){p.setColor(muted);p.setTextSize(dp(11));p.setTextAlign(Paint.Align.CENTER);c.drawText("No data yet",cx,cy,p);return;}
            for(String cat:CATS){double v=data.get(cat);if(v>0){float sweep=(float)(v/total*360);p.setColor(colors[i]);c.drawArc(cx-r,cy-r,cx+r,cy+r,start,sweep,true,p);
                if(sweep>=24){double mid=Math.toRadians(start+sweep/2);float tx=cx+(float)Math.cos(mid)*r*.72f,ty=cy+(float)Math.sin(mid)*r*.72f;
                    p.setColor(Color.WHITE);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(dp(9));p.setTypeface(Typeface.create(Typeface.DEFAULT,Typeface.BOLD));c.drawText(Math.round(v/total*100)+"%",tx,ty+dp(3),p);p.setTypeface(Typeface.DEFAULT);}
                start+=sweep;}i++;}
            p.setColor(surface);c.drawCircle(cx,cy,r*.57f,p);p.setColor(ink);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(dp(8));c.drawText("TOTAL",cx,cy-dp(3),p);p.setTextSize(dp(9));c.drawText(money(total),cx,cy+dp(11),p);
        }
    }
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
            ContentValues expense=new ContentValues();expense.put("category",cat.getSelectedItem().toString());expense.put("description",description.getText().toString().trim());expense.putNull("item");expense.put("amount",paid);expense.put("date",iso.format(new Date()));long expenseId=db.getWritableDatabase().insert("expenses",null,expense);if(expenseId>0)pushExpense(expenseId);if(expenseId<0){Toast.makeText(this,"Could not save expense",Toast.LENGTH_LONG).show();return;}
            ContentValues completed=new ContentValues();completed.put("done",1);completed.put("expense_id",expenseId);db.getWritableDatabase().update("reminders",completed,"id=?",new String[]{String.valueOf(reminderId)});pushReminder(reminderId);ReminderReceiver.cancel(this,reminderId);dialog.dismiss();Toast.makeText(this,"Purchase added to expense history",Toast.LENGTH_SHORT).show();showPage(4);
        });});dialog.show();
    }
    private static class DB extends SQLiteOpenHelper{DB(Context c){super(c,"saqib_finance.db",null,7);}public void onCreate(SQLiteDatabase d){d.execSQL("CREATE TABLE expenses(id INTEGER PRIMARY KEY AUTOINCREMENT,category TEXT NOT NULL,description TEXT NOT NULL,amount REAL NOT NULL,date TEXT NOT NULL,item TEXT)");d.execSQL("CREATE TABLE incomes(id INTEGER PRIMARY KEY AUTOINCREMENT,amount REAL NOT NULL,source TEXT NOT NULL,date TEXT NOT NULL,note TEXT NOT NULL DEFAULT '')");d.execSQL("CREATE TABLE reminders(id INTEGER PRIMARY KEY AUTOINCREMENT,item TEXT NOT NULL,trigger_at INTEGER NOT NULL,done INTEGER NOT NULL DEFAULT 0,estimated_amount REAL NOT NULL DEFAULT 0,expense_id INTEGER)");d.execSQL("CREATE TABLE category_items(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,category TEXT NOT NULL,icon TEXT NOT NULL DEFAULT '📦',UNIQUE(name,category))");d.execSQL("CREATE TABLE expense_drafts(id INTEGER PRIMARY KEY AUTOINCREMENT,category TEXT NOT NULL,item TEXT NOT NULL,amount REAL NOT NULL,date TEXT NOT NULL)");}public void onUpgrade(SQLiteDatabase d,int o,int n){if(o<2)d.execSQL("CREATE TABLE IF NOT EXISTS reminders(id INTEGER PRIMARY KEY AUTOINCREMENT,item TEXT NOT NULL,trigger_at INTEGER NOT NULL,done INTEGER NOT NULL DEFAULT 0)");if(o<3){d.execSQL("ALTER TABLE reminders ADD COLUMN estimated_amount REAL NOT NULL DEFAULT 0");d.execSQL("ALTER TABLE reminders ADD COLUMN expense_id INTEGER");}if(o<4){d.execSQL("CREATE TABLE IF NOT EXISTS category_items(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,category TEXT NOT NULL,icon TEXT NOT NULL DEFAULT '📦',UNIQUE(name,category))");}if(o<5){d.execSQL("ALTER TABLE expenses ADD COLUMN item TEXT");d.execSQL("UPDATE expenses SET item=(SELECT name FROM category_items WHERE category_items.category=expenses.category AND category_items.name=expenses.description LIMIT 1) WHERE item IS NULL");}if(o<6){d.execSQL("CREATE TABLE IF NOT EXISTS incomes(id INTEGER PRIMARY KEY AUTOINCREMENT,amount REAL NOT NULL,source TEXT NOT NULL,date TEXT NOT NULL,note TEXT NOT NULL DEFAULT '')");}if(o<7){d.execSQL("CREATE TABLE IF NOT EXISTS expense_drafts(id INTEGER PRIMARY KEY AUTOINCREMENT,category TEXT NOT NULL,item TEXT NOT NULL,amount REAL NOT NULL,date TEXT NOT NULL)");}}public void onOpen(SQLiteDatabase d){super.onOpen(d);d.execSQL("CREATE TABLE IF NOT EXISTS expense_drafts(id INTEGER PRIMARY KEY AUTOINCREMENT,category TEXT NOT NULL,item TEXT NOT NULL,amount REAL NOT NULL,date TEXT NOT NULL)");}}}
