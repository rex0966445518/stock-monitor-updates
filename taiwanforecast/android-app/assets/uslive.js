// Foreground-only polling. Live rows never overwrite archived closing-price rows.
const usLive={target:null,rows:{},busy:false,last:0,error:''};
function liveJSON(url){return new Promise((resolve,reject)=>{const x=new XMLHttpRequest();x.open('GET',url);x.timeout=8000;x.onload=()=>{try{if(x.status!==200)throw Error('HTTP '+x.status);resolve(JSON.parse(x.responseText))}catch(e){reject(e)}};x.onerror=()=>reject(Error('連線失敗'));x.ontimeout=()=>reject(Error('查詢逾時'));x.send()})}
async function liveChart(symbol,interval='1d'){
 let err;for(const host of ['query1','query2'])try{
 const r=await liveJSON('https://'+host+'.finance.yahoo.com/v8/finance/chart/'+encodeURIComponent(symbol)+'?range=5d&interval='+interval+'&_='+Date.now());
 const c=r.chart?.result?.[0];if(!c)throw Error('無行情');return c;
 }catch(e){err=e}throw err;
}
function liveQuote(c,now){
 const m=c.meta||{},t=m.regularMarketTime*1000,p=m.regularMarketPrice;
 if(!fin(p)||p<=0||!fin(t)||t>now+60000)throw Error('報價時間或價格無效');
 const d=day(t,'America/New_York'),q=c.indicators?.quote?.[0],ts=c.timestamp||[];
 const previous=ts.map((s,i)=>({d:day(s*1000,'America/New_York'),c:q?.close?.[i]})).filter(x=>x.d<d&&fin(x.c)&&x.c>0).pop();
 if(!previous)throw Error('缺前一交易日收盤');
 const close=equityClose(d),closed=now>=close&&t>=close-120000;
 if(!closed&&now-t>120000)throw Error('報價延遲超過 2 分鐘，暫不納入動態評分');
 return {p,t,d,r:ret(p,previous.c),closed};
}
function liveRows(snapshot,now=Date.now()){
 const rows=snapshot.rows.slice();
 if(usLive.target!==snapshot.cal.target||now>=at(snapshot.cal.target,'05:10'))return rows;
 for(const i of [4,5,6,7]){const r=usLive.rows[i];if(r){rows[i]=now-r.checked<=30000?r:{v:NaN,source:'Yahoo · 動態行情',info:'更新中斷，暫停計分'};}}
 return rows;
}
async function pollUS(){
 if(document.hidden||usLive.busy||!snap||Date.now()>=at(snap.cal.target,'05:10'))return;
 usLive.busy=true;const cal={...snap.cal},target=cal.target;
 try{
 const settled=await Promise.allSettled(['TSM','^SOX','^NDX','^GSPC','NQ=F'].map((s,i)=>liveChart(s,i===4?'1m':'1d')));
 const now=Date.now(),out={},quote=i=>{const r=settled[i];if(r.status!=='fulfilled')throw r.reason;const q=liveQuote(r.value,now);if(q.d<cal.prev||q.d>=target)throw Error('尚無本期美股行情');return q};
 const put=(i,fn)=>{try{const a=fn();out[i]={...a,source:'Yahoo · 每 10 秒查詢',checked:now,live:true}}catch(e){out[i]={v:NaN,info:e.message,source:'Yahoo · 每 10 秒查詢',checked:now,live:true}}};
 const pair=(a,b)=>{if(a.d!==b.d||Math.abs(a.t-b.t)>120000)throw Error('報價時間不同步，等待更新')};
 const info=q=>'報價 '+new Date(q.t).toLocaleTimeString('zh-TW',{timeZone:'Asia/Taipei',hour12:false})+' · '+(q.closed?'收盤參考':'盤中暫估');
 put(5,()=>{const q=quote(1);return {v:level(q.r,.5,1.5),date:q.d,info:fmt(q.r)+'% · '+info(q)}});
 put(4,()=>{const a=quote(0),b=quote(1);pair(a,b);return {v:level(a.r-b.r,.5,1.5),date:a.d,info:fmt(a.r-b.r)+' 百分點 · '+info(a)}});
 put(6,()=>{const a=quote(2),b=quote(3);pair(a,b);return {v:agree(level(a.r,.3,.8),level(b.r,.3,.8)),date:a.d,info:fmt(a.r)+'%／'+fmt(b.r)+'% · '+info(a)}});
 put(7,()=>{
 const r=settled[4];if(r.status!=='fulfilled')throw r.reason;const c=r.value,m=c.meta||{},t=m.regularMarketTime*1000,p=m.regularMarketPrice;
 if(!fin(p)||p<=0||!fin(t)||t>now+60000||now-t>120000)throw Error('期貨報價過期，等待更新');
 const s=quote(3),base=equityClose(s.d);if(now<base)throw Error('等待美股收盤建立期貨比較基準');
 const closes=c.indicators?.quote?.[0]?.close||[],bars=(c.timestamp||[]).map((v,i)=>({t:v*1000,c:closes[i]}));
 const b=bars.filter(x=>x.t+60000<=base&&x.t>=base-180000&&fin(x.c)).pop();if(!b||t<base)throw Error('缺美股收盤時的期貨基準');
 const v=ret(p,b.c);return {v:level(v,.15,.4),date:target,info:fmt(v)+'%（相對美股收盤） · 報價 '+taipeiStamp(t)+' · 動態暫估'};
 });
 if(snap?.cal.target===target){usLive.target=target;usLive.rows=out;usLive.last=now;render()}
 }finally{usLive.busy=false}
}
