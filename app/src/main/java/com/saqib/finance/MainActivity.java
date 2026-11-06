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
import android.media.RingtoneManager;
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
    private boolean categoryGridView=true; private GestureDetector swipeDetector; private DB db; private final ArrayList<String> categories=new ArrayList<>(Arrays.asList(CATS)); private final HashSet<String> hiddenCategories=new HashSet<>(); private LinearLayout root,body,nav; private int page=0; private boolean dark=false;
    private Calendar month=Calendar.getInstance(); private String from="",to="",historyType="Both",selectedAddCategory="Grocery",pendingItemName=""; private final HashSet<String> selectedHistoryCategories=new HashSet<>(),selectedBalanceCategories=new HashSet<>();
    private TextView totalText,countText,monthText,pendingCloudText; private LinearLayout historyList;
    private final SimpleDateFormat iso=new SimpleDateFormat("yyyy-MM-dd",Locale.US);
    private final SimpleDateFormat display=new SimpleDateFormat("dd MMM yyyy",Locale.ENGLISH);
    private final SimpleDateFormat monthFmt=new SimpleDateFormat("MMMM yyyy",Locale.ENGLISH);
    private int bg,surface,ink,muted,line; private boolean unlocked=false, lockPromptShowing=false, uiReady=false;
    @Override public void onCreate(Bundle b){super.onCreate(b);swipeDetector=new GestureDetector(this,new GestureDetector.SimpleOnGestureListener(){@Override public boolean onFling(MotionEvent e1,MotionEvent e2,float velocityX,float velocityY){if(e1==null||e2==null)return false;float dx=e2.getX()-e1.getX(),dy=e2.getY()-e1.getY();if(Math.abs(dx)>dp(80)&&Math.abs(dx)>Math.abs(dy)*1.25f&&Math.abs(velocityX)>dp(300)){int current=page==5?1:(page==6?2:page);int next=current+(dx<0?1:-1);if(next>=0&&next<=4){showPage(next);return true;}}return false;}});dark=getPreferences(0).getBoolean("dark",false);db=new DB(this);loadCategorySettings();seedDefaultItems();refreshKnownItemIcons();setupGoogleSignIn();registerCloudNetworkCallback();applyCloudNetworkPreference();if(FirebaseAuth.getInstance().getCurrentUser()!=null)syncAllCloud();else if(getRetentionMonths()>0)cleanupOldRecords(false);palette();if(getPreferences(0).getBoolean("screen_lock",true)){showLockScreen();showSecurityPrompt();}else{unlocked=true;openMainUI(0);}}

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
    private TextView txt(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(Math.max(9f,size*0.94f));t.setTextColor(color);if(bold)t.setTypeface(null,1);return t;}
    private LinearLayout col(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(0);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h));}
    private String money(double v){return "Rs. "+String.format(Locale.US,"%,.0f",v);}
    private String dateLabel(String s){try{return display.format(iso.parse(s));}catch(Exception e){return s;}}
    private void buildShell(){
        root=col();root.setBackgroundColor(bg);setContentView(root);
        FrameLayout frame=new FrameLayout(this);root.addView(frame,new LinearLayout.LayoutParams(-1,0,1));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);body=col();body.setPadding(0,0,0,dp(16));scroll.addView(body);frame.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        nav=row();nav.setBackgroundColor(DEEP);nav.setPadding(dp(5),dp(6),dp(5),dp(6));root.addView(nav,lp(-1,60));
    }
    private void showPage(int p){
        page=p;body.removeAllViews();nav.removeAllViews();body.setBackgroundColor(bg);
        if(p==0)homePage();else if(p==1)addPage();else if(p==2)historyPage();else if(p==3)settingsPage();else if(p==4)remindersPage();else if(p==5)categoryItemsPage(selectedAddCategory);else if(p==6)balancePage();
        String[] labels={"Home","Drafts","History","Settings","Reminders"};String[] icons={"⌂","+","▤","⚙","◷"};
        for(int i=0;i<5;i++){final int index=i;LinearLayout item=col();item.setGravity(Gravity.CENTER);boolean active=(i==p)||(p==5&&i==1);TextView ic=txt(icons[i],22,active?GOLD:0xFFD0DED5,true);ic.setGravity(Gravity.CENTER);TextView label=txt(labels[i],10,active?Color.WHITE:0xFFB5C8BD,active);label.setGravity(Gravity.CENTER);item.addView(ic);item.addView(label);item.setOnClickListener(v->showPage(index));nav.addView(item,new LinearLayout.LayoutParams(0,-1,1));}
    }
    private void pageHeader(String title,String sub){LinearLayout h=col();h.setPadding(dp(14),dp(38),dp(14),dp(13));GradientDrawable gd=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{0xFF78A5E8,0xFF6997E2,0xFF5C89D4});gd.setCornerRadii(new float[]{0,0,0,0,dp(24),dp(24),dp(24),dp(24)});h.setBackground(gd);LinearLayout brand=row();ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.ic_launcher);logo.setScaleType(ImageView.ScaleType.FIT_CENTER);logo.setContentDescription("Saqib Finance app icon");brand.addView(logo,lp(64,64));LinearLayout names=col();names.setGravity(Gravity.CENTER_VERTICAL);names.setPadding(dp(10),0,0,0);TextView brandName=txt("Saqib Finance",23,Color.WHITE,true);brandName.setSingleLine(true);brandName.setIncludeFontPadding(false);names.addView(brandName);TextView brandTagline=txt("Track · Manage · Save",12,0xFFDCE9FF,true);brandTagline.setPadding(0,dp(5),0,0);names.addView(brandTagline);brand.addView(names,new LinearLayout.LayoutParams(0,-2,1));h.addView(brand,lp(-1,64));TextView gear=txt("⚙",25,Color.WHITE,true);gear.setGravity(Gravity.CENTER);gear.setOnClickListener(v->showPage(3));brand.addView(gear,lp(42,52));if(!title.equals("Home")){TextView titleView=txt(title,17,Color.WHITE,true);titleView.setPadding(0,dp(12),0,dp(2));h.addView(titleView);h.addView(txt(sub,11,0xFFDCE9FF,false));}body.addView(h);}
    private LinearLayout content(){LinearLayout c=col();c.setPadding(dp(14),dp(13),dp(14),0);body.addView(c);return c;}
    private LinearLayout card(LinearLayout parent){LinearLayout c=col();c.setPadding(dp(13),dp(12),dp(13),dp(12));c.setBackground(shape(surface,18));c.setElevation(dp(2));LinearLayout.LayoutParams q=lp(-1,-2);q.bottomMargin=dp(13);parent.addView(c,q);return c;}
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
        LinearLayout spent=col();spent.setPadding(dp(13),dp(13),dp(10),dp(13));spent.setBackground(shape(surface,18));spent.setElevation(dp(3));LinearLayout.LayoutParams slp=new LinearLayout.LayoutParams(0,dp(84),1);slp.rightMargin=dp(6);totals.addView(spent,slp);
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
    private void loadMonth(Chart chart,TextView historyCount,TextView historyAmount){String key=new SimpleDateFormat("yyyy-MM",Locale.US).format(month.getTime());Map<String,Double> data=new LinkedHashMap<>();double total=0;int count=0;for(String s:cats())data.put(s,0d);Cursor q=db.getReadableDatabase().rawQuery("SELECT category,SUM(amount),COUNT(*) FROM expenses WHERE substr(date,1,7)=? GROUP BY category",new String[]{key});while(q.moveToNext()){String cat=q.getString(0);double val=q.getDouble(1);data.put(cat,val);total+=val;count+=q.getInt(2);}q.close();totalText.setText(money(total));countText.setText(count+" expense"+(count==1?"":"s")+" recorded");historyCount.setText(count+" records");historyAmount.setText(money(total));chart.setData(data,total);}
    private void addCategoryGrid(LinearLayout parent){
        String[] icons={"🧒","💍","👨‍👩‍👧","🧴","💊","🛒","🏍️","🧾","🛍️","🍽️","📦"};
        int[] tints={0xFFB8D9F8,0xFFF4B8D4,0xFFFFD99A,0xFFF4D7B5,0xFFB9F0D0,0xFFDCC5F4,0xFFBDEBEA,0xFFB8D9F8,0xFFF4B8D4,0xFFC8EDB6,0xFFDCC5F4};
        ArrayList<String> visible=new ArrayList<>();for(String cat:categories)if(!hiddenCategories.contains(cat))visible.add(cat);
        for(int i=0;i<visible.size();i+=4){LinearLayout lineRow=row();LinearLayout.LayoutParams rowLp=lp(-1,-2);rowLp.bottomMargin=dp(8);parent.addView(lineRow,rowLp);for(int j=i;j<Math.min(i+4,visible.size());j++){final String cat=visible.get(j);final int idx=Arrays.asList(CATS).indexOf(cat);int tint=idx>=0?tints[idx]:MINT;String icon=idx>=0?icons[idx]:getPreferences(0).getString("category_icon_"+cat,"📦");if("Other".equals(cat))icon="+";LinearLayout tile=col();tile.setGravity(Gravity.CENTER);tile.setPadding(dp(3),dp(8),dp(3),dp(7));tile.setBackground(shape(surface,19));GradientDrawable tileBorder=shape(surface,19);tileBorder.setStroke(dp(1),dark?0xFF344F78:0xFFD5E4FA);tile.setBackground(tileBorder);tile.setElevation(dp(2));LinearLayout.LayoutParams tileLp=new LinearLayout.LayoutParams(0,dp(76),1);if(j>i)tileLp.leftMargin=dp(5);lineRow.addView(tile,tileLp);TextView symbol=txt(icon,22,ink,true);symbol.setGravity(Gravity.CENTER);tile.addView(symbol,lp(-1,35));TextView label=txt(cat,10,ink,true);label.setGravity(Gravity.CENTER);label.setSingleLine(true);label.setPadding(0,dp(3),0,0);tile.addView(label);tile.setOnClickListener(v->{if("Other".equals(cat)){showNewCategoryDialog();}else{selectedAddCategory=cat;showPage(5);}});tile.setOnLongClickListener(v->{categoryOptions(cat);return true;});}}
    }
    private void showNewCategoryDialog(){
        LinearLayout box=col();box.setPadding(dp(8),dp(4),dp(8),0);
        EditText name=new EditText(this);name.setSingleLine(true);name.setHint("New category name");box.addView(name,lp(-1,52));
        String[] chosen={"🗂️"};TextView preview=txt(chosen[0],34,ink,true);preview.setGravity(Gravity.CENTER);box.addView(preview,lp(-1,58));
        Button choose=smallFilter("Choose category icon");box.addView(choose,lp(-1,42));
        choose.setOnClickListener(v->showItemIconPicker(chosen,preview,new boolean[]{true}));
        new AlertDialog.Builder(this).setTitle("Add New Category").setView(box).setNegativeButton("Cancel",null).setPositiveButton("Add",(d,w)->{
            String n=name.getText().toString().trim();if(n.isEmpty()){Toast.makeText(this,"Enter a category name",Toast.LENGTH_SHORT).show();return;}
            if(categories.contains(n)){Toast.makeText(this,"Category already exists",Toast.LENGTH_SHORT).show();return;}
            categories.add(n);hiddenCategories.remove(n);getPreferences(0).edit().putString("category_icon_"+n,chosen[0]).apply();saveCategorySettings();showPage(0);Toast.makeText(this,"Category added",Toast.LENGTH_SHORT).show();
        }).show();
    }
    private String[] cats(){return categories.toArray(new String[0]);}
    private void loadCategorySettings(){String saved=getPreferences(0).getString("category_order","");if(!saved.isEmpty()){try{JSONArray a=new JSONArray(saved);ArrayList<String> loaded=new ArrayList<>();for(int i=0;i<a.length();i++){String v=a.optString(i,"");if(!v.isEmpty()&&!loaded.contains(v))loaded.add(v);}for(String v:CATS)if(!loaded.contains(v))loaded.add(v);categories.clear();categories.addAll(loaded);}catch(Exception ignored){}}if(!getPreferences(0).getBoolean("category_order_repaired_v1",false)){ArrayList<String> custom=new ArrayList<>();for(String v:categories)if(!Arrays.asList(CATS).contains(v))custom.add(v);categories.clear();categories.addAll(Arrays.asList(CATS));for(String v:custom)if(!categories.contains(v))categories.add(v);getPreferences(0).edit().putBoolean("category_order_repaired_v1",true).apply();saveCategorySettings();}String hidden=getPreferences(0).getString("hidden_categories","");if(!hidden.isEmpty()){try{JSONArray a=new JSONArray(hidden);for(int i=0;i<a.length();i++)hiddenCategories.add(a.optString(i));}catch(Exception ignored){}}}
    private void saveCategorySettings(){JSONArray a=new JSONArray();for(String v:categories)a.put(v);JSONArray h=new JSONArray();for(String v:hiddenCategories)h.put(v);getPreferences(0).edit().putString("category_order",a.toString()).putString("hidden_categories",h.toString()).apply();}
    private void categoryOptions(String cat){String[] options={"Move Up","Move Down","Rename","Remove From Home","Delete Category"};new AlertDialog.Builder(this).setTitle(cat).setItems(options,(d,which)->{if(which==0||which==1){int at=categories.indexOf(cat),to=at+(which==0?-1:1);if(at<0||to<0||to>=categories.size()){Toast.makeText(this,"Cannot move further",Toast.LENGTH_SHORT).show();return;}Collections.swap(categories,at,to);saveCategorySettings();showPage(0);}else if(which==2)renameCategory(cat);else if(which==3){hiddenCategories.add(cat);saveCategorySettings();showPage(0);Toast.makeText(this,"Category removed from Home; its history is safe",Toast.LENGTH_LONG).show();}else deleteCategory(cat);}).setNegativeButton("Cancel",null).show();}
    private void renameCategory(String oldName){EditText input=new EditText(this);input.setSingleLine(true);input.setText(oldName);input.setSelection(input.length());new AlertDialog.Builder(this).setTitle("Rename Category").setView(input).setNegativeButton("Cancel",null).setPositiveButton("Save",(d,w)->{String next=input.getText().toString().trim();if(next.isEmpty()){Toast.makeText(this,"Enter a category name",Toast.LENGTH_SHORT).show();return;}if(next.equals(oldName))return;if(categories.contains(next)){Toast.makeText(this,"Category already exists",Toast.LENGTH_SHORT).show();return;}SQLiteDatabase sql=db.getWritableDatabase();ArrayList<Long> expenseIds=new ArrayList<>(),itemIds=new ArrayList<>();Cursor e=sql.rawQuery("SELECT id FROM expenses WHERE category=?",new String[]{oldName});while(e.moveToNext())expenseIds.add(e.getLong(0));e.close();Cursor it=sql.rawQuery("SELECT id,name FROM category_items WHERE category=?",new String[]{oldName});ArrayList<String> itemNames=new ArrayList<>();while(it.moveToNext()){itemIds.add(it.getLong(0));itemNames.add(it.getString(1));}it.close();sql.beginTransaction();try{ContentValues cv=new ContentValues();cv.put("category",next);sql.update("expenses",cv,"category=?",new String[]{oldName});sql.update("expense_drafts",cv,"category=?",new String[]{oldName});sql.update("category_items",cv,"category=?",new String[]{oldName});sql.setTransactionSuccessful();}finally{sql.endTransaction();}for(int i=0;i<itemIds.size();i++){deleteCloudCategoryItem(oldName,itemNames.get(i));pushCategoryItem(itemIds.get(i));}for(long id:expenseIds)pushExpense(id);int index=categories.indexOf(oldName);if(index>=0)categories.set(index,next);if(hiddenCategories.remove(oldName))hiddenCategories.add(next);saveCategorySettings();showPage(0);Toast.makeText(this,"Category renamed",Toast.LENGTH_SHORT).show();}).show();}
    private void deleteCategory(String cat){SQLiteDatabase sql=db.getReadableDatabase();Cursor e=sql.rawQuery("SELECT COUNT(*) FROM expenses WHERE category=?",new String[]{cat});int expenses=0;if(e.moveToFirst())expenses=e.getInt(0);e.close();Cursor draftCursor=sql.rawQuery("SELECT COUNT(*) FROM expense_drafts WHERE category=?",new String[]{cat});int drafts=0;if(draftCursor.moveToFirst())drafts=draftCursor.getInt(0);draftCursor.close();if(expenses>0||drafts>0){Toast.makeText(this,"This category has history or draft records. Remove From Home instead; records will stay safe.",Toast.LENGTH_LONG).show();return;}ArrayList<String> names=new ArrayList<>();ArrayList<Long> ids=new ArrayList<>();Cursor it=sql.rawQuery("SELECT id,name FROM category_items WHERE category=?",new String[]{cat});while(it.moveToNext()){ids.add(it.getLong(0));names.add(it.getString(1));}it.close();new AlertDialog.Builder(this).setTitle("Delete "+cat+"?").setMessage("This will delete the category and its saved items.").setNegativeButton("Cancel",null).setPositiveButton("Delete",(d,w)->{for(String name:names)deleteCloudCategoryItem(cat,name);sql.delete("category_items","category=?",new String[]{cat});categories.remove(cat);hiddenCategories.remove(cat);saveCategorySettings();showPage(0);Toast.makeText(this,"Category deleted",Toast.LENGTH_SHORT).show();}).show();}
    private void addRecent(LinearLayout target,int limit){Cursor q=db.getReadableDatabase().rawQuery("SELECT category,description,amount,date FROM expenses ORDER BY date DESC,id DESC LIMIT "+limit,null);if(!q.moveToFirst()){TextView e=txt("No expenses yet. Add your first expense.",13,muted,false);e.setPadding(0,dp(18),0,dp(12));target.addView(e);}else do{String cat=q.getString(0),desc=q.getString(1);LinearLayout r=row();TextView left=txt(cat+(desc.isEmpty()?"":" · "+desc),13,ink,true);r.addView(left,new LinearLayout.LayoutParams(0,-2,1));r.addView(txt("-"+money(q.getDouble(2)),13,0xFFB36A45,true));target.addView(r,lp(-1,43));View d=new View(this);d.setBackgroundColor(line);target.addView(d,lp(-1,1));}while(q.moveToNext());q.close();}

    private void balancePage(){
        pageHeader("Balance","Money received and money spent");LinearLayout c=content();
        LinearLayout filter=card(c);filter.addView(txt("CATEGORY · IN / OUT",10,GOLD,true));Button categoryFilter=smallFilter(selectedCategoryLabel(selectedBalanceCategories));filter.addView(categoryFilter,lp(-1,44));categoryFilter.setOnClickListener(v->showCategoryMultiSelect(false));
        boolean allBalanceCategories=selectedBalanceCategories.isEmpty();ArrayList<String> selectedIncomeSources=valuesFor(selectedBalanceCategories,"In — "),selectedExpenseCategories=valuesFor(selectedBalanceCategories,"Out — ");double income=categoryTotal("incomes","source",selectedIncomeSources,allBalanceCategories),out=categoryTotal("expenses","category",selectedExpenseCategories,allBalanceCategories);double available=income-out;
        LinearLayout hero=card(c);hero.setBackground(shape(DEEP,20));hero.addView(txt("AVAILABLE BALANCE",11,0xFFBDD2C4,true));hero.addView(txt(money(available),30,Color.WHITE,true));hero.addView(txt(allBalanceCategories?"All money received minus all expenses":selectedCategoryLabel(selectedBalanceCategories),11,0xFFBDD2C4,false));
        LinearLayout totals=row();LinearLayout.LayoutParams tr=lp(-1,-2);tr.bottomMargin=dp(13);c.addView(totals,tr);
        LinearLayout in=col();in.setPadding(dp(13),dp(14),dp(8),dp(14));in.setBackground(shape(surface,16));LinearLayout.LayoutParams il=new LinearLayout.LayoutParams(0,dp(100),1);il.rightMargin=dp(6);totals.addView(in,il);in.addView(txt("↙  MONEY IN",11,GREEN,true));in.addView(txt(money(income),18,ink,true));
        LinearLayout outCard=col();outCard.setPadding(dp(13),dp(14),dp(8),dp(14));outCard.setBackground(shape(surface,16));totals.addView(outCard,new LinearLayout.LayoutParams(0,dp(100),1));outCard.addView(txt("↗  MONEY OUT",11,0xFFB36A45,true));outCard.addView(txt(money(out),18,ink,true));
        Button add=primaryButton("+  Add Money In");LinearLayout.LayoutParams addLp=lp(-1,50);addLp.bottomMargin=dp(13);c.addView(add,addLp);add.setOnClickListener(v->showAddIncomeDialog());
        LinearLayout incomeCard=card(c);incomeCard.addView(txt("MONEY IN HISTORY",12,GOLD,true));String incomeSql="SELECT id,amount,source,date,note FROM incomes";ArrayList<String> incomeArgs=new ArrayList<>();if(!allBalanceCategories){if(selectedIncomeSources.isEmpty())incomeSql+=" WHERE 1=0";else{incomeSql+=" WHERE source IN ("+placeholders(selectedIncomeSources.size())+")";incomeArgs.addAll(selectedIncomeSources);}}incomeSql+=" ORDER BY date DESC,id DESC";Cursor ic=db.getReadableDatabase().rawQuery(incomeSql,incomeArgs.toArray(new String[0]));
        if(!ic.moveToFirst())incomeCard.addView(txt("No money received yet. Tap Add Money In to record it.",13,muted,false));else do{long id=ic.getLong(0);double amount=ic.getDouble(1);String source=ic.getString(2),date=ic.getString(3),note=ic.getString(4);LinearLayout r=row();r.setPadding(0,dp(10),0,dp(10));LinearLayout details=col();String sourceIcon=getPreferences(0).getString("income_icon_"+source,"");details.addView(txt((sourceIcon.isEmpty()?"":sourceIcon+"  ")+source,14,ink,true));details.addView(txt(dateLabel(date)+(note==null||note.trim().isEmpty()?"":" · "+note),11,muted,false));r.addView(details,new LinearLayout.LayoutParams(0,-2,1));r.addView(txt("+"+money(amount),13,GREEN,true));r.setOnClickListener(v->editIncome(id,amount,source,date,note));incomeCard.addView(r);View sep=new View(this);sep.setBackgroundColor(line);incomeCard.addView(sep,lp(-1,1));}while(ic.moveToNext());ic.close();
        LinearLayout expenseCard=card(c);LinearLayout expenseHead=row();expenseHead.addView(txt("MONEY OUT · EXPENSES",12,GOLD,true),new LinearLayout.LayoutParams(0,-2,1));TextView view=txt("View all →",12,GREEN,true);expenseHead.addView(view);view.setOnClickListener(v->showPage(2));expenseCard.addView(expenseHead);
        String expenseSql="SELECT category,description,amount,date,item FROM expenses";ArrayList<String> expenseArgs=new ArrayList<>();if(!allBalanceCategories){if(selectedExpenseCategories.isEmpty())expenseSql+=" WHERE 1=0";else{expenseSql+=" WHERE category IN ("+placeholders(selectedExpenseCategories.size())+")";expenseArgs.addAll(selectedExpenseCategories);}}expenseSql+=" ORDER BY date DESC,id DESC LIMIT 30";Cursor ec=db.getReadableDatabase().rawQuery(expenseSql,expenseArgs.toArray(new String[0]));if(!ec.moveToFirst())expenseCard.addView(txt("No expenses recorded yet.",13,muted,false));else do{String cat=ec.getString(0),desc=ec.getString(1),date=ec.getString(3),item=ec.getString(4);LinearLayout r=row();r.setPadding(0,dp(9),0,dp(9));LinearLayout details=col();details.addView(txt(cat+(item==null||item.isEmpty()?"":" · "+item),13,ink,true));details.addView(txt(dateLabel(date)+(desc==null||desc.isEmpty()||desc.equals(item)?"":" · "+desc),11,muted,false));r.addView(details,new LinearLayout.LayoutParams(0,-2,1));r.addView(txt("-"+money(ec.getDouble(2)),13,0xFFB36A45,true));expenseCard.addView(r);View sep=new View(this);sep.setBackgroundColor(line);expenseCard.addView(sep,lp(-1,1));}while(ec.moveToNext());ec.close();
    }
    private void showAddIncomeDialog(){
        LinearLayout box=col();box.setPadding(dp(5),dp(5),dp(5),0);
        TextView prompt=txt("SELECT MONEY TYPE",11,muted,true);box.addView(prompt);
        String[] types={"Salary","Advance","Gift","Borrow","New"};String[] icons={"💼","💵","🎁","🤝","+"};
        final String[] selectedSource={""};final String[] selectedIcon={""};LinearLayout choices=col();box.addView(choices);
        for(int rowIndex=0;rowIndex<3;rowIndex++){LinearLayout r=row();LinearLayout.LayoutParams rp=lp(-1,72);rp.topMargin=dp(5);choices.addView(r,rp);
            for(int colIndex=0;colIndex<2;colIndex++){int index=rowIndex*2+colIndex;if(index>=types.length)continue;final int ix=index;
                LinearLayout tile=col();tile.setGravity(Gravity.CENTER);tile.setBackground(shape(surface,12));tile.setElevation(dp(1));LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(0,-1,1);if(colIndex>0)tp.leftMargin=dp(6);r.addView(tile,tp);
                TextView symbol=txt(icons[ix],22,ink,true);symbol.setGravity(Gravity.CENTER);tile.addView(symbol);TextView label=txt(types[ix],11,ink,true);label.setGravity(Gravity.CENTER);tile.addView(label);
                tile.setOnClickListener(v->{if(ix==4){LinearLayout custom=col();custom.setPadding(dp(4),dp(4),dp(4),0);EditText customName=new EditText(this);customName.setSingleLine(true);customName.setHint("Enter source name");custom.addView(customName,lp(-1,50));TextView iconPreview=txt("💰",30,ink,true);iconPreview.setGravity(Gravity.CENTER);custom.addView(iconPreview,lp(-1,48));final String[] customIcon={"💰"};Button pick=smallFilter("Choose icon");custom.addView(pick,lp(-1,40));pick.setOnClickListener(w->showItemIconPicker(customIcon,iconPreview,new boolean[]{false}));
                    new AlertDialog.Builder(this).setTitle("New Money Type").setView(custom).setNegativeButton("Cancel",null).setPositiveButton("Continue",(d,w)->{String n=customName.getText().toString().trim();if(n.isEmpty()){Toast.makeText(this,"Enter a name",Toast.LENGTH_SHORT).show();return;}selectedSource[0]=n;selectedIcon[0]=customIcon[0];getPreferences(0).edit().putString("income_icon_"+n,customIcon[0]).apply();prompt.setText(customIcon[0]+"  "+n+" selected");}).show();
                }else{selectedSource[0]=types[ix];selectedIcon[0]=icons[ix];prompt.setText(icons[ix]+"  "+types[ix]+" selected");}});
            }}
        EditText amount=new EditText(this);amount.setSingleLine(true);amount.setInputType(8194);amount.setHint("Amount received (PKR)");box.addView(amount,lp(-1,50));
        EditText source=new EditText(this);source.setSingleLine(true);source.setHint("Other source (optional)");box.addView(source,lp(-1,48));
        TextView date=txt(display.format(new Date()),14,ink,true);date.setPadding(dp(12),dp(13),dp(12),dp(13));date.setBackground(shape(bg,10));box.addView(date,lp(-1,46));date.setOnClickListener(v->pickDate(date));
        EditText note=new EditText(this);note.setSingleLine(true);note.setHint("Note (optional)");box.addView(note,lp(-1,48));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Add Money In").setView(box).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{try{double value=Double.parseDouble(amount.getText().toString().trim());if(value<=0||Double.isInfinite(value)||Double.isNaN(value))throw new Exception();String from=source.getText().toString().trim();if(from.isEmpty())from=selectedSource[0];if(from.isEmpty()){Toast.makeText(this,"Select Salary, Advance, Gift, Borrow or New",Toast.LENGTH_SHORT).show();return;}if(!selectedIcon[0].isEmpty())getPreferences(0).edit().putString("income_icon_"+from,selectedIcon[0]).apply();Date dt=display.parse(date.getText().toString());ContentValues cv=new ContentValues();cv.put("amount",value);cv.put("source",from);cv.put("date",iso.format(dt));cv.put("note",note.getText().toString().trim());long id=db.getWritableDatabase().insert("incomes",null,cv);if(id<=0)throw new Exception();pushIncome(id);dialog.dismiss();Toast.makeText(this,"Money received saved",Toast.LENGTH_SHORT).show();showPage(6);}catch(Exception e){amount.setError("Enter a valid amount");}}));dialog.show();
    }

    private void editIncome(long id,double value,String sourceValue,String dateValue,String noteValue){
        LinearLayout box=col();EditText amount=new EditText(this);amount.setInputType(8194);amount.setText(String.valueOf(value));box.addView(amount);EditText source=new EditText(this);source.setText(sourceValue);source.setHint("Received from");box.addView(source);
        TextView date=txt(dateLabel(dateValue),14,ink,true);date.setPadding(dp(10),dp(12),dp(10),dp(12));date.setOnClickListener(v->pickDate(date));box.addView(date);EditText note=new EditText(this);note.setText(noteValue==null?"":noteValue);note.setHint("Note (optional)");box.addView(note);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Edit money in").setView(box).setNegativeButton("Cancel",null).setNeutralButton("Delete",(d,w)->new AlertDialog.Builder(this).setMessage("Delete this money-in record?").setNegativeButton("Cancel",null).setPositiveButton("Delete",(x,y)->{db.getWritableDatabase().delete("incomes","id=?",new String[]{String.valueOf(id)});deleteCloudRecord("incomes",id);showPage(page==2?2:6);}).show()).setPositiveButton("Save",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{try{double n=Double.parseDouble(amount.getText().toString().trim());String from=source.getText().toString().trim();Date dt=display.parse(date.getText().toString());if(n<=0||from.isEmpty())throw new Exception();ContentValues cv=new ContentValues();cv.put("amount",n);cv.put("source",from);cv.put("date",iso.format(dt));cv.put("note",note.getText().toString().trim());db.getWritableDatabase().update("incomes",cv,"id=?",new String[]{String.valueOf(id)});pushIncome(id);dialog.dismiss();showPage(page==2?2:6);}catch(Exception e){amount.setError("Enter a valid amount and source");}}));dialog.show();
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
                    LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(0,dp(92),1);if(j>i)tp.leftMargin=dp(8);gridRow.addView(tile,tp);
                    addItemIcon(tile,(ic==null||ic.trim().isEmpty())?"📦":ic,25,lp(-1,36));
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
                addItemIcon(r,(ic==null||ic.trim().isEmpty())?"📦":ic,22,lp(38,38));
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
        iconRow.addView(iconActions,new LinearLayout.LayoutParams(0,-2,1));box.addView(iconRow);
        final String[] chosen={suggestItemIcon("")};final boolean[] manuallyChosen={false};
        name.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int st,int count,int after){}
            public void onTextChanged(CharSequence s,int st,int before,int count){if(!manuallyChosen[0]){chosen[0]=suggestItemIcon(s.toString());preview.setText(chosen[0]);}}
            public void afterTextChanged(android.text.Editable e){}
        });
        choose.setOnClickListener(v->showItemIconPicker(chosen,preview,manuallyChosen));
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
        final String[] choices={"📦","🗂️","📁","📌","📎","🧰","🛒","🛍️","💳","💵","💰","🏦","🏠","🏢","🏫","🏥","🏪","🏬","🚗","🚕","🏍️","🛵","🚲","🚌","🚆","✈️","⛽","🔧","🛠️","🧹","🧽","🧺","👕","👖","👗","👟","👜","💍","💄","🧴","🧒","👶","🧸","🎒","📚","✏️","🖊️","📖","🥛","🍼","🥚","🍞","🥖","🍚","🍎","🍌","🥦","🥕","🧅","🥩","🍗","🐟","🧀","🧈","🧂","🫙","🧼","🪥","🧻","💊","🩺","🧪","🩹","🍽️","☕","🧃","🎁","🎂","🎉","⚽","🏏","🎮","📱","💻","🖨️","🔋","💡","💧","🐾","🌿","🌸","❤️","⭐","🔑","🪑","🛏️","🧯","🛡️"};
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
        Spinner target=new Spinner(this);target.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,cats()));
        target.setSelection(Math.max(0,Arrays.asList(cats()).indexOf(current)));
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
        boolean[] manual={false};
        Cursor c=db.getReadableDatabase().rawQuery("SELECT icon FROM category_items WHERE id=?",new String[]{String.valueOf(id)});if(c.moveToFirst()){chosen[0]=c.getString(0);preview.setText(chosen[0]!=null&&chosen[0].startsWith("@iconify:")?"🌐":chosen[0]);}c.close();
        choose.setOnClickListener(v->showItemIconPicker(chosen,preview,manual));
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
    private void addMonthlyOverview(LinearLayout parent){LinearLayout overview=card(parent);LinearLayout head=row();head.addView(txt("▥  Monthly Overview",17,ink,true),new LinearLayout.LayoutParams(0,-2,1));Button prev=smallButton("‹"),next=smallButton("›");TextView monthName=txt(monthFmt.format(month.getTime()),12,ink,true);monthName.setGravity(Gravity.CENTER);head.addView(prev,lp(32,32));head.addView(monthName,lp(105,32));head.addView(next,lp(32,32));overview.addView(head);Chart chart=new Chart(this);overview.addView(chart,lp(-1,175));LinearLayout legend=col();overview.addView(legend);Runnable refresh=()->{String key=new SimpleDateFormat("yyyy-MM",Locale.US).format(month.getTime());Map<String,Double> data=new LinkedHashMap<>();double total=0;for(String cat:cats())data.put(cat,0d);Cursor q=db.getReadableDatabase().rawQuery("SELECT category,SUM(amount) FROM expenses WHERE substr(date,1,7)=? GROUP BY category",new String[]{key});while(q.moveToNext()){String cat=q.getString(0);double amount=q.getDouble(1);data.put(cat,amount);total+=amount;}q.close();chart.setData(data,total);monthName.setText(monthFmt.format(month.getTime()));legend.removeAllViews();int[] legendColors={0xFF2F8065,0xFFE1B45A,0xFF729AC7,0xFFD97878,0xFF9A83C7,0xFF5EAAA5,0xFFB58A67,0xFF82936B,0xFFCF8DB2,0xFF8B9690};int colorIndex=0;for(String cat:cats()){double amount=data.get(cat);int categoryColor=legendColors[colorIndex%legendColors.length];colorIndex++;if(amount<=0)continue;LinearLayout r=row();r.setPadding(0,dp(4),0,dp(4));TextView marker=txt("●",12,categoryColor,true);r.addView(marker,lp(22,-2));r.addView(txt(cat+"  "+Math.round(amount/total*100)+"%",12,ink,true),new LinearLayout.LayoutParams(0,-2,1));r.addView(txt(money(amount),12,ink,true));legend.addView(r);}if(total<=0)legend.addView(txt("No expenses recorded for this month.",12,muted,false));};prev.setOnClickListener(v->{month.add(Calendar.MONTH,-1);refresh.run();});next.setOnClickListener(v->{month.add(Calendar.MONTH,1);refresh.run();});refresh.run();}
    private ArrayList<String> historyCategoryOptions(){ArrayList<String> options=new ArrayList<>();if(!historyType.equals("Out")){Cursor q=db.getReadableDatabase().rawQuery("SELECT DISTINCT source FROM incomes ORDER BY source COLLATE NOCASE",null);while(q.moveToNext()){String value=q.getString(0);if(value!=null&&!value.isEmpty())options.add("In — "+value);}q.close();}if(!historyType.equals("In"))for(String value:cats())options.add("Out — "+value);return options;}
    private ArrayList<String> balanceCategoryOptions(){ArrayList<String> options=new ArrayList<>();Cursor q=db.getReadableDatabase().rawQuery("SELECT DISTINCT source FROM incomes ORDER BY source COLLATE NOCASE",null);while(q.moveToNext()){String value=q.getString(0);if(value!=null&&!value.isEmpty())options.add("In — "+value);}q.close();for(String value:cats())options.add("Out — "+value);return options;}
    private String selectedCategoryLabel(HashSet<String> selected){return selected.isEmpty()?"All categories":selected.size()+" selected";}
    private ArrayList<String> valuesFor(HashSet<String> selected,String prefix){ArrayList<String> values=new ArrayList<>();for(String value:selected)if(value.startsWith(prefix))values.add(value.substring(prefix.length()));return values;}
    private String placeholders(int count){StringBuilder b=new StringBuilder();for(int i=0;i<count;i++){if(i>0)b.append(",");b.append("?");}return b.toString();}
    private double categoryTotal(String table,String column,ArrayList<String> values,boolean all){if(!all&&values.isEmpty())return 0;String sql="SELECT COALESCE(SUM(amount),0) FROM "+table;if(!all)sql+=" WHERE "+column+" IN ("+placeholders(values.size())+")";Cursor q=db.getReadableDatabase().rawQuery(sql,all?new String[0]:values.toArray(new String[0]));double total=q.moveToFirst()?q.getDouble(0):0;q.close();return total;}
    private void showCategoryMultiSelect(boolean forHistory){HashSet<String> current=forHistory?selectedHistoryCategories:selectedBalanceCategories;ArrayList<String> options=forHistory?historyCategoryOptions():balanceCategoryOptions();boolean[] checked=new boolean[options.size()];for(int i=0;i<options.size();i++)checked[i]=current.contains(options.get(i));HashSet<String> pending=new HashSet<>(current);new AlertDialog.Builder(this).setTitle("Select categories").setMultiChoiceItems(options.toArray(new String[0]),checked,(d,which,isChecked)->{String value=options.get(which);if(isChecked)pending.add(value);else pending.remove(value);}).setNegativeButton("Cancel",null).setPositiveButton("Done",(d,w)->{current.clear();current.addAll(pending);showPage(forHistory?2:6);}).show();}
    private void historyPage(){pageHeader("Money In & Expense History","Find and review your money in and expenses");LinearLayout c=content();addMonthlyOverview(c);LinearLayout filters=card(c);
        LinearLayout dates=row();Button f=smallFilter(from.isEmpty()?"From date":dateLabel(from)),t=smallFilter(to.isEmpty()?"To date":dateLabel(to));dates.addView(f,new LinearLayout.LayoutParams(0,dp(44),1));LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(0,dp(44),1);tp.leftMargin=dp(8);dates.addView(t,tp);filters.addView(dates);
        f.setOnClickListener(v->pickFilter(true));t.setOnClickListener(v->pickFilter(false));
        LinearLayout filterRow=row();Spinner types=new Spinner(this);String[] typeOptions={"Both","In","Out"};types.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,typeOptions));types.setSelection(Arrays.asList(typeOptions).indexOf(historyType));filterRow.addView(types,new LinearLayout.LayoutParams(0,dp(46),1));Button catsButton=smallFilter(selectedCategoryLabel(selectedHistoryCategories));LinearLayout.LayoutParams catLp=new LinearLayout.LayoutParams(0,dp(46),1);catLp.leftMargin=dp(7);filterRow.addView(catsButton,catLp);catsButton.setOnClickListener(v->showCategoryMultiSelect(true));LinearLayout.LayoutParams filterRowLp=lp(-1,-2);filterRowLp.topMargin=dp(7);filters.addView(filterRow,filterRowLp);
        types.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> a){}public void onItemSelected(AdapterView<?> a,View v,int pos,long id){String selected=typeOptions[pos];if(!selected.equals(historyType)){historyType=selected;selectedHistoryCategories.clear();showPage(2);}}});
        LinearLayout actions=row();Button clear=smallFilter("Clear filters");actions.addView(clear);filters.addView(actions);clear.setOnClickListener(v->{from="";to="";selectedHistoryCategories.clear();historyType="Both";showPage(2);});
        historyList=card(c);refreshHistory();
    }
    private Button smallFilter(String s){Button b=smallButton(s);b.setTextSize(12);b.setTextColor(GREEN);return b;}
    private void pickFilter(boolean start){String old=start?from:to;Calendar c=Calendar.getInstance();try{if(!old.isEmpty())c.setTime(iso.parse(old));}catch(Exception e){}new DatePickerDialog(this,(v,y,m,d)->{Calendar x=Calendar.getInstance();x.set(y,m,d);String value=iso.format(x.getTime());if(start)from=value;else to=value;if(!from.isEmpty()&&!to.isEmpty()&&from.compareTo(to)>0){if(start)to=from;else from=to;}showPage(2);},c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH)).show();}
    private void refreshHistory(){if(historyList==null)return;historyList.removeAllViews();StringBuilder sql=new StringBuilder("SELECT id,entry_type,category,description,amount,date,item,source FROM (SELECT id,'OUT' AS entry_type,category,COALESCE(description,'') AS description,amount,date,COALESCE(item,'') AS item,'' AS source FROM expenses UNION ALL SELECT id,'IN' AS entry_type,source AS category,COALESCE(note,'') AS description,amount,date,'' AS item,source FROM incomes) WHERE 1=1");ArrayList<String>a=new ArrayList<>();if(!from.isEmpty()){sql.append(" AND date>=?");a.add(from);}if(!to.isEmpty()){sql.append(" AND date<=?");a.add(to);}if(historyType.equals("In"))sql.append(" AND entry_type='IN'");else if(historyType.equals("Out"))sql.append(" AND entry_type='OUT'");if(!selectedHistoryCategories.isEmpty()){sql.append(" AND (");boolean firstCategory=true;for(String selected:selectedHistoryCategories){if(!firstCategory)sql.append(" OR ");firstCategory=false;if(selected.startsWith("In — ")){sql.append("(entry_type='IN' AND category=?)");a.add(selected.substring(5));}else if(selected.startsWith("Out — ")){sql.append("(entry_type='OUT' AND category=?)");a.add(selected.substring(6));}}sql.append(")");}sql.append(" ORDER BY date DESC,entry_type DESC,id DESC");Cursor q=db.getReadableDatabase().rawQuery(sql.toString(),a.toArray(new String[0]));if(!q.moveToFirst()){historyList.addView(txt("No matching records found.",14,muted,false));}else do{final long id=q.getLong(0);String type=q.getString(1),cat=q.getString(2),desc=q.getString(3),date=q.getString(5),item=q.getString(6),source=q.getString(7);double amount=q.getDouble(4);boolean isIncome="IN".equals(type);LinearLayout r=row();r.setPadding(0,dp(10),0,dp(10));LinearLayout d=col();d.addView(txt((isIncome?"Money In · ":"")+cat+(item==null||item.isEmpty()?"":" · "+item),14,ink,true));String detail=(!desc.isEmpty()&&!desc.equals(item)?desc+" · ":"")+dateLabel(date);d.addView(txt(detail,11,muted,false));r.addView(d,new LinearLayout.LayoutParams(0,-2,1));r.addView(txt((isIncome?"+":"-")+money(amount),14,isIncome?GREEN:0xFFB36A45,true));if(isIncome)r.setOnClickListener(v->editIncome(id,amount,source,date,desc));else r.setOnClickListener(v->editExpense(id,cat,desc,amount,date));historyList.addView(r);View sep=new View(this);sep.setBackgroundColor(line);historyList.addView(sep,lp(-1,1));}while(q.moveToNext());q.close();}
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
        LinearLayout box=col();box.setPadding(dp(20),dp(8),dp(20),dp(8));
        Spinner sp=new Spinner(this);sp.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,cats()));sp.setSelection(Math.max(0,Arrays.asList(cats()).indexOf(cat)));
        Spinner itemSpinner=new Spinner(this);loadExpenseItems(cat,itemSpinner,initialItem);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> a){}
            public void onItemSelected(AdapterView<?> a,View v,int pos,long itemId){
                String selectedCategory=cats()[pos];
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
    private void reminderSettingsPage(){
        pageHeader("Reminder Settings","");
        LinearLayout c=content();
        LinearLayout alerts=card(c);
        alerts.addView(txt("ALERT SOUND",10,GOLD,true));
        alerts.addView(txt("Choose Ringtone",17,ink,true));
        TextView ringtoneName=txt("Tap to choose a ringtone",13,muted,false);
        String savedTone=getPreferences(0).getString("reminder_ringtone_uri","");
        if(!savedTone.isEmpty())try{android.media.Ringtone rt=android.media.RingtoneManager.getRingtone(this,Uri.parse(savedTone));if(rt!=null)ringtoneName.setText(rt.getTitle(this));}catch(Exception ignored){}
        alerts.addView(ringtoneName);
        Button chooseTone=smallFilter("Change Ringtone");alerts.addView(chooseTone,lp(-1,44));
        chooseTone.setOnClickListener(v->{Intent pick=new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);pick.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE,RingtoneManager.TYPE_NOTIFICATION);pick.putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE,"Choose Reminder Ringtone");pick.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT,true);pick.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT,true);String current=getPreferences(0).getString("reminder_ringtone_uri","");if(!current.isEmpty())pick.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,Uri.parse(current));startActivityForResult(pick,903);});
        Switch reminderTune=new Switch(this);reminderTune.setText("Reminder Tune");reminderTune.setTextColor(ink);reminderTune.setChecked(getPreferences(0).getBoolean("reminder_sound_enabled",true));reminderTune.setPadding(0,dp(12),0,0);alerts.addView(reminderTune);
        reminderTune.setOnCheckedChangeListener((button,on)->getPreferences(0).edit().putBoolean("reminder_sound_enabled",on).apply());
        Switch reminderVibration=new Switch(this);reminderVibration.setText("Vibration");reminderVibration.setTextColor(ink);reminderVibration.setChecked(getPreferences(0).getBoolean("reminder_vibration_enabled",true));reminderVibration.setPadding(0,dp(8),0,0);alerts.addView(reminderVibration);
        reminderVibration.setOnCheckedChangeListener((button,on)->getPreferences(0).edit().putBoolean("reminder_vibration_enabled",on).apply());

        LinearLayout repeat=card(c);
        repeat.addView(txt("REPEAT AFTER DISMISS",10,GOLD,true));
        repeat.addView(txt("Show reminder again",17,ink,true));
        repeat.addView(txt("If you swipe a reminder notification away, show it again after the selected time. Tapping the notification will not repeat it.",12,muted,false));
        Spinner repeatSpinner=new Spinner(this);
        String[] repeatOptions={"Off","10 Minutes","30 Minutes","60 Minutes"};
        int[] repeatValues={0,10,30,60};
        repeatSpinner.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,repeatOptions));
        int savedRepeat=getPreferences(0).getInt("reminder_repeat_minutes",0),repeatIndex=0;
        for(int i=0;i<repeatValues.length;i++)if(repeatValues[i]==savedRepeat)repeatIndex=i;
        repeatSpinner.setSelection(repeatIndex);repeat.addView(repeatSpinner,lp(-1,48));
        repeatSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){boolean first=true;public void onNothingSelected(AdapterView<?> parent){}public void onItemSelected(AdapterView<?> parent,View view,int position,long id){if(first){first=false;return;}getPreferences(0).edit().putInt("reminder_repeat_minutes",repeatValues[position]).apply();}});
        Button back=smallFilter("Back To Settings");LinearLayout.LayoutParams backLp=lp(-1,44);backLp.topMargin=dp(8);c.addView(back,backLp);back.setOnClickListener(v->showPage(3));
    }

    private void settingsPage(){
        pageHeader("Settings","Make Saqib Finance feel like yours");
        LinearLayout c=content();
        LinearLayout reminderAlerts=card(c);
        reminderAlerts.addView(txt("REMINDERS",10,GOLD,true));
        reminderAlerts.addView(txt("Reminder Settings",17,ink,true));
        reminderAlerts.addView(txt("Change ringtone, vibration and repeat alerts.",12,muted,false));
        Button openReminderSettings=smallFilter("Open Reminder Settings");
        reminderAlerts.addView(openReminderSettings,lp(-1,44));
        openReminderSettings.setOnClickListener(v->reminderSettingsPage());

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
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){super.onActivityResult(requestCode,resultCode,data);if(requestCode==903&&resultCode==RESULT_OK&&data!=null){Uri picked=data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);getPreferences(0).edit().putString("reminder_ringtone_uri",picked==null?"silent":picked.toString()).apply();reminderSettingsPage();return;}if(requestCode==REQUEST_GOOGLE_SIGN_IN){try{GoogleSignInAccount a=GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException.class);AuthCredential c=GoogleAuthProvider.getCredential(a.getIdToken(),null);FirebaseAuth.getInstance().signInWithCredential(c).addOnSuccessListener(r->{Toast.makeText(this,"Google account connected",Toast.LENGTH_SHORT).show();syncAllCloud();showPage(3);}).addOnFailureListener(e->Toast.makeText(this,"Sign-in failed: "+e.getMessage(),Toast.LENGTH_LONG).show());}catch(ApiException e){Toast.makeText(this,"Google sign-in failed ("+e.getStatusCode()+")",Toast.LENGTH_LONG).show();}}}
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
            for(String cat:cats()){double v=data.get(cat);if(v>0){float sweep=(float)(v/total*360);p.setColor(colors[i%colors.length]);c.drawArc(cx-r,cy-r,cx+r,cy+r,start,sweep,true,p);
                if(sweep>=24){double mid=Math.toRadians(start+sweep/2);float tx=cx+(float)Math.cos(mid)*r*.72f,ty=cy+(float)Math.sin(mid)*r*.72f;
                    p.setColor(Color.WHITE);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(dp(9));p.setTypeface(Typeface.create(Typeface.DEFAULT,Typeface.BOLD));c.drawText(Math.round(v/total*100)+"%",tx,ty+dp(3),p);p.setTypeface(Typeface.DEFAULT);}
                start+=sweep;}i++;}
            p.setColor(surface);c.drawCircle(cx,cy,r*.57f,p);p.setColor(ink);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(dp(8));c.drawText("TOTAL",cx,cy-dp(3),p);p.setTextSize(dp(9));c.drawText(money(total),cx,cy+dp(11),p);
        }
    }
    private void remindersPage(){
        pageHeader("Buy Reminders","");
        LinearLayout c=content();
        final String[] chosen={"",""};
        Calendar when=Calendar.getInstance();when.add(Calendar.HOUR_OF_DAY,1);
        LinearLayout categoryScreen=col();c.addView(categoryScreen,lp(-1,-2));
        LinearLayout itemScreen=col();itemScreen.setVisibility(View.GONE);c.addView(itemScreen,lp(-1,-2));
        TextView itemBack=txt("‹  Categories",14,GREEN,true);itemBack.setPadding(0,dp(4),0,dp(10));itemScreen.addView(itemBack);
        TextView itemTitle=txt("",17,ink,true);itemScreen.addView(itemTitle);
        LinearLayout itemGrid=col();itemScreen.addView(itemGrid,lp(-1,-2));
        final LinearLayout[] currentItemRow={null};
        TextView selectedItem=txt("Select An Item",13,muted,true);selectedItem.setPadding(dp(4),dp(12),dp(4),dp(8));itemScreen.addView(selectedItem);
        EditText price=new EditText(this);price.setSingleLine(true);price.setInputType(8194);price.setHint("Estimated Price (PKR)");itemScreen.addView(price,lp(-1,50));
        TextView date=txt(display.format(when.getTime()),14,ink,true);date.setPadding(dp(12),dp(12),dp(12),dp(12));date.setBackground(shape(bg,10));LinearLayout.LayoutParams dateLp=lp(-1,46);dateLp.topMargin=dp(8);itemScreen.addView(date,dateLp);
        date.setOnClickListener(v->{Calendar x=(Calendar)when.clone();new DatePickerDialog(this,(p,y,mo,d)->{when.set(y,mo,d);date.setText(display.format(when.getTime()));},x.get(Calendar.YEAR),x.get(Calendar.MONTH),x.get(Calendar.DAY_OF_MONTH)).show();});
        TextView clock=txt(new SimpleDateFormat("hh:mm a",Locale.ENGLISH).format(when.getTime()),14,ink,true);clock.setPadding(dp(12),dp(12),dp(12),dp(12));clock.setBackground(shape(bg,10));LinearLayout.LayoutParams clockLp=lp(-1,46);clockLp.topMargin=dp(7);itemScreen.addView(clock,clockLp);
        clock.setOnClickListener(v->new TimePickerDialog(this,(p,h,min)->{when.set(Calendar.HOUR_OF_DAY,h);when.set(Calendar.MINUTE,min);when.set(Calendar.SECOND,0);clock.setText(new SimpleDateFormat("hh:mm a",Locale.ENGLISH).format(when.getTime()));},when.get(Calendar.HOUR_OF_DAY),when.get(Calendar.MINUTE),false).show());
        Button doneButton=primaryButton("Done");doneButton.setEnabled(false);LinearLayout.LayoutParams doneLp=lp(-1,48);doneLp.topMargin=dp(12);itemScreen.addView(doneButton,doneLp);

        LinearLayout categoryGrid=col();
        String[] categoryIcons={"🧒","💍","👨‍👩‍👧","🧴","💊","🛒","🏍️","🧾","🛍️","🍽️","📦"};
        int[] categoryTints={0xFFB8D9F8,0xFFF4B8D4,0xFFFFD99A,0xFFF4D7B5,0xFFB9F0D0,0xFFDCC5F4,0xFFBDEBEA,0xFFB8D9F8,0xFFF4B8D4,0xFFC8EDB6,0xFFDCC5F4};
        ArrayList<String> visibleCategories=new ArrayList<>();
        for(String cat:categories)if(!hiddenCategories.contains(cat))visibleCategories.add(cat);
        for(int i=0;i<visibleCategories.size();i+=4){
            LinearLayout r=row();LinearLayout.LayoutParams rp=lp(-1,-2);rp.bottomMargin=dp(8);categoryGrid.addView(r,rp);
            for(int j=i;j<Math.min(i+4,visibleCategories.size());j++){
                final String cat=visibleCategories.get(j);int categoryIndex=Arrays.asList(CATS).indexOf(cat);
                int tint=categoryIndex>=0?categoryTints[categoryIndex]:MINT;
                String icon=categoryIndex>=0?categoryIcons[categoryIndex]:getPreferences(0).getString("category_icon_"+cat,"📦");
                if("Other".equals(cat))icon="+";
                LinearLayout tile=col();tile.setGravity(Gravity.CENTER);tile.setPadding(dp(3),dp(8),dp(3),dp(7));
                GradientDrawable border=shape(surface,19);border.setStroke(dp(1),dark?0xFF344F78:0xFFD5E4FA);tile.setBackground(border);tile.setElevation(dp(2));
                LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(0,dp(76),1);if(j>i)tp.leftMargin=dp(5);r.addView(tile,tp);
                TextView ic=txt(icon,22,ink,true);ic.setGravity(Gravity.CENTER);tile.addView(ic,lp(-1,35));
                TextView nm=txt(cat,10,ink,true);nm.setGravity(Gravity.CENTER);nm.setSingleLine(true);nm.setPadding(0,dp(3),0,0);tile.addView(nm);
                tile.setOnClickListener(v->{
                    if("Other".equals(cat)){showNewCategoryDialog();return;}
                    chosen[0]=cat;chosen[1]="";selectedItem.setText("Select An Item");doneButton.setEnabled(false);
                    categoryScreen.setVisibility(View.GONE);itemScreen.setVisibility(View.VISIBLE);itemTitle.setText(cat);itemGrid.removeAllViews();
                    Cursor q=db.getReadableDatabase().rawQuery("SELECT name,icon FROM category_items WHERE category=? ORDER BY name COLLATE NOCASE",new String[]{cat});
                    int count=0;
                    while(q.moveToNext()){
                        final String name=q.getString(0),itemIcon=q.getString(1)==null||q.getString(1).trim().isEmpty()?"📦":q.getString(1);
                        if(count%2==0){currentItemRow[0]=row();LinearLayout.LayoutParams irp=lp(-1,-2);irp.topMargin=dp(4);itemGrid.addView(currentItemRow[0],irp);}
                        LinearLayout item=col();item.setGravity(Gravity.CENTER);item.setPadding(dp(8),dp(12),dp(8),dp(12));item.setBackground(shape(MINT,14));
                        LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(0,dp(92),1);if(count%2==1)ip.leftMargin=dp(8);currentItemRow[0].addView(item,ip);
                        addItemIcon(item,itemIcon,25,lp(-1,36));
                        TextView label=txt(name,13,ink,true);label.setGravity(Gravity.CENTER);label.setMaxLines(2);item.addView(label);
                        item.setOnClickListener(w->{chosen[1]=name;selectedItem.setText(itemIcon+"  "+name);doneButton.setEnabled(true);});
                        count++;
                    }
                    q.close();if(count==0)itemGrid.addView(txt("No items in this category yet.",13,muted,false));
                });
            }
        }
        categoryScreen.addView(categoryGrid,lp(-1,-2));
        itemBack.setOnClickListener(v->{itemScreen.setVisibility(View.GONE);categoryScreen.setVisibility(View.VISIBLE);});

        doneButton.setOnClickListener(v->{
            if(chosen[1].isEmpty()){Toast.makeText(this,"Select an item first",Toast.LENGTH_SHORT).show();return;}
            if(when.getTimeInMillis()<=System.currentTimeMillis()){Toast.makeText(this,"Choose a future date and time",Toast.LENGTH_SHORT).show();return;}
            double estimate=0;String raw=price.getText().toString().trim();
            if(!raw.isEmpty())try{estimate=Double.parseDouble(raw);if(estimate<0||Double.isInfinite(estimate)||Double.isNaN(estimate))throw new Exception();}catch(Exception e){price.setError("Enter a valid amount");return;}
            if(android.os.Build.VERSION.SDK_INT>=31&&!getSystemService(android.app.AlarmManager.class).canScheduleExactAlarms()){try{startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,android.net.Uri.parse("package:"+getPackageName())));}catch(Exception e){}Toast.makeText(this,"Allow alarms, then add reminder again",Toast.LENGTH_LONG).show();return;}
            if(android.os.Build.VERSION.SDK_INT>=33&&checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=android.content.pm.PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},901);Toast.makeText(this,"Allow notifications, then add reminder again",Toast.LENGTH_LONG).show();return;}
            ContentValues cv=new ContentValues();cv.put("item",chosen[1]);cv.put("estimated_amount",estimate);cv.put("trigger_at",when.getTimeInMillis());cv.put("done",0);
            long id=db.getWritableDatabase().insert("reminders",null,cv);
            if(id>0){pushReminder(id);ReminderReceiver.schedule(this,id,chosen[1],when.getTimeInMillis());Toast.makeText(this,"Reminder added",Toast.LENGTH_SHORT).show();showPage(4);}
        });
    }

    static class DB extends SQLiteOpenHelper{DB(Context c){super(c,"saqib_finance.db",null,7);}public void onCreate(SQLiteDatabase d){d.execSQL("CREATE TABLE expenses(id INTEGER PRIMARY KEY AUTOINCREMENT,category TEXT NOT NULL,description TEXT NOT NULL,amount REAL NOT NULL,date TEXT NOT NULL,item TEXT)");d.execSQL("CREATE TABLE incomes(id INTEGER PRIMARY KEY AUTOINCREMENT,amount REAL NOT NULL,source TEXT NOT NULL,date TEXT NOT NULL,note TEXT NOT NULL DEFAULT '')");d.execSQL("CREATE TABLE reminders(id INTEGER PRIMARY KEY AUTOINCREMENT,item TEXT NOT NULL,trigger_at INTEGER NOT NULL,done INTEGER NOT NULL DEFAULT 0,estimated_amount REAL NOT NULL DEFAULT 0,expense_id INTEGER)");d.execSQL("CREATE TABLE category_items(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,category TEXT NOT NULL,icon TEXT NOT NULL DEFAULT '📦',UNIQUE(name,category))");d.execSQL("CREATE TABLE expense_drafts(id INTEGER PRIMARY KEY AUTOINCREMENT,category TEXT NOT NULL,item TEXT NOT NULL,amount REAL NOT NULL,date TEXT NOT NULL)");}public void onUpgrade(SQLiteDatabase d,int o,int n){if(o<2)d.execSQL("CREATE TABLE IF NOT EXISTS reminders(id INTEGER PRIMARY KEY AUTOINCREMENT,item TEXT NOT NULL,trigger_at INTEGER NOT NULL,done INTEGER NOT NULL DEFAULT 0)");if(o<3){d.execSQL("ALTER TABLE reminders ADD COLUMN estimated_amount REAL NOT NULL DEFAULT 0");d.execSQL("ALTER TABLE reminders ADD COLUMN expense_id INTEGER");}if(o<4){d.execSQL("CREATE TABLE IF NOT EXISTS category_items(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,category TEXT NOT NULL,icon TEXT NOT NULL DEFAULT '📦',UNIQUE(name,category))");}if(o<5){d.execSQL("ALTER TABLE expenses ADD COLUMN item TEXT");d.execSQL("UPDATE expenses SET item=(SELECT name FROM category_items WHERE category_items.category=expenses.category AND category_items.name=expenses.description LIMIT 1) WHERE item IS NULL");}if(o<6){d.execSQL("CREATE TABLE IF NOT EXISTS incomes(id INTEGER PRIMARY KEY AUTOINCREMENT,amount REAL NOT NULL,source TEXT NOT NULL,date TEXT NOT NULL,note TEXT NOT NULL DEFAULT '')");}if(o<7){d.execSQL("CREATE TABLE IF NOT EXISTS expense_drafts(id INTEGER PRIMARY KEY AUTOINCREMENT,category TEXT NOT NULL,item TEXT NOT NULL,amount REAL NOT NULL,date TEXT NOT NULL)");}}public void onOpen(SQLiteDatabase d){super.onOpen(d);d.execSQL("CREATE TABLE IF NOT EXISTS expense_drafts(id INTEGER PRIMARY KEY AUTOINCREMENT,category TEXT NOT NULL,item TEXT NOT NULL,amount REAL NOT NULL,date TEXT NOT NULL)");}}}
