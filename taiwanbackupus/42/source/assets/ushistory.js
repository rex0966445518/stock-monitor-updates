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
 const end=at(shift(date,1),'05:00');
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
 if(base!==cut&&a.t>=b.t)throw Error('兩個截點未取得獨立行情');return {base:a.c,close:b.c,value:ret(b.c,a.c),observedBase:a.t+span,observedCut:b.t+span};
}
async function historicalNQ(target,base,cut){
 const first=await tvAnchor(base),last=cut===base?first:await tvAnchor(cut);
 if(first&&last)return {value:ret(last.price,first.price),observedBase:first.time,observedCut:last.time,source:'TradingView · NQ1!',backup:false};
 try{
 const raw=await wire('https://stock2.finance.sina.com.cn/futures/api/jsonp.php/var%20t=/GlobalFuturesService.getGlobalFuturesMinLine?symbol=NQ');
 return {...sinaNQPair(raw,base,cut),source:'新浪財經 · NQ',backup:true};
 }catch{throw Error('缺少 05:00 評分所需報價，可直接手動填寫漲跌幅。')}
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
