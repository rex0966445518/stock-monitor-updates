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
function txSnapshotValues(data,daily,cal,now,history){
 const d=daily.filter(x=>x.date===cal.prev&&x.trading_session==='position'&&/^\d{6}$/.test(String(x.contract_date))&&num(x.close)>0).sort((a,b)=>num(b.volume)-num(a.volume))[0];
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
 return v.map((value,i)=>({v:value,live:true,date:day(t),checked:now,source:'FinMind 即時電子盤 · '+code+' · 10 秒查詢／來源約 30 秒更新',info:'成交 '+fmt(p)+' · '+new Date(t).toLocaleString('zh-TW',{timeZone:'Asia/Taipei',hour12:false})+' · '+['相對前日日盤收盤','相對夜盤開盤（盤中暫估）',hour?'相對一小時前成交':'等待累積一小時行情；缺基準不計分','來源提供的完整夜盤高低區間'][i],title:['夜盤漲跌','夜盤開盤至今（暫估）','近一小時（暫估）','夜盤區間位置（暫估）'][i]}));
}
let txDailyCache=null,txHistory={key:'',points:[]};
try{const h=JSON.parse(localStorage.getItem('tx-snapshot-hour-v1'));if(h&&Array.isArray(h.points))txHistory=h}catch{}
async function pollTX(){
 if(document.hidden||txLive.busy||!snap||Date.now()>=at(snap.cal.target,'05:10'))return;
 txLive.busy=true;const cal={...snap.cal};
 try{
 const token=document.getElementById('token').value.trim();
 if(!token)throw Error('尚未設定 FinMind 即時期貨權限憑證；請至行情服務設定填入 Sponsor Token');
 const headers={Authorization:'Bearer '+token};
 let r;
 try{r=await liveJSON('https://api.finmindtrade.com/api/v4/taiwan_futures_snapshot?data_id=TXF&_='+Date.now(),headers)}catch(e){throw Error('即時期貨連線失敗：'+e.message+'；若為 400／401／403，請確認憑證及 Sponsor 權限')}
 if(r.status!==200||!Array.isArray(r.data))throw Error('即時期貨未授權或無資料：'+String(r.msg||r.status));
 if(!txDailyCache||txDailyCache.date!==cal.prev){
 const pars=new URLSearchParams({dataset:'TaiwanFuturesDaily',data_id:'TX',start_date:cal.prev,end_date:cal.prev});
 const daily=await liveJSON('https://api.finmindtrade.com/api/v4/data?'+pars,headers);
 if(daily.status!==200||!Array.isArray(daily.data))throw Error('無法取得前日同月合約收盤基準');
 txDailyCache={date:cal.prev,data:daily.data};
 }
 if(snap.cal.target===cal.target){txLive.target=cal.target;txLive.rows=txSnapshotValues(r.data,txDailyCache.data,cal,Date.now(),txHistory);try{localStorage.setItem('tx-snapshot-hour-v1',JSON.stringify(txHistory))}catch{}render()}
 }catch(e){if(snap.cal.target===cal.target){txLive.target=cal.target;txLive.rows=Array.from({length:4},()=>({v:NaN,live:true,checked:Date.now(),source:'FinMind · 台指期即時電子盤',info:String(e.message)}));render()}}
 finally{txLive.busy=false}
}
