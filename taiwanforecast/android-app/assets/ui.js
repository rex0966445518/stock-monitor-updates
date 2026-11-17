const el=id=>document.getElementById(id),safe=(s)=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
let retry=Date.now()+300000;
let snap=null,busy=false,expanded=new Set(),posterData='';
const signed=n=>(n>0?'+':'')+Number(n.toFixed(1));
function itemScore(rule, value) {
 if (!fin(value)) return '<span class="item-score pending">待評分</span>';
 const tone = value > 0 ? 'positive' : value < 0 ? 'negative' : 'neutral';
 return '<span class="item-score '+tone+'"><span>走勢 '+signed(value*rule[2])+' 分</span><span>開盤 '+signed(value*rule[3])+' 分</span></span>';
}
function render(){
 if(!snap)return;
 const {cal}=snap,rows=liveRows(snap),t=result(rows,2),g=result(rows,3),stage=Date.now()<at(cal.target,'05:10')?'盤前初估':Date.now()<at(cal.target,'08:55')?'05:10 基準 · 日韓更新至 08:55':'固定評分 · 日韓 08:55／其餘 05:10';
 if(!cal.confirmed){g.dir=null;t.dir=null}
 const verdict=!cal.confirmed?'交易日待確認':joint(t,g);
 el('when').textContent=cal.target+' 預測　·　'+stage+(rows.some(r=>r?.live)?' · 動態暫估（美元 2 分鐘／其他 10 秒）':'');
 el('out').textContent=verdict;
 for(const [id,c,k] of [['trend',t,'ct'],['gap',g,'cg']]){
 const pin=el(id+'Pin');
 pin.hidden=!c.coverage;
 pin.style.left=(c.score/10)+'%';
 const zone=state(c.score);
 el(id+'Band').textContent=c.coverage?'分數落在'+(zone<0?'偏空區':zone>0?'偏多區':'觀望區'):'等待評分';
 el(id).style.color=!c.coverage||!zone?'#e0edf0':zone<0?'#80e8a7':'#ff9292';
 el(id).textContent=c.coverage?String(c.score):'—';el(k).textContent='覆蓋 '+fmt(c.coverage)+'% · '+(c.dir===null?'暫不判定':c.dir>0?'偏多':c.dir<0?'偏空':'觀望')}
 const movers=rows.map((r,i)=>r&&fin(r.v)&&r.v?{name:rules[i][1],n:r.v*rules[i][2]}:null).filter(Boolean).sort((a,b)=>Math.abs(b.n)-Math.abs(a.n));
 const reason=movers.slice(0,3).map(r=>r.name+' '+signed(r.n)).join('、');
 el('summary').textContent=(reason?'走勢分主要來自'+reason+'。':'目前沒有已確認的方向訊號。')+(t.dir===null||g.dir===null?'方向判定需確認交易日及達到 80% 覆蓋率。':'');
 const now=Date.now(),slots=rules.map((_,i)=>scheduleFor(i,cal));
 const upcoming=slots.filter((slot,i)=>!fin(rows[i]?.v)&&slot.time>now).sort((a,b)=>a.time-b.time);
 const notice=el('nextTime');notice.hidden=!upcoming.length;
 if(upcoming.length)notice.textContent='下一次擷取　'+taipeiStamp(upcoming[0].time)+'（台灣）｜還有 '+countdown(upcoming[0].time-now);
 const box=el('groups');box.querySelectorAll('details').forEach(d=>d.open?expanded.add(d.dataset.group):expanded.delete(d.dataset.group));
 box.innerHTML=[...new Set(rules.map(r=>r[0]))].map(gr=>{let group=rules.map((r,i)=>[r,i]).filter(([r])=>r[0]===gr);
 return '<details data-group="'+gr+'" '+(expanded.has(gr)?'open':'')+'><summary>'+gr+'　<small>'+group.filter(([r,i])=>fin(rows[i]?.v)).length+'／'+group.length+'</small></summary>'+group.map(([r,i])=>{let a=rows[i],ok=fin(a?.v);return '<div class="row"><div class="item-head"><span><span class="badge '+(ok?'yes':'pending')+'" aria-hidden="true">'+(ok?'✓':'?')+'</span> '+safe(a?.title||r[1])+'</span>'+itemScore(r,a?.v)+'</div><small class="'+(ok?'yes':'pending')+'">'+(a?.live?(ok?'動態評分 · 已納入總分':'動態行情待更新'):(ok?'已取得資料':'尚未取得資料'))+'</small>'+'<small>'+safe(a?.source||'等待更新')+' · '+safe(a?.date||'')+' '+safe(!ok&&!a?.live&&(slots[i].time>now||slots[i].note)?'':a?.info||'')+'</small><small>'+ (a?.live?(i===19?'每 2 分鐘查詢；報價時間以來源為準':'每 10 秒查詢；報價時間以來源為準'):ok?'':safe(waitingText(slots[i],now,busy&&!a,cal,retry)))+'</small><p class="explain">'+safe(explainItem(i,a?.v))+'</p></div>'}).join('')+'</details>'}).join('');
 el('poster').disabled=busy||!rows.some(r=>fin(r?.v));snap.verdict=verdict;snap.reason=reason;snap.scores=[t,g];el('status').textContent=(busy?'更新中　':'最近查詢　')+new Date(snap.time).toLocaleString('zh-TW',{timeZone:'Asia/Taipei',hour12:false})+'（台灣）';
}
async function refresh(){if(busy)return;busy=true;el('refresh').disabled=true;el('poster').disabled=true;el('status').textContent='正在核對交易日…';requests.clear();
 try{let cal=await calendar(),old=snap;snap={cal,time:Date.now(),rows:Array(rules.length).fill(null)};render();pollUS();pollTX();pollGlobal();await collect(cal,snap.rows,render);savePremarket(snap);
 if(!snap.rows.some(r=>fin(r?.v))&&old&&old.cal.target===cal.target&&old.rows.some(r=>fin(r?.v))){snap=old;el('status').textContent='連線失敗，保留上次查詢'}else if(snap.rows.some(r=>fin(r?.v)))localStorage.setItem('forecast-split0510-0855',JSON.stringify(snap));
 }catch(e){el('status').textContent='更新失敗：'+e.message}finally{retry=Date.now()+300000;busy=false;el('refresh').disabled=false;render()}}
