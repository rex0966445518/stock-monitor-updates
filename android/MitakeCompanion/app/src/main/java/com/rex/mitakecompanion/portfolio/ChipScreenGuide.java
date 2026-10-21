package com.rex.mitakecompanion.portfolio;

import android.graphics.Rect;
import java.util.*;

/** OCR-only recovery for icon-only/canvas screens from the user's two recordings. */
final class ChipScreenGuide {
    static final class Line {final String text;final Rect box;Line(String text,Rect box){this.text=text;this.box=new Rect(box);}}
    static final class Action {final String kind;final Rect box;final boolean forward;Action(String kind,Rect box,boolean forward){this.kind=kind;this.box=box;this.forward=forward;}}
    static boolean headerMatches(List<Line> lines,int height,String code){for(Line l:lines)if(l.box.top<height*.18&&ChipCaptureService.exactCode(l.text,code))return true;return false;}
    static List<Line> navigation(List<Line> lines,int height){
        List<Line> best=new ArrayList<>();int bestCount=0;float bestY=Float.MAX_VALUE;
        for(Line seed:lines){if(seed.box.centerY()<height*.14||seed.box.centerY()>height*.48||ChipVideoProfile.order(seed.text)<0)continue;
            LinkedHashMap<String,Line> row=new LinkedHashMap<>();for(Line l:lines)if(ChipVideoProfile.order(l.text)>=0&&Math.abs(l.box.centerY()-seed.box.centerY())<Math.max(18,seed.box.height()*.75)){String key=ChipVideoProfile.canonical(l.text);Line old=row.get(key);if(old==null||l.box.width()<old.box.width())row.put(key,l);}
            if(row.size()>=2&&(row.size()>bestCount||row.size()==bestCount&&seed.box.centerY()<bestY)){bestCount=row.size();bestY=seed.box.centerY();best=new ArrayList<>(row.values());}
        }best.sort(Comparator.comparingInt(l->l.box.left));return best;
    }
    static Rect anchor(List<Line> lines,int height,String target){for(Line l:navigation(lines,height))if(ChipVideoProfile.tabLabel(l.text,target))return new Rect(l.box);return null;}
    static Action plan(List<Line> lines,int width,int height,String code,String target){
        StringBuilder text=new StringBuilder();boolean header=false;int menu=0;Line homeAnchor=null,field=null,paste=null;int suggestions=height;
        for(Line l:lines){text.append(l.text).append('\n');String clean=ChipVideoProfile.clean(l.text);
            if(l.box.top<height*.18&&ChipCaptureService.exactCode(l.text,code))header=true;
            if(l.box.top<height*.15&&(clean.equals("即時")||clean.equals("盤後")||clean.equals("討論"))){menu++;homeAnchor=l;}
            if(l.box.top<height*.23&&(clean.contains("股票代號")||clean.contains("股號/名稱")))field=l;
            if(clean.contains("大家都在搜"))suggestions=Math.min(suggestions,l.box.top);
            if(clean.equals("貼上")||clean.equals("Paste"))paste=l;
        }
        String all=text.toString();
        if(paste!=null&&(all.contains("搜尋")||all.contains("股票代號")))return new Action("paste",paste.box,false);
        // This is the self-selected stock grid, not a stock-detail title or a trade screen.
        if(menu>=2&&all.contains("自選股")&&homeAnchor!=null){int y=homeAnchor.box.centerY();return new Action("search",new Rect((int)(width*.035),y-12,(int)(width*.095),y+12),false);}
        boolean search=all.contains("搜尋")&&(all.contains("個股")||all.contains("熱門搜尋")||field!=null);
        if(search){
            Line typed=null;for(Line l:lines)if(l.box.top<height*.19&&ChipVideoProfile.clean(l.text).equals(code))typed=l;
            if(typed!=null){for(Line l:lines)if(l.box.top>typed.box.bottom&&l.box.bottom<suggestions&&l.box.top<height*.48&&ChipVideoProfile.stockResult(l.text,code))return new Action("result",l.box,false);return null;}
            if(field==null)for(Line l:lines)if(l.box.top>height*.08&&l.box.bottom<height*.19&&l.text.trim().matches("[0-9A-Za-z]{1,8}")){field=l;break;}
            if(field!=null)return new Action("input",field.box,false);return null;
        }
        if(!header)return null;
        if(ChipVideoProfile.bodyMatches(all,target))return new Action("capture",null,false);
        List<String> visible=new ArrayList<>();Rect strip=new Rect();Line tab=null;
        for(Line l:navigation(lines,height)){visible.add(l.text);strip.union(l.box);if(ChipCaptureService.pageLabel(l.text,target))tab=l;}
        if(visible.isEmpty())return null;
        if(tab!=null)return new Action("tab",tab.box,false);
        if(visible.size()<2)return null;
        return new Action("swipe",new Rect((int)(width*.12),strip.top,(int)(width*.82),strip.bottom),ChipVideoProfile.scrollForward(target,visible));
    }
}
