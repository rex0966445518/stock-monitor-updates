const globalLive={target:null,rows:{},busy:false};
function globalDynamicRows(snapshot,now){
 const rows=txDynamicRows(snapshot,now);if(globalLive.target!==snapshot.cal.target||now>=at(snapshot.cal.target,'05:10'))return rows;
 for(const i of [19,20,21]){const r=globalLive.rows[i];if(r)rows[i]=now-r.checked<=30000?r:{v:NaN,live:true,source:'Yahoo · 動態行情',info:'更新中斷，暫停計分'}}return rows;
}
function asianChanges(c,target,now,zone){
 const m=c.meta||{},p=m.regularMarketPrice,t=m.regularMarketTime*1000;
 if(!fin(p)||p<=0||!fin(t)||t>now||now-t>120000||day(t,zone)!==target)throw Error('尚未取得當日日韓開盤行情');
 const q=c.indicators?.quote?.[0]||{},bars=(c.timestamp||[]).map((s,i)=>({d:day(s*1000,zone),o:q.open?.[i],c:q.close?.[i]}));
 const prior=bars.filter(x=>x.d<target&&fin(x.c)&&x.c>0).pop(),today=bars.find(x=>x.d===target&&fin(x.o)&&x.o>0);
 return {previous:ret(p,prior?.c),opening:ret(p,today?.o),t};
}
async function pollGlobal(){
 if(document.hidden||globalLive.busy||!snap||Date.now()>=at(snap.cal.target,'05:10'))return;
 globalLive.busy=true;const cal={...snap.cal},out={},now=Date.now();
 const put=(i,v,info)=>{out[i]={v,info,live:true,checked:Date.now(),date:cal.target,source:'Yahoo · 每 10 秒查詢'}};
 try{
 try{const q=liveQuote(await liveChart('DX-Y.NYB'),Date.now());if(q.d<cal.prev||q.d>=cal.target)throw Error('美元報價日期不符');put(19,-level(q.r,.25,.6),fmt(q.r)+'% · 報價 '+taipeiStamp(q.t)+' · 動態暫估')}catch(e){put(19,NaN,e.message)}
 if(now<at(cal.target,'08:00'))for(const i of [20,21])put(i,NaN,'日韓尚未開盤；本模型 05:10 截止，無當日開盤訊號');
 else try{
 const c=await Promise.all([liveChart('^N225'),liveChart('^KS11')]),a=asianChanges(c[0],cal.target,Date.now(),'Asia/Tokyo'),b=asianChanges(c[1],cal.target,Date.now(),'Asia/Seoul');
 if(Math.abs(a.t-b.t)>120000)throw Error('日韓報價時間不同步');
 for(const [i,k,low,high] of [[20,'previous',.3,.8],[21,'opening',.15,.4]])put(i,agree(level(a[k],low,high),level(b[k],low,high)),fmt(a[k])+'%／'+fmt(b[k])+'% · 報價 '+taipeiStamp(Math.min(a.t,b.t))+' · 動態暫估');
 }catch(e){for(const i of [20,21])put(i,NaN,e.message)}
 if(snap.cal.target===cal.target){globalLive.target=cal.target;globalLive.rows=out;render()}
 }finally{globalLive.busy=false}
}
