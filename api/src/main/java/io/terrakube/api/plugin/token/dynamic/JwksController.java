package io.terrakube.api.plugin.token.dynamic;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/.well-known/jwks")
public class JwksController {

    @Autowired
    DynamicCredentialsService dynamicCredentialsService;

    @Value("${io.terrakube.dynamic.credentials.kid}")
    String kid;

    private JwkData jwkData;

    @GetMapping(produces = "application/json")
    public ResponseEntity<JwkData> jwksEndpoint() throws NoSuchAlgorithmException, InvalidKeySpecException {
        if (jwkData == null) {
            jwkData = getJwkData();
        }
        return ResponseEntity.of(Optional.of(jwkData));

    }

    private String toUnsignedBase64(BigInteger value) {
        byte[] array = value.toByteArray();

        if (array.length > 1 && array[0] == 0) {
            byte[] unsignedArray = new byte[array.length - 1];
            System.arraycopy(array, 1, unsignedArray, 0, unsignedArray.length);
            array = unsignedArray;
        }

        return Base64.getUrlEncoder().withoutPadding().encodeToString(array);
    }

    private JwkData getJwkData() throws NoSuchAlgorithmException, InvalidKeySpecException {
        String publicKey = dynamicCredentialsService.getPublicKey();

        if(publicKey != null && !publicKey.isEmpty()) {

            byte[] encoded = Base64.getDecoder().decode(publicKey);

            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(encoded);

            RSAPublicKey rsa = (RSAPublicKey) keyFactory.generatePublic(keySpec);
            String exponent = toUnsignedBase64(rsa.getPublicExponent());
            String modulus = toUnsignedBase64(rsa.getModulus());
            log.info("RSA Exponent: {}", exponent);
            log.info("RSA Modulus: {}", modulus);

            JwkData jwkData = new JwkData();
            jwkData.setKeys(new ArrayList());

            JwkElement jwkElement = new JwkElement();
            jwkElement.setKty("RSA");
            jwkElement.setN(modulus);
            jwkElement.setE(exponent);
            jwkElement.setKid(kid);
            jwkElement.setUse("sig");
            jwkElement.setAlg("RS256");

            jwkData.getKeys().add(jwkElement);

            return jwkData;
        } else {
            return new JwkData();
        }
    }
}

@Getter
@Setter
class JwkData {
    List<JwkElement> keys;
}

@Getter
@Setter
class JwkElement {

    private String kty;
    private String use;
    private String n;
    private String e;
    private String kid;
    private String alg;
}
