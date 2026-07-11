package com.demo;
public class Checker {
    static int fail=0;
    static void eq(boolean g,boolean e,String m){ if(g!=e){System.out.println("ABLATION_FAIL:"+m+" got="+g);fail++;} }
    public static void main(String[] a){
        eq(Pal.isPalindrome("A man, a plan, a canal: Panama"),true,"panama");
        eq(Pal.isPalindrome("race a car"),false,"racecar");
        eq(Pal.isPalindrome(""),true,"empty");
        eq(Pal.isPalindrome("Ab"),false,"ab");
        eq(Pal.isPalindrome("aba"),true,"aba");
        eq(Pal.isPalindrome("0P"),false,"0p");
        if(fail==0) System.out.println("ABLATION_PASS");
    }
}
