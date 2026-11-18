const txLive={target:null,rows:{},busy:false};
function txDynamicRows(snapshot,now=Date.now()){
 const rows=snapshot.rows.slice();if(txLive.target!==snapshot.cal.target||now>=at(snapshot.cal.target,'05:10'))return rows;
 for(let i=0;i<4;i++){const r=txLive.rows[i];if(r)rows[i]=now-r.checked<=30000?r:{v:NaN,source:'FinMind · TX',info:'更新中斷，暫停動態計分'}}return rows;
}
function txDynamicValues(ticks,daily,cal,now){
 const start=at(cal.prev,'15:00'),end=Math.min(now,at(shift(cal.prev,1),'05:00'));
 const days=daily.filter(x=>x.date===cal.prev&&x.trading_session==='position'&&/^\d{6}$/.test(String(x.contract_date))&&num(x.close)>0).sort((a,b)=>num(b.volume)-num(a.volume));
 const d=days[0];if(!d)throw Error('缺前一日同月合約收盤');
 const a=ticks.filter(x=>String(x.contract_date)===String(d.contract_date)).map(x=>({t:Date.parse(x.date.replace(' ','T')+'+08:00'),p:num(x.price)})).filter(x=>x.t>=start&&x.t<=end&&x.p>0).sort((a,b)=>a.t-b.t);
 const q=last(a);if(!q||end-q.t>120000)throw Error('缺兩分鐘內台指期成交；資料服務可能需授權');
 const opening=a.find(x=>x.t<=start+120000),hour=last(a.filter(x=>x.t<=q.t-3600000&&x.t>=q.t-3720000));
 const complete=!!opening,hi=a.reduce((v,x)=>Math.max(v,x.p),-Infinity),lo=a.reduce((v,x)=>Math.min(v,x.p),Infinity);
 return [level(ret(q.p,num(d.close)),.3,.8),opening?level(ret(q.p,opening.p),.15,.4):NaN,hour?level(ret(q.p,hour.p),.15,.4):NaN,complete?band(pos({c:q.p,h:hi,l:lo}),70,90):NaN].map((v,i)=>({v,live:true,date:day(q.t),checked:now,source:'FinMind · TX '+d.contract_date+' · 每 10 秒查詢',info:'成交 '+fmt(q.p)+' · '+taipeiStamp(q.t)+' · '+['相對前日收盤','相對夜盤開盤（盤中替代基準）','最近一小時（滾動暫估）','本次取得夜盤區間位置'][i]+(!fin(v)?' · 缺計算基準':''),title:['夜盤漲跌','夜盤開盤至今（暫估）','近一小時（暫估）','夜盤區間位置（暫估）'][i]}));
}
// Real-time snapshots use TXF contract codes; daily TX ticks are historical data.
function txSnapshotValues(data,daily,cal,now,history,source='FinMind 即時電子盤'){
 const d=daily.filter(x=>x.date===cal.prev&&x.trading_session==='position'&&/^\d{6}$/.test(String(x.contract_date))).sort((a,b)=>num(b.volume)-num(a.volume))[0];
 if(!d)throw Error('缺前日同月合約收盤基準');
 const month=String(d.contract_date),code='TXF'+'ABCDEFGHIJKL'[Number(month.slice(4))-1]+month[3];
 const x=data.find(x=>x.futures_id===code);if(!x)throw Error('即時來源缺同月契約 '+code+'，不混用連續月報價');
 const t=Date.parse(String(x.date).replace(' ','T').replace(/(\.\d{3})\d+/,'$1')+'+08:00'),p=num(x.close);
 const start=at(cal.prev,'15:00'),end=Math.min(now,at(shift(cal.prev,1),'05:00'));
 if(!fin(t)||t<start||t>end||end-t>120000||!(p>0))throw Error('電子盤成交時間過期或不屬於本夜盤，暫停計分');
 const key=cal.prev+'-'+month;
 if(history.key!==key){history.key=key;history.points=[]}
 const points=history.points.filter(q=>q.t>=t-3720000&&q.t<=t);
 if(!points.some(q=>q.t===t))points.push({t,p});points.sort((a,b)=>a.t-b.t);history.points=points;
 const hour=last(points.filter(q=>q.t<=t-3600000&&q.t>=t-3720000));
 const o=num(x.open),h=num(x.high),l=num(x.low),validRange=h>=p&&l<=p&&h>=l&&l>0;
 const v=[level(ret(p,num(d.close)),.3,.8),o>0?level(ret(p,o),.15,.4):NaN,hour?level(ret(p,hour.p),.15,.4):NaN,validRange?band(pos({c:p,h,l}),70,90):NaN];
 return v.map((value,i)=>({v:value,live:true,date:day(t),checked:now,source:source+' · '+code+' · 每 10 秒查詢',info:'成交 '+fmt(p)+' · '+new Date(t).toLocaleString('zh-TW',{timeZone:'Asia/Taipei',hour12:false})+' · '+[num(d.close)>0?'相對前日日盤收盤':'缺同月前日日盤收盤','相對夜盤開盤（盤中暫估）',hour?'相對一小時前成交':'等待累積一小時行情；缺基準不計分','來源提供的完整夜盤高低區間'][i],title:['夜盤漲跌','夜盤開盤至今（暫估）','近一小時（暫估）','夜盤區間位置（暫估）'][i]}));
}
let txDailyCache=null,txHistory={key:'',points:[]};
try{const h=JSON.parse(localStorage.getItem('tx-snapshot-hour-v1'));if(h&&Array.isArray(h.points))txHistory=h}catch{}
async function publicTXQuotes(){
 const response=await fetch('https://mis.taifex.com.tw/futures/api/getQuoteList',{
 method:'POST',headers:{'Content-Type':'application/json'},cache:'no-store',signal:AbortSignal.timeout(8000),
 body:JSON.stringify({MarketType:'1',SymbolType:'F',KindID:'1',CID:'TXF',ExpireMonth:'',RowSize:'全部',PageNo:'',SortColumn:'',AscDesc:'A'})
 });
 if(!response.ok)throw Error('期交所 HTTP '+response.status);
 const quotes=(await response.json()).RtData?.QuoteList;
 if(!Array.isArray(quotes))throw Error('期交所夜盤尚無報價');
 return quotes;
}
function exchangeRows(quotes,daily,cal,now,history){
 const listed=quotes.filter(x=>/^TXF[A-L]\d-M$/.test(x.SymbolID)&&num(x.CTotalVolume)>0)
 .sort((a,b)=>num(b.CTotalVolume)-num(a.CTotalVolume));
 const x=listed[0];if(!x)throw Error('期交所缺有成交的台指期夜盤月契約');
 const code=x.SymbolID.slice(0,-2),year=Number(cal.prev.slice(0,4));
 const contractYear=year+(Number(code[4])-year%10+10)%10;
 const month=String(contractYear)+String('ABCDEFGHIJKL'.indexOf(code[3])+1).padStart(2,'0');
 const d=String(x.CDate),time=String(x.CTime).padStart(6,'0');
 if(!/^\d{8}$/.test(d)||!/^\d{6}$/.test(time))throw Error('期交所缺成交日期或時間');
 const date=d.slice(0,4)+'-'+d.slice(4,6)+'-'+d.slice(6)+' '+time.slice(0,2)+':'+time.slice(2,4)+':'+time.slice(4);
 const previous=daily.find(row=>row.date===cal.prev&&row.trading_session==='position'&&String(row.contract_date)===month);
 return txSnapshotValues([{futures_id:code,date,close:x.CLastPrice,open:x.COpenPrice,high:x.CHighPrice,low:x.CLowPrice}],
 [{date:cal.prev,trading_session:'position',contract_date:month,close:previous?.close}],cal,now,history,'期交所 · 台指期夜盤');
}
let nextDailyAttempt=0;
async function previousDaily(cal,token){
 if(txDailyCache?.date===cal.prev)return txDailyCache.data;
 if(Date.now()<nextDailyAttempt)return [];
 nextDailyAttempt=Date.now()+60000;
 const pars=new URLSearchParams({dataset:'TaiwanFuturesDaily',data_id:'TX',start_date:cal.prev,end_date:cal.prev});
 try{const r=await liveJSON('https://api.finmindtrade.com/api/v4/data?'+pars,token?{Authorization:'Bearer '+token}:{});
 if(r.status===200&&r.data?.length){txDailyCache={date:cal.prev,data:r.data};return r.data}}catch{}
 return [];
}
async function pollTX(){
 if(document.hidden||txLive.busy||!snap||snap.cal.replay||Date.now()>=at(snap.cal.target,'05:10'))return;
 const cal={...snap.cal},token=document.getElementById('token').value.trim();
 if(Date.now()<at(cal.prev,'15:00')||Date.now()>at(shift(cal.prev,1),'05:00')+600000)return;
 txLive.busy=true;
 try{
 const daily=previousDaily(cal,token);
 let rows;
 try{const [quotes,bars]=await Promise.all([publicTXQuotes(),daily]);rows=exchangeRows(quotes,bars,cal,Date.now(),txHistory)}
 catch(e){if(!token)throw Error('期交所：'+e.message);
 const r=await liveJSON('https://api.finmindtrade.com/api/v4/taiwan_futures_snapshot?data_id=TXF&_='+Date.now(),{Authorization:'Bearer '+token});
 if(r.status!==200||!Array.isArray(r.data))throw Error('期交所：'+e.message+'；FinMind：'+(r.msg||'缺報價'));
 rows=txSnapshotValues(r.data,await daily,cal,Date.now(),txHistory)}
 if(snap?.cal.target===cal.target){txLive.target=cal.target;txLive.rows=rows;try{localStorage.setItem('tx-snapshot-hour-v1',JSON.stringify(txHistory))}catch{}render()}
 }catch(e){if(snap?.cal.target===cal.target){txLive.target=cal.target;txLive.rows=Array.from({length:4},()=>({v:NaN,live:true,checked:Date.now(),source:'台指期夜盤',info:e.message}));render()}}
 finally{txLive.busy=false}
}