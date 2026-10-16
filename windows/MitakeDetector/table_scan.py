"""Read overlapping viewports; stop only after repeated stable stock-code rows."""
def scan_table(controller, roi, read, progress, max_segments=2000, observed=None,resume=None,checkpoint=None,limit_pages=None,scroll_cap=6,learn_scroll=None):
    from autoswitch import absolute_roi
    table=absolute_roi(roi,controller.rect())
    center=(table['left']+table['width']//2,table['top']+table['height']//2)
    from dataclasses import asdict
    from MitakeDetector import Quote
    saved=resume or {}
    collected={q['code']:Quote(**q) for q in saved.get('quotes',[])}
    previous=tuple(saved['codes']) if saved.get('codes') else None
    stable=0;rejected=saved.get('rejected',0);notches=saved.get('notches',1)
    cap=max(1,min(6,scroll_cap,int(saved.get('cap',scroll_cap))));recovery_stalls=0
    offset=saved.get('offset',0);first=saved.get('segment',0)
    if not saved:
        controller.scroll(center,120*4000);controller.pause(.6)
    else:progress(first+1,len(collected),'接續前次斷點，核對目前股票列')
    def save(next_segment,codes):
        if checkpoint:checkpoint(dict(segment=next_segment,codes=list(codes),offset=offset,notches=notches,cap=cap,rejected=rejected,quotes=[asdict(q) for q in collected.values()]))
    for segment in range(first,min(max_segments,limit_pages) if limit_pages else max_segments):
        controller.check()
        progress(segment+1,len(collected),'擷取股票資料')
        image,_=controller.image(roi)
        quotes,bad=read(image)
        # A blank OCR result cannot establish the bottom; retry fresh captures.
        for retry in range(2):
            if quotes:break
            progress(segment+1,len(collected),f'複查辨識 {retry+1}/2')
            controller.pause(.5);image,_=controller.image(roi);quotes,bad=read(image)
        if not quotes:raise RuntimeError('股票資料連續三次無法辨識，未宣稱已讀完整清單')
        codes=tuple(q.code for q in quotes)
        if saved and previous and not set(codes).intersection(previous):
            progress(segment+1,len(collected),'定位前次捲動位置，保留已讀資料')
            controller.scroll(center,120*4000);controller.pause(.4)
            if offset:controller.scroll(center,-120*offset);controller.pause(.4)
            image,_=controller.image(roi);quotes,bad=read(image);codes=tuple(q.code for q in quotes)
        saved={}
        recovered=False
        if previous is not None and not set(codes).intersection(previous):
            attempts=max(12,notches*3)
            for attempt in range(attempts):
                controller.check()
                progress(segment+1,len(collected),f'捲動過頭，自動回找重疊 {attempt+1}/{attempts}')
                controller.scroll(center,120);offset=max(0,offset-1);controller.pause(.3)
                image,_=controller.image(roi);quotes,bad=read(image);codes=tuple(q.code for q in quotes)
                if set(codes).intersection(previous):
                    recovered=True;cap=max(1,min(cap,notches//2));notches=1
                    if learn_scroll:learn_scroll(cap,True)
                    progress(segment+1,len(collected),'已找回重疊股票，縮小步幅接續')
                    break
            if not recovered:raise RuntimeError('已自動回找多次仍無法核對股票列，斷點已保留，請檢查排行是否重新排序')
        rejected+=bad
        recovery_stalls=recovery_stalls+1 if recovered and codes==previous else 0
        if recovery_stalls>=3:raise RuntimeError('已反覆找回原位置，但單格滾輪仍跳過整頁，進度已保留；請降低系統滾輪行數')
        for q in quotes:collected[q.code]=q
        if observed:observed(quotes,bad)
        save(segment+1,codes)
        progress(segment+1,len(collected),'本段已讀取，繼續複查')
        stable=stable+1 if codes==previous and not recovered else 0
        if stable>=3:
            progress(segment+1,len(collected),'複查三次未移動，推定清單末端')
            return list(collected.values()),rejected
        if previous and codes!=previous and not recovered:
            shared=len(set(codes).intersection(previous))
            moved=max(1,len(previous)-shared)
            rows_per_notch=max(1,moved/notches)
            notches=max(1,min(cap,int(max(1,len(codes)-6)/rows_per_notch)))
        if learn_scroll and not recovered:learn_scroll(cap,False)
        previous=codes
        if limit_pages and segment+1>=limit_pages:return list(collected.values()),rejected
        # Learn the actual wheel distance before increasing overlapping strides.
        controller.scroll(center,-120*notches);offset+=notches;save(segment+1,codes);controller.pause(.35)
    if limit_pages:return list(collected.values()),rejected
    raise RuntimeError(f'已達{max_segments}段保護上限，尚未確認清單底部；請檢查捲動區域')
