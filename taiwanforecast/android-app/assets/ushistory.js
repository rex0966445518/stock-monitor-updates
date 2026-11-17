// Only completed bars ending at the required cutoff qualify; never use later bars.
function nqWeekendClosed(time){
 const parts=new Intl.DateTimeFormat('en-US',{timeZone:'America/Chicago',weekday:'short',hour:'2-digit',minute:'2-digit',hourCycle:'h23'}).formatToParts(time);
 const get=k=>parts.find(p=>p.type===k)?.value,wd=get('weekday'),m=Number(get('hour'))*60+Number(get('minute'));
 return wd==='Sat'||wd==='Sun'&&m<17*60||wd==='Fri'&&m>=16*60;
}
function nqPair(bars,base,cut,minutes){
 const span=minutes*60000;
 const get=time=>bars.filter(x=>fin(x.c)&&x.c>0&&x.t+span===time).sort((a,b)=>a.t-b.t).pop();
 const a=get(base),b=get(cut);if(!a||!b)throw Error('缺 '+taipeiStamp(!a?base:cut)+' 截止的完整 '+minutes+' 分鐘K線');
 return {base:a.c,close:b.c,value:ret(b.c,a.c)};
}
async function historicalNQ(target,base,cut,series){
 if(nqWeekendClosed(cut)){const e=Error('指定截點 '+taipeiStamp(cut)+'（台灣）為 CME 週末休市；該時點無成交，不使用開盤後資料補算');e.marketClosed=true;throw e}
 const key='nq-cutoff-pair-v1-'+target+'-'+base+'-'+cut,attempts=[];
 for(const [interval,minutes] of [['1m',1],['5m',5]])try{
 const result=nqPair(await series('NQ=F',interval),base,cut,minutes);
 const row={...result,source:'Yahoo · NQ '+minutes+' 分鐘K線',backup:minutes!==1,baseTime:base,cutTime:cut,symbol:'NQ=F'};
 try{localStorage.setItem(key,JSON.stringify(row))}catch{}return row;
 }catch(e){attempts.push('Yahoo '+interval+'：'+e.message)}
 try{const c=JSON.parse(localStorage.getItem(key));if(c?.symbol==='NQ=F'&&c.baseTime===base&&c.cutTime===cut&&fin(c.base)&&c.base>0&&fin(c.close)&&c.close>0)return {...c,value:ret(c.close,c.base),source:c.source+'・已驗證本機留存',backup:true}}catch{}
 attempts.push('本機留存：無符合日期及截點資料','TradingView：尚無可用的自動歷史行情接口','其他授權資料商：尚未設定授權來源');
 throw Error(attempts.join('；'));
}
