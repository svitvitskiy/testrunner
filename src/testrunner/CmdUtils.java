package testrunner;

import java.util.List;
import java.util.ListIterator;

public class CmdUtils {
    public static String getArgForKey(List<String> a, String key) {
        ListIterator<String> it = a.listIterator();
        boolean found = false;
        while (it.hasNext()) {
            String val = it.next();
            if (key.equals(val)) {
                it.remove();
                found = true;
            } else if (found) {
                it.remove();
                return val;
            }
        }
        return null;
    }

    public static boolean hasKen(List<String> a, String key) {
        ListIterator<String> it = a.listIterator();
        while (it.hasNext()) {
            String val = it.next();
            if (key.equals(val)) {
                it.remove();
                return true;
            }
        }
        return false;
    }
}
