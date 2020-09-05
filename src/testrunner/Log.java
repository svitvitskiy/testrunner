package testrunner;

public class Log {
    private static int logLevel = 1;

    private static String PREFIX_ERROR = (char) 27 + "[91mERROR: ";
    private static String PREFIX_WARN = (char) 27 + "[95mWARN: ";
    private static String SUFFIX_CLEAR = (char) 27 + "[0m";

    public static void error(String message) {
        if (logLevel >= 0)
            System.out.println(PREFIX_ERROR + message + SUFFIX_CLEAR);
    }

    public static void warn(String message) {
        if (logLevel >= 1)
            System.out.println(PREFIX_WARN + message + SUFFIX_CLEAR);
    }

    public static void info(String message) {
        if (logLevel >= 2)
            System.out.println("INFO:  " + message);
    }

    public static void debug(String message) {
        if (logLevel >= 3)
            System.out.println("    DEBUG: " + message);
    }

    public static void debug(Exception e) {
        if (logLevel >= 3) {
            e.printStackTrace(System.out);
        }
    }
    
    public static void info(Exception e) {
        if (logLevel >= 2) {
            e.printStackTrace(System.out);
        }
    }
    
    public static void warn(Exception e) {
        if (logLevel >= 1) {
            System.out.print(PREFIX_WARN);
            e.printStackTrace(System.out);
            System.out.println(SUFFIX_CLEAR);
        }
    }
    
    public static void error(Exception e) {
        if (logLevel >= 0) {
            System.out.print(PREFIX_ERROR);
            e.printStackTrace(System.out);
            System.out.println(SUFFIX_CLEAR);
        }
    }
}
