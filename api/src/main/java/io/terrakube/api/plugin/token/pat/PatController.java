package io.terrakube.api.plugin.token.pat;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import io.terrakube.api.rs.token.pat.Pat;

import java.security.Principal;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/pat/v1")
public class PatController {

    @Autowired
    PatService patService;

    @PostMapping
    public ResponseEntity<PatResponse> createToken(@RequestBody PatTokenRequest patTokenRequest, Principal principal) {
        PatResponse patResponse = new PatResponse();
        JwtAuthenticationToken principalJwt = ((JwtAuthenticationToken) principal);
        log.info("{}", principalJwt);
        patResponse.setToken(patService.createToken(
                patTokenRequest.getDays(),
                patTokenRequest.getDescription(),
                principalJwt.getTokenAttributes().get("name"),
                principalJwt.getTokenAttributes().get("email"),
                principalJwt.getTokenAttributes().get("groups")
            )
        );
        return new ResponseEntity<>(patResponse, HttpStatus.CREATED);
    }

    @GetMapping
    public ResponseEntity<List<Pat>> searchToken(Principal principal){
        return new ResponseEntity<>(patService.searchToken(principal), HttpStatus.ACCEPTED);
    }

    @Transactional
    @DeleteMapping(path = "/{tokenId}")
    public ResponseEntity<String> deleteToken(@PathVariable("tokenId") String tokenId, Authentication authentication){
        if(patService.deleteToken(tokenId, authentication)) {
            return ResponseEntity.accepted().build();
        } else {
            return ResponseEntity.badRequest().build();
        }
    }

    @Getter
    @Setter
    private class PatResponse {
        private String token;
    }

    @Getter
    @Setter
    public static class PatTokenRequest {

        private int days;
        private String description;
    }
}
