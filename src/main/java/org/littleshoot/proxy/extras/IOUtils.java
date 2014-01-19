package org.littleshoot.proxy.extras;

import java.io.InputStream;

/**
 * Created with IntelliJ IDEA.
 * User: Hell
 * Date: 18.1.2014
 * Time: 17:17
 */
public class IOUtils {
    public static void closeQuietly(InputStream is) {
        try {
            is.close();
        } catch (Exception ignore) {}
    }

    public static String toString(InputStream is) {
        java.util.Scanner s = new java.util.Scanner(is).useDelimiter("\\A");
        return s.hasNext() ? s.next() : "";
    }
}
