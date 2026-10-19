package com.audiofx.app;

import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;

/**
 * Verifies Play purchase signatures against the Play Console license key.
 * Adapted from Google's Play Billing sample code. Only the verification
 * outcome is logged; the key, purchase data, and tokens are never logged.
 */
public class Security {
    private static final String TAG = "AudioMechanic";
    private static final String KEY_FACTORY_ALGORITHM = "RSA";
    private static final String SIGNATURE_ALGORITHM = "SHA1withRSA";

    public static boolean verifyPurchase(String base64PublicKey,
            String signedData, String signature) {
        if (TextUtils.isEmpty(signedData)
                || TextUtils.isEmpty(base64PublicKey)
                || TextUtils.isEmpty(signature)) {
            Log.w(TAG, "Purchase verification failed: missing data.");
            return false;
        }
        PublicKey key;
        try {
            key = generatePublicKey(base64PublicKey);
        } catch (IOException e) {
            Log.w(TAG, "Purchase verification failed: bad key.");
            return false;
        }
        return verify(key, signedData, signature);
    }

    private static PublicKey generatePublicKey(String encodedPublicKey)
            throws IOException {
        try {
            byte[] decodedKey = Base64.decode(encodedPublicKey,
                    Base64.DEFAULT);
            KeyFactory keyFactory =
                    KeyFactory.getInstance(KEY_FACTORY_ALGORITHM);
            return keyFactory.generatePublic(
                    new X509EncodedKeySpec(decodedKey));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        } catch (InvalidKeySpecException e) {
            Log.w(TAG, "Invalid key specification.");
            throw new IOException(e);
        }
    }

    private static boolean verify(PublicKey publicKey, String signedData,
            String signature) {
        byte[] signatureBytes;
        try {
            signatureBytes = Base64.decode(signature, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Base64 decoding failed.");
            return false;
        }
        try {
            Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM);
            sig.initVerify(publicKey);
            sig.update(signedData.getBytes());
            if (!sig.verify(signatureBytes)) {
                Log.w(TAG, "Signature verification failed.");
                return false;
            }
            return true;
        } catch (NoSuchAlgorithmException e) {
            Log.w(TAG, "NoSuchAlgorithmException.");
        } catch (InvalidKeyException e) {
            Log.w(TAG, "Invalid key specification.");
        } catch (SignatureException e) {
            Log.w(TAG, "Signature exception.");
        }
        return false;
    }
}
