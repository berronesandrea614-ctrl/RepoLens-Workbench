package com.demo;
public class Checker {
    static int fail=0;
    static void eq(double got,double exp,String m){ if(Math.abs(got-exp)>1e-9){System.out.println("ABLATION_FAIL:"+m+" got="+got+" exp="+exp);fail++;} }
    static void thr(Runnable r,Class<?> ex,String m){ try{r.run();System.out.println("ABLATION_FAIL:"+m+" no-throw");fail++;}catch(Throwable t){ if(!ex.isInstance(t)){System.out.println("ABLATION_FAIL:"+m+" wrong-ex="+t.getClass().getSimpleName());fail++;} } }
    public static void main(String[] a){
        eq(Expr.eval("2 + 3 * (4 - 1)"),11.0,"prec1");
        eq(Expr.eval("(1 + 2) * 3"),9.0,"paren");
        eq(Expr.eval("10 / 4"),2.5,"nonint-div");
        eq(Expr.eval("7 / 2"),3.5,"nonint-div2");
        eq(Expr.eval("2 + 2 * 2"),6.0,"prec2");
        thr(()->Expr.eval("1 / 0"),ArithmeticException.class,"divzero");
        thr(()->Expr.eval(""),IllegalArgumentException.class,"empty");
        thr(()->Expr.eval("2 + a"),IllegalArgumentException.class,"illegal");
        if(fail==0) System.out.println("ABLATION_PASS");
    }
}
