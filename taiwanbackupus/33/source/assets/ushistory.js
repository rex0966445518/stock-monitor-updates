// Resolve the most recent completed US session, including weekends and holidays.
function latestUSSession(cut){
 for(let d=day(cut,'America/New_York'),n=0;n<370;n++,d=shift(d,-1)){
  const list=holidays[d.slice(0,4)];if(!list)throw Error('美股交易日曆待確認：'+d);
  const wd=new Date(d+'T12:00:00Z').getUTCDay();
  if(wd!==0&&wd!==6&&!list.split(' ').includes(d.slice(5))&&equityClose(d)<=cut)return d;
 }
 throw Error('找不到已收盤的美股交易日');
}
function completedUSReturn(bars,cut){
 const date=latestUSSession(cut),previous=latestUSSession(equityClose(date)-1);
 const a=bars.find(x=>x.date===previous),b=bars.find(x=>x.date===date);
 if(!a||!b||!(a.c>0)||!(b.c>0))throw Error('缺美股 '+(!b?date:previous)+' 收盤資料');
 return {r:ret(b.c,a.c),date};
}
function historicalNQWindow(cut){
 const date=latestUSSession(cut),base=equityClose(date);
 // The session's following Taipei morning, never the next reopening.
 const end=at(shift(date,1),'05:10');
 return {date,base,cut:Math.min(cut,end),carried:end<cut};
}
// Only completed bars ending at the required cutoff qualify; never use later bars.
function nqWeekendClosed(time){
 const parts=new Intl.DateTimeFormat('en-US',{timeZone:'America/Chicago',weekday:'short',hour:'2-digit',minute:'2-digit',hourCycle:'h23'}).formatToParts(time);
 const get=k=>parts.find(p=>p.type===k)?.value,wd=get('weekday'),m=Number(get('hour'))*60+Number(get('minute'));
 return wd==='Sat'||wd==='Sun'&&m<17*60||wd==='Fri'&&m>=16*60;
}
function nqPair(bars,base,cut,minutes,tolerance=0){
 const span=minutes*60000;
 const get=time=>bars.filter(x=>fin(x.c)&&x.c>0&&x.t+span<=time&&time-x.t-span<=tolerance).sort((a,b)=>a.t-b.t).pop();
 const a=get(base),b=get(cut);if(!a||!b)throw Error('缺 '+taipeiStamp(!a?base:cut)+' 截止的完整 '+minutes+' 分鐘K線');
 if(a.t>=b.t)throw Error('兩個截點未取得獨立行情');return {base:a.c,close:b.c,value:ret(b.c,a.c),observedBase:a.t+span,observedCut:b.t+span};
}
async function historicalNQ(target,base,cut,series){
 if(nqWeekendClosed(cut)){const e=Error('指定截點 '+taipeiStamp(cut)+'（台灣）為 CME 週末休市；該時點無成交，不使用開盤後資料補算');e.marketClosed=true;throw e}
 const key='nq-cutoff-pair-v1-'+target+'-'+base+'-'+cut,attempts=[];
 for(const [interval,minutes] of [['1m',1],['5m',5]])try{
 const result=nqPair(await series('NQ=F',interval),base,cut,minutes);
 const row={...result,source:'Yahoo · NQ '+minutes+' 分鐘K線',backup:minutes!==1,baseTime:base,cutTime:cut,symbol:'NQ=F'};
 try{localStorage.setItem(key,JSON.stringify(row))}catch{}return row;
 }catch(e){attempts.push('Yahoo '+interval+'：'+e.message)}
 // Request the exact overnight window separately from Yahoo's default range.
 for(const minutes of [1,5])try{
 const params=new URLSearchParams({period1:String(Math.floor((base-15*60000)/1000)),period2:String(Math.ceil((cut+60000)/1000)),interval:minutes+'m',includePrePost:'true'});
 let error;
 for(const host of ['query1','query2'])try{
 const raw=await json('https://'+host+'.finance.yahoo.com/v8/finance/chart/NQ%3DF?'+params),chart=raw.chart?.result?.[0];
 if(chart?.meta?.symbol!=='NQ=F')throw Error('缺 NQ 期貨行情');
 const bars=(chart.timestamp||[]).map((s,i)=>({t:s*1000,c:chart.indicators?.quote?.[0]?.close?.[i]}));
 const r=nqPair(bars,base,cut,minutes,120000);
 const row={...r,source:'Yahoo・指定時段 '+minutes+' 分K'+(r.observedBase!==base||r.observedCut!==cut?'（截點前 2 分鐘內暫估）':''),backup:true,baseTime:base,cutTime:cut,symbol:'NQ=F'};
 try{localStorage.setItem(key,JSON.stringify(row))}catch{}return row;
 }catch(e){error=e}
 throw error;
 }catch(e){attempts.push('指定時段 '+minutes+'m：'+e.message)}
 try{const c=JSON.parse(localStorage.getItem(key));if(c?.symbol==='NQ=F'&&c.baseTime===base&&c.cutTime===cut&&fin(c.base)&&c.base>0&&fin(c.close)&&c.close>0)return {...c,value:ret(c.close,c.base),source:c.source+'・已驗證本機留存',backup:true}}catch{}
 attempts.push('本機留存：無符合日期及截點資料','TradingView：尚無可用的自動歷史行情接口','其他授權資料商：尚未設定授權來源');
 throw Error(attempts.join('；'));
}