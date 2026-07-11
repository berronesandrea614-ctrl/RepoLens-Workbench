package com.demo;
public class Checker {
    static int fail=0;
    static void eq(long g,long e,String m){ if(g!=e){System.out.println("ABLATION_FAIL:"+m+" got="+g+" exp="+e);fail++;} }
    public static void main(String[] a){
        eq(Fib.fib(0),0,"f0"); eq(Fib.fib(1),1,"f1"); eq(Fib.fib(10),55,"f10"); eq(Fib.fib(20),6765,"f20");
        long t=System.currentTimeMillis();
        eq(Fib.fib(50),12586269025L,"f50");           // naive 递归会在这里几十秒/超时
        if(System.currentTimeMillis()-t>3000){System.out.println("ABLATION_FAIL:fib50-too-slow");fail++;}
        try{Fib.fib(-1);System.out.println("ABLATION_FAIL:neg-no-throw");fail++;}catch(IllegalArgumentException e){}catch(Throwable t2){System.out.println("ABLATION_FAIL:neg-wrong-ex");fail++;}
        if(fail==0) System.out.println("ABLATION_PASS");
    }
}
