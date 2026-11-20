const historyEl=id=>document.getElementById(id);
function saved(key){
 try{return JSON.parse(localStorage.getItem(key))||{}}catch{return {}}
}
function scorePair(rows){return [result(rows,3),result(rows,2)]}
function missingIndicators(rows){
 return rules.flatMap(([group,name],i)=>{
  const row=rows[i];
  if(fin(row?.v))return [];
  return [{group,name,source:row?.source||'未記錄',reason:row?.info||'取數程序未回傳明細'}];
 });
}
function showMissing(items,label){
 const panel=historyEl('histMissing');
 panel.replaceChildren();
 if(!label)return;
 const title=document.createElement('h3');
 title.textContent=label+'｜'+(Array.isArray(items)?(items.length?'缺少 '+items.length+' 項指標':'全部指標已取得'):'舊紀錄未保存缺項明細');
 panel.append(title);
 for(const item of items||[]){
  const card=document.createElement('article');
  card.className='missing-card';
  for(const [tag,value] of [['b',item.group+' · '+item.name],['div','資料來源：'+item.source],['div','未取得原因：'+item.reason]]){
   const line=document.createElement(tag);
   line.textContent=value;
   card.append(line);
  }
  panel.append(card);
 }
}
function savePremarket(snapshot){
 const {cal,rows}=snapshot, time=Date.now();
 if(!cal.confirmed||time>=at(cal.target,'09:00')||!rows.some(r=>fin(r?.v)))return;
 const archive=saved('premarket-usprevious-v1');
 const scores=scorePair(rows);
 if(archive[cal.target]?.scores.some((s,i)=>s.coverage>scores[i].coverage))return;
 archive[cal.target]={scores,time,missing:missingIndicators(rows)};localStorage.setItem('premarket-usprevious-v1',JSON.stringify(archive));
}
async function actualMonth(d){
 const r=await tw('TAIEX/MI_5MINS_HIST',d);
 if(r.stat!=='OK'||!Array.isArray(r.data))throw Error('證交所尚無該月行情或連線失敗');
 const fields=['開盤指數','最高指數','最低指數','收盤指數'].map(f=>r.fields.indexOf(f));
 if(fields.includes(-1))throw Error('證交所行情欄位無法辨識');
 return r.data.map(row=>({date:dateNorm(row[0]),values:fields.map(i=>num(row[i]))})).sort((a,b)=>a.date.localeCompare(b.date));
}
function comparison(score,change){
 if(score.dir===null)return '覆蓋不足';
 if(score.dir===0)return '觀望';
 return score.dir===Math.sign(change)?'命中':'未命中';
}
function stats(){
 const records=Object.values(saved('history-results-gap540-v1-nq0500'));
 const lines=[];
 for(const source of ['盤前留存','歷史重算']){
  const group=records.filter(r=>r.source===source);
  lines.push(source+' · 已查 '+group.length+' 個交易日');
  ['開盤','走勢'].forEach((label,i)=>{
   const hits=group.filter(r=>r.outcomes[i]==='命中').length;
   const n=hits+group.filter(r=>r.outcomes[i]==='未命中').length;
   lines.push(label+'：'+(n?(100*hits/n).toFixed(1)+'%（'+hits+'／'+n+'）':'尚無有效樣本')+' · 排除 '+(group.length-n)+' 日');
  });
 }
 historyEl('histStats').textContent=lines.join('\n');
}
const histDate=historyEl('histDate'),histRun=historyEl('histRun'),histResult=historyEl('histResult');
let forecastSelected='';
let latestPreview=tradePair(previewStart());
function syncForecastCalendar(cal){
 if(cal.preview)latestPreview=cal.preview;
 histDate.max=latestPreview.target;
}
function showForecast(cal,rows){
 const scores=scorePair(rows);
 const labels=[['開低','開高'],['走低','走高']];
 const lines=scores.map((s,i)=>{
  const dir=!cal.confirmed?'交易日待確認':s.dir===null?'資料不足':s.dir===0?'觀望':labels[i][+(s.dir>0)];
  return (i?'走勢':'開盤')+'｜'+(s.coverage?s.score+' 分':'待評分')+' · '+dir+' · 覆蓋 '+fmt(s.coverage)+'%';
 });
 histResult.textContent=cal.target+' 盤前初估\n'+lines.join('\n');
}
histDate.max=latestPreview.target;histDate.value=day();
histDate.onchange=()=>{
 forecastSelected='';histRun.textContent=Date.now()<at(histDate.value,'09:00')?'查看盤前預測':'查詢並驗證';
 historyEl('histPoster').hidden=true;historyEl('histDetail').hidden=false;histResult.textContent='';
 const cached=saved('history-results-gap540-v1-nq0500')[histDate.value];historyEl('histRaw').textContent='';historyEl('histDetail').hidden=!cached;
 showMissing(cached?.missing,cached?'上次查詢・'+cached.source:'');
};
histRun.onclick=async()=>{
 historyEl('histPoster').hidden=true;historyEl('histDetail').hidden=true;historyEl('histDetail').open=false;const target=histDate.value;showMissing(null,'');
 if(target && target<=histDate.max && Date.now()<at(target,'09:00')){
  histRun.disabled=histDate.disabled=true;
  try{
   histResult.textContent='正在讀取 '+target+' 盤前預測…';
   const current=snap?.cal.preview?.target===histDate.max && snap.cal.target>=day()?snap.cal:await calendar();syncForecastCalendar(current);
   const cal=[current,current.preview].find(c=>c.target===target);
   if(!cal){histResult.textContent='請選擇可預測的台股交易日。';return}
   forecastSelected=target;
   if(snap?.cal.target===target){
    showForecast(snap.cal,applyTxManual(scoredNqRows(snap),snap.cal));
   }else{
    const preview={cal,rows:Array(rules.length).fill(null)};
    const draw=()=>showForecast(cal,applyTxManual(scoredNqRows(preview),cal));
    draw();await collect(cal,preview.rows,draw);
   }
  }catch(e){histResult.textContent='預測查詢失敗：'+e.message}
  finally{histRun.disabled=histDate.disabled=false}
  return;
 }
 if(!target||target>day()||Date.now()<at(target,'13:31')){histResult.textContent='請選擇已收盤的日期。';return}
 histRun.disabled=histDate.disabled=true;histResult.textContent='正在取得實際行情並核對盤前評分…';
 requests.clear();
 try{
  const month=await actualMonth(target),idx=month.findIndex(r=>r.date===target);
  if(idx<0)throw Error('當日沒有收盤紀錄，可能休市或尚未公布。');
  const previous=idx?month[idx-1]:last(await actualMonth(shift(target.slice(0,7)+'-01',-1)));
  const [o,h,l,c]=month[idx].values,pc=previous?.values[3];
  if(![o,h,l,c,pc].every(x=>fin(x)&&x>0))throw Error('缺少完整指數行情。');
  const changes=[o-pc,c-o],record=saved('premarket-usprevious-v1')[target];
  const source=record?'盤前留存':'歷史重算';
  let scores=record?.scores,missing=record?.missing,backups=[];
  if(!scores){const rows=Array(rules.length).fill(null);await collect({target,prev:previous.date,confirmed:true,replay:true},rows,()=>{});scores=scorePair(rows);missing=missingIndicators(rows);backups=rows.flatMap((r,i)=>r?.backup?[rules[i][1]+'｜'+r.source+'｜'+r.info]:[])}
  scores=scores.map((s,i)=>({...s,dir:s.coverage>=80?state(s.score,i===0?3:2):null}));
  const outcomes=scores.map((s,i)=>comparison(s,changes[i]));
  const sign=n=>(n>0?'+':'')+n.toFixed(2);
  const lines=[target+' 加權指數｜證交所','昨收 '+fmt(pc)+' → 開盤 '+fmt(o)+' → 收盤 '+fmt(c),'最高 '+fmt(h)+'　最低 '+fmt(l),'收盤較昨收 '+sign(c-pc)+' 點（'+sign(ret(c,pc))+'%）',source+(record?' · '+taipeiStamp(record.time):' · 日韓截止 08:55，其餘截止 05:10；歷史資料可能經修訂')];
  scores.forEach((s,i)=>{const labels=i?['走低','走高']:['開低','開高'];const d=changes[i];lines.push((i?'盤中走勢':'開盤跳空')+'｜'+(s.coverage?s.score+' 分':'待評分')+' · 覆蓋 '+fmt(s.coverage)+'%\n預測 '+(s.dir===null?'暫不判定':s.dir===0?'觀望':labels[+(s.dir>0)])+'／實際 '+(d===0?'平盤':labels[+(d>0)])+' '+sign(d)+' 點（'+sign(d/(i?o:pc)*100)+'%） · '+outcomes[i]);});
  if(backups.length)lines.push('備援補齊 '+backups.length+' 項\n'+backups.join('\n'));
  showMissing(missing,source);historyEl('histRaw').textContent=lines.join('\n');historyPoster({target,source,scores,outcomes,pc,o,c});histResult.textContent='';historyEl('histDetail').hidden=false;const records=saved('history-results-gap540-v1-nq0500');records[target]={source,outcomes,missing};localStorage.setItem('history-results-gap540-v1-nq0500',JSON.stringify(records));stats();
 }catch(e){histResult.textContent=target+'｜'+e.message+' 本次未新增樣本。'}finally{histRun.disabled=histDate.disabled=false}
};
stats();
