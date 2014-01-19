package org.littleshoot.proxy.extras;

/**
 * Created with IntelliJ IDEA.
 * User: Hell
 * Date: 18.1.2014
 * Time: 17:16
 */
public class StringUtils {

    public static String substringBefore(String value, String key) {
        return value.split(key)[0];
    }

    public static String substringAfter(String value, String key) {
        return value.split(key)[1];
    }

    public static boolean isBlank(String s) {
        return s == null || s.trim().equals("");
    }

    public static boolean isNotBlank(String s) {
        return s != null && !s.trim().equals("");
    }
}
