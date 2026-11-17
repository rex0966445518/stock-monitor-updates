// US regular equity sessions, NYSE published calendar (2026–2028).
const holidays = {
  2026:'01-01 01-19 02-16 04-03 05-25 06-19 07-03 09-07 11-26 12-25',
  2027:'01-01 01-18 02-15 03-26 05-31 06-18 07-05 09-06 11-25 12-24',
  2028:'01-17 02-21 04-14 05-29 06-19 07-04 09-04 11-23 12-25'
};
const earlyClose=new Set('2026-11-27 2026-12-24 2027-11-26 2028-07-03 2028-11-24'.split(' '));
function equityClose(date){
 const hour=earlyClose.has(date)?13:16;
 const probe=Date.parse(date+'T20:00:00Z');
 const nyHour=Number(new Intl.DateTimeFormat('en-GB',{timeZone:'America/New_York',hour:'numeric',hourCycle:'h23'}).format(probe));
 return probe+(hour-nyHour)*3600000;
}
function scheduleFor(i,cal){

 if([4,5,6].includes(i)){
  for(let d=shift(cal.target,-1);d>=cal.prev;d=shift(d,-1)){
   const list=holidays[d.slice(0,4)];
   if(!list)return {note:'美股交易日曆待確認'};
   const wd=new Date(at(d,'12:00')).getUTCDay();
   if(wd!==0&&wd!==6&&!list.split(' ').includes(d.slice(5)))return {time:equityClose(d)};
  }
  return {note:'美股休市，本期無隔夜收盤資料'};
 }
 if([1,7,20,21].includes(i))return {time:at(cal.target,'05:10')};
 if([0,2,3].includes(i))return {time:at(cal.target,'05:00')};
 if(i===18)return {time:at(cal.prev,'17:00')};
 if([8,9,10,11,12,22,23,24,25].includes(i))return {time:at(cal.prev,'13:31')};
 return {};
}
const taipeiStamp=t=>day(t).slice(5).replace('-','/')+' '+new Intl.DateTimeFormat('en-GB',{timeZone:'Asia/Taipei',hour:'2-digit',minute:'2-digit',hourCycle:'h23'}).format(t);
function countdown(ms){const m=Math.ceil(ms/60000);return (m>=60?Math.floor(m/60)+' 小時 ':'')+(m%60)+' 分';}
function waitingText(slot,now,loading,cal,retry){
 const prefix=cal.confirmed?'':'交易日待確認 · ';
 if(loading)return prefix+'正在查詢行情…';
 if(slot.time>now && slot.time<retry)return prefix+'預計擷取 '+taipeiStamp(slot.time)+'（台灣） · 還有 '+countdown(slot.time-now);
 const next='下次查詢 '+taipeiStamp(retry)+'（台灣） · 還有 '+countdown(Math.max(0,retry-now));
 return prefix+(slot.note?slot.note+' · ':'')+next+(slot.time>now?' · 預計資料時點 '+taipeiStamp(slot.time)+'（還有 '+countdown(slot.time-now)+'）':'');
}
