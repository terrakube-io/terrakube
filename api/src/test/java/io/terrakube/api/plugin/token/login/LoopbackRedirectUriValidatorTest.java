package io.terrakube.api.plugin.token.login;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class LoopbackRedirectUriValidatorTest {

    private final LoopbackRedirectUriValidator v = new LoopbackRedirectUriValidator();

    @Test
    void acceptsLoopbackInRange() {
        v.validate("http://localhost:10000/login");
        v.validate("http://127.0.0.1:10010/login");
        v.validate("http://[::1]:10005/login");
    }

    @Test
    void rejectsNonLoopbackHost() {
        assertThrows(BrokerBadRequestException.class,
            () -> v.validate("http://evil.example.com:10000/login"));
    }

    @Test
    void rejectsPortOutsideRange() {
        assertThrows(BrokerBadRequestException.class,
            () -> v.validate("http://localhost:9999/login"));
        assertThrows(BrokerBadRequestException.class,
            () -> v.validate("http://localhost:10011/login"));
    }

    @Test
    void rejectsWrongPathSchemeOrExtras() {
        assertThrows(BrokerBadRequestException.class, () -> v.validate("http://localhost:10000/callback"));
        assertThrows(BrokerBadRequestException.class, () -> v.validate("https://localhost:10000/login"));
        assertThrows(BrokerBadRequestException.class, () -> v.validate("http://localhost:10000/login?x=1"));
        assertThrows(BrokerBadRequestException.class, () -> v.validate("http://localhost:10000/login#frag"));
        assertThrows(BrokerBadRequestException.class, () -> v.validate("http://localhost/login"));
        assertThrows(BrokerBadRequestException.class, () -> v.validate("not a uri"));
    }
}
