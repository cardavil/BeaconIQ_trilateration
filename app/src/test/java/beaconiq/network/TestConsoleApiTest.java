package beaconiq.network;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;

/** Tests the Apps Script error detection that gates a successful HTTP body. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TestConsoleApiTest {

    @Test
    public void errorStatusThrowsWithMessage() {
        assertThatThrownBy(() -> TestConsoleApi.checkAppsScriptError(
                "{\"status\":\"error\",\"message\":\"bad token\"}"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("bad token");
    }

    @Test
    public void errorStatusWithoutMessageThrowsDefault() {
        assertThatThrownBy(() -> TestConsoleApi.checkAppsScriptError(
                "{\"status\":\"error\"}"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Unknown Apps Script error");
    }

    @Test
    public void okStatusPassesThrough() {
        assertThatCode(() -> TestConsoleApi.checkAppsScriptError(
                "{\"status\":\"ok\",\"session_id\":\"BIQ-0001\"}"))
                .doesNotThrowAnyException();
    }

    @Test
    public void bodyWithoutStatusFieldPassesThrough() {
        assertThatCode(() -> TestConsoleApi.checkAppsScriptError(
                "{\"sessions\":[]}"))
                .doesNotThrowAnyException();
    }

    @Test
    public void nonJsonBodyPassesThrough() {
        assertThatCode(() -> TestConsoleApi.checkAppsScriptError(
                "<html>Service unavailable</html>"))
                .doesNotThrowAnyException();
    }
}
