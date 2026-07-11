package io.github.mara.xbypass;

import android.security.keystore.KeyGenParameterSpec;
import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Arrays;

public final class StrongBoxHook implements IXposedHookLoadPackage {
    private static final String TAG = "XAttestFix";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.twitter.android".equals(lpparam.packageName)) return;

        try {
            Class<?> provider = XposedHelpers.findClassIfExists("com.x.attestation.y1", lpparam.classLoader);
            if (provider == null) {
                Log.w(TAG, "X attestation chain provider not found");
                return;
            }

            for (Method method : provider.getDeclaredMethods()) {
                if (!method.getName().equals("a") || method.getParameterCount() != 2
                        || method.getParameterTypes()[0] != String.class
                        || method.getParameterTypes()[1] != byte[].class) continue;

                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            String alias = resolveAlias(param.thisObject, (String) param.args[0]);
                            Certificate[] chain = generateStrongBoxChain(alias, (byte[]) param.args[1]);
                            param.setResult(Arrays.asList(chain));
                            Log.i(TAG, "StrongBox chain generated: certs=" + chain.length);
                        } catch (Throwable t) {
                            // Leave the original TEE path intact if StrongBox cannot service this request.
                            Log.w(TAG, "StrongBox generation failed: " + t.getClass().getSimpleName());
                        }
                    }
                });
                Log.i(TAG, "StrongBox hook active");
                return;
            }
            Log.w(TAG, "X attestation chain method not found");
        } catch (Throwable t) {
            Log.e(TAG, "StrongBox hook setup failed", t);
        }
    }

    private static String resolveAlias(Object provider, String accountId) throws Exception {
        for (Field field : provider.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            Object candidate = field.get(provider);
            if (candidate == null) continue;
            try {
                Object alias = XposedHelpers.callMethod(candidate, "a", accountId);
                if (alias instanceof String) return (String) alias;
            } catch (Throwable ignored) {
            }
        }
        throw new NoSuchFieldException("X attestation alias provider");
    }

    private static Certificate[] generateStrongBoxChain(String alias, byte[] challenge) throws Exception {
        deleteAlias(alias);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", "AndroidKeyStore");
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(alias, 4)
                .setDigests("SHA-256")
                .setAttestationChallenge(challenge)
                .setIsStrongBoxBacked(true)
                .build();
        generator.initialize(spec);
        generator.generateKeyPair();

        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        Certificate[] chain = keyStore.getCertificateChain(alias);
        if (chain == null || chain.length == 0) throw new IllegalStateException("empty StrongBox chain");
        return chain;
    }

    private static void deleteAlias(String alias) {
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias);
        } catch (Throwable ignored) {
        }
    }
}
