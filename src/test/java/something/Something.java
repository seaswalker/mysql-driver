package something;

import org.junit.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * Test something, test anything...
 *
 * @author skywalker.
 */
public class Something {

    /**
     * 测试{@link java.net.InetAddress#getAllByName(String)}.
     */
    @Test
    public void allNames() throws UnknownHostException {
        InetAddress[] addresses = InetAddress.getAllByName("localhost");
        System.out.println(Arrays.toString(addresses));
    }

}
