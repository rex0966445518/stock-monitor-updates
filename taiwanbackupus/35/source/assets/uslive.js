// Foreground-only polling. Live rows never overwrite archived closing-price rows.
const usLive={target:null,rows:{},busy:false,last:0,error:''};
function liveJSON(url,headers={}){return new Promise((resolve,reject)=>{const x=new XMLHttpRequest();x.open('GET',url);Object.entries(headers).forEach(([k,v])=>x.setRequestHeader(k,v));x.timeout=8000;x.onload=()=>{try{if(x.status!==200)throw Error('HTTP '+x.status);resolve(JSON.parse(x.responseText))}catch(e){reject(e)}};x.onerror=()=>reject(Error('連線失敗'));x.ontimeout=()=>reject(Error('查詢逾時'));x.send()})}
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
 const rows=globalDynamicRows(snapshot,now);
 if(usLive.target!==snapshot.cal.target||now>=at(snapshot.cal.target,'05:10'))return rows;
 for(const i of [4,5,6,7]){const r=usLive.rows[i];if(r){rows[i]=now-r.checked<=30000?r:{v:NaN,source:'Yahoo · 動態行情',info:'更新中斷，暫停計分'};}}
 return rows;
}
const cnbcSymbols = {TSM:'TSM','^SOX':'.SOX','^NDX':'.NDX','^GSPC':'.SPX'};
const fredSeries = {'^SOX':'NASDAQSOX','^NDX':'NASDAQ100','^GSPC':'SP500'};

async function fredClose(symbol,cut){
 const id=fredSeries[symbol], date=latestUSSession(cut);
 const prev=latestUSSession(equityClose(date)-1);
 const csv=await wire('https://fred.stlouisfed.org/graph/fredgraph.csv?id='+id+'&cosd='+prev+'&coed='+date);
 const lines=csv.trim().split(/\r?\n/);
 if(lines.shift()?.split(',')[1]!==id)throw Error('FRED 指數不符');
 const bars=lines.map(line=>{const [date,value]=line.split(',');return {date,c:Number(value)}});
 return {...completedUSReturn(bars,cut),source:'FRED',backup:true};
}

async function cnbcQuote(symbol){
 const id=cnbcSymbols[symbol];
 const data=await liveJSON('https://quote.cnbc.com/quote-html-webservice/restQuote/symbolType/symbol?requestMethod=itv&output=json&symbols='+encodeURIComponent(id));
 const q=data.FormattedQuoteResult?.FormattedQuote?.find(row=>row.symbol===id);
 const p=num(q?.last), prior=num(q?.previous_day_closing);
 const t=Number(q?.last_time_msec)||Date.parse(q?.last_time);
 if(!(p>0&&prior>0&&Number.isFinite(t)))throw Error('CNBC 缺報價、昨收或時間');
 return {p,t,d:day(t,'America/New_York'),r:ret(p,prior),source:'CNBC'};
}

