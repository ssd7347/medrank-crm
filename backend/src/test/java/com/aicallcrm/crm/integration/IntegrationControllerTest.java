package com.aicallcrm.crm.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.aicallcrm.crm.integration.IntegrationController.Integration;
import com.aicallcrm.crm.integration.IntegrationController.State;

class IntegrationControllerTest {

    @Test
    void loginCodesAreConnectedOnlyWhenTheyAreReallySent() {
        Integration shown = IntegrationController.loginCodes(false, true);
        assertThat(shown.state()).isEqualTo(State.NOT_CONNECTED);
        assertThat(shown.today()).contains("shown on the login page");

        // Hiding the code from the screen sends nothing: this used to show as connected.
        Integration neither = IntegrationController.loginCodes(false, false);
        assertThat(neither.state()).isEqualTo(State.NOT_CONNECTED);
        assertThat(neither.today()).contains("nobody can sign in");

        Integration sent = IntegrationController.loginCodes(true, false);
        assertThat(sent.state()).isEqualTo(State.CONNECTED);
        assertThat(sent.key()).isEqualTo("LOGIN_CODES");
        assertThat(sent.today()).contains("MSG91").doesNotContain("OTP_SHOW_ON_SCREEN");

        // Sent and still shown on screen: connected, but it says to stop showing them.
        assertThat(IntegrationController.loginCodes(true, true).today()).contains("OTP_SHOW_ON_SCREEN=false");
    }
}
