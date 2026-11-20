const txManualFields=[
 ['previous','前一日盤收盤'],['open','夜盤開盤'],['high','夜盤最高'],
 ['low','夜盤最低'],['hour','04:00 成交或觀察價'],['close','夜盤收盤']
];
const txManualKey=date=>'tx-manual-'+date;
const txKnownNight={'2026-11-20':{previous:38649,open:39200,high:39765,low:37931,hour:38157,close:38107}};

function txManualRead(date){
 try{return JSON.parse(localStorage.getItem(txManualKey(date)))||null}catch{return null}
}

function applyTxManual(rows,cal){
 const date=cal.target;
 const manual=txManualRead(date);
 const prices=manual||txKnownNight[date];
 if(!prices||Date.now()<txClose(cal))return rows;
 const {previous,open,high,low,hour,close}=prices;
 const computations=[
  [level(ret(close,previous),.3,.8),previous+' → '+close+' 點'],
  [level(ret(close,open),.15,.4),open+' → '+close+' 點'],
  [level(ret(close,hour),.15,.4),'04:00 基準 '+hour+' → '+close+' 點'],
  [band(pos({c:close,h:high,l:low}),70,90),'收盤位於區間 '+fmt(pos({c:close,h:high,l:low}))+'%']
 ];
 for(const [i,[v,info]] of computations.entries()){
  if(fin(v)&&(manual||!fin(rows[i]?.v))){
   const source=manual?'手動提交':['期交所日報／電子盤截圖','晚間報導／電子盤截圖','使用者提供的 04:00 觀察價','電子盤截圖'][i];
   rows[i]={v,info,date,source,backup:!manual};
  }
 }
 return rows;
}

const txForm=document.getElementById('txManualForm');
const txDialog=document.getElementById('txManualDialog');
const txFeedback=document.getElementById('txManualFeedback');
function openTxManual(){
 const date=snap.cal.target;
 const prices=txManualRead(date)||txKnownNight[date]||{};
 txForm.dataset.date=date;
 document.getElementById('txManualDate').textContent=date+' 夜盤交易日 · 同月大台，單位為點';
 for(const [key] of txManualFields)txForm.elements.namedItem(key).value=prices[key]??'';
 txFeedback.textContent='';
 txDialog.showModal();
}

txForm.addEventListener('submit',event=>{
 event.preventDefault();
 const date=txForm.dataset.date;
 const prices={};
 for(const [key,label] of txManualFields){
  const raw=txForm.elements.namedItem(key).value.normalize('NFKC').trim().replaceAll(',','');
  if(!raw)continue;
  const price=Number(raw);
  if(!/^\d+$/.test(raw)||!Number.isSafeInteger(price)||price<=0){
   txFeedback.textContent=label+'請輸入正整數點數。';
   return;
  }
  prices[key]=price;
 }
 const {open,high,low,hour,close}=prices;
 if(!close||!(prices.previous||open||hour||(high&&low))){
  txFeedback.textContent='請填夜盤收盤及至少一項比較基準。收盤位置需要最高與最低。';
  return;
 }
 if((low&&high&&low>high)||[open,hour,close].filter(Boolean).some(price=>(low&&price<low)||(high&&price>high))){
  txFeedback.textContent='請核對夜盤高低區間與各時點價格。';
  return;
 }
 try{localStorage.setItem(txManualKey(date),JSON.stringify(prices))}catch{
  txFeedback.textContent='無法儲存手動資料。';
  return;
 }
 txDialog.close();
 render();
});

document.getElementById('txManualClear').onclick=()=>{
 try{localStorage.removeItem(txManualKey(txForm.dataset.date))}catch{
  txFeedback.textContent='無法清除手動資料。';
  return;
 }
 txDialog.close();
 render();
};
document.getElementById('txManualCancel').onclick=()=>txDialog.close();
document.getElementById('groups').addEventListener('click',event=>{
 if(event.target.closest('[data-tx-manual]'))openTxManual();
});
