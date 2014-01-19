package org.littleshoot.proxy.extras;

/**
 * Created with IntelliJ IDEA.
 * User: Hell
 * Date: 18.1.2014
 * Time: 17:37
 */
public class NumberUtils {

    public static boolean isNumber(String s) {
        return s.matches("-?\\d+(\\.\\d+)?");  //match a number with optional '-' and decimal.
    }
}
