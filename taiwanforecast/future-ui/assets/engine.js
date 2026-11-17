'use strict';
const rules=[
['台指期','夜盤漲跌',30,85],['台指期','08:55 相對夜收',35,65],['台指期','最後一小時',20,20],['台指期','夜盤收盤位置',15,20],
['美股','TSM 相對費半',20,40],['美股','費城半導體',20,40],['美股','Nasdaq 100 與 S&P 500',10,15],['美股','Nasdaq 100 期貨',5,10],
['台股價量','成交金額與量比',25,8],['台股價量','上漲家數占比',35,10],['台股價量','大盤收盤位置',25,8],['台股價量','十大權值股',20,8],['台股價量','櫃買、電子及金融',20,6],
['法人籌碼','外資現貨淨買比',25,12],['法人籌碼','外資 TX 部位變化',20,10],['法人籌碼','投信連續買賣',10,4],['法人籌碼','五日融資背離',20,10],['法人籌碼','借券賣出壓力',10,4],
['匯率與亞洲','新台幣升貶',20,20],['匯率與亞洲','美元指數',10,10],['匯率與亞洲','日韓相對昨收',15,25],['匯率與亞洲','日韓開盤後延續',10,10],
['台股技術','5／10／20 日均線',30,20],['台股技術','MACD（12,26,9）',20,15],['台股技術','RSI（14）',10,10],['台股技術','20 日突破或跌破',20,15]];
const fin=Number.isFinite, avg=a=>a.reduce((s,x)=>s+x,0)/a.length, last=a=>a[a.length-1], ret=(a,b)=>fin(a)&&fin(b)&&b>0?(a-b)/b*100:NaN;
const level=(v,a,b)=>fin(v)?Math.sign(v)*(Math.abs(v)>=b?1:Math.abs(v)>=a?.5:0):NaN;
const band=(v,a,b)=>level(v-50,a-50,b-50);
const agree=(a,b)=>!fin(a)||!fin(b)?NaN:a*b>0?Math.sign(a)*Math.min(Math.abs(a),Math.abs(b)):0;
const pos=x=>x.h>x.l?100*(x.c-x.l)/(x.h-x.l):NaN;
const state=n=>n>=600?1:n<=400?-1:0;
function result(rows,col){
 let sum=500,weight=0;rows.forEach((r,i)=>{if(r&&fin(r.v)){sum+=r.v*rules[i][col];weight+=rules[i][col]}});
 const score=Math.round(sum);return {score,coverage:weight/5,dir:weight>=400?state(score):null};
}
function joint(t,g){if(t.dir===null||g.dir===null)return '資料不足';if(!t.dir&&!g.dir)return '多空觀望';return (g.dir===1?'開高':g.dir===-1?'開低':'開盤方向未明')+(t.dir===1?'走高':t.dir===-1?'走低':'・走勢觀望')}
function tech(x,put){
 const a=x.map(v=>v.c),d=last(x),prev=x[x.length-2];if(!prev)return;
 put(10,band(pos(d),70,90),'區間位置 '+fmt(pos(d))+'%');
 if(a.length<35)return;
 const ma=n=>avg(a.slice(-n)),m=[ma(5),ma(10),ma(20)],c=d.c,old5=avg(a.slice(-6,-1));
 let sign=m.every(v=>c>v)?1:m.every(v=>c<v)?-1:0;
 put(22,sign*(sign*(m[0]-m[1])>0&&sign*(m[1]-m[2])>0&&sign*(m[0]-old5)>0?1:.5),'均線 '+m.map(v=>v.toFixed(0)).join('／'));
 let fast=a[0],slow=a[0],dea=0,cache=[];
 for(const v of a){fast+=(v-fast)*2/13;slow+=(v-slow)*2/27;let dif=fast-slow;dea+=(dif-dea)*.2;cache.push([dif,dif-dea])}
 const [f,h]=last(cache),[pf,ph]=cache[cache.length-2];
 put(23,h>0&&ph<=0&&f>pf?1:h<0&&ph>=0&&f<pf?-1:Math.sign(h-ph)*.5,'柱值 '+fmt(h));
 let up=0,dn=0,rs=[];for(let i=1;i<a.length;i++){let ch=a[i]-a[i-1];if(i<=14){up+=Math.max(ch,0)/14;dn+=Math.max(-ch,0)/14}else{up=(13*up+Math.max(ch,0))/14;dn=(13*dn+Math.max(-ch,0))/14}if(i>=14)rs.push(up+dn?100*up/(up+dn):50)}
 let r=last(rs),before=rs[rs.length-2];put(24,r>before&&r>=55?(r>=65?1:.5):r<before&&r<=45?(r<=35?-1:-.5):0,'RSI '+fmt(r));
}
const fmt=v=>fin(v)?v.toFixed(2):'—';
