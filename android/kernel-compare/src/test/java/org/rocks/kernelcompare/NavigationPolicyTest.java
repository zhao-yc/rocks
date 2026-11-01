package org.rocks.kernelcompare;

import org.junit.Test;
import static org.junit.Assert.*;

/** 防止测试地址输入执行脚本、外部应用跳转或访问任意本地数据。 */
public final class NavigationPolicyTest {
    @Test public void acceptsWebAddressesAndAddsHttpsForBareHosts() {
        assertEquals("https://www.baidu.com", NavigationPolicy.address(" www.baidu.com "));
        assertEquals("http://127.0.0.1:18748/", NavigationPolicy.address("http://127.0.0.1:18748/"));
    }
    @Test public void rejectsScriptsIntentsLocalFilesAndCredentialUrls() {
        for (String input : new String[]{"javascript:alert(1)", "intent://test", "file:///sdcard/test", "content://test", "https://a:b@example.org/", "", "not a url"}) {
            assertNull(input, NavigationPolicy.address(input));
        }
    }
    @Test public void permitsOnlyTheExactBuiltInDataPage() {
        final String builtin = "data:text/html;base64,TEST";
        assertTrue(NavigationPolicy.allowed(builtin, builtin));
        assertFalse(NavigationPolicy.allowed("data:text/html;base64,OTHER", builtin));
        assertFalse(NavigationPolicy.allowed("intent://test", builtin));
    }
}
