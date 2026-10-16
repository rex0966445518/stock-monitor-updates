"""Read overlapping viewports; stop only after repeated stable stock-code rows."""
def scan_table(controller, roi, read, progress, max_segments=2000, observed=None):
    from autoswitch import absolute_roi
    table=absolute_roi(roi,controller.rect())
    center=(table['left']+table['width']//2,table['top']+table['height']//2)
    controller.scroll(center,120*4000);controller.pause(.6)
    collected={};previous=None;stable=0;rejected=0
    for segment in range(max_segments):
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
        if previous is not None and not set(codes).intersection(previous):
            raise RuntimeError('捲動後股票列沒有重疊，可能跳過資料；請降低Windows滾輪捲動行數後重試')
        for q in quotes:collected[q.code]=q
        if observed:observed(quotes,bad)
        progress(segment+1,len(collected),'本段已讀取，繼續複查')
        stable=stable+1 if codes==previous else 0
        if stable>=3:
            progress(segment+1,len(collected),'複查三次未移動，推定清單末端')
            return list(collected.values()),rejected
        previous=codes
        # One notch normally moves three rows, retaining substantial overlap.
        controller.scroll(center,-120);controller.pause(.4)
    raise RuntimeError(f'已達{max_segments}段保護上限，尚未確認清單底部；請檢查捲動區域')
