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
async function pollTX(){
 if(document.hidden||txLive.busy||!snap||Date.now()>=at(snap.cal.target,'05:10'))return;
 txLive.busy=true;const cal={...snap.cal};
 try{
 const token=document.getElementById('token').value.trim(),headers=token?{Authorization:'Bearer '+token}:{};
 const read=async(dataset,start,end)=>{const p=new URLSearchParams({dataset,start_date:start,end_date:end,data_id:'TX',_:String(Date.now())});const r=await liveJSON('https://api.finmindtrade.com/api/v4/data?'+p,headers);if(r.status!==200||!Array.isArray(r.data))throw Error('FinMind '+(r.msg||r.status||'無資料'));return r.data};
 const [ticks,daily]=await Promise.all([read('TaiwanFuturesTick',cal.prev,cal.target),read('TaiwanFuturesDaily',cal.prev,cal.prev)]);
 if(snap.cal.target===cal.target){txLive.target=cal.target;txLive.rows=txDynamicValues(ticks,daily,cal,Date.now());render()}
 }catch(e){if(snap.cal.target===cal.target){txLive.target=cal.target;txLive.rows=Array.from({length:4},()=>({v:NaN,live:true,checked:Date.now(),source:'FinMind · TX · 每 10 秒查詢',info:String(e.message)}));render()}}
 finally{txLive.busy=false}
}
