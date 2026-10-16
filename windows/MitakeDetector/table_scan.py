"""Read overlapping viewports; stop only after repeated stable stock-code rows."""
def scan_table(controller, roi, read, progress, max_segments=2000, observed=None,resume=None,checkpoint=None,limit_pages=None):
    from autoswitch import absolute_roi
    table=absolute_roi(roi,controller.rect())
    center=(table['left']+table['width']//2,table['top']+table['height']//2)
    from dataclasses import asdict
    from MitakeDetector import Quote
    saved=resume or {}
    collected={q['code']:Quote(**q) for q in saved.get('quotes',[])}
    previous=tuple(saved['codes']) if saved.get('codes') else None
    stable=0;rejected=saved.get('rejected',0);notches=saved.get('notches',1)
    offset=saved.get('offset',0);first=saved.get('segment',0)
    if not saved:
        controller.scroll(center,120*4000);controller.pause(.6)
    else:progress(first+1,len(collected),'接續前次斷點，核對目前股票列')
    def save(next_segment,codes):
        if checkpoint:checkpoint(dict(segment=next_segment,codes=list(codes),offset=offset,notches=notches,rejected=rejected,quotes=[asdict(q) for q in collected.values()]))
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
        rejected+=bad
        codes=tuple(q.code for q in quotes)
        if saved and previous and not set(codes).intersection(previous):
            progress(segment+1,len(collected),'定位前次捲動位置，保留已讀資料')
            controller.scroll(center,120*4000);controller.pause(.4)
            if offset:controller.scroll(center,-120*offset);controller.pause(.4)
            image,_=controller.image(roi);quotes,bad=read(image);codes=tuple(q.code for q in quotes)
            if not set(codes).intersection(previous):
                raise RuntimeError('無法確認前次股票位置，進度已保留；請還原畫面後按F8接續，或按重新開始')
        saved={}
        if previous is not None and not set(codes).intersection(previous):
            raise RuntimeError('捲動後股票列沒有重疊，可能跳過資料；請降低Windows滾輪捲動行數後重試')
        for q in quotes:collected[q.code]=q
        if observed:observed(quotes,bad)
        save(segment+1,codes)
        progress(segment+1,len(collected),'本段已讀取，繼續複查')
        stable=stable+1 if codes==previous else 0
        if stable>=3:
            progress(segment+1,len(collected),'複查三次未移動，推定清單末端')
            return list(collected.values()),rejected
        if previous and codes!=previous:
            shared=len(set(codes).intersection(previous))
            moved=max(1,len(previous)-shared)
            rows_per_notch=max(1,moved/notches)
            notches=max(1,min(6,int(max(1,len(codes)-6)/rows_per_notch)))
        previous=codes
        if limit_pages and segment+1>=limit_pages:return list(collected.values()),rejected
        # Learn the actual wheel distance before increasing overlapping strides.
        controller.scroll(center,-120*notches);offset+=notches;save(segment+1,codes);controller.pause(.35)
    if limit_pages:return list(collected.values()),rejected
    raise RuntimeError(f'已達{max_segments}段保護上限，尚未確認清單底部；請檢查捲動區域')
