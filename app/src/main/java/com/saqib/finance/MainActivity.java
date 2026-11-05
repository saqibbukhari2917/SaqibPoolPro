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

public class MainActivity extends Activity {
    private static final int GREEN=Color.rgb(20,75,59), DEEP=Color.rgb(12,49,39),
            MINT=Color.rgb(222,242,231), BG=Color.rgb(246,248,245),
            INK=Color.rgb(31,42,36), MUTED=Color.rgb(111,124,115);
    private static final String[] CATEGORIES={"Kids","Wife","Family","Medicine","Grocery","Transport","Bills","Shopping","Dining","Other"};
    private DB db;
    private LinearLayout root,list;
    private TextView monthLabel,totalLabel,countLabel;
    private ChartView chart;
    private Calendar month=Calendar.getInstance();
    private final SimpleDateFormat dateFmt=new SimpleDateFormat("dd MMM yyyy",Locale.ENGLISH);
    private final SimpleDateFormat isoFmt=new SimpleDateFormat("yyyy-MM-dd",Locale.US);
    private final SimpleDateFormat keyFmt=new SimpleDateFormat("yyyy-MM",Locale.ENGLISH);

    @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().setStatusBarColor(DEEP);getWindow().setNavigationBarColor(DEEP);db=new DB(this);render();}
    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private GradientDrawable bg(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(null,1);return t;}
    private LinearLayout vertical(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);return l;}
    private LinearLayout horizontal(){LinearLayout l=new LinearLayout(this);l.setOrientation(0);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h));}
    private String money(double n){return "Rs. "+String.format(Locale.US,"%,.0f",n);}
    private String displayDate(String iso){try{return dateFmt.format(isoFmt.parse(iso));}catch(Exception e){return iso;}}

    private void render(){
        root=vertical();root.setBackgroundColor(BG);ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(root);setContentView(scroll);
        LinearLayout header=vertical();header.setPadding(dp(22),dp(24),dp(22),dp(22));header.setBackground(bg(DEEP,0));
        LinearLayout brand=horizontal();TextView mark=text("SF",16,GREEN,true);mark.setGravity(Gravity.CENTER);mark.setBackground(bg(MINT,14));brand.addView(mark,lp(46,46));
        LinearLayout name=vertical();name.setPadding(dp(12),0,0,0);name.addView(text("Saqib Finance",23,Color.WHITE,true));name.addView(text("YOUR FAMILY EXPENSE TRACKER",10,0xFFB8D8C7,true));brand.addView(name,new LinearLayout.LayoutParams(0,-2,1));header.addView(brand);
        TextView overview=text("MONTHLY OVERVIEW",11,0xFFB8D8C7,true);overview.setPadding(0,dp(26),0,dp(7));header.addView(overview);
        totalLabel=text("Rs. 0",30,Color.WHITE,true);header.addView(totalLabel);countLabel=text("0 purchases recorded",12,0xFFD0E2D8,false);countLabel.setPadding(0,dp(3),0,0);header.addView(countLabel);
        LinearLayout monthRow=horizontal();monthRow.setPadding(0,dp(18),0,0);Button prev=navButton("‹");prev.setOnClickListener(v->{month.add(Calendar.MONTH,-1);render();});Button next=navButton("›");next.setOnClickListener(v->{month.add(Calendar.MONTH,1);render();});
        monthLabel=text(new SimpleDateFormat("MMMM yyyy",Locale.ENGLISH).format(month.getTime()),16,Color.WHITE,true);monthLabel.setGravity(Gravity.CENTER);monthRow.addView(prev,lp(42,38));monthRow.addView(monthLabel,new LinearLayout.LayoutParams(0,dp(38),1));monthRow.addView(next,lp(42,38));header.addView(monthRow);root.addView(header);
        LinearLayout content=vertical();content.setPadding(dp(18),dp(18),dp(18),dp(26));root.addView(content);
        LinearLayout add=horizontal();add.addView(text("Spending breakdown",18,INK,true),new LinearLayout.LayoutParams(0,-2,1));Button addBtn=new Button(this);addBtn.setText("+ Add expense");addBtn.setTextSize(12);addBtn.setTextColor(Color.WHITE);addBtn.setAllCaps(false);addBtn.setBackground(bg(GREEN,22));addBtn.setPadding(dp(14),0,dp(14),0);add.addView(addBtn,lp(-2,44));addBtn.setOnClickListener(v->showAddDialog());content.addView(add);
        chart=new ChartView(this);LinearLayout chartCard=vertical();chartCard.setBackground(bg(Color.WHITE,18));chartCard.setElevation(dp(2));chartCard.setPadding(dp(14),dp(10),dp(14),dp(12));LinearLayout.LayoutParams cp=lp(-1,250);cp.topMargin=dp(14);content.addView(chartCard,cp);chartCard.addView(chart,new LinearLayout.LayoutParams(-1,-1));
        TextView recent=text("Recent expenses",18,INK,true);LinearLayout.LayoutParams rp=lp(-1,-2);rp.topMargin=dp(23);rp.bottomMargin=dp(10);content.addView(recent,rp);list=vertical();list.setBackground(bg(Color.WHITE,16));list.setPadding(dp(14),dp(4),dp(14),dp(4));content.addView(list);refreshData();
    }
    private Button navButton(String s){Button b=new Button(this);b.setText(s);b.setTextSize(24);b.setTextColor(Color.WHITE);b.setAllCaps(false);b.setPadding(0,0,0,dp(4));b.setBackground(bg(0xFF285747,12));return b;}

    private void refreshData(){
        String key=keyFmt.format(month.getTime());double total=0;int count=0;Map<String,Double>sums=new LinkedHashMap<>();for(String cat:CATEGORIES)sums.put(cat,0d);
        Cursor c=db.getReadableDatabase().rawQuery("SELECT category,SUM(amount),COUNT(*) FROM expenses WHERE substr(date,1,7)=? GROUP BY category",new String[]{key});
        while(c.moveToNext()){String cat=c.getString(0);double val=c.getDouble(1);total+=val;count+=c.getInt(2);sums.put(cat,val);}c.close();
        totalLabel.setText(money(total));countLabel.setText(count+" purchase"+(count==1?"":"s")+" recorded");chart.setData(sums,total);list.removeAllViews();
        Cursor rows=db.getReadableDatabase().rawQuery("SELECT id,category,description,amount,date FROM expenses WHERE substr(date,1,7)=? ORDER BY date DESC,id DESC",new String[]{key});
        if(!rows.moveToFirst()){TextView empty=text("No expenses yet. Tap + Add expense to get started.",14,MUTED,false);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(10),dp(28),dp(10),dp(28));list.addView(empty);}
        else do{final long id=rows.getLong(0);String cat=rows.getString(1),desc=rows.getString(2),date=rows.getString(4);double amount=rows.getDouble(3);
            LinearLayout row=horizontal();row.setPadding(0,dp(12),0,dp(12));TextView icon=text(categoryIcon(cat),19,GREEN,true);icon.setGravity(Gravity.CENTER);icon.setBackground(bg(MINT,13));row.addView(icon,lp(44,44));
            LinearLayout detail=vertical();detail.setPadding(dp(12),0,0,0);detail.addView(text(desc.isEmpty()?cat:cat+"  ·  "+desc,14,INK,true));detail.addView(text(displayDate(date),11,MUTED,false));row.addView(detail,new LinearLayout.LayoutParams(0,-2,1));
            LinearLayout amountBox=vertical();amountBox.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);amountBox.addView(text(money(amount),14,INK,true));TextView edit=text("Edit / Delete",10,GREEN,true);edit.setPadding(0,dp(4),0,0);amountBox.addView(edit);row.addView(amountBox);row.setOnClickListener(v->showEditDialog(id,cat,desc,amount,displayDate(date)));list.addView(row);View line=new View(this);line.setBackgroundColor(0xFFECEFEC);list.addView(line,lp(-1,1));
        }while(rows.moveToNext());rows.close();
    }
    private String categoryIcon(String c){switch(c){case "Kids":return "K";case "Wife":return "W";case "Family":return "F";case "Medicine":return "+";case "Grocery":return "G";case "Transport":return "T";case "Bills":return "B";case "Shopping":return "S";case "Dining":return "D";default:return "•";}}
    private void showAddDialog(){showExpenseDialog(0,"Family","","","",false);}
    private void showEditDialog(long id,String cat,String desc,double amount,String date){showExpenseDialog(id,cat,desc,String.valueOf(amount),date,true);}
    private void showExpenseDialog(long id,String oldCat,String oldDesc,String oldAmount,String oldDate,boolean editing){
        LinearLayout box=vertical();box.setPadding(dp(8),dp(8),dp(8),0);box.addView(text(editing?"Edit expense":"Add an expense",20,INK,true));
        TextView label=text("CATEGORY",10,MUTED,true);label.setPadding(0,dp(16),0,dp(4));box.addView(label);Spinner spinner=new Spinner(this);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,CATEGORIES);spinner.setAdapter(adapter);spinner.setSelection(Math.max(0,Arrays.asList(CATEGORIES).indexOf(oldCat)));box.addView(spinner);
        TextView dl=text("DESCRIPTION (OPTIONAL)",10,MUTED,true);dl.setPadding(0,dp(12),0,dp(4));box.addView(dl);EditText description=new EditText(this);description.setSingleLine(true);description.setHint("Optional — e.g. School shoes");description.setText(oldDesc);box.addView(description);
        TextView al=text("AMOUNT (PKR)",10,MUTED,true);al.setPadding(0,dp(12),0,dp(4));box.addView(al);EditText amount=new EditText(this);amount.setSingleLine(true);amount.setInputType(8194);amount.setHint("0");amount.setText(oldAmount);box.addView(amount);
        TextView dateLabel=text("DATE",10,MUTED,true);dateLabel.setPadding(0,dp(12),0,dp(4));box.addView(dateLabel);TextView dateValue=text(oldDate.isEmpty()?dateFmt.format(new Date()):oldDate,14,INK,true);dateValue.setPadding(dp(12),dp(12),dp(12),dp(12));dateValue.setBackground(bg(BG,10));box.addView(dateValue);
        dateValue.setOnClickListener(v->{Calendar cal=Calendar.getInstance();try{cal.setTime(dateFmt.parse(dateValue.getText().toString()));}catch(Exception ignored){}new DatePickerDialog(this,(view,y,m,d)->{Calendar picked=Calendar.getInstance();picked.set(y,m,d);dateValue.setText(dateFmt.format(picked.getTime()));},cal.get(Calendar.YEAR),cal.get(Calendar.MONTH),cal.get(Calendar.DAY_OF_MONTH)).show();});
        AlertDialog dialog=new AlertDialog.Builder(this).setView(box).setNegativeButton("Cancel",null).setPositiveButton(editing?"Save changes":"Save expense",null).setNeutralButton(editing?"Delete":"",null).create();
        dialog.setOnShowListener(x->{dialog.getButton(-1).setTextColor(GREEN);dialog.getButton(-2).setTextColor(MUTED);if(editing){dialog.getButton(-3).setTextColor(Color.RED);dialog.getButton(-3).setOnClickListener(v->new AlertDialog.Builder(this).setMessage("Delete this expense?").setNegativeButton("Cancel",null).setPositiveButton("Delete",(a,b)->{db.getWritableDatabase().delete("expenses","id=?",new String[]{String.valueOf(id)});dialog.dismiss();refreshData();}).show());}else dialog.getButton(-3).setVisibility(View.GONE);
            dialog.getButton(-1).setOnClickListener(v->{String desc=description.getText().toString().trim(),amt=amount.getText().toString().trim();if(amt.isEmpty()){Toast.makeText(this,"Enter an amount",Toast.LENGTH_SHORT).show();return;}try{double value=Double.parseDouble(amt);if(value<=0||Double.isNaN(value)||Double.isInfinite(value))throw new Exception();Date parsed=dateFmt.parse(dateValue.getText().toString());ContentValues cv=new ContentValues();cv.put("category",spinner.getSelectedItem().toString());cv.put("description",desc);cv.put("amount",value);cv.put("date",isoFmt.format(parsed));if(editing)db.getWritableDatabase().update("expenses",cv,"id=?",new String[]{String.valueOf(id)});else db.getWritableDatabase().insert("expenses",null,cv);dialog.dismiss();refreshData();}catch(Exception e){Toast.makeText(this,"Enter a valid amount and date",Toast.LENGTH_SHORT).show();}});
        });dialog.show();
    }
    private class ChartView extends View{
        private Map<String,Double>data=new LinkedHashMap<>();private double total=0;private final Paint p=new Paint(3);private final int[]colors={0xFF2F8065,0xFFE3A94B,0xFF729AC7,0xFFD97878,0xFF9A83C7,0xFF5EAAA5,0xFFB58A67,0xFF82936B,0xFFCF8DB2,0xFF8B9690};
        ChartView(Context c){super(c);}void setData(Map<String,Double>d,double t){data=new LinkedHashMap<>(d);total=t;invalidate();}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);float w=getWidth(),h=getHeight();if(total<=0){p.setColor(MUTED);p.setTextSize(dp(14));p.setTextAlign(Paint.Align.CENTER);canvas.drawText("Your category chart will appear here",w/2,h/2,p);return;}
            float cx=w*.27f,cy=h*.48f,r=Math.min(w*.22f,h*.37f),start=-90;int i=0;for(String cat:CATEGORIES){double val=data.containsKey(cat)?data.get(cat):0;if(val>0){p.setColor(colors[i%colors.length]);canvas.drawArc(cx-r,cy-r,cx+r,cy+r,start,(float)(val/total*360),true,p);start+=(float)(val/total*360);}i++;}
            p.setColor(Color.WHITE);canvas.drawCircle(cx,cy,r*.57f,p);p.setColor(INK);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(dp(10));p.setTypeface(Typeface.DEFAULT_BOLD);canvas.drawText("TOTAL",cx,cy-dp(3),p);p.setTextSize(dp(12));canvas.drawText(money(total),cx,cy+dp(15),p);
            float x=w*.52f,y=dp(22);i=0;p.setTextAlign(Paint.Align.LEFT);p.setTypeface(Typeface.DEFAULT);p.setTextSize(dp(10));for(String cat:CATEGORIES){double val=data.containsKey(cat)?data.get(cat):0;if(val>0){p.setColor(colors[i%colors.length]);canvas.drawCircle(x,y-dp(3),dp(4),p);p.setColor(INK);canvas.drawText(cat+"  "+money(val),x+dp(10),y,p);y+=dp(21);}i++;}
        }
    }
    private static class DB extends SQLiteOpenHelper{DB(Context c){super(c,"saqib_finance.db",null,1);}public void onCreate(SQLiteDatabase d){d.execSQL("CREATE TABLE expenses(id INTEGER PRIMARY KEY AUTOINCREMENT,category TEXT NOT NULL,description TEXT NOT NULL,amount REAL NOT NULL,date TEXT NOT NULL)");}public void onUpgrade(SQLiteDatabase d,int oldV,int newV){}}
}
