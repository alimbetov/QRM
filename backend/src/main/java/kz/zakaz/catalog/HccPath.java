package kz.zakaz.catalog;

import java.util.*;

/** 2 / 2 / 3 / 5 / 8 positional HCC segments; no gaps or ordinal meaning. */
public final class HccPath {
    private static final int[] ENDS={2,4,7,12,20};
    private HccPath(){}
    public static int depth(String code){
        if(code==null||!code.matches("[0-9]{20}"))
            throw new IllegalArgumentException("HCC must be exactly 20 digits");
        int last=0, start=0;
        boolean ended=false;
        for(int end:ENDS){
            String segment=code.substring(start,end);
            boolean zero=segment.chars().allMatch(c->c=='0');
            if(zero) ended=true;
            else {
                if(ended) throw new IllegalArgumentException("HCC has gap between positional segments");
                last++;
            }
            start=end;
        }
        if(last==0) throw new IllegalArgumentException("Empty HCC");
        return last;
    }
    public static boolean isDescendant(String child,String parent){
        int cd=depth(child),pd=depth(parent);
        return cd>pd&&child.substring(0,ENDS[pd-1]).equals(parent.substring(0,ENDS[pd-1]));
    }
}
