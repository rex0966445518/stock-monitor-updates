package com.rex.mitakecompanion.portfolio;

/** In-memory icon matching. No captured browser image is stored or uploaded by this helper. */
final class GoogleComposerVision {
    private static boolean blue(int c){int r=(c>>16)&255,g=(c>>8)&255,b=c&255;return b>140&&b-r>65&&b-g>20;}
    private static boolean white(int c){int r=(c>>16)&255,g=(c>>8)&255,b=c&255;return Math.min(r,Math.min(g,b))>160&&Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b))<65;}
    private static boolean at(int[] p,int w,int h,int x,int y){return x>=0&&x<w&&y>=0&&y<h&&white(p[y*w+x]);}
    /** Returns plus-x/y and send-x/y only when BOTH icons form one unambiguous composer row. */
    static int[] find(int[] p,int w,int h){
        if(w<240||h<320||w>2000||h>4000||p.length!=w*h)return null;
        int left=(int)(w*.76),right=(int)(w*.98),top=(int)(h*.30),bottom=(int)(h*.98),rw=right-left,rh=bottom-top;
        boolean[] visited=new boolean[rw*rh];int[] queue=new int[rw*rh],result=null;int distance=Math.max(4,Math.round(w*.012f));
        for(int y=top;y<bottom;y++)for(int x=left;x<right;x++){
            int index=(y-top)*rw+x-left;if(visited[index]||!blue(p[y*w+x]))continue;
            int head=0,tail=1;queue[0]=index;visited[index]=true;int minX=x,maxX=x,minY=y,maxY=y;
            while(head<tail){int q=queue[head++],u=q%rw+left,v=q/rw+top;minX=Math.min(minX,u);maxX=Math.max(maxX,u);minY=Math.min(minY,v);maxY=Math.max(maxY,v);
                int[] next={q-1,q+1,q-rw,q+rw};for(int k=0;k<4;k++){int n=next[k];if(n<0||n>=visited.length||(k==0&&q%rw==0)||(k==1&&q%rw==rw-1)||visited[n])continue;
                    if(blue(p[(n/rw+top)*w+n%rw+left])){visited[n]=true;queue[tail++]=n;}}
            }
            int bw=maxX-minX+1,bh=maxY-minY+1;if(bw<w*.055||bw>w*.18||bh<w*.05||bh>w*.18||tail<bw*bh*.4)continue;
            int cx=(minX+maxX)/2,cy=(minY+maxY)/2,d=distance;
            if(!at(p,w,h,cx,cy-d)||!at(p,w,h,cx,cy+d)||!at(p,w,h,cx-d,cy-d/2)||!at(p,w,h,cx+d,cy-d/2))continue;
            int count=0,sumX=0,sumY=0,firstX=0,firstY=0;boolean ambiguous=false;
            for(int py=Math.max(d,cy-bh/3);py<Math.min(h-d,cy+bh/3);py++)for(int px=(int)(w*.04);px<(int)(w*.23);px++){
                if(at(p,w,h,px,py)&&at(p,w,h,px+d,py)&&at(p,w,h,px-d,py)&&at(p,w,h,px,py+d)&&at(p,w,h,px,py-d)
                        &&!at(p,w,h,px+d,py+d)&&!at(p,w,h,px+d,py-d)&&!at(p,w,h,px-d,py+d)&&!at(p,w,h,px-d,py-d)){
                    if(count==0){firstX=px;firstY=py;}else if(Math.abs(px-firstX)>d||Math.abs(py-firstY)>d)ambiguous=true;
                    sumX+=px;sumY+=py;count++;
                }
            }
            if(count>0&&!ambiguous){if(result!=null)return null;result=new int[]{sumX/count,sumY/count,cx,cy};}
        }return result;
    }
}
