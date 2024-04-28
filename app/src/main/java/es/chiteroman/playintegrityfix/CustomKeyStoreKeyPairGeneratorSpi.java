package es.chiteroman.playintegrityfix;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import org.bouncycastle.asn1.ASN1Boolean;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1Enumerated;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyPairGeneratorSpi;
import java.security.ProviderException;
import java.security.SecureRandom;
import java.security.Security;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.util.Locale;
import java.util.Objects;

public class CustomKeyStoreKeyPairGeneratorSpi extends KeyPairGeneratorSpi {

    private static final int ATTESTATION_APPLICATION_ID_PACKAGE_INFOS_INDEX = 0;
    private static final int ATTESTATION_APPLICATION_ID_SIGNATURE_DIGESTS_INDEX = 1;
    private static final int ATTESTATION_PACKAGE_INFO_PACKAGE_NAME_INDEX = 0;
    private static final int ATTESTATION_PACKAGE_INFO_VERSION_INDEX = 1;

    final String KEYSTORE = "AndroidKeyStore";
    private final String requestedAlgo;
    private KeyGenParameterSpec params;

    private KeyPairGenerator baseGenerator;
    public static final class RSA extends CustomKeyStoreKeyPairGeneratorSpi {
        public RSA() {
            super(KeyProperties.KEY_ALGORITHM_RSA);
        }
    }

    public static final class EC extends CustomKeyStoreKeyPairGeneratorSpi {
        public EC() {
            super(KeyProperties.KEY_ALGORITHM_EC);
        }
    }
    protected CustomKeyStoreKeyPairGeneratorSpi(String algo){
        requestedAlgo = algo;
    }

    @Override
    public void initialize(int keysize, SecureRandom random) {
        // do nothing = ok
        try {
            baseGenerator = KeyPairGenerator.getInstance("OLD" + requestedAlgo, Security.getProvider(KEYSTORE));
            baseGenerator.initialize(keysize, random);
        } catch (Exception e) {
            EntryPoint.LOG("Failed to load OLD KeyPairGen: " + e);
        }
    }

    @Override
    public void initialize(AlgorithmParameterSpec params, SecureRandom random) {
        this.params = (KeyGenParameterSpec) params;
        try {
            baseGenerator = KeyPairGenerator.getInstance("OLD" + requestedAlgo, Security.getProvider(KEYSTORE));
            baseGenerator.initialize(params, random);
        } catch (Exception e) {
            EntryPoint.LOG("Failed to load OLD KeyPairGen: " + e);
        }
    }

