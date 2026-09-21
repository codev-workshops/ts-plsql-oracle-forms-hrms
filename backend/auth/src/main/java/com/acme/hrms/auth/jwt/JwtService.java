package com.acme.hrms.auth.jwt;

import com.acme.hrms.auth.config.AuthProperties;
import com.acme.hrms.common.security.CallerIdentity;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * RS256 access tokens with exactly the frozen claim set: {@code sub} (user account id), {@code
 * empId}, {@code roles}, {@code jti} (random UUID - SEC-08), {@code iat}, {@code exp}.
 */
@Service
public class JwtService {

  public static final String CLAIM_EMP_ID = "empId";
  public static final String CLAIM_ROLES = "roles";

  private final RSASSASigner signer;
  private final RSASSAVerifier verifier;
  private final String issuer;
  private final String keyId;
  private final Clock clock;

  public JwtService(AuthProperties props, Clock clock) {
    this.clock = clock;
    this.issuer = props.jwt().issuer();
    this.keyId = props.jwt().keyId();
    KeyPair pair = loadOrGenerate(props.jwt().privateKeyPem());
    this.signer = new RSASSASigner(pair.getPrivate());
    this.verifier = new RSASSAVerifier((RSAPublicKey) pair.getPublic());
  }

  public record IssuedToken(String token, String jti, Instant issuedAt, Instant expiresAt) {}

  public IssuedToken issue(String userId, long empId, Set<String> roles, int ttlSeconds) {
    Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    Instant exp = now.plusSeconds(ttlSeconds);
    String jti = UUID.randomUUID().toString();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject(userId)
            .claim(CLAIM_EMP_ID, empId)
            .claim(CLAIM_ROLES, List.copyOf(new java.util.TreeSet<>(roles)))
            .jwtID(jti)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(exp))
            .build();
    SignedJWT jwt =
        new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId).build(), claims);
    try {
      jwt.sign(signer);
    } catch (JOSEException e) {
      throw new IllegalStateException("JWT signing failed", e);
    }
    return new IssuedToken(jwt.serialize(), jti, now, exp);
  }

  /** Signature + expiry + required claims; revocation is checked by the caller. */
  public Optional<CallerIdentity> verify(String token) {
    try {
      SignedJWT jwt = SignedJWT.parse(token);
      if (!jwt.verify(verifier)) {
        return Optional.empty();
      }
      JWTClaimsSet c = jwt.getJWTClaimsSet();
      if (!issuer.equals(c.getIssuer())
          || c.getSubject() == null
          || c.getJWTID() == null
          || c.getExpirationTime() == null
          || c.getLongClaim(CLAIM_EMP_ID) == null) {
        return Optional.empty();
      }
      if (!c.getExpirationTime().toInstant().isAfter(clock.instant())) {
        return Optional.empty();
      }
      List<String> roles = c.getStringListClaim(CLAIM_ROLES);
      return Optional.of(
          new CallerIdentity(
              c.getSubject(),
              c.getLongClaim(CLAIM_EMP_ID),
              roles == null ? Set.of() : new LinkedHashSet<>(roles),
              c.getJWTID()));
    } catch (ParseException | JOSEException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /** Expiry of a token regardless of validity (for revocation bookkeeping). */
  public Optional<Instant> expiryOf(String token) {
    try {
      Date exp = SignedJWT.parse(token).getJWTClaimsSet().getExpirationTime();
      return Optional.ofNullable(exp).map(Date::toInstant);
    } catch (ParseException e) {
      return Optional.empty();
    }
  }

  static KeyPair loadOrGenerate(String pem) {
    try {
      if (pem == null || pem.isBlank()) {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
      }
      String body =
          pem.replace("-----BEGIN PRIVATE KEY-----", "")
              .replace("-----END PRIVATE KEY-----", "")
              .replaceAll("\\s", "");
      KeyFactory kf = KeyFactory.getInstance("RSA");
      RSAPrivateKey priv =
          (RSAPrivateKey)
              kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
      if (!(priv instanceof RSAPrivateCrtKey crt)) {
        throw new IllegalStateException("RSA private key must carry the public exponent (CRT)");
      }
      RSAPublicKey pub =
          (RSAPublicKey)
              kf.generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
      return new KeyPair(pub, priv);
    } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
      throw new IllegalStateException("Cannot load JWT signing key", e);
    }
  }
}
