function historyPoster({target,source,scores,outcomes,pc,o,c}){
 const signed=n=>(n>0?'+':'')+n.toFixed(2);
 const points=[o-pc,c-o],pct=[100*points[0]/pc,100*points[1]/o];
 const tone=n=>n>0?'up':n<0?'down':'flat';
 const biggest=Math.max(...pct.map(Math.abs),0.01);
 const evaluated=outcomes.filter(v=>['命中','未命中'].includes(v)).length;
 const hits=outcomes.filter(v=>v==='命中').length;
 const bothWatching=scores.length===2&&scores.every(s=>s.dir===0);
 const title=bothWatching?'開盤、走勢皆為觀望':evaluated?'命中 '+hits+'／'+evaluated+' 項':'本日無可評估的預判';
 const rows=scores.map((s,i)=>{
  const names=i?['走低','走高']:['開低','開高'];
  const actual=points[i]===0?'平盤':names[+(points[i]>0)];
  const prediction=s.dir===null?'暫不判定':s.dir===0?'觀望':names[+(s.dir>0)];
  return '<div class="hp-row"><div class="hp-heading"><b>'+['開盤方向','盤中走勢'][i]+'</b><span class="hp-outcome">'+outcomes[i]+'</span></div><div class="hp-pair"><div><small>盤前預判</small><b>'+prediction+'</b></div><div><small>實際結果</small><b class="'+tone(points[i])+'">'+actual+'</b></div></div><small>'+ (s.coverage?s.score+' 分':'待評分')+' · 資料覆蓋 '+Number(s.coverage).toFixed(1)+'%</small></div>';
 }).join('');
 const bars=pct.map((v,i)=>'<div class="hp-barline"><div>'+['昨收 → 開盤','開盤 → 收盤'][i]+'<b class="'+tone(v)+'">'+signed(v)+'%</b></div><div class="hp-track"><i class="'+tone(v)+'" style="width:'+Math.abs(v)/biggest*49+'%;'+(v<0?'right':'left')+':50%"></i></div><small>'+signed(points[i])+' 點</small></div>').join('');
 const node=historyEl('histPoster');
 node.innerHTML='<small>'+target+'　台股加權指數 · '+source+'</small><h2>'+title+'</h2>'+(evaluated===1?'<small>另 1 項未計入</small>':'')+rows+'<h3>實際漲跌幅</h3>'+bars+'<div class="hp-total">全日收盤較昨收 <b class="'+tone(c-pc)+'">'+signed(c-pc)+' 點（'+signed(100*(c-pc)/pc)+'%）</b></div>';
 node.hidden=false;
}
