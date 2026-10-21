package com.rex.mitakecompanion.portfolio;

import android.content.*;
import org.json.*;

/** Candidate observation metadata is never inserted as an invented personal holding. */
public final class CandidateCapture {
    public static final String EXTRA="candidate",RUN="run_id";
    private CandidateCapture(){}
    public static JSONObject target(JSONObject row)throws Exception{
        int matched=row.optInt("matched");String code=row.optString("code");
        if(row.optBoolean("demo")||matched<5||matched>6||row.optInt("total",6)!=6||!PortfolioStore.code(code))throw new IllegalArgumentException("只有真實5/6、6/6卡片可啟動自動分析");
        JSONObject target=new JSONObject().put("code",code).put("name",row.optString("name")).put("shares","").put("cost","").put("price","").put("matched",matched).put("peak",row.optInt("peak",matched)).put("scan_ts",row.optDouble("ts",0));
        double price=row.optDouble("price");if(Double.isFinite(price)&&price>0)target.put("price",Double.toString(price));
        return target;
    }
    public static Intent intent(Context context,JSONObject row)throws Exception{
        target(row);return new Intent(context,CandidateCaptureActivity.class).putExtra(EXTRA,row.toString());
    }
    public static Intent testIntent(Context context,String code,String name)throws Exception{
        JSONObject row=new JSONObject().put("code",code).put("name",name).put("matched",5).put("total",6).put("capture_test",true);return intent(context,row);
    }
    public static JSONObject newRun(PortfolioStore store,JSONObject row)throws Exception{
        JSONObject run=store.newRun(target(row));run.put("candidate",new JSONObject(row.toString())).put("source",row.optBoolean("capture_test")?"manual_capture_test":"radar_candidate").put("web_mode","google_ai_images").put("auto_analyze",false).put("auto_web",true).put("status","準備收集六頁並交由Google搜尋AI模式分析");store.updateRun(run);return run;
    }
}
