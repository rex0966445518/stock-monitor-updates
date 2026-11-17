// Exchange files contain the entire trading day. Keep only trades observed by each cutoff.
const txWindows={'04:00':14400,'05:00':18000,'05:10':18600};
function txObservations(csv,date,nightDate){
 const contracts={};
 for(const line of csv.split('\n')){
  const [day,symbol,month,clock,value]=line.split(',',5).map(x=>x.trim());
  if(symbol!=='TX'||!/^\d{6}$/.test(month)||!/^\d{6}$/.test(clock)||!(Number(value)>0))continue;
  const seconds=Number(clock.slice(0,2))*3600+Number(clock.slice(2,4))*60+Number(clock.slice(4));
  for(const [time,end] of Object.entries(txWindows)){
   if(day!==(time==='05:10'?date:nightDate).replaceAll('-','')||seconds>end||seconds<end-120)continue;
   const obs=contracts[month]??={};
   if(!obs[time]||seconds>=obs[time].seconds)obs[time]={seconds,price:Number(value)};
  }
 }
 return contracts;
}
async function txArchive(date,format,nightDate){
 const name='Daily_'+date.replaceAll('-','_');
 const folder=format==='CSV'?'DailydownloadCSV':'Dailydownload';
 const url='https://www.taifex.com.tw/file/taifex/Dailydownload/'+folder+'/'+name+'.zip';
 const bytes=await new Promise((resolve,reject)=>{
  const x=new XMLHttpRequest();x.open('GET',url);x.responseType='arraybuffer';x.timeout=35000;
  x.onload=()=>x.status===200?resolve(x.response):reject(Error('HTTP '+x.status));
  x.onerror=()=>reject(Error('連線失敗'));x.ontimeout=()=>reject(Error('下載逾時'));x.send();
 });
 const view=new DataView(bytes);
 if(view.getUint32(0,true)!==0x04034b50)throw Error('回應並非逐筆壓縮檔');
 const start=30+view.getUint16(26,true)+view.getUint16(28,true);
 const size=view.getUint32(18,true),method=view.getUint16(8,true);
 const blob=new Blob([bytes.slice(start,start+size)]);
 const stream=method===8?blob.stream().pipeThrough(new DecompressionStream('deflate-raw')):blob.stream();
 return txObservations(new TextDecoder('big5').decode(await new Response(stream).arrayBuffer()),date,nightDate);
}
async function fillTxPrices(date,month,prices,required,replay,nightDate){
 const notes=[],sources={};
 function fill(data,label){
  for(const time of required){
   const value=data?.[month]?.[time]?.price;
   if(!fin(prices[time])&&fin(value)&&value>0){prices[time]=value;sources[time]=label}
  }
 }
 if(replay){
  const key='tx-ticks-v1-'+date;
  try{fill(JSON.parse(localStorage.getItem(key)),'期交所・本機留存')}catch{}
  fill(txVerified[date],'期交所・已核對逐筆檔');
  for(const format of ['CSV','RPT']){
   if(required.every(t=>fin(prices[t])))break;
   try{
    const data=await txArchive(date,format,nightDate);fill(data,'期交所・'+format);
    if(Object.keys(data).length){try{localStorage.setItem(key,JSON.stringify(data))}catch{}}
    if(required.some(t=>!fin(prices[t])))notes.push(format+' 缺同月指定時點成交');
   }catch(e){notes.push(format+' '+e.message)}
  }
 }
 return {sources,notes};
}
async function txReport(date,night){
 const stamp=date.replaceAll('-','/'),session=night?'盤後':'一般';
 const url='https://www.taifex.com.tw/cht/3/futDailyMarketReport?queryType=2&marketCode='+(night?1:0)+'&commodity_id=TX&queryDate='+encodeURIComponent(stamp);
 const doc=new DOMParser().parseFromString(await wire(url),'text/html');
 const report=doc.querySelector('#printhere');
 if(!report||!Array.from(report.querySelectorAll('p')).some(p=>p.textContent.replace(/\s/g,'')==='日期：'+stamp)||!report.textContent.includes(session+'交易時段行情表'))throw Error('期交所未回傳指定日期與交易時段');
 const records=[];
 for(const row of doc.querySelectorAll('tr')){
  const c=[...row.children].map(td=>td.textContent.trim());
  if(c[0]!=='TX'||!/^\d{6}$/.test(c[1])||!(num(c[5])>0))continue;
  records.push({date,contract_date:c[1],trading_session:night?'after_market':'position',max:num(c[3]),min:num(c[4]),close:num(c[5]),volume:num(c[night?8:9])});
 }
 if(!records.length)throw Error('期交所缺 TX 同月合約行情');
 return records;
}
