package com.demo;

public class Pal {
    // BUG: 这个实现没有忽略大小写和非字母数字字符，行为不对，需要修复
    public static boolean isPalindrome(String s) {
        int i = 0, j = s.length() - 1;
        while (i < j) {
            if (s.charAt(i) != s.charAt(j)) return false;
            i++; j--;
        }
        return true;
    }
}
