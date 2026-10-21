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
    public static JSONObject newRun(PortfolioStore store,JSONObject row)throws Exception{
        JSONObject run=store.newRun(target(row));run.put("candidate",new JSONObject(row.toString())).put("source","radar_candidate").put("auto_analyze",false).put("auto_web",true).put("status","準備收集六頁並查詢Google網頁");store.updateRun(run);return run;
    }
}
