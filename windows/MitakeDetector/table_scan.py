"""Read overlapping viewports; stop only after repeated stable stock-code rows."""
class RecoverableScanError(RuntimeError):
    """Transient OCR/overlap failure; retry only from a preserved checkpoint."""


def scan_with_recovery(run,state,check,progress,pause,on_error=lambda exc:None,attempts=3):
    for attempt in range(attempts):
        check()
        try:return run(state[0])
        except RecoverableScanError as exc:
            on_error(exc)
            check()
            if attempt+1>=attempts:
                raise RecoverableScanError('自動恢復已達上限，進度已保留；最後原因：'+str(exc)) from exc
            progress(f'暫時讀取失敗，保留斷點自動接續 {attempt+1}/{attempts-1}｜'+str(exc))
            pause(1)


def scan_table(controller, roi, read, progress, max_segments=2000, observed=None,resume=None,checkpoint=None,limit_pages=None,scroll_cap=12,learn_scroll=None,read_anchors=None,recover_layout=None,deferred_view=None):
    from autoswitch import absolute_roi
    table=absolute_roi(roi,controller.rect())
    center=(table['left']+table['width']//2,table['top']+table['height']//2)
    from dataclasses import asdict
    from MitakeDetector import Quote
    saved=resume or {}
    collected={q['code']:Quote(**q) for q in saved.get('quotes',[]) if len(q['code'])==4 and q['code'].isascii() and q['code'].isdigit()}
    previous=tuple(saved['codes']) if saved.get('codes') else None
    stable=0;rejected=saved.get('rejected',0);notches=saved.get('notches',1)
    cap=max(1,min(12,scroll_cap,int(saved.get('cap',scroll_cap))));recovery_stalls=0
    rebuilds=0;layout_repairs=0;safe_moves=0
    offset=saved.get('offset',0);first=saved.get('segment',0)
    if not saved or saved.get('relayout'):
        controller.scroll(center,120*4000);controller.pause(.6)
    else:progress(first+1,len(collected),'接續前次斷點，核對目前股票列')
    def anchor_codes(image,quotes):
        codes=tuple(q.code for q in quotes)
        if read_anchors:
            anchors=tuple(dict.fromkeys(read_anchors(image)))
            if anchors:return anchors
        return codes
    def save(next_segment,codes):
        if checkpoint:checkpoint(dict(segment=next_segment,codes=list(codes),offset=offset,notches=notches,cap=cap,rejected=rejected,quotes=[asdict(q) for q in collected.values()]))
    def excluded_view(image,quotes):
        if quotes or not read_anchors:return False
        codes=anchor_codes(image,quotes)
        from stock_codes import excluded_only
        return excluded_only(codes)
    def pending_view(image,quotes):
        return bool(deferred_view and deferred_view() and anchor_codes(image,quotes))
    for segment in range(first,min(max_segments,limit_pages) if limit_pages else max_segments):
        controller.check()
        progress(segment+1,len(collected),'擷取股票資料')
        image,_=controller.image(roi)
        quotes,bad=read(image)
        # Repair geometry before repeating OCR against the same stale columns.
        excluded=excluded_view(image,quotes)
        if not quotes and not excluded and not pending_view(image,quotes) and recover_layout and layout_repairs<3:
            layout_repairs+=1
            progress(segment+1,len(collected),f'資料欄位失效，重新定位表頭 {layout_repairs}/3')
            controller.check()
            if recover_layout():
                controller.check()
                table=absolute_roi(roi,controller.rect())
                center=(table['left']+table['width']//2,table['top']+table['height']//2)
                image,_=controller.image(roi);quotes,bad=read(image)
                progress(segment+1,len(collected),'表頭已重新定位，重讀當前區段並核對重疊')
        # A blank OCR result cannot establish the bottom; retry fresh captures.
        for retry in range(2):
            if quotes or excluded_view(image,quotes) or pending_view(image,quotes):break
            progress(segment+1,len(collected),f'複查辨識 {retry+1}/2')
            controller.pause(.5);image,_=controller.image(roi);quotes,bad=read(image)
        if not quotes and not excluded_view(image,quotes) and not pending_view(image,quotes):raise RecoverableScanError('股票資料連續三次無法辨識，未宣稱已讀完整清單')
        if not quotes:
            progress(segment+1,len(collected),'本段數字欄位待補，已記錄失敗並繼續捲動' if pending_view(image,quotes) else '本段皆為超過四位股號，跳過並繼續捲動')
        codes=anchor_codes(image,quotes)
        if saved and previous and not set(codes).intersection(previous):
            progress(segment+1,len(collected),'定位前次捲動位置，保留已讀資料')
            controller.scroll(center,120*4000);controller.pause(.4)
            if offset:controller.scroll(center,-120*offset);controller.pause(.4)
            image,_=controller.image(roi);quotes,bad=read(image);codes=anchor_codes(image,quotes)
        saved={}
        recovered=False
        if previous is not None and not set(codes).intersection(previous):
            attempts=max(12,notches*3)
            reference=set(previous)
            for direction,steps,label in [(120,attempts,'向上'),(-120,attempts*2+notches,'反向向下')]:
                last_probe=None;unchanged=0
                for attempt in range(steps):
                    controller.check()
                    progress(segment+1,len(collected),f'自動回找重疊 {label} {attempt+1}/{steps}｜目前股號 '+','.join(codes[:4]))
                    controller.scroll(center,direction);offset=max(0,offset+(-1 if direction>0 else 1));controller.pause(.3)
                    image,_=controller.image(roi)
                    if read_anchors:
                        codes=tuple(dict.fromkeys(read_anchors(image)))
                    else:
                        quotes,bad=read(image);codes=anchor_codes(image,quotes)
                    if reference.intersection(codes):
                        if read_anchors:quotes,bad=read(image)
                        recovered=True;cap=1;notches=1;safe_moves=0
                        if learn_scroll:learn_scroll(cap,True)
                        progress(segment+1,len(collected),'已找回重疊股票，縮小步幅接續')
                        break
                    unchanged=unchanged+1 if codes and codes==last_probe else 0
                    last_probe=codes
                    if unchanged>=2:break
                if recovered:break
            if not recovered:
                if rebuilds>=2:raise RecoverableScanError('雙向回找及重新定位後仍反覆失去重疊，已保留斷點與診斷，請檢查清單是否持續重新排序')
                rebuilds+=1;cap=1;notches=1;offset=0;stable=0;previous=None;recovery_stalls=0
                progress(segment+1,len(collected),f'定位點已失效，保留已讀資料，自動重建目前排行位置 {rebuilds}/2')
                controller.scroll(center,120*4000);controller.pause(.5)
                if learn_scroll:learn_scroll(1,True)
                save(segment+1,())
                continue
        rejected+=bad
        recovery_stalls=recovery_stalls+1 if recovered and codes==previous else 0
        if recovery_stalls>=3:raise RecoverableScanError('已反覆找回原位置，但單格滾輪仍跳過整頁，進度已保留；請降低系統滾輪行數')
        for q in quotes:collected[q.code]=q
        if observed:
            extra=observed(quotes,bad)
            if isinstance(extra,(list,tuple)):
                for q in extra:
                    if q.code in collected and q.ts>collected[q.code].ts:collected[q.code]=q
        save(segment+1,codes)
        progress(segment+1,len(collected),'本段含待補欄位，繼續捲動' if pending_view(image,quotes) else '本段已讀取，繼續複查')
        stable=stable+1 if codes==previous and not recovered else 0
        if stable>=3:
            progress(segment+1,len(collected),'複查三次未移動，推定清單末端')
            return list(collected.values()),rejected
        if previous and codes!=previous and not recovered:
            shared=len(set(codes).intersection(previous))
            moved=max(1,len(previous)-shared)
            rows_per_notch=max(1,moved/notches)
            # Retain 2–3 rows for continuity, rather than six plus a low wheel cap.
            overlap=min(3,max(2,len(codes)//10))
            safe_moves+=1
            if safe_moves>=2 and shared>overlap+2:
                cap=min(12,cap+1)
            notches=max(1,min(cap,int(max(1,len(codes)-overlap)/rows_per_notch)))
            progress(segment+1,len(collected),f'捲動校準｜重疊{shared}列｜下一段捲動{notches}格｜保留約{overlap}列核對')
        if learn_scroll and not recovered:learn_scroll(cap,False)
        previous=codes
        if limit_pages and segment+1>=limit_pages:return list(collected.values()),rejected
        # Learn the actual wheel distance before increasing overlapping strides.
        controller.scroll(center,-120*notches);offset+=notches;save(segment+1,codes);controller.pause(.35)
    if limit_pages:return list(collected.values()),rejected
    raise RuntimeError(f'已達{max_segments}段保護上限，尚未確認清單底部；請檢查捲動區域')
