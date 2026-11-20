const day=(ms=Date.now(),zone='Asia/Taipei')=>new Intl.DateTimeFormat('sv-SE',{timeZone:zone,year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date(ms));
const at=(d,t)=>Date.parse(d+'T'+t+':00+08:00'),shift=(d,n)=>day(at(d,'12:00')+n*86400000);
const num=x=>x==null||String(x).trim()===''?NaN:Number(String(x).replace(/[, %]/g,''));
let requests=new Map(),running=0,queue=[];
function wire(url,headers={}){if(requests.has(url))return requests.get(url);const pending=new Promise((resolve,reject)=>{
 const start=()=>{running++;const x=new XMLHttpRequest();x.open('GET',url);x.timeout=15000;Object.entries(headers).forEach(([k,v])=>x.setRequestHeader(k,v));x.onload=()=>x.status===200?resolve(x.responseText):reject(Error('HTTP '+x.status));x.onerror=()=>reject(Error('連線失敗'));x.ontimeout=()=>reject(Error('逾時'));x.onloadend=()=>{running--;queue.shift()?.()};x.send()};if(running<6)start();else queue.push(start)
 });requests.set(url,pending);return pending}
const json=async(url,headers)=>JSON.parse(await wire(url,headers));
async function chart(symbol,interval='1d',target){
 const period=target?'period1='+Math.floor(at(shift(target,interval==='1d'?-365:-5),'00:00')/1000)+'&period2='+Math.floor(at(shift(target,1),'00:00')/1000):'range='+(interval==='1d'?'1y':'5d');
 const path='/v8/finance/chart/'+encodeURIComponent(symbol)+'?'+period+'&interval='+interval;
 let r,lastError;for(const host of ['query1','query2']){try{r=(await json('https://'+host+'.finance.yahoo.com'+path)).chart.result[0];if(r)break}catch(e){lastError=e}}
 if(!r)throw Error(symbol+' 報價未取得（'+(lastError?.message||'回應無報價')+'）');const q=r.indicators.quote[0];
 return (r.timestamp||[]).map((t,i)=>({t:t*1000,date:day(t*1000,r.meta.exchangeTimezoneName),c:q.close[i],o:q.open[i],h:q.high[i],l:q.low[i]})).filter(x=>fin(x.c)&&x.c>0);
}
async function fm(dataset,start,end,id){
 const pars=new URLSearchParams({dataset,start_date:start,end_date:end});if(id)pars.set('data_id',id);
 const tok=document.getElementById('token').value.trim(),r=await json('https://api.finmindtrade.com/api/v4/data?'+pars,tok?{Authorization:'Bearer '+tok}:{});
 if(r.status!==200||!Array.isArray(r.data))throw Error('FinMind '+(r.status||'無資料'));return r.data;
}
const tw=(path,d,extra='')=>json('https://www.twse.com.tw/rwd/zh/'+path+'?response=json&date='+d.replace(/-/g,'')+extra);
const dateNorm=x=>{const m=String(x).match(/(\d{3,4})[-/年](\d{1,2})[-/月](\d{1,2})/);return m?(+m[1]<1900?+m[1]+1911:m[1])+'-'+m[2].padStart(2,'0')+'-'+m[3].padStart(2,'0'):String(x).replace(/^(\d{4})(\d\d)(\d\d)$/,'$1-$2-$3')};
// TWSE 2026 annual calendar, used while the online calendar is loading.
const scheduledClosures=new Set('01-01 02-12 02-13 02-15 02-16 02-17 02-18 02-19 02-20 02-27 02-28 04-03 04-04 04-05 04-06 05-01 06-19 09-25 09-28 10-09 10-10 10-25 10-26 12-25'.split(' ').map(d=>'2026-'+d));
function tradePair(initial,closed=scheduledClosures,verified=new Set(['2026'])){
 const ok=d=>![0,6].includes(new Date(at(d,'12:00')).getUTCDay())&&!closed.has(d);
 let target=initial;
 while(!ok(target))target=shift(target,1);
 let prev=shift(target,-1);
 while(!ok(prev))prev=shift(prev,-1);
 return {target,prev,confirmed:[target,prev].every(d=>verified.has(d.slice(0,4)))};
}
function previewStart(now=Date.now()){
 const today=day(now);
 return now>=at(today,'05:00')?shift(today,1):today;
}
function txClose(cal){return at(shift(cal.prev,1),'05:00')}
async function calendar(){
 const today=day(),initial=Date.now()<at(today,'09:00')?today:shift(today,1),closed=new Set(),verified=new Set();
 for(const year of new Set([initial.slice(0,4),shift(initial,-15).slice(0,4),shift(initial,15).slice(0,4)])){
  for(const suffix of ['&date='+year+'0101']){if(verified.has(year))break;try{const r=await json('https://www.twse.com.tw/rwd/zh/holidaySchedule/holidaySchedule?response=json'+suffix);for(const row of r.data||[]){const d=dateNorm(row[0]);if(String(r.queryYear)===year&&d.startsWith(year)){verified.add(year);if(!/開始交易|最後交易/.test(row[1]))closed.add(d)}}}catch{}}
 }
 if(!verified.has('2026')){
  for(const d of scheduledClosures)closed.add(d);
  verified.add('2026');
 }
 const cal=tradePair(initial,closed,verified);
 cal.preview=tradePair(previewStart(),closed,verified);
 return cal;
}
async function collect(cal,rows,draw){
 const {target,prev}=cal,cut=Math.min(Date.now(),at(target,'05:10')),from=shift(prev,-40),jobs=[];
 const series=(symbol,interval)=>chart(symbol,interval,cal.replay?target:null);
 const add=(ids,source,work)=>jobs.push((async()=>{
 const put=(i,v,info,date=prev)=>{if(fin(v))rows[i]={v,info,date,source}};
 try{await work(put)}catch(e){for(const i of ids)if(!rows[i])rows[i]={info:String(e.message).slice(0,500),source}}
 for(const i of ids)if(!rows[i])rows[i]={source,info:'截至 '+new Date(cut).toLocaleTimeString('zh-TW',{timeZone:'Asia/Taipei',hour12:false})+'，未取得計算「'+rules[i][1]+'」所需的有效資料；來源未提供更細原因'};draw();
 })());
 const tseries=marketSeries(prev,()=>series('^TWII'));
 const flags=tseries.then(a=>Math.sign(last(a).c-a[a.length-2].c));flags.catch(()=>{});
 const month=async(d)=>(await tw('afterTrading/FMTQIK',d)).data||[];
 const volume=marketVolume(prev,async()=>{const all=(await Promise.all([month(prev),month(shift(prev,-30)),month(shift(prev,-60))])).flat();
 let map=new Map(all.map(r=>[dateNorm(r[0]),num(r[2])]));let dates=[...map.keys()].filter(x=>x<=prev).sort().slice(-21);if(dates.length!==21||last(dates)!==prev)throw Error('成交金額不足 21 日');const vals=dates.map(d=>map.get(d));if(!vals.every(x=>fin(x)&&x>0))throw Error('成交金額存在缺值');return {dates,amount:last(vals),ratio:last(vals)/avg(vals.slice(0,-1))}});volume.catch(()=>{});
 add([10,22,23,24],'Yahoo · 加權指數',async put=>{const a=await tseries;tech(a,put);markSources(rows,[10,22,23,24],a)});
 add([8,25],'證交所／加權指數',async put=>{let [a,v,sg]=await Promise.all([tseries,volume,flags]),d=last(a),pc=pos(d),n=v.amount/1e8,ratio=v.ratio;
 let raw=(n>=9000?2:n>=8000?1:0)+(ratio>=1.5?3:ratio>=1.3?2:ratio>=1.1?1:0);
 if(!fin(pc))throw Error('來源僅有收盤價，缺少當日高低價；成交金額指標暫不計分');
 put(8,(sg>0&&pc>=65?1:sg<0&&pc<=35?-1:0)*raw/5,fmt(n)+' 億／'+fmt(ratio)+' 倍');markSources(rows,[8],a,v);
 let hist=a.slice(-21,-1);if(hist.length!==20||!hist.every(x=>fin(x.h)&&fin(x.l)))throw Error('來源缺少 20 日高低價，無法計算突破');let br=d.c>Math.max(...hist.map(v=>v.h))?1:d.c<Math.min(...hist.map(v=>v.l))?-1:0;put(25,br*(ratio>=1.3?1:.5),'方向 '+br+'／量比 '+fmt(ratio));markSources(rows,[8,25],a,v);});
 const mi=tw('afterTrading/MI_INDEX',prev,'&type=ALLBUT0999').then(r=>{if(dateNorm(r.date)!==prev)throw Error('交易日不符');return r.tables||[]});mi.catch(()=>{});
 add([9,11],'證交所／期交所權重表',async put=>{const ts=await mi,tab=ts.find(x=>x.fields?.includes('證券代號')),f=tab.fields,idx=f.findIndex(x=>x.includes('漲跌(+/-)')||x.includes('漲跌(+／-)')),id=f.indexOf('證券代號'),sg=r=>{let s=String(r[idx]).replace(/<[^>]+>/g,'');return s.includes('+')?1:s.includes('-')?-1:0};
 const breadth=ts.find(t=>t.fields?.some(v=>v==='股票')&&t.data?.some(r=>String(r[0]).includes('上漲')));if(breadth){const j=breadth.fields.indexOf('股票'),ct=k=>num(String(breadth.data.find(r=>String(r[0]).includes(k))[j]).split('(')[0]),u=ct('上漲'),d=ct('下跌');put(9,band(100*u/(u+d),55,65),u+' 漲／'+d+' 跌')}
 if(idx<0)throw Error('行情表缺少漲跌方向欄位');
 const weightUrl='https://www.taifex.com.tw/cht/2/weightedPropertion';
 const top=new Map();let weightDate='',weightSource='期交所';
 function readWeights(html){
  const doc=new DOMParser().parseFromString(html,'text/html');
  const stamp=dateNorm(doc.body.textContent.match(/資料日期[^\d]*(\d{4}[/\-]\d{1,2}[/\-]\d{1,2})/)?.[1]||'');
  if(!stamp||stamp>=prev||stamp<shift(prev,-65))return;
  const ranks=new Map();
  for(const tr of doc.querySelectorAll('tr')){
   const cells=[...tr.querySelectorAll(':scope > td')].map(x=>x.textContent.trim());
   for(let j=0;j<cells.length-1;j++)if(/^([1-9]|10)$/.test(cells[j])&&/^\d{4}$/.test(cells[j+1])){
    const rank=Number(cells[j]);if(ranks.has(rank)&&ranks.get(rank)!==cells[j+1])return;
    ranks.set(rank,cells[j+1]);
   }
  }
  if(new Set(ranks.values()).size===10){for(const [rank,code] of ranks)top.set(rank,code);weightDate=stamp}
 }
 readWeights(await wire(weightUrl).catch(()=>''));
 if(top.size!==10&&weightSnapshot.date<prev&&weightSnapshot.date>=shift(prev,-32)){
  top.clear();weightSnapshot.codes.forEach((code,i)=>top.set(i+1,code));weightDate=weightSnapshot.date;
 }
 if(top.size!==10){
  const until=shift(prev,-1).replaceAll('-','')+'155959';
  const since=shift(prev,-65).replaceAll('-','');
  const query=new URLSearchParams({url:weightUrl,from:since,to:until,output:'json',filter:'statuscode:200',fl:'timestamp',limit:'-5'});
  const captures=await json('https://web.archive.org/cdx/search/cdx?'+query);
  for(const [stamp] of captures.slice(1).reverse()){
   if(!/^\d{14}$/.test(stamp)||stamp>until)continue;
   readWeights(await wire('https://web.archive.org/web/'+stamp+'id_/'+weightUrl).catch(()=>''));
   if(top.size===10){weightSource='期交所・Internet Archive 歷史備份';break}
  }
 }
 if(top.size!==10)throw Error('查無交易日前適用的完整十大權重名單');
 let arr=[...top.values()].map(code=>tab.data.find(r=>r[id]===code));if(!arr.every(Boolean))throw Error('行情表缺少權值股：'+[...top.values()].filter((c,i)=>!arr[i]).join('、'));{let up=arr.filter(r=>sg(r)>0).length,dn=arr.filter(r=>sg(r)<0).length;put(11,up>=8?1:up>=6?.5:dn>=8?-1:dn>=6?-.5:0,up+' 漲／'+dn+' 跌，權重 '+weightDate+'（'+weightSource+'）')}});
 add([12],'櫃買中心／Goodinfo／證交所',async put=>{
  const [ts,sg,otc]=await Promise.all([mi,flags,otcDirection(prev)]);
  const tab=ts.find(t=>t.fields?.[0]==='指數'&&t.data?.some(r=>r[0]==='金融保險類指數'));
  const dirs=['電子工業類指數','金融保險類指數'].map(k=>{
   const row=tab?.data.find(r=>r[0]===k),v=num(row?.[4]);
   if(!fin(v))throw Error(prev+' 缺'+k+'漲跌百分比');return Math.sign(v);
  });
  dirs.push(otc.sign);const n=dirs.filter(d=>d===sg).length;
  put(12,sg*(n===3?1:n===2?.5:0),n+' 類與大盤同向');rows[12].source=otc.source+'／證交所';
 });
 const inst=fm('TaiwanStockTotalInstitutionalInvestors',from,prev);inst.catch(()=>{});
 add([13],'FinMind／證交所／Goodinfo',async put=>{
  const [n,v]=await Promise.all([institutionValue(prev,'foreign',inst),marketAmount(prev)]);const k=n.value/v.value*100;
  put(13,level(k,2,4),fmt(k)+'%');markSources(rows,[13],n,v);
 });
 add([15],'FinMind／證交所／Goodinfo',async put=>{
  const ts=await tseries,dates=ts.slice(-5).map(x=>x.date);if(dates.length!==5)throw Error('不足五個交易日');
  const values=await Promise.all(dates.map(d=>institutionValue(d,'trust',inst))),streak=values.map(x=>Math.sign(x.value)),s=last(streak);
  const len=streak.slice(-3).every(x=>x===s)?(streak.every(x=>x===s)?5:3):0;
  put(15,s*(len===5?1:len===3?.5:0),s===0?'當日買賣平衡':'連續 '+len+' 日');markSources(rows,[15],ts,...values);
 });
 add([16],'FinMind／Goodinfo · 融資金額',async put=>{
  const a=await tseries,old=a[a.length-6];if(!old)throw Error('不足六個交易日');
  const r=await marginBalances([old.date,prev],fm('TaiwanStockTotalMarginPurchaseShortSale',from,prev));
  const v=ret(r.value[1],r.value[0]),sg=Math.sign(last(a).c-old.c);
  put(16,sg*v<0?-level(v,1,3):0,'五日融資 '+fmt(v)+'%');markSources(rows,[16],a,r);
 });
 add([17],'證交所 · TWTASU',async put=>{let v=await volume;const dates=v.dates;const one=async d=>{
  if(fin(loanSnapshot[d]))return loanSnapshot[d];
  const key='sbl-value-v1-'+d,stored=localStorage.getItem(key);
  if(stored!==null&&fin(num(stored)))return num(stored);
  const value=loanValue(await json('https://www.twse.com.tw/rwd/zh/afterTrading/TWTASU?response=json&date='+d.replaceAll('-','')),d);
  try{localStorage.setItem(key,String(value))}catch{}return value;
 };
 let vals=await Promise.all(dates.map(one)),d=ret(last(vals),avg(vals.slice(0,-1))),s=await flags;if(fin(d))put(17,s*d<0?-level(d,20,40):0,'較均額 '+fmt(d)+'%')});
 const future=fm('TaiwanFuturesDaily',from,target,'TX');future.catch(()=>{});
 add([0,1,2,3],'FinMind · TX 同月合約',async put=>{if(cut<txClose(cal))return;
 scoreTxClose(rows,cal,localTxClose(cal));draw();
 const ending=await closingTX(cal);
 scoreTxClose(rows,cal,ending);draw();
 const nightDay=shift(prev,1);
 const errors=[];
 const pullFinMind=async()=>{try{return await future}catch(e){errors.push(e.message);return []}};
 const official=async(date,night)=>{
  try{return await txReport(date,night)}catch(e){errors.push(e.message)}
  try{
   const list=await json('https://openapi.taifex.com.tw/v1/DailyMarketReportFut');
   return list.filter(r=>dateNorm(r.Date)===date&&r.Contract==='TX'&&r.TradingSession===(night?'盤後':'一般')&&/^\d{6}$/.test(r['ContractMonth(Week)'])&&num(r.Last)>0).map(r=>({date,contract_date:r['ContractMonth(Week)'],open:num(r.Open),close:num(r.Last),max:num(r.High),min:num(r.Low),volume:num(r.Volume),source:'期交所 OpenAPI・每日行情'}));
  }catch(e){errors.push(e.message);return []}
 };
 let [days,nights]=await Promise.all([official(prev,false),official(target,true)]);
 if(!days.length||!nights.length){
  const finmind=await pullFinMind();
  const fmRows=(date,session)=>finmind.filter(r=>r.date===date&&r.trading_session===session&&/^\d{6}$/.test(r.contract_date)&&num(r.close)>0).map(r=>({...r,source:'FinMind・每日行情'}));
  if(!days.length)days=fmRows(prev,'position');
  if(!nights.length)nights=fmRows(target,'after_market');
 }
 days.sort((a,b)=>num(b.volume)-num(a.volume));
 const archive=await txArchiveFallback(target,nightDay,data=>{
  const active=days.find(r=>nights.some(n=>n.contract_date===r.contract_date)||data[r.contract_date]);
  const front=active??[...nights].sort((a,b)=>num(b.volume)-num(a.volume))[0];
  return front?data[front.contract_date]?.['04:00']:Object.values(data).some(r=>r['04:00']);
 });
 errors.push(...archive.notes);
 const getNight=month=>{
  const report=nights.find(r=>String(r.contract_date)===String(month));
  const trades=archive.data[month];
  if(report&&[report.open,report.max,report.min,report.close].every(x=>num(x)>0))return report;
  return trades?{...trades,contract_date:month,source:archive.source}:report;
 };
 const market=nights.length?nights:Object.entries(archive.data).map(([month,record])=>({...record,contract_date:month}));
 market.sort((a,b)=>num(b.volume)-num(a.volume));
 const d=ending?days.find(r=>String(r.contract_date)===ending.month):days.find(r=>getNight(r.contract_date))||days[0];
 const month=String(ending?.month??d?.contract_date??market[0]?.contract_date??'');
 const night=getNight(month);
 if(!month)throw Error(errors.filter(Boolean).join('；')||'等候指定夜盤月契約資料');
 const fromArchive=archive.data[month]?.['04:00'];
 let point=fromArchive;
 let pointSource=point?archive.source:'';
 if(point)saveTxObservation(target,month,'04:00',point);
 if(!point){point=txSavedObservation(target,month,'04:00');if(point)pointSource='期交所逐筆・本機留存'}
 if(!point){
  const verified=txVerified[target]?.[month]?.['04:00'];
  if(txBeforeFour(verified)){point=verified;pointSource='期交所・已核對逐筆檔'}
 }
 if(!point){
  try{
   const ticks=await fm('TaiwanFuturesTick',nightDay,nightDay,'TX'),cutoff=at(nightDay,'04:00');
   const rr=ticks.filter(x=>String(x.contract_date)===month&&x.date.startsWith(nightDay)).sort((a,b)=>a.date.localeCompare(b.date));
   const tick=last(rr.filter(x=>{const t=Date.parse(x.date.replace(' ','T')+'+08:00');return t>=cutoff-120000&&t<cutoff&&num(x.price)>0}));
   if(tick){point={price:num(tick.price),seconds:(Date.parse(tick.date.replace(' ','T')+'+08:00')-at(nightDay,'00:00'))/1000};pointSource='FinMind・逐筆'}
  }catch(e){errors.push(e.message)}
 }
 const nc=num(night?.close??ending?.p),prior=num(d?.close),op=num(night?.open),high=num(night?.max),low=num(night?.min);
 const source=night?(night.source||'期交所・盤後日報'):ending?.source,daySource=d?.source||'期交所・一般日報';
 const finalRows=[
  [level(ret(nc,prior),.3,.8),fmt(ret(nc,prior))+'% · 昨日 '+fmt(prior)+' → '+fmt(nc),source+'／'+daySource],
  [level(ret(nc,op),.15,.4),fmt(ret(nc,op))+'% · 夜開 '+fmt(op)+' → '+fmt(nc),source],
  [level(ret(nc,point?.price),.15,.4),fmt(ret(nc,point?.price))+'% · '+(point?taipeiStamp(at(nightDay,'00:00')+point.seconds*1000)+' 成交 '+fmt(point.price):'')+' → '+fmt(nc),source+'／'+pointSource],
  [high>=nc&&low<=nc&&low>0?band(pos({c:nc,h:high,l:low}),70,90):NaN,fmt(pos({c:nc,h:high,l:low}))+'% · 高 '+fmt(high)+'／低 '+fmt(low),source]
 ];
 for(const [i,[v,info,origin]] of finalRows.entries()){
  if(fin(v)){put(i,v,'TX '+month+' · '+info,target);rows[i].source=origin}
  else if(!fin(rows[i]?.v))rows[i]={source:'期交所／FinMind',status:'unavailable',info:'缺 TX '+month+' '+rules[i][1]+'的有效成交。'+errors.filter(Boolean).join('；')};
 }
 });
 add([14],'FinMind · TX 外資',async put=>{let [daily,inst,ts]=await Promise.all([future,fm('TaiwanFuturesInstitutionalInvestors',from,prev,'TX'),tseries]),old=ts[ts.length-2].date;
 let net=d=>{let x=inst.find(r=>r.date===d&&/外資|Foreign/i.test(r.institutional_investors));return num(x?.long_open_interest_balance_volume)-num(x?.short_open_interest_balance_volume)},oi=daily.filter(x=>x.date===old&&x.trading_session==='position'&&/^\d{6}$/.test(x.contract_date)).reduce((s,r)=>s+num(r.open_interest),0),v=(net(prev)-net(old))/oi*100;if(!(oi>0)||!fin(v))throw Error('缺指定日期 TX 部位或市場未平倉口數');put(14,level(v,1,2),fmt(v)+'%');markSources(rows,[14],{source:'FinMind · TX 外資'},ts)});
 const amer=async sym=>{
 try{return {...completedUSReturn(await series(sym),cut),source:'Yahoo'}}
 catch(e){if(!fredSeries[sym])throw e;return fredClose(sym,cut)}
 };
 const sox=amer('^SOX');sox.catch(()=>{});
 add([5],'Yahoo · SOX',async put=>{let s=await sox;put(5,level(s.r,.5,1.5),fmt(s.r)+'%',s.date);markSources(rows,[5],s)});
 add([4],'Yahoo · TSM／SOX',async put=>{let [a,b]=await Promise.all([amer('TSM'),sox]);if(a.date===b.date)put(4,level(a.r-b.r,.5,1.5),fmt(a.r-b.r)+' 百分點',a.date);markSources(rows,[4],a,b)});
 add([6],'Yahoo · NDX／GSPC',async put=>{let [a,b]=await Promise.all([amer('^NDX'),amer('^GSPC')]);if(a.date===b.date)put(6,agree(level(a.r,.3,.8),level(b.r,.3,.8)),fmt(a.r)+'%／'+fmt(b.r)+'%',a.date);markSources(rows,[6],a,b)});
 add([19],'Yahoo · DXY',async put=>{let a=await amer('DX-Y.NYB');put(19,-level(a.r,.25,.6),fmt(a.r)+'%',a.date)});
 const bar=(a,time)=>last(a.filter(x=>x.t+60000<=time&&x.t>=time-180000));
 add([7],'TradingView · NQ1!',async put=>{
 const window=historicalNQWindow(at(target,'05:00'));
 if(cut<window.cut)return;
 const {base,date}=window;
 try{const q=await historicalNQ(target,base,window.cut,series);put(7,level(q.value,.15,.4),fmt(q.value)+'% · 美股交易日 '+date+' · '+taipeiStamp(q.observedBase??base)+' → '+taipeiStamp(q.observedCut??window.cut)+(window.carried?' · 休市沿用最近已收盤交易日':''),date);rows[7].source=q.source;rows[7].backup=q.backup}catch(e){rows[7]={source:'Nasdaq 100 期貨 · '+date+' 歷史備援',status:'unavailable',info:e.message}}

 });
 add([20,21],'Yahoo · 日經／KOSPI · 08:55 截點',async put=>{const asiaCut=Math.min(Date.now(),at(target,'08:55'));if(asiaCut<at(target,'08:00'))return;const values=await Promise.all(['^N225','^KS11'].map(async sym=>{let [a,b]=await Promise.all([series(sym,'1m'),series(sym)]),q=bar(a,asiaCut),op=a.find(x=>x.date===target&&x.t===at(target,'08:00')),prior=last(b.filter(x=>x.date<target));if(!q||q.date!==target)return [NaN,NaN];return [ret(q.c,prior?.c),ret(q.c,op?.o)]}));for(const [i,k,x,y] of [[20,0,.3,.8],[21,1,.15,.4]]){let a=values[0][k],b=values[1][k];put(i,agree(level(a,x,y),level(b,x,y)),fmt(a)+'%／'+fmt(b)+'%',target)}});
 add([18],'中央銀行 · 銀行間收盤匯率',async put=>{let r=await json('https://cpx.cbc.gov.tw/api/OpenData/FTDOpenData_Day'),entries=[];function visit(o){if(!o||typeof o!=='object')return;let vals=Object.values(o),d=vals.map(dateNorm).find(v=>/^\d{4}-\d\d-\d\d$/.test(v)),rate=vals.map(num).find(n=>n>10&&n<100);if(d&&rate)entries.push({d,rate});else vals.forEach(visit)}visit(r);entries=entries.filter(x=>x.d<=prev).sort((a,b)=>a.d.localeCompare(b.d));if(last(entries)?.d!==prev||cut<at(prev,'17:00'))return;let v=ret(last(entries).rate,entries[entries.length-2]?.rate);put(18,-level(v,.15,.4),fmt(v)+'%')});
 await Promise.allSettled(jobs);
 if(cal.replay&&cut===at(target,'05:10')){
  const key='replay-tv-nq0500-'+target;
  try{
   const previous=JSON.parse(localStorage.getItem(key)||'[]');
   rows.forEach((row,i)=>{
    if(!fin(row?.v)&&row?.status!=='marketClosed'&&fin(previous[i]?.v)){
     rows[i]={...previous[i],source:previous[i].source+'・同日分時截點留存',backup:true};
    }else if(fin(row?.v)){previous[i]=row}
   });
   localStorage.setItem(key,JSON.stringify(previous));
  }catch{}
 }
}
