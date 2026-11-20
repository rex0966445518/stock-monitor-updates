// Public, read-only backups. Never evaluate scripts from a provider page.
const backupStatus={Goodinfo:{state:'待命',detail:'大盤日K、成交金額、法人買賣超、融資餘額'},'財報狗':{state:'待命',detail:'大盤歷史收盤價；不提供本模型所需的法人與期貨完整欄位'}};
function setBackupStatus(name,state,detail){backupStatus[name]={state,detail,time:Date.now()};renderBackupStatus()}
function renderBackupStatus(){
 const box=document.getElementById('backupStatus');if(!box)return;box.replaceChildren();
 for(const [name,s] of Object.entries(backupStatus)){const p=document.createElement('p');p.textContent=name+'｜'+s.state+'｜'+s.detail+(s.time?' · '+new Date(s.time).toLocaleTimeString('zh-TW',{timeZone:'Asia/Taipei',hour12:false}):'');box.append(p)}
}
function shortDate(s){s=String(s).trim().replace(/^['’]/,'');if(/^\d{2}\/\d{2}\/\d{2}$/.test(s))s='20'+s;return dateNorm(s)}
function uniqueDays(rows){const m=new Map();for(const r of rows){if(!/^\d{4}-\d\d-\d\d$/.test(r.date))continue;if(m.has(r.date)&&JSON.stringify(m.get(r.date))!==JSON.stringify(r))throw Error('同日資料重複且不一致');m.set(r.date,r)}return [...m.values()].sort((a,b)=>a.date.localeCompare(b.date))}
function validateSeries(rows,date,count=2){const a=uniqueDays(rows.filter(r=>r.date<=date&&fin(r.c)&&r.c>0));if(last(a)?.date!==date)throw Error('缺 '+date+' 收盤；最新 '+(last(a)?.date||'無'));if(a.length<count)throw Error('歷史交易日不足 '+count+' 日');return a}
function parseGoodinfo(html){
 const doc=new DOMParser().parseFromString(html,'text/html'),rows=[];
 for(const table of doc.querySelectorAll('table')){
  const label=table.textContent.replace(/\s/g,'');
  if(!/法人買賣超[（(]億元[）)]/.test(label)||!/融資[（(]億元[）)]/.test(label)||!label.includes('日期開盤最高最低收盤')||!label.includes('外資投信自營合計餘額增減餘額增減'))continue;
  for(const tr of table.querySelectorAll('tr')){
   const c=[...tr.children].map(td=>td.textContent.trim());if(c.length!==18)continue;
   const date=shortDate(c[0]);if(!/^\d{4}-\d\d-\d\d$/.test(date))continue;
   const r={date,o:num(c[1]),h:num(c[2]),l:num(c[3]),c:num(c[4]),amount:num(c[8])*1e8,foreign:num(c[10])*1e8,trust:num(c[11])*1e8,margin:num(c[14])*1e8};
   if(![r.o,r.h,r.l,r.c].every(v=>fin(v)&&v>0)||r.h<Math.max(r.o,r.c)||r.l>Math.min(r.o,r.c))continue;rows.push(r);
  }
  if(rows.length)break;
 }
 if(!rows.length)throw Error('頁面無可核對的大盤日資料或欄位已變更');return uniqueDays(rows);
}
function parseStatementdog(html){
 const match=html.match(/var\s+taiexData\s*=\s*\{([\s\S]*?)\};/);if(!match)throw Error('頁面缺少大盤歷史資料');
 const read=name=>{const m=match[1].match(new RegExp('(?:^|[,\\n])\\s*'+name+'\\s*:\\s*(\\[[\\s\\S]*?\\])\\s*(?:,\\s*[a-zA-Z]+\\s*:|$)'));if(!m)throw Error('缺少 '+name+' 欄位');return JSON.parse(m[1])};
 const dates=read('date'),index=read('index'),values=new Map(index);if(dates.length!==index.length||values.size!==index.length)throw Error('日期與收盤資料長度不符');
 const rows=dates.map(([i,d])=>({date:shortDate(d),c:num(values.get(i))}));if(rows.some(r=>!fin(r.c)||r.c<=0))throw Error('收盤數值無效');return uniqueDays(rows);
}
async function publicBackup(name,url,parser){
 setBackupStatus(name,'查詢中','正在讀取公開資料');
 try{const rows=parser(await wire(url));setBackupStatus(name,'已讀取','最新資料 '+last(rows).date+'；使用時另核對指定日期');return rows}
 catch(e){setBackupStatus(name,'未取得',e.message+'；不以缺值計分');throw Error(name+'：'+e.message)}
}
const goodinfoRows=()=>publicBackup('Goodinfo','https://goodinfo.tw/tw/data/ShowK_Chart.asp?CHT_CAT2=DATE&PERIOD=365&STEP=DATA&STOCK_ID='+encodeURIComponent('加權指數'),parseGoodinfo);
const statementdogRows=()=>publicBackup('財報狗','https://statementdog.com/taiex',parseStatementdog);
async function chooseSource(options){const errors=[];for(const [source,fn] of options){try{return {value:await fn(),source,backup:errors.length>0}}catch(e){errors.push(source+'：'+e.message)}}throw Error(errors.join('；'))}
async function marketSeries(date,primary){
 if(Date.now()<at(date,'13:31'))throw Error(date+' 尚未收盤');
 const selected=await chooseSource([
  ['Yahoo · 加權指數',async()=>validateSeries(await primary(),date,35)],
  ['證交所 · 大盤日K',async()=>{
   const months=[...new Set([0,-1,-2,-3].map(n=>{const d=new Date(date.slice(0,7)+'-01T12:00:00+08:00');d.setUTCMonth(d.getUTCMonth()+n);return day(d.getTime())}))];
   const reports=await Promise.all(months.map(d=>tw('TAIEX/MI_5MINS_HIST',d)));
   const rows=reports.flatMap(r=>(r.data||[]).map(x=>({date:dateNorm(x[0]),o:num(x[1]),h:num(x[2]),l:num(x[3]),c:num(x[4])})));
   return validateSeries(rows,date,35);
  }],
  ['Goodinfo · 大盤日K',async()=>validateSeries(await goodinfoRows(),date,35)],
  ['財報狗 · 大盤收盤',async()=>validateSeries(await statementdogRows(),date,35)]
 ]);const a=selected.value;a.source=selected.source;a.backup=selected.backup;return a;
}
async function marketVolume(date,primary){
 const r=await chooseSource([
  ['證交所 · 成交金額',primary],
  ['Goodinfo · 成交金額（億元約數）',async()=>{
   const a=(await goodinfoRows()).filter(x=>x.date<=date).slice(-21);
   if(a.length!==21||last(a).date!==date||!a.every(x=>fin(x.amount)&&x.amount>0))throw Error('缺少指定日或連續 21 交易日成交金額');
   return {dates:a.map(x=>x.date),amount:last(a).amount,ratio:last(a).amount/avg(a.slice(0,-1).map(x=>x.amount))};
  }]
 ]);return {...r.value,source:r.source,backup:r.backup};
}
async function marketAmount(date){
 return chooseSource([
  ['證交所 · 當日成交金額',async()=>{const r=await tw('afterTrading/FMTQIK',date);const row=(r.data||[]).find(x=>dateNorm(x[0])===date),amount=num(row?.[2]);if(!(amount>0))throw Error('缺 '+date+' 成交金額');return amount}],
  ['Goodinfo · 當日成交金額（億元約數）',async()=>{const row=(await goodinfoRows()).find(x=>x.date===date);if(!(row?.amount>0))throw Error('缺 '+date+' 成交金額');return row.amount}]
 ]);
}
function parseInstitution(report,date,kind){
 if(dateNorm(report.date)!==date)throw Error('法人報表日期不符');
 const fields=report.fields||[],col=fields.indexOf('買賣差額');
 const row=(report.data||[]).find(x=>kind==='trust'?x[0]==='投信':/^外資及陸資\(不含外資自營商\)$/.test(x[0]));
 const v=num(row?.[col]);if(col<0||!fin(v))throw Error('缺法人買賣差額');return v;
}
async function institutionValue(date,kind,primary){
 return chooseSource([
  ['FinMind · 法人買賣超',async()=>{const list=await primary;const r=list.find(x=>x.date===date&&x.name===(kind==='trust'?'Investment_Trust':'Foreign_Investor'));const n=num(r?.buy)-num(r?.sell);if(!fin(n))throw Error('缺 '+date+' 法人資料');return n}],
  ['證交所 · 法人買賣超',async()=>parseInstitution(await json('https://www.twse.com.tw/rwd/zh/fund/BFI82U?response=json&dayDate='+date.replaceAll('-','')+'&type=day'),date,kind)],
  ['Goodinfo · 法人買賣超（億元約數）',async()=>{const r=(await goodinfoRows()).find(x=>x.date===date),v=r?.[kind];if(!fin(v))throw Error('缺 '+date+' 法人資料');return v}]
 ]);
}
async function marginBalances(dates,primary){
 return chooseSource([
  ['FinMind · 融資金額',async()=>{const rows=await primary;const values=dates.map(d=>num(rows.find(x=>x.date===d&&x.name==='MarginPurchaseMoney')?.TodayBalance));if(!values.every(v=>fin(v)&&v>0))throw Error('缺指定日期融資餘額');return values}],
  ['Goodinfo · 融資金額（億元約數）',async()=>{const rows=await goodinfoRows(),values=dates.map(d=>rows.find(x=>x.date===d)?.margin);if(!values.every(v=>fin(v)&&v>0))throw Error('缺指定日期融資餘額');return values}]
 ]);
}
function markSources(rows,ids,...values){const source=[...new Set(values.map(v=>v?.source).filter(Boolean))].join('／');for(const i of ids)if(fin(rows[i]?.v)){rows[i].source=source;rows[i].backup=values.some(v=>v?.backup)}}
renderBackupStatus();
const backupCheck=document.getElementById('backupCheck');if(backupCheck)backupCheck.onclick=async()=>{backupCheck.disabled=true;await Promise.allSettled([goodinfoRows(),statementdogRows()]);backupCheck.disabled=false};