    @Override
    public KeyPair generateKeyPair() {
        for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
            // TODO hex3l: bypass keystore only for droidguard
            //  currently only in attestation test app
            if (e.getClassName().toLowerCase(Locale.ROOT).contains("vvb2060")) {
                try {
                    EntryPoint.LOG("Requested KeyPair with alias: " + params.getKeystoreAlias());
                    KeyPair rootKP;
                    KeyPair kp;
                    X500Name issuer;
                    int size = params.getKeySize();
                    if(size == -1) size = getKeySizeFromCurve();

                    if (Objects.equals(requestedAlgo, KeyProperties.KEY_ALGORITHM_EC)) {
                        EntryPoint.LOG("GENERATING EC KEYPAIR OF SIZE " + size);
                        kp = buildECKeyPair();
                        Keybox k = EntryPoint.box("ecdsa");
                        rootKP = k.keypair();
                        issuer = k.certificateChainSubject().getFirst();
                    } else {
                        // TODO hex3l: validate process for RSA
                        kp = buildRSAKeyPair();
                        Keybox k = EntryPoint.box("rsa");
                        rootKP = k.keypair();
                        issuer = k.certificateChainSubject().getFirst();
                    }

                    X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(issuer, params.getCertificateSerialNumber(), params.getCertificateNotBefore(), params.getCertificateNotAfter(), new X500Name(params.getCertificateSubject().getName()), kp.getPublic());

                    KeyUsage keyUsage = new KeyUsage(KeyUsage.keyCertSign);
                    certBuilder.addExtension(Extension.keyUsage, true, keyUsage);

                    certBuilder.addExtension(createExtension(size));

                    // TODO hex3l: validate the process for RSA
                    ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256withECDSA").build(rootKP.getPrivate());

                    X509CertificateHolder certHolder = certBuilder.build(contentSigner);

                    EntryPoint.append(params.getKeystoreAlias(), new JcaX509CertificateConverter().getCertificate(certHolder));
                    EntryPoint.LOG("Successfully generated X500 Cert for alias: " + params.getKeystoreAlias());

                    return kp;
                } catch (Throwable t) {
                    EntryPoint.LOG("Error while generating KeyPair:" + t);
                    throw new ProviderException("Failed to generate key pair.");
                }
            }
        }
        return baseGenerator.generateKeyPair();
    }

    private Extension createExtension(int size) {
        try {
            SecureRandom random = new SecureRandom();

            byte[] bytes1 = new byte[32];
            byte[] bytes2 = new byte[32];

            random.nextBytes(bytes1);
            random.nextBytes(bytes2);

            ASN1Encodable[] rootOfTrustEncodables = {new DEROctetString(bytes1), ASN1Boolean.TRUE, new ASN1Enumerated(0), new DEROctetString(bytes2)};

            ASN1Sequence rootOfTrustSeq = new DERSequence(rootOfTrustEncodables);

            // TODO hex3l: validate that SIGN is the only required or create a parser
            ASN1Integer[] purposesArray = {
                    new ASN1Integer(2) //params.getPurposes()
            };

            var Apurpose = new DERSet(purposesArray);
            var Aalgorithm = new ASN1Integer(getAlgorithm());
            var AkeySize = new ASN1Integer(size);
            var Adigest = new DERSet(getDigests());
            var AecCurve = new ASN1Integer(getEcCurve());
            var AnoAuthRequired = DERNull.INSTANCE;

            // TODO hex3l: add device properties to attestation
            ASN1Encodable[] deviceProperties;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (params.isDevicePropertiesAttestationIncluded()) {
                    var platformReportedBrand = new DEROctetString(getSystemProperty(Build.BRAND).getBytes());
                    var platformReportedDevice = new DEROctetString(getSystemProperty(Build.DEVICE).getBytes());
                    var platformReportedProduct = new DEROctetString(getSystemProperty(Build.PRODUCT).getBytes());
                    var platformReportedManufacturer = new DEROctetString(getSystemProperty(Build.MANUFACTURER).getBytes());
                    var platformReportedModel = new DEROctetString(getSystemProperty(Build.MODEL).getBytes());
                    deviceProperties = new ASN1Encodable[]{platformReportedBrand, platformReportedDevice, platformReportedProduct, platformReportedManufacturer, platformReportedModel};
                }
            }

            // To be loaded
            var AosVersion = new ASN1Integer(130000);
            var AosPatchLevel = new ASN1Integer(202401);

            // TODO hex3l: add applicationID to attestation
            //  var AapplicationID = createApplicationId();
            var AbootPatchlevel = new ASN1Integer(20231101);
            var AvendorPatchLevel = new ASN1Integer(20231101);

            var AcreationDateTime = new ASN1Integer(System.currentTimeMillis());
            var Aorigin = new ASN1Integer(0);

            var purpose = new DERTaggedObject(true, 1, Apurpose);
            var algorithm = new DERTaggedObject(true, 2, Aalgorithm);
            var keySize = new DERTaggedObject(true, 3, AkeySize);
            var digest = new DERTaggedObject(true, 5, Adigest);
            var ecCurve = new DERTaggedObject(true, 10, AecCurve);
            var noAuthRequired = new DERTaggedObject(true, 503, AnoAuthRequired);
            var creationDateTime = new DERTaggedObject(true, 701, AcreationDateTime);
            var origin = new DERTaggedObject(true, 702, Aorigin);
            var rootOfTrust = new DERTaggedObject(true, 704, rootOfTrustSeq);
            var osVersion = new DERTaggedObject(true, 705, AosVersion);
            var osPatchLevel = new DERTaggedObject(true, 706, AosPatchLevel);
            // TODO hex3l: add applicationID to attestation
            //  var applicationID = new DERTaggedObject(true, 709, AapplicationID);
            var vendorPatchLevel = new DERTaggedObject(true, 718, AvendorPatchLevel);
            var bootPatchLevel = new DERTaggedObject(true, 719, AbootPatchlevel);

            ASN1Encodable[] teeEnforcedEncodables = {purpose, algorithm, keySize, digest, ecCurve, noAuthRequired, creationDateTime, origin, rootOfTrust, osVersion, osPatchLevel, vendorPatchLevel, bootPatchLevel};

            ASN1Integer attestationVersion = new ASN1Integer(4);
            ASN1Enumerated attestationSecurityLevel = new ASN1Enumerated(1);
            ASN1Integer keymasterVersion = new ASN1Integer(41);
            ASN1Enumerated keymasterSecurityLevel = new ASN1Enumerated(1);
            ASN1OctetString attestationChallenge = new DEROctetString(params.getAttestationChallenge());
            ASN1OctetString uniqueId = new DEROctetString("".getBytes());
            ASN1Sequence softwareEnforced = new DERSequence();
            ASN1Sequence teeEnforced = new DERSequence(teeEnforcedEncodables);

            ASN1Encodable[] keyDescriptionEncodables = {attestationVersion, attestationSecurityLevel, keymasterVersion, keymasterSecurityLevel, attestationChallenge, uniqueId, softwareEnforced, teeEnforced};

            ASN1Sequence keyDescriptionHackSeq = new DERSequence(keyDescriptionEncodables);

            ASN1OctetString keyDescriptionOctetStr = new DEROctetString(keyDescriptionHackSeq);

            return new Extension(new ASN1ObjectIdentifier("1.3.6.1.4.1.11129.2.1.17"), false, keyDescriptionOctetStr);

        } catch (Throwable t) {
            EntryPoint.LOG(t.toString());
        }
        return null;
    }

    private ASN1Encodable[] getDigests() {
        String[] digests = params.getDigests();
        ASN1Encodable[] result = new ASN1Encodable[digests.length];
        for (int i = 0; i < digests.length; i++) {
            String digest = digests[i];
            int d;
            switch (digest){
                case KeyProperties.DIGEST_MD5 -> d = 1;
                case KeyProperties.DIGEST_SHA1 -> d = 2;
                case KeyProperties.DIGEST_SHA224 -> d = 3;
                case KeyProperties.DIGEST_SHA256 -> d = 4;
                case KeyProperties.DIGEST_SHA384 -> d = 5;
                case KeyProperties.DIGEST_SHA512 -> d = 6;
                default -> d = 0;
            }
            result[i] = new ASN1Integer(d);
        }
        return result;
    }

    // TODO hex3l: improve compatibility
    private int getEcCurve() {
        String name = ((ECGenParameterSpec) params.getAlgorithmParameterSpec()).getName();
        int res;
            switch (name){
                case "secp256r1" -> res = 1;
                default -> res = 0;
            }
        return res;
    }

    // TODO hex3l: improve compatibility, view commented code bottom of file
    private int getKeySizeFromCurve() {
        String name = ((ECGenParameterSpec) params.getAlgorithmParameterSpec()).getName();
        int res;
            switch (name){
                case "secp256r1" -> res = 256;
                default -> res = -1;
            }
        return res;
    }

    // TODO hex3l: improve compatibility
    private int getAlgorithm()
    {
        return switch (requestedAlgo) {
            case KeyProperties.KEY_ALGORITHM_EC -> 3;
            default -> 0;
        };
    }

    private KeyPair buildECKeyPair() throws Exception {
        ECGenParameterSpec spec = ((ECGenParameterSpec) params.getAlgorithmParameterSpec());
        Security.addProvider(new BouncyCastleProvider());
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("ECDSA", BouncyCastleProvider.PROVIDER_NAME);
        kpg.initialize(spec);
        return kpg.generateKeyPair();
    }

    private KeyPair buildRSAKeyPair() throws Exception {
        RSAKeyGenParameterSpec spec = ((RSAKeyGenParameterSpec) Objects.requireNonNull(params.getAlgorithmParameterSpec()));
        Security.addProvider(new BouncyCastleProvider());
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME);
        kpg.initialize(spec);
        return kpg.generateKeyPair();
    }


    ASN1Sequence createApplicationId(String packageName, int version, byte[] signatureDigests) {
        ASN1Encodable[] packageInfoAsn1Array = new ASN1Encodable[2];
        packageInfoAsn1Array[ATTESTATION_PACKAGE_INFO_PACKAGE_NAME_INDEX] =
                new DEROctetString(packageName.getBytes(StandardCharsets.UTF_8));
        packageInfoAsn1Array[ATTESTATION_PACKAGE_INFO_VERSION_INDEX] = new ASN1Integer(version);



        ASN1Encodable[] applicationIdAsn1Array = new ASN1Encodable[2];
        applicationIdAsn1Array[ATTESTATION_APPLICATION_ID_PACKAGE_INFOS_INDEX] =
                new DERSet(packageInfoAsn1Array);

        applicationIdAsn1Array[ATTESTATION_APPLICATION_ID_SIGNATURE_DIGESTS_INDEX] =
                new DERSet(new DEROctetString(signatureDigests));

        return new DERSequence(applicationIdAsn1Array);
    }

    public String getSystemProperty(String key) {
        String value = null;

        try {
            value = (String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class).invoke(null, key);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return value;
    }
}