el('refresh').onclick=refresh;
el('poster').onclick=()=>{
 const c=document.createElement('canvas');c.width=1080;c.height=1440;const ctx=c.getContext('2d');
 const txt=(s,x,y,size,color='#ebf4ef',weight=500)=>{ctx.fillStyle=color;ctx.font=weight+' '+size+'px sans-serif';ctx.fillText(s,x,y)};
 const bg=ctx.createLinearGradient(0,0,1080,1440);bg.addColorStop(0,'#142d50');bg.addColorStop(1,'#030b23');ctx.fillStyle=bg;ctx.fillRect(0,0,1080,1440);ctx.fillStyle='#b9ecff';ctx.fillRect(70,80,72,5);const logo=document.querySelector('.brand img');if(logo.complete&&logo.naturalWidth)ctx.drawImage(logo,890,60,120,120);
 txt('台股隔日預測神器',70,190,56);txt(snap.cal.target+(liveRows(snap).some(r=>r?.live)?'　美股動態暫估':'　台灣加權指數'),70,262,34,'#a8bedb');
 txt(snap.verdict,70,445,snap.verdict.length>9?64:88,'#b9ecff',700);
 snap.scores.forEach((s,i)=>{let x=70+i*465;txt(['走勢分','開盤分'][i],x,610,40);txt(s.coverage?String(s.score):'—',x,765,115,'#ffffff',700);txt('覆蓋率 '+fmt(s.coverage)+'%',x,835,32,'#b9ecff')});
 ctx.fillStyle='#365173';ctx.fillRect(70,900,940,2);txt('影響走勢分的主要指標',70,992,36);
 (snap.reason?snap.reason.split('、'):['尚無方向訊號']).forEach((s,i)=>txt(s,72,1067+i*58,37));
 txt('規則評分 0–1000 分｜尚未校準勝率',70,1320,30,'#a8bedb');txt('資料查詢 '+new Date(snap.time).toLocaleString('zh-TW',{timeZone:'Asia/Taipei',hour12:false}),70,1378,27,'#a8bedb');
 posterData=c.toDataURL('image/png');el('preview').src=posterData;el('dlg').showModal();
};
el('save').onclick=()=>{if(window.Poster)window.Poster.savePoster(posterData);else{let a=document.createElement('a');a.href=posterData;a.download='Taiwan-Forecast.png';a.click()}};
el('closeDlg').onclick=()=>el('dlg').close();
try{snap=JSON.parse(localStorage.getItem('forecast-split0510-0855'));if(snap)render()}catch{snap=null}
let lastAuto=0;
function tick(){
 if(document.hidden||busy||!snap)return;
 const now=Date.now();
 const due=rules.some((_,i)=>{const t=scheduleFor(i,snap.cal).time;return t>Math.max(snap.time,lastAuto)&&t<=now});
 if(due||((!snap.cal.confirmed||snap.rows.some(r=>!fin(r?.v)))&&now>=retry)||day(now)>snap.cal.target){lastAuto=now;refresh()}else render();
}
refresh();setInterval(tick,15000);document.addEventListener('visibilitychange',tick);

setInterval(pollUS,10000);document.addEventListener('visibilitychange',()=>{if(!document.hidden){render();pollUS()}});

setInterval(pollTX,10000);document.addEventListener("visibilitychange",()=>{if(!document.hidden)pollTX()});

setInterval(pollGlobal,10000);document.addEventListener("visibilitychange",()=>{if(!document.hidden)pollGlobal()});
