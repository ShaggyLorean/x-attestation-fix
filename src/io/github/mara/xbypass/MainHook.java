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
import java.util.List;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "XAttestFix";
    private static final String TARGET = "com.twitter.android";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET.equals(lpparam.packageName)) return;
        Log.i(TAG, "loaded for " + TARGET);
        hookChainProvider(lpparam);
        hookSigningGate(lpparam);
    }

    /**
     * X v12.7.1 generates attestation keys through com.x.attestation.y1, which
     * creates a KeyGenParameterSpec without setIsStrongBoxBacked(true). On devices
     * where the TEE's Remote Key Provisioning (CSR v2) is broken, this causes
     * keystore2 to fail with error -18, leaving the attestation token cache empty.
     *
     * The signing gate (com.twitter.network.q.a) checks whether a cached token
     * exists before allowing POST requests to jf.x.com/onboarding/* and other
     * auth endpoints. With no token, every signed request fails and login is
     * rejected with "Please use official X apps to proceed or try again later."
     *
     * This hook replaces y1.a() to generate keys with StrongBox backing instead
     * of TEE. StrongBox uses pre-provisioned keys and does not depend on CSR v2,
     * so it works even when RKP is broken.
     */
    private void hookChainProvider(final XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> clz = XposedHelpers.findClassIfExists("com.x.attestation.y1", lpparam.classLoader);
        if (clz == null) {
            Log.w(TAG, "y1 not found, trying k1 (older builds)");
            clz = XposedHelpers.findClassIfExists("com.x.attestation.k1", lpparam.classLoader);
        }
        if (clz == null) {
            Log.e(TAG, "chain provider class not found, giving up");
            return;
        }

        for (final Method m : clz.getDeclaredMethods()) {
            if (!m.getName().equals("a")) continue;
            if (m.getParameterCount() != 2) continue;
            if (m.getParameterTypes()[0] != String.class) continue;
            if (m.getParameterTypes()[1] != byte[].class) continue;

            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        String accountId = (String) param.args[0];
                        byte[] challenge = (byte[]) param.args[1];
                        String alias = resolveAlias(param.thisObject, accountId);
                        Log.i(TAG, "generating StrongBox key for alias=" + alias
                                + " challenge=" + challenge.length + "b");

                        Certificate[] chain = generateStrongBoxChain(alias, challenge);
                        if (chain != null && chain.length > 0) {
                            param.setResult(Arrays.asList(chain));
                            Log.i(TAG, "StrongBox chain OK, certs=" + chain.length);
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "StrongBox generation failed", t);
                    }
                }
            });
            Log.i(TAG, "hooked chain provider");
        }
    }

    @SuppressWarnings("unchecked")
    private String resolveAlias(Object chainProvider, String accountId) throws Exception {
        for (Field field : chainProvider.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            Object candidate = field.get(chainProvider);
            if (candidate == null) continue;
            try {
                Object alias = XposedHelpers.callMethod(candidate, "a", new Object[]{accountId});
                if (alias instanceof String) return (String) alias;
            } catch (Throwable ignored) {}
        }
        throw new NoSuchFieldException(
            "no alias provider in " + chainProvider.getClass().getName());
    }

    private Certificate[] generateStrongBoxChain(String alias, byte[] challenge) throws Exception {
        deleteAlias(alias);
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", "AndroidKeyStore");
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(alias, 4)
                .setDigests("SHA-256")
                .setAttestationChallenge(challenge)
                .setIsStrongBoxBacked(true)
                .build();
        kpg.initialize(spec);
        kpg.generateKeyPair();
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        Certificate[] chain = ks.getCertificateChain(alias);
        if (chain == null || chain.length == 0)
            throw new IllegalStateException("empty certificate chain");
        return chain;
    }

    private void deleteAlias(String alias) {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(alias)) ks.deleteEntry(alias);
        } catch (Throwable ignored) {}
    }

    /**
     * Diagnostic hook on the signing gate. Logs whether the gate passes or fails
     * for each request. Does not alter the return value.
     */
    private void hookSigningGate(final XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> clz = XposedHelpers.findClassIfExists("com.twitter.network.q", lpparam.classLoader);
        if (clz == null) return;

        for (final Method m : clz.getDeclaredMethods()) {
            if (!m.getName().equals("a")) continue;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    String host = param.args.length > 2 ? String.valueOf(param.args[2]) : "?";
                    String path = param.args.length > 3 ? String.valueOf(param.args[3]) : "?";
                    Log.i(TAG, "gate " + host + path + " => " + result);
                }
            });
        }
        Log.i(TAG, "hooked signing gate");
    }
}