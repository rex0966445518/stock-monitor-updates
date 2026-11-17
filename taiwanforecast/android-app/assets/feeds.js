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
async function calendar(){
 const today=day(),initial=Date.now()<at(today,'09:00')?today:shift(today,1),closed=new Set(),verified=new Set();
 for(const year of new Set([initial.slice(0,4),shift(initial,-15).slice(0,4),shift(initial,15).slice(0,4)])){
  for(const suffix of ['&date='+year+'0101']){if(verified.has(year))break;try{const r=await json('https://www.twse.com.tw/rwd/zh/holidaySchedule/holidaySchedule?response=json'+suffix);for(const row of r.data||[]){const d=dateNorm(row[0]);if(String(r.queryYear)===year&&d.startsWith(year)){verified.add(year);if(!/開始交易|最後交易/.test(row[1]))closed.add(d)}}}catch{}}
 }
 const ok=d=>![0,6].includes(new Date(at(d,'12:00')).getUTCDay())&&!closed.has(d);
 let target=initial;while(!ok(target))target=shift(target,1);let prev=shift(target,-1);while(!ok(prev))prev=shift(prev,-1);
 return {target,prev,confirmed:verified.has(target.slice(0,4))&&verified.has(prev.slice(0,4))};
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
 const doc=new DOMParser().parseFromString(await wire('https://www.taifex.com.tw/cht/2/weightedPropertion').catch(()=> ''),'text/html'),stamp=dateNorm(doc.body.textContent.match(/資料日期[^\d]*(\d{4}[/\-]\d{1,2}[/\-]\d{1,2})/)?.[1]||'');
 if(idx<0)throw Error('行情表缺少漲跌方向欄位');const top=new Map();
 let weightDate=stamp;
 if(stamp&&stamp<prev&&stamp>=shift(prev,-65)){
  for(const tr of doc.querySelectorAll('tr')){
   const cells=[...tr.querySelectorAll(':scope > td')].map(x=>x.textContent.trim());
   for(let j=0;j<cells.length-1;j++)if(/^([1-9]|10)$/.test(cells[j])&&/^\d{4}$/.test(cells[j+1]))top.set(+cells[j],cells[j+1]);
  }
 }
 if(top.size!==10&&weightSnapshot.date<prev&&weightSnapshot.date>=shift(prev,-32)){
  top.clear();weightSnapshot.codes.forEach((code,i)=>top.set(i+1,code));weightDate=weightSnapshot.date;
 }
 if(top.size!==10)throw Error('查無交易日前適用的完整十大權重名單');
 let arr=[...top.values()].map(code=>tab.data.find(r=>r[id]===code));if(!arr.every(Boolean))throw Error('行情表缺少權值股：'+[...top.values()].filter((c,i)=>!arr[i]).join('、'));{let up=arr.filter(r=>sg(r)>0).length,dn=arr.filter(r=>sg(r)<0).length;put(11,up>=8?1:up>=6?.5:dn>=8?-1:dn>=6?-.5:0,up+' 漲／'+dn+' 跌，權重 '+weightDate)}});
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
 add([0,1,2,3],'FinMind · TX 同月合約',async put=>{if(cut<at(target,'05:00'))return;
 let all=[],dailyFallback=false,primaryError='';
 try{all=await future}catch(e){primaryError=e.message}
 for(const [date,night] of [[prev,false],[target,true]]){
  if(all.some(r=>r.date===date&&r.trading_session===(night?'after_market':'position')&&/^\d{6}$/.test(r.contract_date)&&num(r.close)>0))continue;
  try{all=all.concat(await txReport(date,night));dailyFallback=true}catch(e){if(!night)throw Error([primaryError,e.message].filter(Boolean).join('；'))}
 }
 const days=all.filter(r=>r.date===prev&&r.trading_session==='position'&&/^\d{6}$/.test(r.contract_date)&&num(r.close)>0).sort((a,b)=>b.volume-a.volume);
 const overnight=all.filter(r=>r.date===target&&r.trading_session==='after_market'&&num(r.close)>0);let d=days.find(d=>overnight.some(r=>r.contract_date===d.contract_date))||days[0];if(!d)return;let night=overnight.find(r=>r.contract_date===d.contract_date);
 let nc=num(night?.close);const log=v=>fmt(v)+'% · TX '+d.contract_date;
 if(night){let n=ret(nc,num(d.close)),p=pos({c:nc,h:num(night.max),l:num(night.min)});put(0,level(n,.3,.8),log(n),target);put(3,band(p,70,90),fmt(p)+'%',target);const oc=ret(nc,num(night.open));put(1,level(oc,.15,.4),log(oc)+' · 夜盤開盤至收盤',target)}
 if(dailyFallback)for(const i of [0,1,3])if(fin(rows[i]?.v)){rows[i].source='期交所／FinMind・每日行情';rows[i].backup=true}
 const prices={},need=['04:00','05:00'];
 let error='';
 try{
  const ticks=await fm('TaiwanFuturesTick',target,target,'TX');
  const rr=ticks.filter(x=>String(x.contract_date)===String(d.contract_date)&&x.date.startsWith(target)).sort((a,b)=>a.date.localeCompare(b.date));
  for(const t of need){
   const end=at(target,t),z=rr.filter(x=>{const tm=Date.parse(x.date.replace(' ','T')+'+08:00');return tm<=end&&tm>=end-120000&&num(x.price)>0});
   prices[t]=num(last(z)?.price);
  }
 }catch(e){error=e.message}
 if(fin(nc))prices['05:00']=nc;
 const {sources,notes}=await fillTxPrices(target,String(d.contract_date),prices,need,cal.replay||cut>=at(target,'05:00'),shift(prev,1));
 nc=prices['05:00'];
 function complete(i,v,t,threshold,strong){
  put(i,level(v,threshold,strong),log(v),target);
  const source=[...new Set([sources[t],sources['05:00']].filter(Boolean))].join('、');
  if(fin(rows[i]?.v)&&(source||dailyFallback)){rows[i].source=[dailyFallback?'期交所・每日行情':'FinMind',source].filter(Boolean).join('／');rows[i].backup=true}
  if(!fin(rows[i]?.v))rows[i]={source:cal.replay?'FinMind／期交所逐筆檔':'FinMind',info:[error||'指定時點無有效成交',...notes].join('；')};
 }
 if(!fin(rows[0]?.v))complete(0,ret(nc,num(d.close)),'05:00',.3,.8);
 complete(2,ret(nc,prices['04:00']),'04:00',.15,.4);
 if(!fin(rows[1]?.v))rows[1]={source:'FinMind／期交所・夜盤每日行情',status:'unavailable',info:'夜盤已收盤，但來源未提供同月夜盤開盤價；不能以 05:10 價格代替'};
 if(!fin(rows[2]?.v))rows[2]={...rows[2],status:'unavailable',info:'夜盤已收盤；缺 04:00 同月成交基準，無法計算最後一小時。'+(rows[2]?.info||'')};
 });
 add([14],'FinMind · TX 外資',async put=>{let [daily,inst,ts]=await Promise.all([future,fm('TaiwanFuturesInstitutionalInvestors',from,prev,'TX'),tseries]),old=ts[ts.length-2].date;
 let net=d=>{let x=inst.find(r=>r.date===d&&/外資|Foreign/i.test(r.institutional_investors));return num(x?.long_open_interest_balance_volume)-num(x?.short_open_interest_balance_volume)},oi=daily.filter(x=>x.date===old&&x.trading_session==='position'&&/^\d{6}$/.test(x.contract_date)).reduce((s,r)=>s+num(r.open_interest),0),v=(net(prev)-net(old))/oi*100;if(!(oi>0)||!fin(v))throw Error('缺指定日期 TX 部位或市場未平倉口數');put(14,level(v,1,2),fmt(v)+'%');markSources(rows,[14],{source:'FinMind · TX 外資'},ts)});
 const amer=async sym=>{const a=(await series(sym)).filter(x=>{const ny=day(cut,'America/New_York'),hour=+new Intl.DateTimeFormat('en-GB',{timeZone:'America/New_York',hour:'2-digit',hourCycle:'h23'}).format(cut);return x.date<ny||x.date===ny&&cut>=equityClose(x.date)});let d=last(a);if(!d||d.date<prev||d.date>=target||a.length<2)throw Error('尚無對應隔夜收盤');return {r:ret(d.c,a[a.length-2].c),date:d.date}};
 const sox=amer('^SOX');sox.catch(()=>{});
 add([5],'Yahoo · SOX',async put=>{let s=await sox;put(5,level(s.r,.5,1.5),fmt(s.r)+'%',s.date)});
 add([4],'Yahoo · TSM／SOX',async put=>{let [a,b]=await Promise.all([amer('TSM'),sox]);if(a.date===b.date)put(4,level(a.r-b.r,.5,1.5),fmt(a.r-b.r)+' 百分點',a.date)});
 add([6],'Yahoo · NDX／GSPC',async put=>{let [a,b]=await Promise.all([amer('^NDX'),amer('^GSPC')]);if(a.date===b.date)put(6,agree(level(a.r,.3,.8),level(b.r,.3,.8)),fmt(a.r)+'%／'+fmt(b.r)+'%',a.date)});
 add([19],'Yahoo · DXY',async put=>{let a=await amer('DX-Y.NYB');put(19,-level(a.r,.25,.6),fmt(a.r)+'%',a.date)});
 const bar=(a,time)=>last(a.filter(x=>x.t+60000<=time&&x.t>=time-180000));
 add([7],'Yahoo · NQ 近月連續報價',async put=>{if(cut<at(target,'05:10'))return;let [a,s]=await Promise.all([series('NQ=F','1m'),amer('^GSPC')]);const base=at(shift(s.date,1),'04:00'),usclose=day(base,'America/New_York')===s.date&&new Date(base).toLocaleTimeString('en-GB',{timeZone:'America/New_York'}).startsWith('16:')?base:base+3600000;
 const v=ret(bar(a,cut)?.c,bar(a,usclose)?.c);put(7,level(v,.15,.4),fmt(v)+'%',target)});
 add([20,21],'Yahoo · 日經／KOSPI · 08:55 截點',async put=>{const asiaCut=Math.min(Date.now(),at(target,'08:55'));if(asiaCut<at(target,'08:00'))return;const values=await Promise.all(['^N225','^KS11'].map(async sym=>{let [a,b]=await Promise.all([series(sym,'1m'),series(sym)]),q=bar(a,asiaCut),op=a.find(x=>x.date===target&&x.t===at(target,'08:00')),prior=last(b.filter(x=>x.date<target));if(!q||q.date!==target)return [NaN,NaN];return [ret(q.c,prior?.c),ret(q.c,op?.o)]}));for(const [i,k,x,y] of [[20,0,.3,.8],[21,1,.15,.4]]){let a=values[0][k],b=values[1][k];put(i,agree(level(a,x,y),level(b,x,y)),fmt(a)+'%／'+fmt(b)+'%',target)}});
 add([18],'中央銀行 · 銀行間收盤匯率',async put=>{let r=await json('https://cpx.cbc.gov.tw/api/OpenData/FTDOpenData_Day'),entries=[];function visit(o){if(!o||typeof o!=='object')return;let vals=Object.values(o),d=vals.map(dateNorm).find(v=>/^\d{4}-\d\d-\d\d$/.test(v)),rate=vals.map(num).find(n=>n>10&&n<100);if(d&&rate)entries.push({d,rate});else vals.forEach(visit)}visit(r);entries=entries.filter(x=>x.d<=prev).sort((a,b)=>a.d.localeCompare(b.d));if(last(entries)?.d!==prev||cut<at(prev,'17:00'))return;let v=ret(last(entries).rate,entries[entries.length-2]?.rate);put(18,-level(v,.15,.4),fmt(v)+'%')});
 await Promise.allSettled(jobs);
 if(cal.replay&&cut===at(target,'05:10')){
  const key='replay-nightopen-split-v1-'+target;
  try{
   const previous=JSON.parse(localStorage.getItem(key)||'[]');
   rows.forEach((row,i)=>{
    if(!fin(row?.v)&&fin(previous[i]?.v)){
     rows[i]={...previous[i],source:previous[i].source+'・同日分時截點留存',backup:true};
    }else if(fin(row?.v)){previous[i]=row}
   });
   localStorage.setItem(key,JSON.stringify(previous));
  }catch{}
 }
}