const usMaxLag=20*60000;
function minuteUSQuote(chart,now){
 const date=day(now,'America/New_York'),close=equityClose(date);
 const open=close-((earlyClose.has(date)?3.5:6.5)*3600000);
 const prior=latestUSSession(open-1),priorEnd=equityClose(prior);
 const prices=chart.indicators?.quote?.[0]?.close||[];
 const bars=(chart.timestamp||[]).map((s,i)=>({t:s*1000,p:prices[i]})).filter(b=>fin(b.p)&&b.p>0);
 // Use the closing minute of the preceding session only as the fallback baseline.
 const lastMinute=bars.find(b=>b.t===priorEnd-60000);
 const previous=num(chart.meta?.previousClose)>0?num(chart.meta.previousClose):lastMinute?.p;
 if(!(previous>0))throw Error('分鐘行情缺前一交易日收盤基準');
 const series=bars.filter(b=>b.t>=open&&b.t<close&&b.t+60000<=now)
 .map(b=>({t:b.t+60000,p:b.p}));
 const m=chart.meta||{},t=m.regularMarketTime*1000;
 if(fin(m.regularMarketPrice)&&m.regularMarketPrice>0&&t>=open&&t<=close&&t<=now)series.push({t,p:m.regularMarketPrice});
 series.sort((a,b)=>a.t-b.t);
 const q=series.at(-1);if(!q)throw Error('尚無當日美股一般交易行情');
 return {...q,d:date,r:ret(q.p,previous),previous,series};
}
function alignedUS(a,b){
 if(a.d!==b.d)throw Error('報價交易日不同');
 if(Math.abs(a.t-b.t)<=120000)return [a,b];
 const newer=a.t>b.t?a:b,older=newer===a?b:a;
 const match=newer.series?.filter(p=>p.t<=older.t&&older.t-p.t<=120000).at(-1);
 if(!match||Date.now()-match.t>usMaxLag)throw Error('缺同時段分鐘行情');
 const adjusted={...newer,...match,r:ret(match.p,newer.previous),closed:false};
 return newer===a?[adjusted,b]:[a,adjusted];
}
async function currentUSQuote(symbol,cal){
 const expected=day(Date.now(),'America/New_York');
 const providers=[
 ['Yahoo 分鐘行情',async()=>minuteUSQuote(await liveChart(symbol,'1m'),Date.now())],
 ['CNBC',()=>cnbcQuote(symbol)]
 ];
 if(fredSeries[symbol]&&Date.now()>=equityClose(expected)){
 providers.push(['FRED',async()=>{const q=await fredClose(symbol,Date.now());return {...q,d:q.date,t:equityClose(q.date)}}]);
 }
 const errors=[];
 for(const [source,read] of providers)try{
 const q=await read(),now=Date.now(),close=equityClose(q.d);
 if(q.d!==expected||q.d<cal.prev||q.d>=cal.target||q.t>now+60000)throw Error('報價日期或時間不符');
 const closed=now>=close&&q.t>=close-120000&&q.t<=close+60000;
 if(!closed&&now-q.t>usMaxLag)throw Error('報價延遲超過 20 分鐘');
 if(source==='CNBC'&&(q.t<close-6.5*3600000||q.t>close+60000))throw Error('非一般交易時段');
 return {...q,closed,source};
 }catch(e){errors.push(source+'：'+e.message)}
 throw Error(errors.join('；'));
}
async function pollUS(){
 if(document.hidden||usLive.busy||!snap||Date.now()>=at(snap.cal.target,'05:10'))return;
 usLive.busy=true;const cal={...snap.cal},target=cal.target;
 try{
 const quotes=['TSM','^SOX','^NDX','^GSPC'].map(symbol=>currentUSQuote(symbol,cal));
 async function update(i,get){
 let row;
 try{const a=await get();row={...a,source:(a.source||'Yahoo')+' · 每 10 秒查詢'}}
 catch(e){row={v:NaN,info:e.message,source:i===7?'Yahoo':'美股行情來源'}}
 if(snap?.cal.target!==target||Date.now()>=at(target,'05:10'))return;
 if(usLive.target!==target){usLive.target=target;usLive.rows={}}
 usLive.rows[i]={...row,checked:Date.now(),live:true};render();
 }
 function info(q){
 const lag=Date.now()-q.t;
 return '報價 '+taipeiStamp(q.t)+'（台灣） · '+(q.closed?'收盤參考':lag>120000?'延遲約 '+Math.ceil(lag/60000)+' 分鐘 · 暫估':'盤中暫估');
 }
 async function paired(left,right,calculate){
 const [a,b]=alignedUS(...await Promise.all([quotes[left],quotes[right]]));
 const result=calculate(a,b);return {...result,source:[...new Set([a.source,b.source])].join('／'),date:a.d,info:result.info+' · '+info(a.t<b.t?a:b)};
 }
 await Promise.all([
 update(5,async()=>{const q=await quotes[1];return {source:q.source,date:q.d,v:level(q.r,.5,1.5),info:fmt(q.r)+'% · '+info(q)}}),
 update(4,()=>paired(0,1,(a,b)=>({v:level(a.r-b.r,.5,1.5),info:fmt(a.r-b.r)+' 百分點'}))),
 update(6,()=>paired(2,3,(a,b)=>({v:agree(level(a.r,.3,.8),level(b.r,.3,.8)),info:fmt(a.r)+'%／'+fmt(b.r)+'%'}))),
 update(7,()=>currentNQ(target))
 ]);
 }finally{usLive.busy=false}
}

async function currentNQ(target){
 let err;for(const interval of [1,5])try{return liveFuture(await liveChart('NQ=F',interval+'m'),Date.now(),target,interval)}catch(e){err=e}
 throw err;
}
function liveFuture(c,now,target,minutes=1){
 const window=historicalNQWindow(at(target,'05:10'));
 captureNQ(c,minutes,target,window.base,window.cut,now);
 const m=c.meta||{},closes=c.indicators?.quote?.[0]?.close||[];
 const bars=(c.timestamp||[]).map((v,i)=>({t:v*1000,c:closes[i]})).filter(x=>fin(x.c)&&x.c>0&&x.t<=now);
 const candidates=bars.map(x=>({t:x.t,p:x.c}));
 if(fin(m.regularMarketPrice)&&m.regularMarketPrice>0&&fin(m.regularMarketTime)&&m.regularMarketTime*1000<=now)candidates.push({t:m.regularMarketTime*1000,p:m.regularMarketPrice});
 const q=candidates.sort((a,b)=>a.t-b.t).pop();
 if(!q||now-q.t>20*60000)throw Error('期貨報價延遲超過 20 分鐘，暫停計分');
 let d=day(now,'America/New_York'),base=null;
 for(let n=0;n<7;n++,d=shift(d,-1)){
 const wd=new Date(d+'T12:00:00Z').getUTCDay(),h=holidays[d.slice(0,4)];
 if(!h)throw Error('美股交易日曆待確認');
 if(wd!==0&&wd!==6&&!h.split(' ').includes(d.slice(5))&&equityClose(d)<=q.t){base=equityClose(d);break}
 }
 if(!base)throw Error('缺比較基準時間');
 const b=bars.filter(x=>x.t+minutes*60000<=base&&x.t>=base-(minutes+2)*60000).pop();
 if(!b)throw Error('缺最近已完成美股收盤時的期貨基準');
 const v=ret(q.p,b.c),delay=Math.max(0,Math.floor((now-q.t)/60000));
 return {v:level(v,.15,.4),date:day(q.t),info:fmt(q.p)+' · '+fmt(v)+'%（相對 '+taipeiStamp(base)+' 美股收盤時期貨價） · 報價 '+taipeiStamp(q.t)+' · '+(delay>=2?'延遲約 '+delay+' 分鐘，延遲行情暫估':'動態暫估')};
}