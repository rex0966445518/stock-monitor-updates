const tvEndpoint='https://scanner.tradingview.com/symbol?symbol=CME_MINI%3ANQ1%21&fields=close%2Cname%2Clast_bar_update_time';
let tvPending=null,tvLatest=null;
async function tradingViewNq(){
 if(tvLatest&&Date.now()-tvLatest.fetched<8000)return tvLatest;
 if(!tvPending)tvPending=liveJSON(tvEndpoint+'&_='+Date.now()).then(m=>{
  const time=m.last_bar_update_time*1000,price=m.close;
  if(m.name!=='NQ1!'||!Number.isFinite(price)||price<=0||!Number.isFinite(time)||time<=0||time>Date.now())throw Error('TradingView 報價資料不足');
  tvLatest={price,time,fetched:Date.now()};
  return tvLatest;
 }).finally(()=>{tvPending=null});
 return tvPending;
}
async function tvAnchor(time){
 const key='tv-nq-anchor-'+time;
 let q;
 try{q=await tradingViewNq()}catch{}
 let old;
 try{old=JSON.parse(localStorage.getItem(key))}catch{}
 const valid=p=>p&&Number.isFinite(p.price)&&p.price>0&&p.time<=time&&p.time>=time-120000;
 if(valid(q)&&(!valid(old)||q.time>old.time)){localStorage.setItem(key,JSON.stringify(q));return q;}
 return valid(old)?old:null;
}
const nqDraft=new Map();
function nqEditor(){
 const target=snap.cal.target;
 const value=nqDraft.get(target)??localStorage.getItem('nq-manual-'+target)??'';
 return '<form id="nqForm" data-target="'+safe(target)+'"><label for="nqInput">手動輸入漲跌幅（%）</label><small>'+safe(target)+' · 台灣 05:00 相對最近一次美股收盤時的期貨價格</small><input id="nqInput" type="text" inputmode="text" autocomplete="off" placeholder="例如 -0.35 或 +0.40" style="box-sizing:border-box;width:100%;margin:8px 0;padding:12px;color:white;background:#102641;border:1px solid #84cfe7;border-radius:8px" value="'+safe(value)+'"><button type="submit">提交並更新評分</button> <button type="button" class="secondary" data-nq-action="clear">恢復自動</button></form>';
}
function submitNq(clear){
 const field=el('nqInput'),target=field.form.dataset.target;
 if(target!==snap.cal.target){field.blur();render();return;}
 const raw=field.value.normalize('NFKC').trim().replace(/%$/,'').trim(),pct=Number(raw);
 if(!clear&&(!/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)$/.test(raw)||!Number.isFinite(pct)||pct<=-100)){
  field.setCustomValidity('請填寫大於 -100 的漲跌幅，例如 -0.35');field.reportValidity();return;
 }
 if(clear)localStorage.removeItem('nq-manual-'+target);
 else localStorage.setItem('nq-manual-'+target,String(pct));
 nqDraft.delete(target);field.blur();render();
}
const nqFeeds=[
 ['TradingView · CME_MINI:NQ1!',tradingViewNq],
 ['新浪財經 · NQ',async()=>{
  const raw=await wire('https://stock2.finance.sina.com.cn/futures/api/jsonp.php/var%20t=/GlobalFuturesService.getGlobalFuturesMinLine?symbol=NQ');
  const wrapper=raw.trim().match(/^(?:\/\/[^\n]*\n\s*)?var\s+t\s*=\s*\(([\s\S]*)\)\s*;?$/);
  const rows=JSON.parse(wrapper?wrapper[1]:raw).minLine_1d||[];
  let latest;
  for(const row of rows){
   if(!Array.isArray(row))continue;
   const stamp=row.find(v=>typeof v==='string'&&/^\d{4}-\d\d-\d\d[ T]\d\d:\d\d(:\d\d)?$/.test(v));
   if(!stamp||String(String(row[0]).length===10?row[4]:row[0]).slice(0,5)!==stamp.slice(11,16))continue;
   const time=Date.parse(stamp.replace(' ','T')+'+08:00'),price=Number(row[1]);
   if(time<=Date.now()&&price>0&&(!latest||time>latest.time))latest={price,time};
  }
  if(!latest)throw Error();
  return latest;
 }]
];
const nqLines=nqFeeds.map(()=> '查詢中…');
let nqChecked=0,nqBusy=false,nqTarget='';
function nqPanel(){
 return nqEditor()+'<small>各網站最新報價（台灣時間）</small>'+nqFeeds.map(([name],i)=>'<small>'+safe(name)+'　'+safe(nqLines[i])+'</small>').join('')+'<button type="button" class="secondary" data-nq-action="refresh">更新來源報價</button>';
}
async function refreshNqQuotes(){
 if(nqBusy)return;
 nqBusy=true;nqChecked=Date.now();
 await Promise.all(nqFeeds.map(async ([,read],i)=>{
  try{
   const {price,time}=await read();
   if(!Number.isFinite(price)||price<=0||!Number.isFinite(time)||time<=0||time>Date.now())throw Error();
   nqLines[i]=price.toLocaleString('en-US',{maximumFractionDigits:2})+' 點 · '+taipeiStamp(time)+(Date.now()-time>120000?'（延遲或休市）':'');
  }catch{nqLines[i]='未取得有效報價';}
 }));
 nqBusy=false;render();
}
function scoredNqRows(snapshot){
 const rows=liveRows(snapshot).slice();
 try{
  const s=localStorage.getItem('nq-manual-'+snapshot.cal.target);
  if(s!==null&&s.trim()&&Number.isFinite(Number(s))&&Number(s)>-100){
   const pct=Number(s);
   rows[7]={date:snapshot.cal.target,source:'手動提交',info:'05:00 相對基準 '+(pct>0?'+':'')+pct+'% · 已納入總分',v:level(pct,.15,.4)};
  }
 }catch{}
 return rows;
}

// The groups container survives card refreshes. Bind once for touch, mouse and Enter.
const nqGroups=document.getElementById('groups');
nqGroups.addEventListener('submit',event=>{
 if(event.target.id!=='nqForm')return;
 event.preventDefault();
 submitNq(false);
});
nqGroups.addEventListener('input',event=>{
 const field=event.target;
 if(field.id!=='nqInput')return;
 field.setCustomValidity('');
 nqDraft.set(field.form.dataset.target,field.value);
});
nqGroups.addEventListener('click',event=>{
 const action=event.target.closest('button[data-nq-action]')?.dataset.nqAction;
 if(action==='clear')submitNq(true);
 if(action==='refresh')refreshNqQuotes();
});
