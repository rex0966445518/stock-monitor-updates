function loanValue(report,date){
 if(dateNorm(report.date)!==date)throw Error('借券報表日期不符：'+date);
 const table=report.tables?.[0]||report;
 const group=table.groups?.find(g=>g.title==='借券賣出成交');
 const col=table.fields.findIndex((v,i)=>/借券賣出.*(金額|成交值)/.test(v)||(group&&i>=group.start&&i<Number(group.start)+Number(group.span)&&v==='金額'));
 const row=table.data.find(r=>String(r[0]).trim()==='合計');
 if(col<0||!row||!fin(num(row[col])))throw Error(date+' 缺借券賣出成交金額合計');
 return num(row[col]);
}
async function otcDirection(date){
 try{
  const url='https://www.tpex.org.tw/www/zh-tw/afterTrading/tradingIndex?date='+date.replaceAll('-','/')+'&response=json';
  const t=(await json(url)).tables[0],i=t.fields.indexOf('漲/跌');
  const row=t.data.find(r=>dateNorm(r[0])===date);
  if(row&&i>=0&&fin(num(row[i])))return {sign:Math.sign(num(row[i])),source:'櫃買中心'};
 }catch{}
 const url='https://goodinfo.tw/tw/ShowK_Chart.asp?CHT_CAT=DATE&STOCK_ID='+encodeURIComponent('櫃買指數');
 const html=new DOMParser().parseFromString(await wire(url),'text/html');
 for(const tr of html.querySelectorAll('tr')){
  const c=Array.from(tr.children,td=>td.textContent.trim());
  if(c.length<7||c[0].replace(/^['’]/,'')!==date.slice(2).replaceAll('-','/'))continue;
  const close=num(c[4]),delta=num(c[5]);
  if(close>0&&fin(delta))return {sign:Math.sign(delta),source:'Goodinfo 櫃買日K'};
 }
 throw Error(date+' 櫃買中心與 Goodinfo 均無可核對的櫃買日行情');
}

const weightSnapshot={"date": "2026-10-30", "codes": ["2330", "2454", "2308", "2317", "3711", "1303", "2881", "2882", "2383", "3037"]};
const loanSnapshot={"2026-10-06": 30878513840, "2026-10-07": 33612760000, "2026-10-08": 30332029510, "2026-10-12": 34564707810, "2026-10-13": 50727996090, "2026-10-14": 43682937830, "2026-10-15": 85829897860, "2026-10-16": 37484724850, "2026-10-19": 43821620450, "2026-10-20": 32528737510, "2026-10-21": 31935127980, "2026-10-22": 32238467040, "2026-10-27": 39090505030, "2026-10-28": 23422147270, "2026-10-29": 35510997300, "2026-10-30": 36945246190, "2026-11-02": 33564400170, "2026-11-03": 61972037540, "2026-11-04": 52002332360, "2026-11-05": 52808075160, "2026-11-06": 39270448730, "2026-11-09": 17515362420, "2026-11-10": 17591374220, "2026-11-11": 16352918650, "2026-11-12": 15954530150, "2026-11-13": 15405538940, "2026-11-16": 15881262870, "2026-11-17": 11184590220};
