// The exchange file is named for the trading day and records calendar dates per trade.
const txWindows={'04:00':14400,'05:00':18000};
function txObservations(csv,nightDate){
 const contracts={},morning=nightDate.replaceAll('-',''),evening=shift(nightDate,-1).replaceAll('-','');
 for(const line of csv.split('\n')){
  const [day,symbol,month,clock,value,quantity]=line.split(',',6).map(x=>x.trim());
  if(symbol!=='TX'||!/^\d{6}$/.test(month)||!/^\d{6}$/.test(clock)||!(Number(value)>0))continue;
  const seconds=Number(clock.slice(0,2))*3600+Number(clock.slice(2,4))*60+Number(clock.slice(4));
  if(!((day===evening&&seconds>=54000)||(day===morning&&seconds<=18000)))continue;
  const price=Number(value), order=seconds+(day===morning?86400:0);
  const obs=contracts[month]??={open:price,max:price,min:price,close:price,first:order,last:order,volume:0};
  if(order<obs.first){obs.open=price;obs.first=order}
  if(order>=obs.last){obs.close=price;obs.last=order}
  obs.max=Math.max(obs.max,price);obs.min=Math.min(obs.min,price);obs.volume+=Number(quantity)/2||0;
  if(day!==morning)continue;
  for(const [time,end] of Object.entries(txWindows)){
   if(seconds<end-120||seconds>end||(time==='04:00'&&seconds===end))continue;
   if(!obs[time]||seconds>=obs[time].seconds)obs[time]={seconds,price};
  }
 }
 return contracts;
}
const txArchiveCache=new Map();
async function txArchive(date,format,nightDate){
 const key=date+format+nightDate,old=txArchiveCache.get(key);if(old&&Date.now()-old.time<300000)return old.promise;
 const promise=txArchiveRead(date,format,nightDate);txArchiveCache.set(key,{time:Date.now(),promise});return promise;
}
async function txArchiveRead(date,format,nightDate){
 const name='Daily_'+date.replaceAll('-','_');
 const folder=format==='CSV'?'DailydownloadCSV':'Dailydownload';
 const url='https://www.taifex.com.tw/file/taifex/Dailydownload/'+folder+'/'+name+'.zip';
 const bytes=await new Promise((resolve,reject)=>{
  const x=new XMLHttpRequest();x.open('GET',url);x.responseType='arraybuffer';x.timeout=35000;
  x.onload=()=>x.status===200?resolve(x.response):reject(Error('HTTP '+x.status));
  x.onerror=()=>reject(Error('連線失敗'));x.ontimeout=()=>reject(Error('下載逾時'));x.send();
 });
 const view=new DataView(bytes);
 if(view.byteLength<30||view.getUint32(0,true)!==0x04034b50)throw Error('回應並非逐筆壓縮檔');
 const start=30+view.getUint16(26,true)+view.getUint16(28,true);
 const size=view.getUint32(18,true),method=view.getUint16(8,true);
 const blob=new Blob([bytes.slice(start,start+size)]);
 const stream=method===8?blob.stream().pipeThrough(new DecompressionStream('deflate-raw')):blob.stream();
 return txObservations(new TextDecoder('big5').decode(await new Response(stream).arrayBuffer()),nightDate);
}
async function txArchiveFallback(date,nightDate,needsData){
 const notes=[];let partial={},partialSource='';
 for(const format of ['CSV','RPT']){
  try{
   const data=await txArchive(date,format,nightDate);
   const source='期交所逐筆・'+format;
   if(needsData(data))return {data,source,notes};
   if(!partialSource&&Object.keys(data).length){partial=data;partialSource=source}
   notes.push(format+' 缺同月指定時段成交');
  }catch(e){notes.push(format+' '+e.message)}
 }
 return {data:partial,source:partialSource,notes};
}
function txBeforeFour(point){return point?.seconds>=14280&&point.seconds<14400&&point.price>0}
function txSavedObservation(date,month,time){
 try{
  const key='tx-observation-v2-'+date+'-'+month;
  const point=JSON.parse(localStorage.getItem(key))?.[time];
  if(txBeforeFour(point))return point;
 }catch{}
}
function saveTxObservation(date,month,time,point){
 try{
  const key='tx-observation-v2-'+date+'-'+month;
  const data=JSON.parse(localStorage.getItem(key))||{};
  data[time]=point;
  localStorage.setItem(key,JSON.stringify(data));
 }catch{}
}
async function txReport(date,night){
 const stamp=date.replaceAll('-','/'),session=night?'盤後':'一般';
 const url='https://www.taifex.com.tw/cht/3/futDailyMarketReport?queryType=2&marketCode='+(night?1:0)+'&commodity_id=TX&queryDate='+encodeURIComponent(stamp);
 const doc=new DOMParser().parseFromString(await wire(url),'text/html');
 const report=doc.querySelector('#printhere');
 if(!report||!Array.from(report.querySelectorAll('p')).some(p=>p.textContent.replace(/\s/g,'')==='日期：'+stamp)||!report.textContent.includes(session+'交易時段行情表'))throw Error('期交所未回傳指定日期與交易時段');
 const records=[];
 for(const row of report.querySelectorAll('tr')){
  const c=[...row.children].map(td=>td.textContent.trim());
  if(c[0]!=='TX'||!/^\d{6}$/.test(c[1])||!(num(c[5])>0))continue;
  records.push({date,contract_date:c[1],trading_session:night?'after_market':'position',open:num(c[2]),max:num(c[3]),min:num(c[4]),close:num(c[5]),volume:num(c[night?8:9])});
 }
 if(!records.length)throw Error('期交所缺 TX 同月合約行情');
 return records;
}
