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
 // Recover each observation independently; a single response need not contain both.
 const empty={meta:{symbol:'NQ=F'}};
 let recovered=captureNQ(empty,1,target,base,cut);
 if(recovered)return recovered;
 for(const minutes of [1,5,15])try{
 const chart=await liveChart('NQ=F',minutes+'m');
 recovered=captureNQ(chart,minutes,target,base,cut);
 if(recovered)return recovered;
 const recent=chart.meta?.regularMarketTime*1000;
 if(fin(recent)&&minutes===1)attempts.push('來源最新報價 '+taipeiStamp(recent)+'（台灣）');
 }catch(e){attempts.push('分時補查：'+e.message)}
 for(const [interval,minutes] of [['1m',1],['5m',5]])try{
 const result=nqPair(await series('NQ=F',interval),base,cut,minutes);
 const row={...result,source:'Yahoo · NQ '+minutes+' 分鐘K線',backup:minutes!==1,baseTime:base,cutTime:cut,symbol:'NQ=F'};
 try{localStorage.setItem(key,JSON.stringify(row))}catch{}return row;
 }catch(e){attempts.push('Yahoo '+interval+'：'+e.message)}
 try{
 const r=await alternateNQ(base,cut);
 const row={...r,symbol:'NQ=F',baseTime:base,cutTime:cut,backup:true};
 try{localStorage.setItem(key,JSON.stringify(row))}catch{}return row;
 }catch(e){attempts.push(e.message)}
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
 attempts.push('本機沒有這兩個截點的留存報價');
 throw Error(attempts.join('；'));
}
function sinaNQPair(raw,base,cut){
 const wrapper=raw.trim().match(/^(?:\/\/[^\n]*\n\s*)?var\s+t\s*=\s*\(([\s\S]*)\)\s*;?$/);
 const data=JSON.parse(wrapper?wrapper[1]:raw);
 const bars=[];
 for(const row of data.minLine_1d||[]){
  if(!Array.isArray(row)||!(num(row[1])>0))continue;
  const stamp=row.find(v=>typeof v==='string'&&/^\d{4}-\d\d-\d\d[ T]\d\d:\d\d(?::\d\d)?$/.test(v));
  if(!stamp)continue;
  const clock=String(row[0]).length===10?row[4]:row[0];
  if(String(clock).slice(0,5)!==stamp.slice(11,16))continue;
  const t=Date.parse(stamp.slice(0,16).replace(' ','T')+':00+08:00');
  if(fin(t))bars.push({t,c:num(row[1])});
 }
 if(!bars.length)throw Error('缺可核對日期的分鐘報價');
 return nqPair(bars,base,cut,1,0);
}
async function alternateNQ(base,cut){
 const month=new Date(base).getUTCMonth();
 const quarter=Math.floor(month/3);
 // During quarterly rollover use only the continuous series.
 const symbols=month%3===2?[]:['NQ'+'HMUZ'[quarter]+String(new Date(base).getUTCFullYear()).slice(-2)+'.CME'];
 const errors=[];
 for(const symbol of symbols)try{
 const params=new URLSearchParams({period1:Math.floor(base/1000)-600,period2:Math.floor(cut/1000)+60,interval:'1m',includePrePost:'true'});
 const r=await liveJSON('https://query1.finance.yahoo.com/v8/finance/chart/'+symbol+'?'+params);
 const c=r.chart?.result?.[0];if(c?.meta?.symbol!==symbol)throw Error('契約不符');
 const bars=(c.timestamp||[]).map((s,i)=>({t:s*1000,c:c.indicators?.quote?.[0]?.close?.[i]}));
 return {...nqPair(bars,base,cut,1,120000),source:'Yahoo・'+symbol+' 分月契約（採用時間見下方）'};
 }catch(e){errors.push(symbol+'：'+e.message)}
 try{
 const raw=await wire('https://stock2.finance.sina.com.cn/futures/api/jsonp.php/var%20t=/GlobalFuturesService.getGlobalFuturesMinLine?symbol=NQ');
 return {...sinaNQPair(raw,base,cut),source:'新浪財經・NQ 分鐘行情'};
 }catch(e){errors.push('新浪 NQ：'+e.message)}
 throw Error(errors.join('；'));
}
function captureNQ(chart,minutes,target,base,cut,now=Date.now()){
 if(chart.meta?.symbol!=='NQ=F')throw Error('回傳商品不符');
 const key='nq-observations-v1-'+target+'-'+base+'-'+cut;
 let pair={};
 try{pair=JSON.parse(localStorage.getItem(key))||{}}catch{}
 function valid(p,end){return fin(p?.p)&&p.p>0&&fin(p.t)&&p.t<=end&&end-p.t<=120000&&p.t<=now}
 function accept(t,p){
 for(const [slot,end] of [['base',base],['cut',cut]]){
 if(valid({t,p},end)&&(!valid(pair[slot],end)||t>pair[slot].t))pair[slot]={t,p};
 }
 }
 const closes=chart.indicators?.quote?.[0]?.close||[];
 const stamps=chart.timestamp||[];
 stamps.forEach((start,i)=>{
 const end=start*1000+minutes*60000;
 if((stamps[i+1]*1000>=end||chart.meta.regularMarketTime*1000>=end)&&end<=now)accept(end,closes[i]);
 });
 accept(chart.meta.regularMarketTime*1000,chart.meta.regularMarketPrice);
 try{localStorage.setItem(key,JSON.stringify(pair))}catch{}
 if(valid(pair.base,base)&&valid(pair.cut,cut)&&pair.base.t<pair.cut.t){
 return {base:pair.base.p,close:pair.cut.p,value:ret(pair.cut.p,pair.base.p),observedBase:pair.base.t,observedCut:pair.cut.t,source:'Yahoo NQ 分時・截點成交或已完成 K 線',backup:true};
 }
 return null;
}