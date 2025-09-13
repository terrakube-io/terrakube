package io.terrakube.api.plugin.token.pat;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import io.terrakube.api.repository.PatRepository;
import io.terrakube.api.rs.token.pat.Pat;

import javax.crypto.SecretKey;
import java.security.Principal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Slf4j
@Service
public class PatService {

    @Value("${io.terrakube.token.pat}")
    private String base64Key;
    private static final String ISSUER = "Terrakube";

    @Autowired
    private PatRepository patRepository;

    public String createToken(int days, String description, Object name, Object email, Object groups) {
        String jws = "";
        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64URL.decode(this.base64Key));

        Pat pat = new Pat();
        pat.setDays(days);
        pat.setDeleted(false);
        pat.setDescription(description);
        pat = patRepository.save(pat);

        try {
            log.info("Generated Pat {}", pat.getId());

            if (days > 0) {
                log.info("Pat will expire");
                jws = Jwts.builder()
                        .issuer(ISSUER)
                        .subject(String.format("%s (Token)", name))
                        .audience().add(ISSUER).and()
                        .id(pat.getId().toString())
                        .claim("email", email)
                        .claim("email_verified", true)
                        .claim("name", String.format("%s (Token)", name))
                        .claim("groups", groups)
                        .issuedAt(Date.from(Instant.now()))
                        .expiration(Date.from(Instant.now().plus(days, ChronoUnit.DAYS)))
                        .signWith(key)
                        .compact();
            } else {
                log.info("Pat will not expire");
                jws = Jwts.builder()
                        .issuer(ISSUER)
                        .subject(String.format("%s (Token)", name))
                        .audience().add(ISSUER).and()
                        .id(pat.getId().toString())
                        .claim("email", email)
                        .claim("email_verified", true)
                        .claim("name", String.format("%s (Token)", name))
                        .claim("groups", groups)
                        .issuedAt(Date.from(Instant.now()))
                        .signWith(key)
                        .compact();
            }
        } catch (Exception e) {
            log.error("Error generating token", e);
            patRepository.delete(pat);
        }
        return jws;
    }


    public boolean deleteToken(String tokenId){
        Optional<Pat> searchPat = patRepository.findById(UUID.fromString(tokenId));
        if(searchPat.isPresent()){
            Pat pat = searchPat.get();
            pat.setDeleted(true);
            patRepository.save(pat);
            return true;
        }else{
            return false;
        }
    }

    public List<Pat> searchToken(Principal principal) {
        JwtAuthenticationToken principalJwt = ((JwtAuthenticationToken) principal);
        List<Pat> patList = patRepository.findByCreatedBy((String) principalJwt.getTokenAttributes().get("email"));
        List<Pat> activeList = new ArrayList();
        patList.forEach(pat -> {
            //Date jobExpiration = Date.from(pat.getCreatedDate().toInstant().plus(pat.getDays(), ChronoUnit.DAYS));
            //if(jobExpiration.after(new Date(System.currentTimeMillis())) && !pat.isDeleted())
            if(!pat.isDeleted())
                activeList.add(pat);

        });
        return activeList;
    }
}
