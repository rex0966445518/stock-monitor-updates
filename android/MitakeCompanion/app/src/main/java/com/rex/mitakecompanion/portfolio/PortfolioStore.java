package com.rex.mitakecompanion.portfolio;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.*;

/** Private, atomic storage. Holdings require user confirmation; no OCR row is committed silently. */
public final class PortfolioStore {
    public static final String[] PAGES={"K線","籌碼日報","法人","多空","主力","大戶"};
    private final Context context;
    private static PortfolioStore instance;
    public static synchronized PortfolioStore get(Context c){if(instance==null)instance=new PortfolioStore(c);return instance;}
    private PortfolioStore(Context c){context=c.getApplicationContext();}
    public File root(){File f=new File(context.getFilesDir(),"portfolio");if(!f.exists()&&!f.mkdirs())throw new IllegalStateException("無法建立股倉資料夾");return f;}
    public synchronized JSONObject read()throws Exception{
        AtomicFile f=new AtomicFile(new File(root(),"portfolio.json"));
        if(!f.getBaseFile().exists()&&!new File(root(),"portfolio.json.bak").exists())return new JSONObject().put("holdings",new JSONArray()).put("runs",new JSONArray());
        return new JSONObject(new String(f.readFully(),StandardCharsets.UTF_8));
    }
    private synchronized void write(JSONObject value)throws Exception{
        AtomicFile f=new AtomicFile(new File(root(),"portfolio.json"));FileOutputStream out=null;
        try{out=f.startWrite();out.write(value.toString().getBytes(StandardCharsets.UTF_8));f.finishWrite(out);}catch(Exception e){if(out!=null)f.failWrite(out);throw e;}
    }
    public static boolean code(String s){return s!=null&&s.matches("[0-9]{4,6}[A-Z]?");}
    public static String number(String s,boolean integer,boolean zeroAllowed){
        if(s==null||s.trim().isEmpty())return "";
        try{String input=s.trim();if(input.length()>40||!input.matches("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]+)?"))throw new Exception();BigDecimal d=new BigDecimal(input.replace(",",""));
            if(d.signum()<0||(!zeroAllowed&&d.signum()==0)||d.precision()>16||d.scale()>6||integer&&d.stripTrailingZeros().scale()>0)throw new Exception();
            return d.stripTrailingZeros().toPlainString();
        }catch(Exception e){throw new IllegalArgumentException(integer?"股數請輸入整數，單位為股":"價格請輸入有效的非負數");}
    }
    public static JSONObject holding(String code,String name,String shares,String cost,String price)throws Exception{
        code=code.trim().toUpperCase(Locale.ROOT);if(!code(code))throw new IllegalArgumentException("請核對4至6碼股號，可帶一個英文字尾");
        name=name.trim();if(name.isEmpty()||name.length()>40)throw new IllegalArgumentException("請核對股名");
        String n=number(shares,true,false);if(n.isEmpty())throw new IllegalArgumentException("請填股數，1張請填1000股");
        return new JSONObject().put("code",code).put("name",name).put("shares",n).put("cost",number(cost,false,true)).put("price",number(price,false,true));
    }
    public synchronized void saveHolding(JSONObject h)throws Exception{
        holding(h.getString("code"),h.getString("name"),h.getString("shares"),h.optString("cost"),h.optString("price"));
        JSONObject db=read();JSONArray a=db.getJSONArray("holdings");boolean replaced=false;
        for(int i=0;i<a.length();i++)if(a.getJSONObject(i).getString("code").equals(h.getString("code"))){a.put(i,h);replaced=true;break;}
        if(!replaced)a.put(h);write(db);
    }
    public synchronized void deleteHolding(String code)throws Exception{
        JSONObject db=read();JSONArray a=db.getJSONArray("holdings");for(int i=a.length()-1;i>=0;i--)if(a.getJSONObject(i).getString("code").equals(code))a.remove(i);write(db);
    }
    public synchronized JSONObject newRun(JSONObject h)throws Exception{
        JSONObject run=new JSONObject().put("id",UUID.randomUUID().toString()).put("holding",new JSONObject(h.toString())).put("created",System.currentTimeMillis()).put("pages",new JSONObject()).put("status","等待圖片");
        JSONObject db=read();db.getJSONArray("runs").put(run);write(db);return run;
    }
    public synchronized JSONObject run(String id)throws Exception{
        if(!id.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("分析識別碼無效");
        JSONArray a=read().getJSONArray("runs");for(int i=0;i<a.length();i++)if(a.getJSONObject(i).getString("id").equals(id))return a.getJSONObject(i);throw new IllegalArgumentException("找不到本次分析");
    }
    public synchronized void updateRun(JSONObject run)throws Exception{
        JSONObject db=read();JSONArray a=db.getJSONArray("runs");for(int i=0;i<a.length();i++)if(a.getJSONObject(i).getString("id").equals(run.getString("id"))){a.put(i,run);write(db);return;}throw new IllegalArgumentException("分析已刪除");
    }
    public File imageFile(String run,String page)throws Exception{
        if(!run.matches("[a-f0-9-]{36}")||!Arrays.asList(PAGES).contains(page))throw new IllegalArgumentException("圖片分類無效");
        File dir=new File(root(),run);if(!dir.exists()&&!dir.mkdirs())throw new IOException("無法儲存圖片");return new File(dir,Arrays.asList(PAGES).indexOf(page)+".jpg");
    }
    public synchronized void status(String id,String text)throws Exception{JSONObject r=run(id);r.put("status",text);updateRun(r);}
    public synchronized void attach(String id,String page,File image,String source,String ocr)throws Exception{
        if(!image.getCanonicalFile().equals(imageFile(id,page).getCanonicalFile()))throw new IllegalArgumentException("圖片位置無效");
        JSONObject r=run(id);r.getJSONObject("pages").put(page,new JSONObject().put("path",image.getAbsolutePath()).put("source",source).put("saved",System.currentTimeMillis()).put("ocr",ocr));
        r.remove("analysis");r.remove("analyzed");if(r.has("bundle")){new File(image.getParentFile(),"six-pages.zip").delete();r.remove("bundle");r.remove("bundled");}r.put("status","圖片已更新，請檢查日期與股號");updateRun(r);
    }
    public synchronized void deleteRun(String id)throws Exception{
        if(!id.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("分析識別碼無效");
        JSONObject db=read();JSONArray a=db.getJSONArray("runs");for(int i=a.length()-1;i>=0;i--)if(a.getJSONObject(i).getString("id").equals(id))a.remove(i);write(db);
        File dir=new File(root(),id);File[] fs=dir.listFiles();if(fs!=null)for(File f:fs)if(!f.delete())throw new IOException("部分圖片未能刪除");if(dir.exists()&&!dir.delete())throw new IOException("資料夾未能刪除");
    }
    public static String pnl(JSONObject h){try{
        if(h.optString("cost").isEmpty()||h.optString("price").isEmpty())return "損益待核對";
        return new BigDecimal(h.getString("price")).subtract(new BigDecimal(h.getString("cost"))).multiply(new BigDecimal(h.getString("shares"))).stripTrailingZeros().toPlainString()+" 元（未扣交易費用）";
    }catch(Exception e){return "損益待核對";}}
    public static JSONArray parseExtract(String text)throws Exception{
        JSONObject response=new JSONObject(text);JSONArray rows=response.getJSONArray("holdings");if(rows.length()==0||rows.length()>100)throw new IllegalArgumentException("未辨識到可核對的持股，請改用手動新增");
        JSONArray result=new JSONArray();Set<String> seen=new HashSet<>();
        for(int i=0;i<rows.length();i++){
            JSONObject r=rows.getJSONObject(i);String c=r.optString("code","").trim().toUpperCase(Locale.ROOT);
            if(!code(c)||!seen.add(c))throw new IllegalArgumentException("辨識出重複或無效股號，請重選圖片或手動新增");
            JSONObject clean=new JSONObject().put("code",c).put("name",r.optString("name",""));
            for(String f:new String[]{"shares","cost","price"})clean.put(f,r.isNull(f)?"":number(r.optString(f,""),f.equals("shares"),!f.equals("shares")));
            clean.put("source","截圖辨識，待使用者核對");result.put(clean);
        }return result;
    }
}
