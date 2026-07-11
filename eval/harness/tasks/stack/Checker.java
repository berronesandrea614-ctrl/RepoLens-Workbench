package com.demo;
public class Checker {
    static int fail=0;
    static void t(boolean c,String m){ if(!c){System.out.println("ABLATION_FAIL:"+m);fail++;} }
    public static void main(String[] a){
        IntStack s=new IntStack();
        t(s.isEmpty(),"empty0"); t(s.size()==0,"size0");
        s.push(1); s.push(2); s.push(3);
        t(s.size()==3,"size3"); t(s.peek()==3,"peek3"); t(s.size()==3,"peek-nopop");
        t(s.pop()==3,"pop3"); t(s.pop()==2,"pop2"); t(s.pop()==1,"pop1"); t(s.isEmpty(),"emptyEnd");
        try{s.pop();System.out.println("ABLATION_FAIL:pop-empty-nothrow");fail++;}catch(java.util.EmptyStackException e){}catch(Throwable x){System.out.println("ABLATION_FAIL:pop-empty-wrongex");fail++;}
        try{s.peek();System.out.println("ABLATION_FAIL:peek-empty-nothrow");fail++;}catch(java.util.EmptyStackException e){}catch(Throwable x){System.out.println("ABLATION_FAIL:peek-empty-wrongex");fail++;}
        if(fail==0) System.out.println("ABLATION_PASS");
    }
}
