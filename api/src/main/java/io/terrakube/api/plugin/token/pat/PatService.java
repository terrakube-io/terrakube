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
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

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

    @Value("${io.terrakube.owner:}")
    private String instanceOwner;

    @Autowired
    private PatRepository patRepository;

    public String createToken(int days, String description, Object name, Object email, Object groups) {
        return createToken(days, description, name, email, groups, "API");
    }

    public String createToken(int days, String description, Object name, Object email, Object groups, String source) {
        return issueToken(days, description, name, email, groups, source, null).token();
    }

    public record IssuedToken(UUID id, String token) {
    }

    /**
     * Creates a PAT and returns its id alongside the signed token. When createdBy is set the row is
     * attributed to that user; needed when the token is minted from an unauthenticated endpoint
     * (the terraform login broker), where the auditing listener would otherwise record the
     * created_by of the anonymous request.
     */
    public IssuedToken issueToken(int days, String description, Object name, Object email, Object groups,
                                  String source, String createdBy) {
        String jws = "";
        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64URL.decode(this.base64Key));

        Pat pat = new Pat();
        pat.setDays(days);
        pat.setDeleted(false);
        pat.setDescription(description);
        pat.setSource(source);
        pat = patRepository.save(pat);

        try {
            log.info("Generated Pat {} (source {})", pat.getId(), source);

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
            return new IssuedToken(pat.getId(), jws);
        }
        // @CreatedBy is only populated on insert, so this explicit set on a subsequent save sticks.
        if (createdBy != null && !createdBy.isBlank()) {
            pat.setCreatedBy(createdBy);
            pat.setUpdatedBy(createdBy);
            pat = patRepository.save(pat);
        }
        return new IssuedToken(pat.getId(), jws);
    }


    public boolean deleteToken(String tokenId) {
        return deleteToken(tokenId, SecurityContextHolder.getContext().getAuthentication());
    }

    public boolean deleteToken(String tokenId, Authentication authentication) {
        UUID patUuid;
        try {
            patUuid = UUID.fromString(tokenId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Personal access token not found");
        }

        Optional<Pat> searchPat = patRepository.findById(patUuid);
        if (searchPat.isEmpty() || searchPat.get().isDeleted()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Personal access token not found");
        }

        Pat pat = searchPat.get();
        String callerEmail = extractEmail(authentication);
        boolean isOwner = callerEmail != null && callerEmail.equalsIgnoreCase(pat.getCreatedBy());
        boolean isSuperUser = isInstanceOwner(authentication);

        if (!isOwner && !isSuperUser) {
            log.warn("User {} is not authorized to delete PAT {} owned by {}", callerEmail, tokenId, pat.getCreatedBy());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User is not authorized to delete this token");
        }

        pat.setDeleted(true);
        patRepository.save(pat);
        log.info("PAT {} revoked by {}", tokenId, callerEmail);
        return true;
    }

    private String extractEmail(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwt) {
            Object email = jwt.getTokenAttributes().get("email");
            if (email != null) {
                return email.toString();
            }
        }
        if (authentication != null && authentication.getName() != null) {
            return authentication.getName();
        }
        return null;
    }

    private boolean isInstanceOwner(Authentication authentication) {
        if (instanceOwner == null || instanceOwner.isBlank() || authentication == null) {
            return false;
        }
        if (authentication instanceof JwtAuthenticationToken jwt) {
            Object email = jwt.getTokenAttributes().get("email");
            if (instanceOwner.equals(email)) {
                return true;
            }
            Object groups = jwt.getTokenAttributes().get("groups");
            if (groups instanceof Collection<?> groupList) {
                return groupList.contains(instanceOwner);
            }
        }
        return false;
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