/*
        // Aliases for NIST P-224
        SUPPORTED_EC_CURVE_NAME_TO_SIZE.put("p-224", 224);
        SUPPORTED_EC_CURVE_NAME_TO_SIZE.put("secp224r1", 224);

        // Aliases for NIST P-256
        SUPPORTED_EC_CURVE_NAME_TO_SIZE.put("p-256", 256);
        SUPPORTED_EC_CURVE_NAME_TO_SIZE.put("secp256r1", 256);
        SUPPORTED_EC_CURVE_NAME_TO_SIZE.put("prime256v1", 256);
        // Aliases for Curve 25519
        SUPPORTED_EC_CURVE_NAME_TO_SIZE.put(CURVE_X_25519.toLowerCase(Locale.US), 256);
        SUPPORTED_EC_CURVE_NAME_TO_SIZE.put(CURVE_ED_25519.toLowerCase(Locale.US), 256);

        // Aliases for NIST P-384
        SUPPORTED_EC_CURVE_NAME_TO_SIZE.put("p-384", 384);
        SUPPORTED_EC_CURVE_NAME_TO_SIZE.put("secp384r1", 384);

        // Aliases for NIST P-521
        SUPPORTED_EC_CURVE_NAME_TO_SIZE.put("p-521", 521);
        SUPPORTED_EC_CURVE_NAME_TO_SIZE.put("secp521r1", 521);

 */
