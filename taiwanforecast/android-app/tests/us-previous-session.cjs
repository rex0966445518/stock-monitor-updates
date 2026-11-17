const fs=require('fs'),vm=require('vm'),assert=require('assert'),path=require('path');
const c=vm.createContext({console,Date,Intl});
for(const f of ['engine','feeds','schedule','ushistory'])vm.runInContext(fs.readFileSync(path.join(__dirname,'../assets',f+'.js'),'utf8'),c);
const run=s=>vm.runInContext(s,c);
assert.equal(run("latestUSSession(at('2026-11-16','05:10'))"),'2026-11-13');
assert.equal(run("latestUSSession(at('2026-07-06','05:10'))"),'2026-07-02'); // Friday holiday + weekend
assert.equal(run("latestUSSession(at('2026-11-27','05:10'))"),'2026-11-25'); // Thanksgiving
assert.equal(run("latestUSSession(at('2026-11-18','05:10'))"),'2026-11-17');
assert.equal(run("historicalNQWindow(at('2026-11-16','05:10')).cut"),run("at('2026-11-14','05:10')"));
assert.equal(run("historicalNQWindow(at('2026-07-06','05:10')).base"),run("at('2026-07-03','04:00')"));
assert.equal(run("completedUSReturn([{date:'2026-11-12',c:100},{date:'2026-11-13',c:101},{date:'2026-11-16',c:999}],at('2026-11-16','05:10')).r"),1);
assert.throws(()=>run("completedUSReturn([{date:'2026-11-11',c:99},{date:'2026-11-12',c:100}],at('2026-11-16','05:10'))"));
console.log('PASS Monday→Friday; holiday rollback; ordinary weekday; NQ prior-session window; DST; future/stale rejection');
