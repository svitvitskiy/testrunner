package testrunner;

import java.util.Arrays;

public class TestRunnerMain {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Syntax: testrunner <command> ...\n" 
                    + "  where command is:\n"
                    + "    leaf         For leaf agent\n" 
                    + "    balancer     For balancer agent\n"
                    + "    client       For client interface\n" 
                    + "    scheduler    Test scheduler\n");
            return;
        }
        String command = args[0];
        String[] newArgs = Arrays.copyOfRange(args, 1, args.length);
        if ("leaf".equals(command)) {
            LeafAgent.main(newArgs);
        } else if ("balancer".equals(command)) {
            BalancingAgent.main(newArgs);
        } else if ("client".equals(command)) {
            AgentConnection.main(newArgs);
        } else if ("scheduler".equals(command)) {
            TestRunner.main(newArgs);
        } else {
            System.err.println("Unknown command: '" + command + "'");
        }
    }
}
